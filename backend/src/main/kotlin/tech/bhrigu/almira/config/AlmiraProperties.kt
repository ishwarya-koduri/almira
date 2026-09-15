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
    val auth: Auth = Auth(),
    val outbox: Outbox = Outbox(),
    val webauthn: WebAuthn = WebAuthn(),
    val ops: Ops = Ops(),
    /**
     * Gates the checks that must not be bypassable by forgetting a flag:
     * anything other than exactly "development" makes them strict.
     *
     * Empty by default, not "development". A default of development is a
     * default of "relax every protection", and it applied precisely when
     * somebody had forgotten to say what they were running — see
     * application.yml for what that allowed.
     */
    val environment: String = "",
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
        val s3: S3 = S3(),
    ) {
        /**
         * S3 or an S3-compatible store (R2, MinIO, an Indian-region provider).
         * Read only when `provider` is `s3`. Blank keys mean the SDK's default
         * credential chain (an instance role), never an anonymous client.
         */
        data class S3(
            val bucket: String = "",
            val region: String = "",
            /** Blank for AWS itself; the URL of anything S3-compatible. */
            val endpoint: String = "",
            val accessKeyId: String = "",
            val secretAccessKey: String = "",
            /** `bucket.host` (false) or `host/bucket` (true). Most compatible stores want true. */
            val pathStyle: Boolean = false,
            /** Prepended to every key, so one bucket can hold more than one install. */
            val prefix: String = "documents/",
        )
    }

    data class Otp(
        /** `log` prints the code to the application log — local development only. */
        val provider: String = "log",
        val length: Int = 6,
        val ttl: Duration = Duration.ofMinutes(5),
        val maxAttempts: Int = 5,
        val resendCooldown: Duration = Duration.ofSeconds(30),
        val maxPerHour: Int = 5,
        val maxPerIpPerHour: Int = 20,
        /**
         * Wrong codes one network may submit in an hour, across every number.
         * The per-challenge cap stops guessing at one person; this stops one
         * host guessing five times at each of a thousand people whose codes are
         * live. Only misses count, so an office signing in many people is not
         * throttled by succeeding.
         */
        val maxVerifyFailuresPerIpPerHour: Int = 30,
        /**
         * How long one send of a code may take before the person is told it is
         * delayed. One attempt, never retried: somebody is waiting, and their
         * resend button is the retry (docs/13 "Interactive and background").
         *
         * Five seconds, because an SMS or email gateway's send API answers when
         * it has ACCEPTED a message, not when it is delivered, and that answer
         * normally takes well under a second — five is several times a normal
         * slow answer, and still short enough that the person sees "delayed"
         * and a working resend button while they are looking at the screen.
         * The provider's own `timeout` (10s for SMS) is sized for a background
         * job and is not used. Bounded to (0, 15s] at startup by OtpService.
         */
        val sendTimeout: Duration = Duration.ofSeconds(5),
    )

    /**
     * The background worker that sends queued notifications
     * (provider/NotificationOutbox.kt, docs/13 "Interactive and background").
     */
    data class Outbox(
        /**
         * How often it looks for queued rows, as well as being woken after each
         * commit that queues one. Read by the @Scheduled annotation; ISO-8601
         * (`PT2S`). It bounds how long a message a wake missed, or one left by a
         * stopped worker, waits.
         */
        val pollInterval: Duration = Duration.ofSeconds(2),
        /** Rows claimed per transaction. */
        val batchSize: Int = 50,
        /**
         * Whether this process drains on its own — the poll and the wake after
         * each commit. Always on in a real server. The test suite turns it off:
         * one JVM holds many cached application contexts against one database,
         * and every one of them polling made each outbox test race workers it
         * could not see (a row "sent" while the test had its worker paused).
         * Tests drive [tech.bhrigu.almira.provider.NotificationOutbox.drain]
         * themselves; OutboxBackgroundDrainTest keeps the background path proven.
         */
        val background: Boolean = true,
    )

    /**
     * What only the operator sees (docs/17 §8).
     *
     * [healthToken] (`ALMIRA_OPS_HEALTH_TOKEN`): empty by default, and then
     * nothing here exists. Set, a request to `/health` carrying it in
     * `X-Almira-Ops-Token` is also told how many sign-in emails a listed
     * address did not get in the last hour (auth/SignInEmailAlerts.kt). It is
     * behind a token because a count anyone could read would say, to someone
     * who had just asked for a code for an address, whether that address is on
     * the allowlist. Without it, or with a wrong one, `/health` answers exactly
     * as it always has.
     */
    data class Ops(val healthToken: String = "") {
        init {
            require(healthToken.isEmpty() || healthToken.length >= MIN_TOKEN_LENGTH) {
                "almira.ops.health-token (ALMIRA_OPS_HEALTH_TOKEN) is ${healthToken.length} characters; " +
                    "it must be at least $MIN_TOKEN_LENGTH (openssl rand -hex 32), or empty to switch the " +
                    "operator's view of /health off."
            }
        }

        /** Never print the token, wherever these properties are logged. */
        override fun toString() = "Ops(healthToken=${if (healthToken.isEmpty()) "unset" else "set"})"

        companion object {
            const val MIN_TOKEN_LENGTH = 32
        }
    }

    /**
     * Passkeys (auth/PasskeyService.kt). Off unless [rpId] and [origins] are
     * both set: a passkey is bound to a domain for life, so the domain is chosen
     * by whoever deploys, never defaulted. Nothing here calls out.
     */
    data class WebAuthn(
        /** The registrable domain passkeys are made for, e.g. `almira.example.in`. `localhost` in development. */
        val rpId: String = "",
        /** What a person's device shows as the name of the site. */
        val rpName: String = "Almira",
        /** Exact origins a ceremony may come from, e.g. `https://almira.example.in`. */
        val origins: List<String> = emptyList(),
    ) {
        val enabled: Boolean get() = rpId.isNotBlank() && origins.any { it.isNotBlank() }
    }

    /**
     * How people may sign in. Checked at startup by SignInChannels, which
     * refuses a value it does not understand rather than guessing.
     */
    data class Auth(
        /**
         * `phone`, `email`, or both. `phone` by default, so a fresh checkout,
         * the test suites and the end-to-end scripts behave exactly as before.
         *
         * The closed alpha runs `email` ALONE. With both on, the same person can
         * sign in once with a number and once with an address and end up with
         * two accounts that nothing joins — which is why the owner chose one.
         */
        val signInChannels: List<String> = listOf("phone"),
        /**
         * The only addresses that can sign in by email. Normalised the way
         * sign-in normalises (trimmed, lower-cased, nothing else — see
         * EmailAddress). There is no wildcard and an empty list admits nobody:
         * with email enabled and this empty, the server refuses to start,
         * because a closed alpha nobody can enter is a misconfiguration, not a
         * policy.
         *
         * Being left off it is invisible from outside: the request is answered
         * exactly as for a listed address, its email is queued the same way and
         * dropped by the worker with no provider call, and the challenge it
         * creates cannot be completed (AuthService.requestEmailOtp,
         * SignInEmailOutbox).
         */
        val emailAllowlist: List<String> = emptyList(),
    )

    /**
     * Every outside service, and which of three states it is in: `disabled`,
     * `sandbox` or `live`.
     *
     * These modes were being read by `@ConditionalOnProperty` annotations and
     * declared nowhere else — not here, not in application.yml, not in
     * `.env.production.example`. That is why they are a typed block now: a
     * switch nobody can find is a switch nobody can audit, and "flip one live
     * with a config change" is not a claim you can make about a property that
     * exists only inside an annotation.
     *
     * `sandbox` is the default for all of them but `aa` and `digilocker`, so a
     * fresh checkout runs with no configuration and talks to nothing real. `aa`
     * defaults to `disabled`: Account Aggregator is cut from v1, because
     * production access needs an FIU regulated by RBI, SEBI, IRDAI or PFRDA
     * (owner's decision, docs/providers/account-aggregator.md). `digilocker` is
     * `disabled` too: no client offers it yet, and it stays hidden until one
     * does (docs/providers/digilocker.md). Keep these defaults in step with
     * application.yml, `.env.production.example` and ProviderModeCheck.DEFAULT_MODES.
     */
    data class Providers(
        /** One-time codes for sign-in. See also [Otp.provider], which selects the sender. */
        val sms: Provider = Provider(),
        val email: Provider = Provider(),
        val push: Provider = Provider(),
        /** DigiLocker. Hidden until a client offers it, so disabled unless asked for. */
        val digilocker: Provider = Provider(mode = "disabled"),
        /** Account Aggregator, under the RBI framework. Cut from v1, so disabled unless asked for. */
        val aa: Provider = Provider(mode = "disabled"),
        val whatsapp: Provider = Provider(),
        /**
         * The most a person waits on one DigiLocker or Account Aggregator call,
         * retries included (ProviderCalls.interactive, known-issues 21). The
         * provider's own `timeout` still bounds each attempt; this bounds them all.
         */
        val connectBudget: Duration = Duration.ofSeconds(20),
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
        /**
         * `disabled`, `sandbox` or `live`. Anything else refuses to start —
         * including `off`, the old name for `disabled` (see ProviderModeCheck).
         */
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
        /**
         * Attempts in total, not retries after the first. 1 disables retrying.
         * Only timeouts and "unavailable" are ever retried — see ProviderCalls,
         * which enforces [timeout], this and [retryBackoff] on every call.
         */
        val maxAttempts: Int = 3,
        /** Doubling from here, with jitter, between attempts. */
        val retryBackoff: Duration = Duration.ofMillis(500),
        /**
         * For `email` only: the SMTP relay the live adapter sends through
         * (provider/SmtpEmailSender.kt). Any provider that offers SMTP — SES,
         * Postmark, a company relay — so choosing one is configuration, not code.
         * Ignored by every other provider and by the email sandbox.
         */
        val smtp: Smtp = Smtp(),
    ) {
        val isLive: Boolean get() = mode.equals("live", ignoreCase = true)
        val isSandbox: Boolean get() = mode.equals("sandbox", ignoreCase = true)
        val isDisabled: Boolean get() = mode.equals("disabled", ignoreCase = true)
    }

    /**
     * An SMTP relay. Nothing here is read unless `almira.providers.email.mode` is
     * `live`, and then [host] and [from] are required at startup
     * (ProviderModeCheck). The password comes from the environment.
     */
    data class Smtp(
        val host: String = "",
        /** 587 with STARTTLS is what every current relay documents. */
        val port: Int = 587,
        val username: String = "",
        val password: String = "",
        /** The verified sending address, e.g. `Almira <reminders@example.in>`. */
        val from: String = "",
        /**
         * Required by default: an SMTP session that falls back to plain text sends
         * a reminder's amount and institution across the network readable. Only a
         * relay on the same host, or a test's fake server, turns this off.
         */
        val startTls: Boolean = true,
    )

    /** True only when development was chosen, never when nothing was said. */
    val isDevelopment: Boolean get() = environment.equals("development", ignoreCase = true)

    companion object {
        /**
         * Said once, so every check that refuses because nothing was chosen
         * explains the same way. Without it the message would describe the
         * symptom — "the JWT secret is the development default" — to someone
         * whose actual fix is one environment variable.
         */
        const val MISSING_ENVIRONMENT_HINT =
            " ALMIRA_ENV is not set, and an unset environment is not treated as " +
                "development. For a local run set ALMIRA_ENV=development; for anything " +
                "else set ALMIRA_ENV=production and supply the value above."

        /**
         * The value in application.yml, so that JwtService can recognise it and
         * refuse to run with it anywhere but development. It is a placeholder,
         * not a secret — which is exactly the problem it guards against.
         */
        const val DEVELOPMENT_JWT_SECRET =
            "development-only-secret-please-override-in-every-real-environment"
    }
}
