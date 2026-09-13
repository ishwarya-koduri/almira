package tech.bhrigu.almira.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "almira")
data class AlmiraProperties(
    val db: Db,
    val jwt: Jwt,
    val otp: Otp,
    val encryption: Encryption = Encryption(),
    val storage: Storage = Storage(),
    val providers: Providers = Providers(),
    /**
     * Gates the checks that must not be bypassable by forgetting a flag:
     * anything other than "development" requires a real key-encryption key.
     */
    val environment: String = "development",
) {
    /**
     * Two sets of credentials against the same database, on purpose.
     *
     * `owner` runs migrations and owns the schema. `app` is what serves
     * requests. PostgreSQL lets a table's owner bypass its own row-level
     * security policies, so serving traffic as the owner would quietly disable
     * every privacy policy in V4. Splitting the roles is what makes RLS real.
     */
    data class Db(
        val url: String,
        val ownerUser: String,
        val ownerPassword: String,
        val appUser: String,
        val appPassword: String,
        val maxPoolSize: Int = 10,
    )

    data class Jwt(
        val secret: String,
        val issuer: String = "almira",
        val accessTtl: Duration = Duration.ofMinutes(15),
        val refreshTtl: Duration = Duration.ofDays(30),
    )

    data class Encryption(
        /** local | aws-kms | gcp-kms — the KeyManagementService implementation. */
        val provider: String = "local",
        /** Base64, 32 bytes. Required outside development; see LocalKeyManagement. */
        val masterKey: String = "",
        /** Key identifier for a managed KMS provider. */
        val kmsKeyId: String = "",
        /**
         * Where the local provider keeps this install's development key when no
         * master key is configured. Gitignored, generated on first run, unique
         * per machine. Delete it to rotate.
         */
        val devKeyFile: String = "./var/dev-kek",
    )

    data class Storage(
        /** filesystem | s3 — the DocumentStorage implementation. */
        val provider: String = "filesystem",
        /** Where the filesystem provider writes. Encrypted bytes only. */
        val root: String = "./var/documents",
        val maxFileBytes: Long = 20L * 1024 * 1024,
    )

    data class Otp(
        /** `log` prints the code to the application log — local development only. */
        val provider: String = "log",
        val length: Int = 6,
        val ttl: Duration = Duration.ofMinutes(5),
        val maxAttempts: Int = 5,
        val resendCooldown: Duration = Duration.ofSeconds(30),
        val maxPerHour: Int = 5,
        val maxPerIpPerHour: Int = 20,
    )

    /**
     * Every outside service, and which of three states it is in.
     *
     * These modes were being read by `@ConditionalOnProperty` annotations and
     * declared nowhere else — not here, not in application.yml, not in
     * `.env.production.example`. That is why they are a typed block now: a
     * switch nobody can find is a switch nobody can audit, and "flip one live
     * with a config change" is not a claim you can make about a property that
     * exists only inside an annotation.
     *
     * `sandbox` is the default for all of them, so a fresh checkout runs with
     * no configuration and talks to nothing real.
     */
    data class Providers(
        /** One-time codes for sign-in. See also [Otp.provider], which selects the sender. */
        val sms: Provider = Provider(),
        val email: Provider = Provider(),
        val push: Provider = Provider(),
        val digilocker: Provider = Provider(),
        /** Account Aggregator, under the RBI framework. */
        val aa: Provider = Provider(),
        val whatsapp: Provider = Provider(),
    ) {
        /** Named so the startup report can print them without a `when`. */
        fun all(): Map<String, Provider> = mapOf(
            "sms" to sms, "email" to email, "push" to push,
            "digilocker" to digilocker, "aa" to aa, "whatsapp" to whatsapp,
        )
    }

    /**
     * One outside service's configuration.
     *
     * Credentials live here and are read from the environment — never from
     * code, never from a checked-in file. [baseUrl] is configurable because a
     * provider's sandbox and production endpoints differ, and pointing at the
     * wrong one should be a configuration mistake rather than a redeploy.
     */
    data class Provider(
        /** `off`, `sandbox` or `live`. Anything else refuses to start. */
        val mode: String = "sandbox",
        val baseUrl: String = "",
        val clientId: String = "",
        val clientSecret: String = "",
        /** A single token, where a provider uses one instead of a pair. */
        val apiKey: String = "",
        /** For verifying inbound webhooks. */
        val webhookSecret: String = "",
        /**
         * India's DLT registration, for SMS. The sender id and template id are
         * not secrets, but sending with the wrong ones means the operator drops
         * the message silently — so they belong in configuration where they can
         * be corrected without a build.
         */
        val senderId: String = "",
        val templateId: String = "",
        /**
         * How long to wait before giving up on one call. Deliberately short:
         * an outside service that has not answered in ten seconds is not going
         * to, and a request thread held open is a request thread not serving
         * somebody.
         */
        val timeout: Duration = Duration.ofSeconds(10),
        /** Attempts in total, not retries after the first. 1 disables retrying. */
        val maxAttempts: Int = 3,
        /** Doubling from here, with jitter, between attempts. */
        val retryBackoff: Duration = Duration.ofMillis(500),
    ) {
        val isLive: Boolean get() = mode.equals("live", ignoreCase = true)
        val isSandbox: Boolean get() = mode.equals("sandbox", ignoreCase = true)
        val isOff: Boolean get() = mode.equals("off", ignoreCase = true)
    }

    companion object {
        /**
         * The value in application.yml, so that JwtService can recognise it and
         * refuse to run with it anywhere but development. It is a placeholder,
         * not a secret — which is exactly the problem it guards against.
         */
        const val DEVELOPMENT_JWT_SECRET =
            "development-only-secret-please-override-in-every-real-environment"
    }
}
