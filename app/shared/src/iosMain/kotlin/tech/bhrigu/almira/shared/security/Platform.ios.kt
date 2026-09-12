@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package tech.bhrigu.almira.shared.security

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
// A category member on NSString, so it arrives as an extension that has to be
// imported by name rather than coming along with the class.
import platform.Foundation.dataUsingEncoding
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import tech.bhrigu.almira.shared.api.TokenStore
import kotlin.coroutines.resume

/**
 * On iOS the host carries nothing.
 *
 * Android's `PlatformHost` wraps a `FragmentActivity` because `BiometricPrompt`
 * has to attach its dialog to one. `LAContext` needs no view controller, and
 * the Keychain is process-wide — so this is genuinely empty, which is the seam
 * doing its job rather than a gap in it.
 */
actual class PlatformHost

actual fun createTokenStore(host: PlatformHost): TokenStore = KeychainTokenStore

actual fun createAppLock(host: PlatformHost): AppLock = LocalAuthenticationLock

private const val SERVICE = "tech.bhrigu.almira.session"

/**
 * The session in the Keychain, bound to this device and to the screen having
 * been unlocked at least once since boot.
 *
 * Simpler than the Android side, and deliberately so. There the token had to be
 * wrapped in an RSA-KEK envelope because an auth-bound AES key refuses to
 * *encrypt* as well as decrypt, which broke saving at sign-in. The Keychain has
 * no such asymmetry: `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` is enforced
 * by the system on read, writing is always allowed, and `ThisDeviceOnly` keeps
 * the item out of every backup and off every other phone.
 *
 * The in-memory cache and [forget] exist for parity with Android rather than
 * from necessity: they make "locked" drop what the process is holding, so the
 * next read goes back through the system. Without them the app would still be
 * correct and would simply ask the Keychain on every request.
 */
private object KeychainTokenStore : TokenStore {

    private const val ACCESS = "access"
    private const val REFRESH = "refresh"

    /**
     * Holds "1" and nothing else: its presence is the answer to "is there a
     * session at all". [hasSession] has to be answerable while locked — exactly
     * as on Android, where it reads a plain preference rather than decrypting.
     */
    private const val MARKER = "present"

    private var cachedAccess: String? = null
    private var cachedRefresh: String? = null

    override suspend fun hasSession(): Boolean = read(MARKER) != null

    override suspend fun accessToken(): String? =
        cachedAccess ?: read(ACCESS)?.also { cachedAccess = it }

    override suspend fun refreshToken(): String? =
        cachedRefresh ?: read(REFRESH)?.also { cachedRefresh = it }

    override suspend fun save(access: String, refresh: String) {
        write(ACCESS, access)
        write(REFRESH, refresh)
        write(MARKER, "1")
        cachedAccess = access
        cachedRefresh = refresh
    }

    override suspend fun clear() {
        listOf(ACCESS, REFRESH, MARKER).forEach(::delete)
        cachedAccess = null
        cachedRefresh = null
    }

    override suspend fun forget() {
        cachedAccess = null
        cachedRefresh = null
    }

    private fun read(account: String): String? = memScoped {
        val found = alloc<CFTypeRefVar>()
        val status = withQuery(
            account,
            kSecReturnData to kCFBooleanTrue,
            kSecMatchLimit to kSecMatchLimitOne,
        ) { query -> SecItemCopyMatching(query, found.ptr) }

        if (status != 0) return@memScoped null
        val data = CFBridgingRelease(found.value) as? NSData ?: return@memScoped null
        NSString.create(data = data, encoding = NSUTF8StringEncoding) as String?
    }

    private fun write(account: String, value: String) {
        // Replace rather than update: an add over an existing item fails with
        // errSecDuplicateItem, and a half-written session is worse than none.
        delete(account)
        @Suppress("CAST_NEVER_SUCCEEDS")
        val data = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        val bridged = CFBridgingRetain(data)
        try {
            withQuery(
                account,
                kSecValueData to bridged,
                kSecAttrAccessible to kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            ) { query -> SecItemAdd(query, null) }
        } finally {
            CFBridgingRelease(bridged)
        }
    }

    /** Not-found is the desired end state here, not a failure, so nothing is reported. */
    private fun delete(account: String) {
        withQuery(account) { query -> SecItemDelete(query) }
    }

    /**
     * Builds a Keychain query dictionary, runs one call against it, and tears
     * it down again.
     *
     * Hand-built with `CFDictionaryCreateMutable` rather than by bridging a
     * Kotlin `Map`: the keys are `CFStringRef` constants, which are C pointers
     * from Kotlin's point of view and do not survive a Map-to-NSDictionary
     * bridge. The service and account strings are retained for the length of
     * the call and released after it, so nothing leaks per request — which a
     * store that is read on every single API call would do noticeably.
     *
     * Created with null key and value callbacks, so the dictionary itself
     * neither retains nor releases what it holds; this function owns those
     * lifetimes and they outlive the call.
     */
    private inline fun <T> withQuery(
        account: String,
        vararg extra: Pair<CFStringRef?, CFTypeRef?>,
        block: (CFDictionaryRef?) -> T,
    ): T {
        @Suppress("CAST_NEVER_SUCCEEDS")
        val service = CFBridgingRetain(SERVICE as NSString)
        @Suppress("CAST_NEVER_SUCCEEDS")
        val acct = CFBridgingRetain(account as NSString)
        val dictionary = CFDictionaryCreateMutable(null, (3 + extra.size).convert(), null, null)
        CFDictionaryAddValue(dictionary, kSecClass, kSecClassGenericPassword)
        CFDictionaryAddValue(dictionary, kSecAttrService, service)
        CFDictionaryAddValue(dictionary, kSecAttrAccount, acct)
        extra.forEach { (key, value) -> CFDictionaryAddValue(dictionary, key, value) }
        try {
            return block(dictionary)
        } finally {
            CFRelease(dictionary)
            CFBridgingRelease(service)
            CFBridgingRelease(acct)
        }
    }
}

/**
 * Face ID or Touch ID, with the passcode behind it.
 *
 * `LAPolicyDeviceOwnerAuthentication` is the exact counterpart of Android's
 * `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`: one prompt, biometry where it is
 * enrolled, the passcode where it is not, and a fall-through from the first to
 * the second that the person does not have to find a button for.
 */
private object LocalAuthenticationLock : AppLock {

    override fun availability(): LockAvailability {
        // A fresh context per question: LAContext caches its answer for the
        // life of the object, so a reused one would keep saying "no biometry"
        // after someone enrolled a face in Settings.
        val context = LAContext()
        return when {
            context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, null) ->
                LockAvailability.Biometric
            context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, null) ->
                LockAvailability.DeviceCredentialOnly
            else -> LockAvailability.None
        }
    }

    override suspend fun unlock(title: String, subtitle: String): UnlockResult {
        if (availability() == LockAvailability.None) return UnlockResult.Unavailable

        return suspendCancellableCoroutine { continuation ->
            val context = LAContext()
            // iOS shows one reason string rather than a title and a subtitle,
            // so the subtitle is the one to pass: it is the half that says why.
            // The title is the app's own name, which the system supplies.
            context.evaluatePolicy(LAPolicyDeviceOwnerAuthentication, subtitle) { succeeded, error ->
                if (!continuation.isActive) return@evaluatePolicy
                continuation.resume(
                    when {
                        succeeded -> UnlockResult.Unlocked
                        // -2 is userCancel and -4 systemCancel: a decision not
                        // to answer, not a failure to. Neither is worth showing
                        // an error for, exactly as on Android.
                        error == null || error.code == -2L || error.code == -4L ->
                            UnlockResult.Cancelled
                        else -> UnlockResult.Failed(error.localizedDescription)
                    },
                )
            }
        }
    }
}
