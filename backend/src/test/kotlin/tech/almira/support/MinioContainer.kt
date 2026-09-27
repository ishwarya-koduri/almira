package tech.almira.support

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI

/**
 * An S3-compatible store for the object-storage tests: MinIO, pinned, started
 * once per JVM and only by the tests that ask for it. The credentials are
 * throwaway values for a container that lives as long as the test run.
 *
 * Like [TestInfra], an external one wins: ALMIRA_TEST_S3_ENDPOINT points at a
 * MinIO already running with these credentials (CI, or a machine where
 * Testcontainers cannot reach Docker), and nothing is started.
 */
object MinioContainer {
    const val ACCESS_KEY = "almira-test"
    const val SECRET_KEY = "almira-test-secret"
    const val REGION = "ap-south-1"

    private val external: String? = System.getenv("ALMIRA_TEST_S3_ENDPOINT")

    private val container: GenericContainer<*> by lazy {
        GenericContainer(DockerImageName.parse("minio/minio:RELEASE.2025-04-22T22-12-26Z"))
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000))
            .also { it.start() }
    }

    val endpoint: String
        get() = external ?: "http://${container.host}:${container.getMappedPort(9000)}"

    fun client(): S3Client = S3Client.builder()
        .region(Region.of(REGION))
        .endpointOverride(URI.create(endpoint))
        .forcePathStyle(true)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
        .build()

    fun createBucket(name: String): String {
        client().use { s3 -> s3.createBucket { it.bucket(name) } }
        return name
    }
}
