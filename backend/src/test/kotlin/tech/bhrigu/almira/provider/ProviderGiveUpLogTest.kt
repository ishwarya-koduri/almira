package tech.bhrigu.almira.provider

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.AppenderBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import tech.bhrigu.almira.auth.LoggingOtpSender
import tech.bhrigu.almira.config.AlmiraProperties
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.function.DoubleSupplier

/**
 * When ProviderCalls gives up, the operator gets exactly one WARN naming the
 * provider, the operation, the kind and the attempts — and nothing that came
 * from the call: not the recipient, not the code, not the adapter's detail.
 *
 * No Spring and no database: the real ProviderCalls and the real sandbox
 * adapters, with only the ProviderCalls logger captured, at DEBUG.
 */
@DisplayName("Provider calls: giving up is logged, and only the safe fields")
class ProviderGiveUpLogTest {

    private val phone = "+919812345678"
    private val code = "73914628"
    private val email = "give.up.log@example.test"

    private fun props(): AlmiraProperties {
        val p = AlmiraProperties.Provider(timeout = Duration.ofSeconds(10), maxAttempts = 3, retryBackoff = Duration.ofMillis(500))
        return AlmiraProperties(
            db = AlmiraProperties.Db("jdbc:postgresql://x/y", "u", "p", "u2", "p2"),
            jwt = AlmiraProperties.Jwt("test-only-secret-that-is-long-enough-for-hmac256-signing"),
            otp = AlmiraProperties.Otp(),
            providers = AlmiraProperties.Providers(p, p, p, p, p, p),
            environment = "development",
        )
    }

    private fun calls() = ProviderCalls(props(), ProviderCallsTest.RecordingSleeper(), DoubleSupplier { 1.0 }, Clock.systemUTC())

    private class Event(val level: Level, val text: String, val hasThrowable: Boolean)

    private class Capture : AppenderBase<ILoggingEvent>() {
        val events = ConcurrentLinkedQueue<Event>()
        override fun append(e: ILoggingEvent) {
            val parts = mutableListOf(e.message ?: "", e.formattedMessage ?: "")
            e.argumentArray?.forEach { parts += it.toString() }
            e.mdcPropertyMap?.forEach { (k, v) -> parts += "$k=$v" }
            e.keyValuePairs?.forEach { parts += "${it.key}=${it.value}" }
            var t: IThrowableProxy? = e.throwableProxy
            while (t != null) {
                parts += "${t.className}: ${t.message}"
                t = t.cause
            }
            events += Event(e.level, parts.joinToString(" | "), e.throwableProxy != null)
        }
        fun warns() = events.filter { it.level == Level.WARN }
    }

    private fun captured(block: () -> Unit): Capture {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val logger: Logger = context.getLogger(ProviderCalls::class.java)
        val capture = Capture().apply { this.context = context; start() }
        val saved = logger.level
        logger.addAppender(capture)
        logger.level = Level.DEBUG
        try {
            block()
        } finally {
            logger.detachAppender(capture)
            logger.level = saved
        }
        return capture
    }

    private fun assertSafe(capture: Capture) {
        capture.events.forEach { e ->
            listOf(phone, phone.takeLast(10), code, email, "careless adapter").forEach {
                assertThat(e.text).describedAs("ProviderCalls log event").doesNotContain(it)
            }
        }
    }

    @Test
    fun `a rejected one-time code is one WARN with provider, operation, kind and attempts`() {
        val faults = SandboxFaults(Duration.ofSeconds(5)).apply { always("otp", SandboxFault.REJECTED) }
        val sender = LoggingOtpSender(props(), faults)
        val capture = captured {
            runCatching { calls().call("sms", "otp") { sender.send(phone, code) } }
                .onSuccess { throw AssertionError("expected the call to give up") }
        }
        val warns = capture.warns()
        assertThat(warns).hasSize(1)
        assertThat(warns[0].text).contains("provider=sms", "operation=otp", "kind=rejected", "attempts=1")
        assertThat(warns[0].hasThrowable).isFalse()
        assertSafe(capture)
    }

    @Test
    fun `a connect call that runs out of attempts against an outage is one WARN, after the retries`() {
        val faults = SandboxFaults(Duration.ofSeconds(5)).apply { always("digilocker", SandboxFault.UNAVAILABLE) }
        val vault = SandboxDocumentVault(faults)
        val capture = captured {
            runCatching { calls().call("digilocker", "exchange", idempotent = false) { vault.exchange(java.util.UUID.randomUUID(), code) } }
                .onSuccess { throw AssertionError("expected the call to give up") }
        }
        val warns = capture.warns()
        assertThat(warns).hasSize(1)
        assertThat(warns[0].text).contains("provider=digilocker", "operation=exchange", "kind=unavailable", "attempts=3")
        assertThat(capture.events.count { it.level == Level.INFO }).describedAs("the retry lines, unchanged").isEqualTo(2)
        assertSafe(capture)
    }

    @Test
    fun `every kind is logged on giving up, and an adapter's careless detail never reaches the line`() {
        for (kind in FailureKind.entries) {
            val capture = captured {
                runCatching {
                    calls().call("email", "otp") {
                        throw ProviderFailure(kind, "careless adapter: to=$email phone=$phone code=$code")
                    }
                }.onSuccess { throw AssertionError("expected the call to give up") }
            }
            val warns = capture.warns()
            assertThat(warns).describedAs(kind.code).hasSize(1)
            assertThat(warns[0].text).contains("provider=email", "operation=otp", "kind=${kind.code}")
            assertThat(warns[0].hasThrowable).isFalse()
            if (kind.accountLevel) {
                assertThat(capture.events.count { it.level == Level.ERROR }).describedAs("the account alarm, unchanged").isEqualTo(1)
            }
            assertSafe(capture)
        }
    }

    @Test
    fun `a call that succeeds, even after retrying, logs no WARN`() {
        val faults = SandboxFaults(Duration.ofSeconds(5)).apply { script("otp", SandboxFault.UNAVAILABLE) }
        val sender = LoggingOtpSender(props(), faults)
        val capture = captured {
            calls().call("sms", "otp") { sender.send(phone, code) }
            calls().call("sms", "otp") { sender.send(phone, code) }
        }
        assertThat(capture.warns()).isEmpty()
        assertThat(capture.events.count { it.level == Level.INFO }).isEqualTo(1)
    }
}
