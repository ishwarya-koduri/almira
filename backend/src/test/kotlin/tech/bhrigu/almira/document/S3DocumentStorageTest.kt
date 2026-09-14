package tech.bhrigu.almira.document

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import tech.bhrigu.almira.config.AlmiraProperties
import tech.bhrigu.almira.support.MinioContainer
import java.nio.file.NoSuchFileException
import java.util.UUID

@DisplayName("S3 document storage: the same contract as the filesystem, in a bucket")
class S3DocumentStorageTest {

    private fun properties(s3: AlmiraProperties.Storage.S3) = AlmiraProperties(
        db = AlmiraProperties.Db("jdbc:postgresql://unused/x", "o", "o", "a", "a"),
        jwt = AlmiraProperties.Jwt("unused-secret"),
        otp = AlmiraProperties.Otp(),
        storage = AlmiraProperties.Storage(provider = "s3", s3 = s3),
    )

    private fun minio(bucket: String, prefix: String = "documents/") = AlmiraProperties.Storage.S3(
        bucket = bucket, region = MinioContainer.REGION, endpoint = MinioContainer.endpoint,
        accessKeyId = MinioContainer.ACCESS_KEY, secretAccessKey = MinioContainer.SECRET_KEY,
        pathStyle = true, prefix = prefix,
    )

    private fun bucket() = MinioContainer.createBucket("almira-" + UUID.randomUUID().toString().take(8))

    @Test
    fun `put, get and delete round-trip bytes under the prefix`() {
        val bucket = bucket()
        val storage = S3DocumentStorage(properties(minio(bucket)))
        val key = "${UUID.randomUUID()}/${UUID.randomUUID()}"
        val bytes = ByteArray(300_000) { (it % 251).toByte() }

        storage.put(key, bytes)
        assertThat(storage.get(key)).isEqualTo(bytes)
        MinioContainer.client().use { s3 ->
            assertThat(s3.listObjectsV2 { it.bucket(bucket) }.contents().map { it.key() })
                .containsExactly("documents/$key")
        }

        storage.put(key, byteArrayOf(1, 2, 3))
        assertThat(storage.get(key)).containsExactly(1, 2, 3)

        storage.delete(key)
        assertThatThrownBy { storage.get(key) }.isInstanceOf(NoSuchFileException::class.java)
        storage.delete(key) // absent is not an error
        storage.destroy()
    }

    @Test
    fun `a key that would escape on the filesystem is refused here too`() {
        val storage = S3DocumentStorage(properties(minio(bucket())))
        listOf("../other/x", "/abs", "a//b", "a/./b", "").forEach { key ->
            assertThatThrownBy { storage.put(key, byteArrayOf(1)) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
        storage.destroy()
    }

    @Test
    fun `missing settings refuse to start, naming them`() {
        assertThatThrownBy { S3DocumentStorage(properties(AlmiraProperties.Storage.S3())) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("ALMIRA_S3_BUCKET").hasMessageContaining("ALMIRA_S3_REGION")
        assertThatThrownBy {
            S3DocumentStorage(properties(minio("x").copy(secretAccessKey = "")))
        }.hasMessageContaining("or neither")
    }

    @Test
    fun `a bucket that is not there refuses to start`() {
        assertThatThrownBy { S3DocumentStorage(properties(minio("almira-no-such-bucket"))) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("almira-no-such-bucket")
            .hasMessageNotContaining(MinioContainer.SECRET_KEY)
    }

    @Test
    fun `an unknown provider refuses before the context is built`() {
        fun check(value: String?) {
            val env = StandardEnvironment()
            if (value != null) env.propertySources.addFirst(MapPropertySource("t", mapOf("almira.storage.provider" to value)))
            StorageProviderCheck().postProcessEnvironment(env, SpringApplication())
        }
        check(null)
        check("filesystem")
        check("s3")
        assertThatThrownBy { check("minio") }.hasMessageContaining("filesystem, s3")
    }
}
