package tech.bhrigu.almira.provider

import org.slf4j.LoggerFactory
import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment

/**
 * What every outside service is set to, checked at startup and printed once.
 *
 * Three jobs, and the first is the reason this exists at all.
 *
 * **Say no clearly.** Before this, setting a provider to `live` removed the
 * sandbox bean and left nothing in its place, so the application died on a
 * `NoSuchBeanDefinitionException` naming an interface — technically fail-closed,
 * and useless to the person reading it, who would reasonably conclude the build
 * was broken rather than that they had asked for something that does not exist
 * yet. Refusing with a sentence costs nothing and saves an afternoon.
 *
 * **Fail closed on nonsense.** A mode that is not one of the three —
 * `disabled`, `sandbox`, `live` — refuses.
 * `almira.providers.sms.mode=liev` must not quietly fall back to sandbox and
 * spend a month not sending anything, and it must not fall back to live either.
 *
 * **Refuse a live provider with no credentials.** Turning something on and
 * forgetting its key produces an outage discovered by a person who needed a
 * one-time code, at the moment they needed it.
 *
 * **Absent is not an error.** `disabled` starts: each connect provider has a
 * disabled adapter that reports itself and refuses every call with 409
 * `provider_disabled`, and a disabled notification channel is simply not in the
 * list of senders. `off`, the old spelling, used to pass this check and then
 * crash startup for DigiLocker, Account Aggregator and WhatsApp (known-issues
 * 11). It now refuses with a sentence naming `disabled`, rather than being
 * accepted as a second word for the same state: one spelling means a grep of the
 * configuration, the startup line and the status endpoint all say the same
 * thing, and nobody can have had `off` working for a connect provider.
 *
 * **Say which one-time-code sender cannot exist.** `almira.otp.provider` has one
 * implementation, `log`; anything else used to die on a missing `OtpSender` bean
 * (known-issues 12). It refuses here instead.
 *
 * None of this fires in the defaults: `sandbox` for everything but `aa`, which is
 * `disabled` because Account Aggregator is cut from v1.
 *
 * It is an [EnvironmentPostProcessor] rather than a bean, and that is the whole
 * trick: it runs before the application context is built. A check that waited
 * for startup would never speak, because Spring would already have failed
 * trying to inject the provider bean that `live` removed — with the
 * `NoSuchBeanDefinitionException` this exists to replace.
 */
class ProviderModeCheck : EnvironmentPostProcessor {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Which providers have a live implementation at all.
     *
     * Empty, today, and deliberately a list rather than a `TODO` in a comment:
     * an adapter written against a contract nobody has seen is a guess, and
     * docs/13 records which of these have a reachable sandbox and which need
     * partner onboarding before an adapter can honestly exist. When one is
     * written, its name goes here and the refusal below stops applying to it.
     */
    private val implemented = emptySet<String>()

    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication,
    ) {
        val problems = mutableListOf<String>()
        val modes = mutableListOf<String>()

        PROVIDERS.forEach { name ->
            // Read from the environment rather than from bound properties:
            // nothing is bound yet, which is the point of running this early.
            val raw = environment.getProperty("almira.providers.$name.mode", defaultMode(name))
            val mode = raw.lowercase()
            modes += "$name=$mode"
            val credentials = CREDENTIAL_KEYS.map {
                environment.getProperty("almira.providers.$name.$it", "")
            }
            when {
                mode == "off" -> problems += buildString {
                    append("almira.providers.$name.mode is '$raw'. 'off' is not a mode any more: ")
                    append("write 'disabled', which starts, reports $name as disabled and ")
                    append("refuses its calls with provider_disabled.")
                }

                mode !in KNOWN_MODES -> problems += buildString {
                    append("almira.providers.$name.mode is '$raw', ")
                    append("which is not one of ${KNOWN_MODES.joinToString(", ")}. ")
                    append("Refusing rather than guessing which was meant.")
                }

                mode == "live" && name !in implemented -> problems += buildString {
                    append("almira.providers.$name.mode is 'live', but there is no live ")
                    append("adapter for $name yet — only a sandbox one. This is a ")
                    append("missing implementation, not a missing setting. See ")
                    append("docs/13-providers-and-going-live.md for what $name needs ")
                    append("before one can be written.")
                }

                mode == "live" && credentials.all { it.isBlank() } -> problems += buildString {
                    append("almira.providers.$name.mode is 'live' with no credentials ")
                    append("configured. A live provider without a key is an outage ")
                    append("discovered by whoever needed it.")
                }
            }
        }

        val otpProvider = environment.getProperty("almira.otp.provider", "log")
        if (otpProvider.lowercase() !in OTP_SENDERS) {
            problems += buildString {
                append("almira.otp.provider is '$otpProvider', but the only one-time-code sender ")
                append("that exists is ${OTP_SENDERS.joinToString(", ") { "'$it'" }}. A live SMS sender ")
                append("is not written yet — see docs/providers/sms.md.")
            }
        }

        if (problems.isNotEmpty()) {
            throw IllegalStateException(
                "Refusing to start — provider configuration:\n  " +
                    problems.joinToString("\n  "),
            )
        }

        // Printed at every boot, because "which of these is real right now" is
        // the question people get wrong about a deployment, and an answer in the
        // log is one nobody has to derive from six environment variables.
        log.info(
            "Providers: {}  (otp sender: {})",
            modes.joinToString("  "),
            environment.getProperty("almira.otp.provider", "log"),
        )
    }

    /** Exposed so a test can assert the list rather than restate it. */
    companion object {
        fun providerNames(): List<String> = PROVIDERS
        fun credentialKeys(): List<String> = CREDENTIAL_KEYS

        val KNOWN_MODES = setOf("disabled", "sandbox", "live")
        val PROVIDERS = listOf("sms", "email", "push", "digilocker", "aa", "whatsapp")

        /**
         * What a provider is when nothing says otherwise. The same as the
         * defaults in application.yml and AlmiraProperties; used only when a
         * property source without application.yml is checked.
         */
        val DEFAULT_MODES: Map<String, String> = PROVIDERS.associateWith { if (it == "aa") "disabled" else "sandbox" }

        fun defaultMode(name: String): String = DEFAULT_MODES.getValue(name)

        /** Every `OtpSender` implementation, by its `almira.otp.provider` value. */
        val OTP_SENDERS = setOf("log")

        /**
         * Enough of a credential to be worth trying: a client pair, a single
         * key, or — for SMS in India — the DLT sender and template ids, which
         * are what actually decide whether an operator delivers the message.
         */
        val CREDENTIAL_KEYS = listOf(
            "client-id", "client-secret", "api-key", "sender-id", "template-id",
        )
    }
}

