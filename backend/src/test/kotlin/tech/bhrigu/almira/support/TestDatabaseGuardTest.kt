package tech.bhrigu.almira.support

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import tech.bhrigu.almira.AlmiraApplication
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The guard stops a wrongly-configured application before it opens a single
 * connection — not after Flyway has already run against whatever it reached.
 *
 * The "wrong" database here is a socket this test owns on 127.0.0.1, which
 * counts every connection made to it and hangs up. Nothing real is ever
 * pointed at: if the guard were missing, the application would connect to this
 * socket, fail there, and the count would say so.
 */
@DisplayName("Tests cannot start an application on a database that is not the test database")
class TestDatabaseGuardTest {

    @Test
    fun `the real application, given another database, is refused before anything connects to it`() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { fakeDatabase ->
            val connections = AtomicInteger()
            val acceptor = thread(isDaemon = true, name = "fake-database") {
                try {
                    while (true) fakeDatabase.accept().use { connections.incrementAndGet() }
                } catch (_: SocketException) {
                    // closed at the end of the test
                }
            }
            val elsewhere = "jdbc:postgresql://127.0.0.1:${fakeDatabase.localPort}/almira_guard_elsewhere"
            check(elsewhere != TestInfra.dbUrl)

            // Exactly how ProviderDisabledStartupTest starts the application, and
            // with no listener added by hand: the guard must arrive from
            // spring.factories, as it does for every other test.
            val args = mapOf(
                "almira.db.url" to elsewhere,
                "almira.db.owner-user" to TestInfra.dbOwnerUser,
                "almira.db.owner-password" to TestInfra.dbOwnerPassword,
                "almira.db.app-user" to TestInfra.dbAppUser,
                "almira.db.app-password" to TestInfra.dbAppPassword,
                "almira.db.max-pool-size" to 1,
                "spring.data.redis.host" to TestInfra.redisHost,
                "spring.data.redis.port" to TestInfra.redisPort,
                "almira.otp.provider" to "log",
                "ALMIRA_ENV" to "development",
                "almira.jwt.secret" to "test-only-secret-that-is-long-enough-for-hmac256-signing",
                "server.port" to 0,
            ).map { (key, value) -> "--$key=$value" }.toTypedArray()

            val failure = catchThrowable {
                SpringApplicationBuilder(AlmiraApplication::class.java).run(*args).close()
            }

            fakeDatabase.close()
            acceptor.join(5_000)

            assertThat(connections.get())
                .describedAs("connections the application made to the database that is not the test database")
                .isZero()
            assertThat(generateSequence(failure) { it.cause }.toList())
                .describedAs("the startup failure is the guard's refusal, not a failed connection")
                .anyMatch { it is TestDatabaseGuard.NotTheTestDatabase }
            assertThat(failure).hasMessageContaining(elsewhere)
        }
    }
}
