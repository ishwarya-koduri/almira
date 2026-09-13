package tech.bhrigu.almira.provider

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import tech.bhrigu.almira.auth.LoggingOtpSender
import tech.bhrigu.almira.config.AlmiraProperties
import tech.bhrigu.almira.reminder.OutboundNotification
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.DoubleSupplier

/**
 * Every sandbox adapter, every way of failing, through the one policy.
 *
 * The same table as docs/13 "When a provider fails", executed: for each of the
 * seven adapters (one-time codes, SMS, email, push, DigiLocker, the Account
 * Aggregator, WhatsApp replies) and each [SandboxFault], the call either gives up
 * with the right [FailureKind] after the right number of attempts, or — for a
 * hang — is cut off by the timeout.
 */
@DisplayName("Sandbox adapters x failure outcomes")
class SandboxFailureMatrixTest {

    /** Short enough to prove a hang is cut off; used only where nothing real runs. */
    private val hangTimeout = Duration.ofMillis(150)

    private fun props(timeout: Duration = Duration.ofSeconds(10)): AlmiraProperties {
        val p = AlmiraProperties.Provider(timeout = timeout, maxAttempts = 3, retryBackoff = Duration.ofMillis(500))
        return AlmiraProperties(
            db = AlmiraProperties.Db("jdbc:postgresql://x/y", "u", "p", "u2", "p2"),
            jwt = AlmiraProperties.Jwt("test-only-secret-that-is-long-enough-for-hmac256-signing"),
            otp = AlmiraProperties.Otp(),
            providers = AlmiraProperties.Providers(p, p, p, p, p, p),
            environment = "development",
        )
    }

    /**
     * One adapter operation: the fault key, the provider configuration it runs
     * under, and a setup that builds the real sandbox adapter (doing any
     * preparation while its faults are still off) and returns the call to make.
     */
    private class Operation(
        val adapter: String,
        val provider: String,
        val name: String,
        val idempotent: Boolean,
        val setup: (SandboxFaults) -> () -> Any?,
    )

    private val notification = OutboundNotification(UUID.randomUUID(), null, null, "test", "title", "body")
    private val household = UUID.randomUUID()
    private val session = ProviderSession("sandbox-token", java.time.Instant.now().plusSeconds(60), "files")
    private fun consentRequest() = ConsentRequest("test", listOf("DEPOSIT"), LocalDate.now(), LocalDate.now())

    private fun operations() = listOf(
        Operation("otp", "sms", "otp", true) { f ->
            val sender = LoggingOtpSender(props(), f); { sender.send("+919000000000", "123456") }
        },
        Operation("sms", "sms", "notify", true) { f ->
            val sender = SandboxSmsSender(f); val key = "matrix:${UUID.randomUUID()}"; { sender.send(notification, null, key) }
        },
        Operation("email", "email", "notify", true) { f ->
            val sender = SandboxEmailSender(f); val key = "matrix:${UUID.randomUUID()}"; { sender.send(notification, null, key) }
        },
        Operation("push", "push", "notify", true) { f ->
            val sender = SandboxPushSender(f); val key = "matrix:${UUID.randomUUID()}"; { sender.send(notification, null, key) }
        },
        Operation("digilocker", "digilocker", "exchange", false) { f ->
            val vault = SandboxDocumentVault(f); { vault.exchange(household, "code") }
        },
        Operation("digilocker", "digilocker", "list", true) { f ->
            val vault = SandboxDocumentVault(f); { vault.list(session) }
        },
        Operation("digilocker", "digilocker", "fetch", true) { f ->
            val vault = SandboxDocumentVault(f); { vault.fetch(session, "in.gov.pan-PANCR-ABCDE1234F") }
        },
        Operation("aa", "aa", "consent", false) { f ->
            val aa = SandboxAccountAggregator(f); { aa.requestConsent(household, consentRequest()) }
        },
        Operation("aa", "aa", "consent-status", true) { f ->
            val aa = SandboxAccountAggregator(f)
            val handle = aa.requestConsent(household, consentRequest()).handle
            ({ aa.consentStatus(handle) })
        },
        Operation("aa", "aa", "fetch", true) { f ->
            val aa = SandboxAccountAggregator(f)
            val handle = aa.requestConsent(household, consentRequest()).handle
            aa.consentStatus(handle) // approves
            ({ aa.fetch(handle) })
        },
        Operation("whatsapp", "whatsapp", "reply", true) { f ->
            val gateway = SandboxWhatsAppGateway(ObjectMapper(), f); { gateway.reply("919876543210", "Got it") }
        },
    )

    private data class Expected(val kind: FailureKind?, val attempts: Int)

    private fun expected(fault: SandboxFault?, idempotent: Boolean): Expected = when (fault) {
        null -> Expected(null, 1)
        SandboxFault.TIMEOUT, SandboxFault.HANG ->
            Expected(FailureKind.TIMEOUT, if (idempotent) 3 else 1)
        SandboxFault.UNAVAILABLE -> Expected(FailureKind.UNAVAILABLE, 3)
        SandboxFault.REJECTED -> Expected(FailureKind.REJECTED, 1)
        SandboxFault.INSUFFICIENT_BALANCE -> Expected(FailureKind.INSUFFICIENT_BALANCE, 1)
    }

    @TestFactory
    fun `each adapter, each outcome`(): List<DynamicTest> = operations().flatMap { op ->
        (listOf<SandboxFault?>(null) + SandboxFault.entries).map { fault ->
            DynamicTest.dynamicTest("${op.adapter}.${op.name} / ${fault ?: "succeeds"}") {
                val faults = SandboxFaults(Duration.ofSeconds(5))
                val call = op.setup(faults)
                fault?.let { faults.always(op.adapter, it) }
                val counted = AtomicInteger()
                val slept = ProviderCallsTest.RecordingSleeper()
                val timeout = if (fault == SandboxFault.HANG) hangTimeout else Duration.ofSeconds(10)
                val calls = ProviderCalls(props(timeout), slept, DoubleSupplier { 1.0 }, Clock.systemUTC())
                val want = expected(fault, op.idempotent)

                val started = System.nanoTime()
                val outcome = runCatching {
                    calls.execute(op.provider, op.name, op.idempotent) {
                        counted.incrementAndGet()
                        call()
                    }
                }
                val elapsed = Duration.ofNanos(System.nanoTime() - started)

                if (want.kind == null) {
                    assertThat(outcome.getOrThrow().attempts).isEqualTo(1)
                } else {
                    val failure = outcome.exceptionOrNull() as? ProviderCallFailed
                        ?: throw AssertionError("expected ProviderCallFailed, got ${outcome.exceptionOrNull() ?: "success"}")
                    assertThat(failure.kind).isEqualTo(want.kind)
                    assertThat(failure.attempts).isEqualTo(want.attempts)
                    assertThat(counted.get()).describedAs("attempts actually made").isEqualTo(want.attempts)
                    assertThat(slept.slept).describedAs("a wait between attempts, none after the last")
                        .hasSize(want.attempts - 1)
                    assertThat(calls.accountProblems().containsKey(op.provider))
                        .isEqualTo(want.kind == FailureKind.INSUFFICIENT_BALANCE)
                }
                if (fault == SandboxFault.HANG) {
                    assertThat(elapsed).describedAs("cut off by the ${hangTimeout.toMillis()}ms timeout, not the 5s hang")
                        .isLessThan(Duration.ofSeconds(3))
                }
            }
        }
    }
}
