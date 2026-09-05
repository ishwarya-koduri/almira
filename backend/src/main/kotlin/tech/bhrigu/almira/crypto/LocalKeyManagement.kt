package tech.bhrigu.almira.crypto

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import tech.bhrigu.almira.config.AlmiraProperties
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A KEK held by this process, from configuration.
 *
 * Appropriate for local development and for a self-hosted deployment where the
 * key is delivered by the platform's own secret store and never written to
 * disk. It is NOT equivalent to a managed KMS: the key is in this process's
 * memory, so it is only as protected as the process, and there is no audit
 * trail of key use, no hardware backing, and no central revocation.
 * docs/05 §4 asks for a managed KMS in production; this is the seam it plugs
 * into, not a substitute for it.
 *
 * The safety property that matters here is that it refuses to run insecurely:
 * outside development a real key is required, and the well-known development
 * key is rejected outright.
 */
@Component
@ConditionalOnProperty(
    name = ["almira.encryption.provider"],
    havingValue = "local",
    matchIfMissing = true,
)
class LocalKeyManagement(props: AlmiraProperties) : KeyManagementService {

    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()
    private val key: SecretKeySpec
    private val kekIdentifier: String
    override val kekId: String get() = kekIdentifier

    init {
        val configured = props.encryption.masterKey.trim()
        val isDevelopment = props.environment.equals("development", ignoreCase = true)

        if (configured.isEmpty()) {
            require(isDevelopment) {
                "ALMIRA_KMS_MASTER_KEY is not set. Outside development, a key-encryption " +
                    "key is required before any sensitive field can be stored — refusing to " +
                    "start rather than run without one."
            }
            log.error(
                "=== No ALMIRA_KMS_MASTER_KEY set. Using the built-in DEVELOPMENT key. " +
                    "Data encrypted now cannot be read by any other environment, and this " +
                    "key protects nothing. Never run this way with real data. ===",
            )
        } else {
            require(configured != DEVELOPMENT_KEY || isDevelopment) {
                "ALMIRA_KMS_MASTER_KEY is set to the published development key. " +
                    "It is in the repository and protects nothing — refusing to start."
            }
        }

        val material = configured.ifEmpty { DEVELOPMENT_KEY }
        val bytes = runCatching { Base64.getDecoder().decode(material) }.getOrElse {
            throw IllegalArgumentException("ALMIRA_KMS_MASTER_KEY must be base64-encoded")
        }
        require(bytes.size == 32) {
            "ALMIRA_KMS_MASTER_KEY must decode to 32 bytes (AES-256); got ${bytes.size}"
        }

        key = SecretKeySpec(bytes, "AES")
        // A fingerprint, not the key: enough to tell two KEKs apart in the
        // database, useless to anyone who reads it.
        kekIdentifier = "local:" + MessageDigest.getInstance("SHA-256").digest(bytes)
            .take(6).joinToString("") { "%02x".format(it) }
    }

    override fun wrap(dataKey: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(ALGORITHM).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            updateAAD(kekId.toByteArray())
        }
        return iv + cipher.doFinal(dataKey)
    }

    override fun unwrap(wrappedDataKey: ByteArray): ByteArray {
        require(wrappedDataKey.size > IV_BYTES) { "wrapped key is malformed" }
        val iv = wrappedDataKey.copyOfRange(0, IV_BYTES)
        val body = wrappedDataKey.copyOfRange(IV_BYTES, wrappedDataKey.size)
        val cipher = Cipher.getInstance(ALGORITHM).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            updateAAD(kekId.toByteArray())
        }
        return cipher.doFinal(body)
    }

    private companion object {
        const val ALGORITHM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128

        /**
         * Published on purpose: it is in version control, so it can never be
         * mistaken for a secret. The checks above make it unusable outside
         * development.
         */
        const val DEVELOPMENT_KEY = "<scrubbed-development-key-placeholder>="
    }
}
