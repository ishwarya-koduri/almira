package tech.bhrigu.almira.shared.security

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tech.bhrigu.almira.shared.api.TokenStore
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * The session, encrypted by a key the app is never allowed to see, behind a
 * key the person has to prove themselves to use.
 *
 * Written against the Keystore directly rather than pulling in Jetpack
 * Security's `EncryptedSharedPreferences`: that library has been in maintenance
 * for years, and this is the same AES-256-GCM it would have done with two
 * properties it would not have given us — the tokens are unreadable without a
 * recent device authentication, and a changed set of fingerprints destroys
 * them rather than inheriting them.
 *
 * ### Why there are two keys
 *
 * The obvious design — one auth-bound AES key over the tokens — is wrong, and
 * wrong in a way that only shows up in use. An Android key minted
 * `setUserAuthenticationRequired` gates *every* operation on it, encryption
 * included, for a fixed window after the last authentication. So that design
 * both refuses to save the session at sign-in, when no device authentication
 * has happened at all, and refuses to save a rotated refresh token forty
 * minutes into a session, which would end the session in the middle of
 * somebody using it.
 *
 * So: an RSA key pair is the key-encryption key, and a random AES-256 data key
 * does the actual work.
 *
 * - **Public half — no authentication.** It is ordinary bytes, so the data key
 *   can be wrapped at sign-in and rewrapped whenever it needs to be.
 * - **Private half — authentication required.** Unwrapping the data key is the
 *   one operation that needs the person, and it happens once per unlock.
 * - **The data key lives in memory only while the app is unlocked**, which is
 *   what lets a refresh at minute forty work. [forget] drops it the moment the
 *   app leaves the foreground, so backgrounding really does re-lock rather than
 *   just redrawing.
 *
 * Ciphertext lives in ordinary SharedPreferences, because ciphertext is not a
 * secret. Each GCM IV is stored beside its own ciphertext, as GCM requires.
 */
internal class KeystoreTokenStore(context: Context) : TokenStore {

    private val app: Context = context.applicationContext

    private val prefs: SharedPreferences =
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Held only between an unlock and the next background. Never written out. */
    private var dataKey: SecretKey? = null
    private val gate = Mutex()

    /**
     * Whether this device can recognise a person right now: a PIN, pattern,
     * password, or any biometric enrolled behind one.
     */
    private val deviceHasALock: Boolean
        get() = (app.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
            ?.isDeviceSecure == true

    override suspend fun hasSession(): Boolean = prefs.contains(KEY_REFRESH)

    override suspend fun accessToken(): String? = read(KEY_ACCESS)
    override suspend fun refreshToken(): String? = read(KEY_REFRESH)

    override suspend fun save(access: String, refresh: String): Unit = withContext(Dispatchers.IO) {
        gate.withLock {
            val key = usableDataKey() ?: return@withLock
            // Both or neither. A half-written session is worse than none: it is
            // an access token that expires into a refresh that isn't there.
            val sealedAccess = seal(key, access)
            val sealedRefresh = seal(key, refresh)
            prefs.edit()
                .putString(KEY_ACCESS, sealedAccess)
                .putString(KEY_REFRESH, sealedRefresh)
                .apply()
        }
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        gate.withLock {
            dataKey = null
            prefs.edit().clear().apply()
        }
    }

    /**
     * Drop the decrypted data key. The stored session stays; it just cannot be
     * read again until the next unlock.
     */
    override suspend fun forget() {
        gate.withLock { dataKey = null }
    }

    // --- reading ------------------------------------------------------------

    private suspend fun read(name: String): String? = withContext(Dispatchers.IO) {
        gate.withLock {
            val stored = prefs.getString(name, null) ?: return@withLock null
            val key = try {
                usableDataKey()
            } catch (_: UserNotAuthenticatedException) {
                // The lock has not been passed, or its window expired. Nothing
                // to repair — the caller's job is to ask for the lock.
                return@withLock null
            } ?: return@withLock null

            try {
                open(key, stored)
            } catch (_: Exception) {
                // The key is gone or no longer usable: a new fingerprint
                // enrolled, the screen lock removed, the app's data restored
                // onto another phone. The session cannot be recovered and must
                // not look as though it might be.
                dataKey = null
                prefs.edit().clear().apply()
                null
            }
        }
    }

    // --- the two keys -------------------------------------------------------

    /**
     * The data key, unwrapping it first if this is the first read since the app
     * was unlocked. Throws [UserNotAuthenticatedException] when the person has
     * not authenticated recently enough — which is the lock doing its job.
     */
    private fun usableDataKey(): SecretKey? {
        dataKey?.let { return it }

        val keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        ensureKeyPairMatchesDevice(keystore)

        val wrapped = prefs.getString(KEY_DEK, null)
        if (wrapped == null) {
            // First session on this device: mint a data key and wrap it with
            // the public half, which needs no authentication.
            val fresh = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            prefs.edit().putString(KEY_DEK, wrap(publicKey(keystore), fresh)).apply()
            dataKey = fresh
            return fresh
        }

        val privateKey = (keystore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry)
            ?.privateKey ?: return null
        return unwrap(privateKey, wrapped).also { dataKey = it }
    }

    /**
     * Someone who adds a screen lock after signing in should get the stronger
     * key, and someone who removes one must not keep a key whose conditions can
     * no longer be met. Either way the existing pair is wrong and the session
     * goes with it — one sign-in is a small price for the guarantee actually
     * matching what the device can do.
     */
    private fun ensureKeyPairMatchesDevice(keystore: KeyStore) {
        val wanted = if (deviceHasALock) MODE_AUTH_BOUND else MODE_HARDWARE_ONLY
        val stored = prefs.getString(KEY_MODE, null)

        if (stored != null && stored != wanted) {
            runCatching { keystore.deleteEntry(KEY_ALIAS) }
            prefs.edit().clear().apply()
            dataKey = null
        }

        if (!keystore.containsAlias(KEY_ALIAS)) {
            generateKeyPair(authBound = deviceHasALock)
            prefs.edit().putString(KEY_MODE, wanted).apply()
        }
    }

    private fun generateKeyPair(authBound: Boolean) {
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setKeySize(2048)

        if (authBound) {
            builder.setUserAuthenticationRequired(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(
                    AUTH_VALIDITY_SECONDS,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                )
            } else {
                @Suppress("DEPRECATION")
                builder.setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
            }
            // Without this, enrolling a new fingerprint would silently extend
            // access to a session the original person opened.
            builder.setInvalidatedByBiometricEnrollment(true)
        }

        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
            .apply { initialize(builder.build()) }
            .generateKeyPair()
    }

    /**
     * The public half, rebuilt outside the Keystore provider.
     *
     * A `PublicKey` handed back by AndroidKeyStore carries the private key's
     * authentication requirement with it on several releases, which would defeat
     * the entire point of wrapping with the public half. Re-encoding it through
     * the default provider gives plain bytes doing plain software RSA.
     */
    private fun publicKey(keystore: KeyStore): PublicKey {
        val fromStore = keystore.getCertificate(KEY_ALIAS).publicKey
        return KeyFactory.getInstance(fromStore.algorithm)
            .generatePublic(X509EncodedKeySpec(fromStore.encoded))
    }

    private fun wrap(publicKey: PublicKey, key: SecretKey): String {
        val cipher = Cipher.getInstance(RSA_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, oaep())
        return Base64.encodeToString(cipher.doFinal(key.encoded), Base64.NO_WRAP)
    }

    private fun unwrap(privateKey: PrivateKey, wrapped: String): SecretKey {
        val cipher = Cipher.getInstance(RSA_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, privateKey, oaep())
        val raw = cipher.doFinal(Base64.decode(wrapped, Base64.NO_WRAP))
        return SecretKeySpec(raw, "AES")
    }

    /**
     * Spelled out rather than left to the provider's default: AndroidKeyStore
     * uses SHA-256 for the OAEP digest but SHA-1 for its MGF1, and a Cipher
     * built from the string alone disagrees with it and fails to decrypt.
     */
    private fun oaep() = OAEPParameterSpec(
        "SHA-256",
        "MGF1",
        MGF1ParameterSpec.SHA1,
        PSource.PSpecified.DEFAULT,
    )

    // --- the tokens themselves ----------------------------------------------

    private fun seal(key: SecretKey, plaintext: String): String {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val bytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        // iv.ciphertext, one string, because they belong to each other.
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
            Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun open(key: SecretKey, stored: String): String? {
        val parts = stored.split(".", limit = 2)
        if (parts.size != 2) return null
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(GCM_TAG_BITS, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "almira.session.kek.v1"
        private const val RSA_TRANSFORMATION = "RSA/ECB/OAEPPadding"
        private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val PREFS = "almira.session"
        private const val KEY_ACCESS = "access"
        private const val KEY_REFRESH = "refresh"
        private const val KEY_DEK = "dek"
        private const val KEY_MODE = "key_mode"
        private const val MODE_AUTH_BOUND = "auth"
        private const val MODE_HARDWARE_ONLY = "hardware"

        /**
         * How long one authentication is good for. It only has to cover the
         * single unwrap that follows the prompt, so it is deliberately short —
         * the session's own lifetime is held by the data key in memory, not by
         * this window.
         */
        const val AUTH_VALIDITY_SECONDS = 30
    }
}
