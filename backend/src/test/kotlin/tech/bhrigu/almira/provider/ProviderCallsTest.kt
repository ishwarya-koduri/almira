package tech.bhrigu.almira.provider

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tech.bhrigu.almira.config.AlmiraProperties
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.DoubleSupplier

/**
 * The timeout and retry policy, on its own. Deterministic: backoff goes to a
 * recording sleeper and jitter is fixed, so nothing here sits through a real
 * delay except the one test that proves the timeout is real.
 */
@DisplayName("Provider calls: timeout and retry")
class ProviderCallsTest {

    class RecordingSleeper : Sleeper {
        val slept = CopyOnWriteArrayList<Duration>()
        override fun sleep(duration: Duration) { slept += duration }
    }

    private val sleeper = RecordingSleeper()

    private fun props(
        timeout: Duration = Duration.ofSeconds(10),
        maxAttempts: Int = 3,
        backoff: Duration = Duration.ofMillis(500),
    ): AlmiraProperties {
        val p = AlmiraProperties.Provider(timeout = timeout, maxAttempts = maxAttempts, retryBackoff = backoff)
        return AlmiraProperties(
            db = AlmiraProperties.Db("jdbc:postgresql://x/y", "u", "p", "u2", "p2"),
            jwt = AlmiraProperties.Jwt("test-only-secret-that-is-long-enough-for-hmac256-signing"),
            otp = AlmiraProperties.Otp(),
            providers = AlmiraProperties.Providers(p, p, p, p, p, p),
        )
    }

    private fun calls(props: AlmiraProperties = props(), jitter: Double = 1.0) = ProviderCalls(
        props, sleeper, DoubleSupplier { jitter },
        Clock.fixed(Instant.parse("2026-09-13T00:00:00Z"), ZoneOffset.UTC),
    )

    private fun failing(kind: FailureKind, succeedOn: Int = Int.MAX_VALUE): Pair<AtomicInteger, () -> String> {
        val count = AtomicInteger()
        return count to {
            if (count.incrementAndGet() >= succeedOn) "ok" else throw ProviderFailure(kind, "test")
        }
    }

    private fun gaveUp(block: () -> Unit): ProviderCallFailed =
        runCatching(block).exceptionOrNull() as? ProviderCallFailed
            ?: throw AssertionError("expected ProviderCallFailed")

    @Test
    fun `a call that succeeds is made once and never waits`() {
        val result = calls().execute("sms", "notify") { "sent" }
        assertThat(result).isEqualTo(ProviderResult("sent", 1))
        assertThat(sleeper.slept).isEmpty()
    }

    @Test
    fun `a timeout is retried up to maxAttempts in total, with doubling backoff`() {
        val (count, block) = failing(FailureKind.TIMEOUT)
        val failure = gaveUp { calls().call("sms", "notify", block = block) }
        assertThat(failure.kind).isEqualTo(FailureKind.TIMEOUT)
        assertThat(failure.attempts).isEqualTo(3)
        assertThat(count.get()).describedAs("three attempts in total, not three retries").isEqualTo(3)
        assertThat(sleeper.slept).containsExactly(Duration.ofMillis(500), Duration.ofMillis(1000))
    }

    @Test
    fun `unavailable is retried, and a later success is reported with its attempt count`() {
        val (count, block) = failing(FailureKind.UNAVAILABLE, succeedOn = 3)
        val result = calls().execute("digilocker", "list", block = block)
        assertThat(result.attempts).isEqualTo(3)
        assertThat(count.get()).isEqualTo(3)
    }

    @Test
    fun `a rejection is never retried`() {
        val (count, block) = failing(FailureKind.REJECTED)
        val failure = gaveUp { calls().call("sms", "notify", block = block) }
        assertThat(failure.kind).isEqualTo(FailureKind.REJECTED)
        assertThat(failure.attempts).isEqualTo(1)
        assertThat(count.get()).isEqualTo(1)
        assertThat(sleeper.slept).isEmpty()
    }

    @Test
    fun `insufficient balance is never retried, and is raised for the operator`() {
        val calls = calls()
        val (count, block) = failing(FailureKind.INSUFFICIENT_BALANCE)
        val failure = gaveUp { calls.call("sms", "otp", block = block) }
        assertThat(failure.kind).isEqualTo(FailureKind.INSUFFICIENT_BALANCE)
        assertThat(count.get()).isEqualTo(1)
        assertThat(sleeper.slept).isEmpty()
        assertThat(calls.accountProblems()).containsKey("sms")

        val (_, rejected) = failing(FailureKind.REJECTED)
        runCatching { calls.call("email", "notify", block = rejected) }
        assertThat(calls.accountProblems())
            .describedAs("a rejected message is not an account problem")
            .doesNotContainKey("email")
    }

    @Test
    fun `an operation that must not happen twice is not retried after a timeout, but is after unavailable`() {
        val (timeouts, timingOut) = failing(FailureKind.TIMEOUT)
        assertThat(gaveUp { calls().call("aa", "consent", idempotent = false, block = timingOut) }.attempts)
            .isEqualTo(1)
        assertThat(timeouts.get()).isEqualTo(1)

        val (refusals, refusing) = failing(FailureKind.UNAVAILABLE)
        assertThat(gaveUp { calls().call("aa", "consent", idempotent = false, block = refusing) }.attempts)
            .describedAs("nothing was accepted, so trying again cannot duplicate it")
            .isEqualTo(3)
        assertThat(refusals.get()).isEqualTo(3)
    }

    @Test
    fun `maxAttempts of one disables retrying`() {
        val (count, block) = failing(FailureKind.TIMEOUT)
        gaveUp { calls(props(maxAttempts = 1)).call("push", "notify", block = block) }
        assertThat(count.get()).isEqualTo(1)
    }

    @Test
    fun `something that is not a provider failure passes through unchanged, after one attempt`() {
        val count = AtomicInteger()
        assertThatThrownBy {
            calls().call("aa", "fetch") {
                count.incrementAndGet()
                throw IllegalStateException("consent is not active")
            }
        }.isInstanceOf(IllegalStateException::class.java).hasMessage("consent is not active")
        assertThat(count.get()).isEqualTo(1)
    }

    @Test
    fun `the timeout is enforced here, not trusted to the adapter`() {
        // The sandbox's HANG never answers inside the timeout. Only ProviderCalls
        // can turn that into a TIMEOUT; without enforcement this call would
        // wait out the hang and then succeed.
        val faults = SandboxFaults(Duration.ofSeconds(5))
        faults.always("sms", SandboxFault.HANG)
        val sender = SandboxSmsSender(faults)
        val notification = tech.bhrigu.almira.reminder.OutboundNotification(
            java.util.UUID.randomUUID(), null, null, "test", "title", "body",
        )

        val started = System.nanoTime()
        val failure = gaveUp {
            calls(props(timeout = Duration.ofMillis(100), maxAttempts = 2))
                .call("sms", "notify") { sender.send(notification, null, "test-key") }
        }
        val elapsed = Duration.ofNanos(System.nanoTime() - started)

        assertThat(failure.kind).isEqualTo(FailureKind.TIMEOUT)
        assertThat(failure.attempts).isEqualTo(2)
        assertThat(elapsed).describedAs("two 100ms attempts, not two 5s hangs").isLessThan(Duration.ofSeconds(2))
    }

    @Test
    fun `jitter spreads the backoff between half and all of it, and it is capped`() {
        val low = calls(jitter = 0.0)
        val high = calls(jitter = 1.0)
        assertThat(low.backoff(Duration.ofMillis(500), 1)).isEqualTo(Duration.ofMillis(250))
        assertThat(high.backoff(Duration.ofMillis(500), 1)).isEqualTo(Duration.ofMillis(500))
        assertThat(high.backoff(Duration.ofMillis(500), 3)).isEqualTo(Duration.ofMillis(2000))
        assertThat(high.backoff(Duration.ofSeconds(10), 30)).isEqualTo(ProviderCalls.MAX_BACKOFF)
    }

    @Test
    fun `configuration that would make the policy meaningless refuses to start`() {
        assertThatThrownBy { calls(props(maxAttempts = 0)) }.hasMessageContaining("max-attempts")
        assertThatThrownBy { calls(props(maxAttempts = 50)) }.hasMessageContaining("max-attempts")
        assertThatThrownBy { calls(props(timeout = Duration.ZERO)) }.hasMessageContaining("timeout")
        assertThatThrownBy { calls(props(backoff = Duration.ofMinutes(5))) }.hasMessageContaining("retry-backoff")
    }

    @Test
    fun `an unknown provider name is a bug, not a silent default policy`() {
        assertThatThrownBy { calls().call("carrier-pigeon", "send") { "ok" } }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `callOnce makes one attempt under its own timeout, whatever the provider allows`() {
        for (kind in FailureKind.entries) {
            val counted = java.util.concurrent.atomic.AtomicInteger()
            val calls = calls(props(timeout = Duration.ofSeconds(60), maxAttempts = 5))
            val failure = runCatching {
                calls.callOnce("sms", "otp", Duration.ofMillis(200)) {
                    counted.incrementAndGet()
                    throw ProviderFailure(kind, "scripted")
                }
            }.exceptionOrNull() as ProviderCallFailed
            assertThat(failure.kind).isEqualTo(kind)
            assertThat(failure.attempts).isEqualTo(1)
            assertThat(counted.get()).describedAs(kind.code).isEqualTo(1)
        }

        val started = System.nanoTime()
        val hung = runCatching {
            calls(props(timeout = Duration.ofSeconds(60), maxAttempts = 5))
                .callOnce("sms", "otp", Duration.ofMillis(200)) { Thread.sleep(5_000) }
        }.exceptionOrNull() as ProviderCallFailed
        assertThat(hung.kind).isEqualTo(FailureKind.TIMEOUT)
        assertThat(Duration.ofNanos(System.nanoTime() - started))
            .describedAs("its own 200ms, not the provider's 60s").isLessThan(Duration.ofSeconds(2))
    }
}
