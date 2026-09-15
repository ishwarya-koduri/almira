package tech.bhrigu.almira.document

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.S3Exception
import tech.bhrigu.almira.config.AlmiraProperties
import tech.bhrigu.almira.config.StartupRefusal
import java.net.URI
import java.nio.file.NoSuchFileException
import java.time.Duration

/**
 * Documents in S3 or an S3-compatible store, selected by
 * `almira.storage.provider=s3`. The filesystem stays the default; nothing here
 * is constructed, and nothing is called, unless this is chosen.
 *
 * What it is handed is already ciphertext (see [DocumentStorage]), so the bucket
 * policy is a second line, not the first: a public bucket leaks bytes that open
 * only with the household's key, which itself opens only with the KMS key.
 *
 * It refuses to start rather than fail on the first upload: a missing bucket or
 * region, or a bucket this identity cannot reach, is named at boot, before a
 * family's scan is the thing that discovers it.
 *
 * Those refusals are in the constructor, and it is a [StartupRefusal] only so
 * that the constructor runs before Flyway migrates: the Flyway bean takes every
 * StartupRefusal as a parameter, which builds this first. Before that it was
 * built whenever the first upload-handling bean needed it — after migration.
 * [verifyBeforeMigrating] has nothing left to do.
 */
@Component
@ConditionalOnProperty(name = ["almira.storage.provider"], havingValue = "s3")
class S3DocumentStorage(props: AlmiraProperties) : DocumentStorage, DisposableBean, StartupRefusal {

    private val log = LoggerFactory.getLogger(javaClass)
    private val config = props.storage.s3
    private val bucket = config.bucket.trim()
    private val prefix = config.prefix.trim().trimStart('/')

    private val client: S3Client

    init {
        val missing = buildList {
            if (bucket.isEmpty()) add("ALMIRA_S3_BUCKET")
            if (config.region.isBlank()) add("ALMIRA_S3_REGION")
            if (config.accessKeyId.isBlank() != config.secretAccessKey.isBlank()) {
                add("both ALMIRA_S3_ACCESS_KEY_ID and ALMIRA_S3_SECRET_ACCESS_KEY, or neither")
            }
        }
        check(missing.isEmpty()) {
            "Refusing to start — almira.storage.provider is s3 but ${missing.joinToString(", ")} " +
                "is not set. Set it, or use the filesystem provider."
        }

        val credentials: AwsCredentialsProvider =
            if (config.accessKeyId.isNotBlank()) {
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(config.accessKeyId, config.secretAccessKey),
                )
            } else {
                DefaultCredentialsProvider.builder().build()
            }

        client = S3Client.builder()
            .region(Region.of(config.region.trim()))
            .credentialsProvider(credentials)
            .forcePathStyle(config.pathStyle)
            .apply { if (config.endpoint.isNotBlank()) endpointOverride(URI.create(config.endpoint.trim())) }
            .overrideConfiguration {
                it.apiCallTimeout(Duration.ofSeconds(60)).apiCallAttemptTimeout(Duration.ofSeconds(20))
            }
            .build()

        try {
            client.headBucket { it.bucket(bucket) }
        } catch (e: S3Exception) {
            client.close()
            throw IllegalStateException(
                "Refusing to start — the document bucket '$bucket' cannot be reached with these " +
                    "credentials (S3 status ${e.statusCode()}). Check the bucket name, region and access.",
            )
        }
        log.info("document storage: s3 bucket {} prefix {}", bucket, prefix)
    }

    /** Construction was the check: a bucket that cannot be reached has already refused. */
    override fun verifyBeforeMigrating() = Unit

    override val describe = "s3:$bucket/$prefix"

    override fun put(key: String, ciphertext: ByteArray) {
        client.putObject(
            { it.bucket(bucket).key(objectKey(key)).contentType("application/octet-stream") },
            RequestBody.fromBytes(ciphertext),
        )
    }

    override fun get(key: String): ByteArray = try {
        client.getObjectAsBytes { it.bucket(bucket).key(objectKey(key)) }.asByteArray()
    } catch (_: NoSuchKeyException) {
        // The same thing the filesystem provider throws for a missing file.
        throw NoSuchFileException(key)
    }

    /** Deleting a key that is not there is not an error, as on the filesystem. */
    override fun delete(key: String) {
        client.deleteObject { it.bucket(bucket).key(objectKey(key)) }
    }

    override fun destroy() = client.close()

    /**
     * Keys are built by DocumentService (`<household>/<document>`). An object
     * store has no directories to escape, but a key that would be a traversal on
     * the filesystem is refused here too, so the two providers accept the same
     * keys and a key moved between them means the same object.
     */
    private fun objectKey(key: String): String {
        require(key.isNotEmpty() && !key.startsWith("/") && key.split('/').none { it == ".." || it == "." || it.isEmpty() }) {
            "storage key escapes the storage root"
        }
        return prefix + key
    }
}
