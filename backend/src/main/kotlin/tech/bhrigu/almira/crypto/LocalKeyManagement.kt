package tech.bhrigu.almira.crypto

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import tech.bhrigu.almira.config.AlmiraProperties
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A key-encryption key held by this process, from configuration.
 *
 * Appropriate for local development and for a self-hosted deployment where the
 * key is delivered by the platform's own secret store. It is NOT equivalent to a
 * managed KMS: the key sits in this process's memory, so it is only as protected
 * as the process, and there is no hardware backing, no audit trail of key use and
 * no central revocation. docs/05 §4 asks for a managed KMS in production; this is
 * the seam one plugs into, not a substitute.
 *
 * THERE IS NO KEY IN THIS REPOSITORY. An earlier version shipped a published
 * "development key" constant, guarded by checks that refused it outside
 * development. That works, but it normalises the wrong thing: a repository that
 * contains something key-shaped, next to the encryption system, teaches everyone
 * who reads it that keys in version control are sometimes fine. Instead, each
 * install generates its own development key on first run and stores it in a
 * gitignored file. Nothing to leak, nothing to rotate by editing code, and
 * rotating is just deleting the file.
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

        val material: ByteArray = when {
            configured.isNotEmpty() -> decode(configured)

            // Outside development the key must be supplied deliberately. Falling
            // back to a generated one would mean a deploy that "works" while
            // protecting data with a key nobody chose, kept nowhere durable, and
            // lost on the next container.
            !isDevelopment -> throw IllegalArgumentException(
                "ALMIRA_KMS_MASTER_KEY is not set. Outside development a key-encryption " +
                    "key must be supplied before any sensitive field can be stored — " +
                    "refusing to start rather than run without one.",
            )

            else -> developmentKey(Path.of(props.encryption.devKeyFile))
        }

        require(material.size == 32) {
            "the key-encryption key must be 32 bytes (AES-256); got ${material.size}"
        }
        key = SecretKeySpec(material, "AES")
        // A fingerprint, not the key: enough to tell two KEKs apart in the
        // database, useless to anyone who reads it.
        kekIdentifier = "local:" + MessageDigest.getInstance("SHA-256").digest(material)
            .take(6).joinToString("") { "%02x".format(it) }
    }

    /**
     * Reads this install's development key, creating one if it is absent.
     *
     * Per-install and random, so no two checkouts share a key and nothing
     * encrypted on one machine is readable on another. Deleting the file rotates
     * it — and makes every development record unreadable, which is the honest
     * consequence of rotating a KEK and worth meeting in development rather than
     * in production.
     */
    private fun developmentKey(path: Path): ByteArray {
        path.parent?.let { Files.createDirectories(it) }

        if (Files.exists(path)) {
            log.warn(
                "Using the development key-encryption key at {}. It protects nothing " +
                    "beyond this machine — never run this way with real data.",
                path.toAbsolutePath(),
            )
            return decode(Files.readString(path).trim())
        }

        val generated = ByteArray(32).also(random::nextBytes)
        Files.writeString(path, Base64.getEncoder().encodeToString(generated))
        runCatching {
            // Owner-only. Not protection so much as a signal that this file is
            // not ordinary, on any filesystem that will honour it.
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        }
        log.warn(
            "Generated a development key-encryption key at {}. It is unique to this " +
                "machine and is not in version control. Delete it to rotate — every " +
                "record encrypted under it becomes unreadable, which is what rotating " +
                "a KEK means.",
            path.toAbsolutePath(),
        )
        return generated
    }

    private fun decode(base64: String): ByteArray =
        runCatching { Base64.getDecoder().decode(base64) }.getOrElse {
            throw IllegalArgumentException("the key-encryption key must be base64-encoded")
        }

    override fun wrap(dataKey: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(ALGORITHM).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            updateAAD(kekIdentifier.toByteArray())
        }
        return iv + cipher.doFinal(dataKey)
    }

    override fun unwrap(wrappedDataKey: ByteArray): ByteArray {
        require(wrappedDataKey.size > IV_BYTES) { "wrapped key is malformed" }
        val iv = wrappedDataKey.copyOfRange(0, IV_BYTES)
        val body = wrappedDataKey.copyOfRange(IV_BYTES, wrappedDataKey.size)
        val cipher = Cipher.getInstance(ALGORITHM).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            updateAAD(kekIdentifier.toByteArray())
        }
        return cipher.doFinal(body)
    }

    private companion object {
        const val ALGORITHM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
