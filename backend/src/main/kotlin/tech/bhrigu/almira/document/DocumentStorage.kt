package tech.bhrigu.almira.document

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import tech.bhrigu.almira.config.AlmiraProperties
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Where encrypted document bytes live.
 *
 * The bytes handed to an implementation are ALREADY ciphertext — encryption
 * happens in DocumentService, before this boundary, so a storage backend never
 * holds anything readable. That ordering is the point: it means swapping the
 * filesystem for S3 changes where the ciphertext sits and nothing about how well
 * it is protected, and a misconfigured bucket leaks nothing.
 *
 * This is the seam S3/GCS/R2 plugs into (docs/09 §11).
 */
interface DocumentStorage {
    fun put(key: String, ciphertext: ByteArray)
    fun get(key: String): ByteArray
    fun delete(key: String)
    val describe: String
}

@Component
@ConditionalOnProperty(
    name = ["almira.storage.provider"],
    havingValue = "filesystem",
    matchIfMissing = true,
)
class FilesystemDocumentStorage(props: AlmiraProperties) : DocumentStorage {

    private val log = LoggerFactory.getLogger(javaClass)
    private val root: Path = Path.of(props.storage.root).toAbsolutePath().normalize()

    init {
        Files.createDirectories(root)
        log.info("document storage: filesystem at {}", root)
    }

    override val describe = "filesystem:$root"

    override fun put(key: String, ciphertext: ByteArray) {
        val target = resolve(key)
        Files.createDirectories(target.parent)
        // Write beside, then move: a crash mid-write leaves a stray temp file
        // rather than a half-written document that decrypts to nothing.
        val temp = Files.createTempFile(target.parent, ".upload", ".tmp")
        try {
            Files.write(temp, ciphertext)
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    override fun get(key: String): ByteArray = Files.readAllBytes(resolve(key))

    override fun delete(key: String) {
        Files.deleteIfExists(resolve(key))
    }

    /**
     * Keys are built by this application, but resolving them without checking
     * would make "../" in a key a path traversal. Cheap to check, catastrophic
     * to miss.
     */
    private fun resolve(key: String): Path {
        val path = root.resolve(key).normalize()
        require(path.startsWith(root)) { "storage key escapes the storage root" }
        return path
    }
}
