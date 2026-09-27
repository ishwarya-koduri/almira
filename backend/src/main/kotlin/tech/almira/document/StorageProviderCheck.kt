package tech.almira.document

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment

/**
 * `almira.storage.provider` is `filesystem` or `s3`, and nothing else.
 *
 * Without this, a typo (`S3 `, `minio`, `gcs`) left no [DocumentStorage] bean and
 * startup died on a NoSuchBeanDefinitionException naming an interface — the
 * same unhelpful refusal ProviderModeCheck exists to replace. Checked before
 * the context is built, for the same reason.
 */
class StorageProviderCheck : EnvironmentPostProcessor {

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val raw = environment.getProperty("almira.storage.provider", "filesystem")
        check(raw in PROVIDERS) {
            "Refusing to start — almira.storage.provider (ALMIRA_STORAGE_PROVIDER) is '$raw', " +
                "which is not one of ${PROVIDERS.joinToString(", ")}."
        }
    }

    companion object {
        val PROVIDERS = listOf("filesystem", "s3")
    }
}
