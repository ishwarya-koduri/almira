@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package tech.bhrigu.almira.shared.security

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
import platform.Foundation.NSLog
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
 *
 * ### Off the caller's thread, and one at a time
 *
 * Every Keychain call here is synchronous C that can block on the system
 * keychain daemon, and [accessToken] sits on the path of every single API
 * request — so the work leaves the caller's thread, which on iOS is the one
 * drawing the screen. Android's store says `Dispatchers.IO`; Kotlin/Native does
 * not publish that dispatcher, so this is `Dispatchers.Default`, and the
 * difference does not bite: the [Mutex] below already allows one Keychain call
 * at a time, so this can never occupy more than a single pool thread.
 *
 * The lock does the other half. Without it a token refresh writing three items
 * could interleave with a read that sees the marker gone and calls the session
 * over. It is taken only by the `TokenStore` overrides; the private helpers
 * assume it is already held, so nothing here can wait on itself.
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

    /** `errSecSuccess`, which the Security bindings do not hand over as a constant. */
    private const val OK = 0

    /** `errSecItemNotFound`: the end state [delete] wants, not a failure. */
    private const val NOT_FOUND = -25300

    /** `errSecParam`, borrowed for the one failure that never reaches Security. */
    private const val BAD_PARAM = -50

    private var cachedAccess: String? = null
    private var cachedRefresh: String? = null

    /** See the class note: one Keychain conversation at a time, off the caller's thread. */
    private val gate = Mutex()

    override suspend fun hasSession(): Boolean = onKeychain { read(MARKER) != null }

    override suspend fun accessToken(): String? = onKeychain {
        cachedAccess ?: read(ACCESS)?.also { cachedAccess = it }
    }

    override suspend fun refreshToken(): String? = onKeychain {
        cachedRefresh ?: read(REFRESH)?.also { cachedRefresh = it }
    }

    /**
     * All three items or none of them, with the marker written last.
     *
     * `SecItemAdd` returns an `OSStatus` that nobody is obliged to read, and the
     * version of this that did not read it reported success no matter what the
     * Keychain did. Because [write] deletes the old item before adding the new
     * one, a failed add did not leave the previous value alone — it destroyed
     * it, so a refresh-token rotation that failed here ended the session at the
     * next cold start with nothing anywhere saying why.
     *
     * The marker goes down last and comes back up first because it is what
     * [hasSession] reads: a marker standing over a missing refresh token is a
     * returning person sent to a lock screen with nothing behind it. If any of
     * the three fails, all three are removed and the store is honestly empty.
     *
     * Does not throw, for the same reason Android's does not: a token that
     * cannot be stored is a session that will not survive a restart, which is a
     * smaller problem than an exception surfacing as "couldn't reach Almira".
     */
    override suspend fun save(access: String, refresh: String): Unit = onKeychain {
        // Held whatever the Keychain does below. These tokens are sound - the
        // server issued them a moment ago - and only their durability is at
        // stake, so a storage failure must not end a session that is working.
        // `forget` still drops them the moment the app backgrounds.
        cachedAccess = access
        cachedRefresh = refresh

        for ((account, value) in listOf(ACCESS to access, REFRESH to refresh, MARKER to "1")) {
            val status = write(account, value)
            if (status == OK) continue

            report("could not store $account", status)
            listOf(ACCESS, REFRESH, MARKER).forEach { rollback ->
                val undo = delete(rollback)
                if (undo != OK && undo != NOT_FOUND) report("could not roll back $rollback", undo)
            }
            return@onKeychain
        }
    }

    override suspend fun clear(): Unit = onKeychain {
        listOf(ACCESS, REFRESH, MARKER).forEach { account ->
            val status = delete(account)
            // Absent is what was wanted. Anything else means a sign-out that
            // did not take, which is the one failure worth saying out loud.
            if (status != OK && status != NOT_FOUND) report("could not clear $account", status)
        }
        cachedAccess = null
        cachedRefresh = null
    }

    override suspend fun forget() {
        // No Keychain work, so no dispatch: only the cache, which the lock
        // still guards against a read landing halfway through.
        gate.withLock {
            cachedAccess = null
            cachedRefresh = null
        }
    }

    /**
     * The one way in: off the caller's thread and behind the lock.
     *
     * Never call this from inside itself. `Mutex` is not reentrant, and the
     * public surface is arranged so that nothing needs it to be — the helpers
     * below are private and lock-free.
     */
    private suspend fun <T> onKeychain(block: () -> T): T =
        withContext(Dispatchers.Default) { gate.withLock { block() } }

    /** Says what went wrong and never what was being stored. */
    private fun report(what: String, status: Int) {
        NSLog("almira keychain: $what (OSStatus $status)")
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

    /** Returns the `OSStatus` from `SecItemAdd`. [OK] and nothing else means stored. */
    private fun write(account: String, value: String): Int {
        // Replace rather than update: an add over an existing item fails with
        // errSecDuplicateItem, and a half-written session is worse than none.
        delete(account)
        @Suppress("CAST_NEVER_SUCCEEDS")
        val data = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding)
            ?: return BAD_PARAM
        val bridged = CFBridgingRetain(data)
        try {
            return withQuery(
                account,
                kSecValueData to bridged,
                kSecAttrAccessible to kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            ) { query -> SecItemAdd(query, null) }
        } finally {
            CFBridgingRelease(bridged)
        }
    }

    /**
     * Returns the `OSStatus` from `SecItemDelete`. [NOT_FOUND] is the desired
     * end state rather than a failure, so callers treat it as success; they
     * decide what anything else means, because it differs between rolling a
     * failed save back and signing out for good.
     */
    private fun delete(account: String): Int =
        withQuery(account) { query -> SecItemDelete(query) }

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
                    if (succeeded) {
                        UnlockResult.Unlocked
                    } else {
                        // Which LAError means what is pure, so it lives in
                        // AppleUnlockOutcome.kt where a test can reach it.
                        appleUnlockOutcome(error?.code, error?.localizedDescription)
                    },
                )
            }

            // The counterpart of Android's `prompt.cancelAuthentication()`, and
            // needed for the same reason: `attemptUnlock` runs inside a
            // `LaunchedEffect`, so backgrounding cancels it. Without this the
            // Face ID sheet stays up over an app that is no longer asking, and
            // the next foreground raises a second one behind the first.
            //
            // It also keeps `context` alive. `evaluatePolicy` does not retain
            // its receiver, and the local would otherwise be the only reference
            // — released as soon as this lambda returns, which dismisses the
            // prompt and means the callback never arrives at all.
            continuation.invokeOnCancellation { context.invalidate() }
        }
    }
}
