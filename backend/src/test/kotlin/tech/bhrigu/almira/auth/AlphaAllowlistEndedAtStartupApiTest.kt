package tech.bhrigu.almira.auth

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import tech.bhrigu.almira.config.AlmiraProperties
import tech.bhrigu.almira.security.JwtService
import tech.bhrigu.almira.support.ApiTestBase
import tech.bhrigu.almira.support.TestInfra
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Ending the email alpha by configuration signs email-only testers out when the
 * server starts (owner's decision, 2026-09: removing a tester ends their
 * sessions — and so does removing all of them).
 *
 * Email on with an empty allowlist refuses to start (SignInChannels), so the
 * two ways to end the alpha are both "email taken out of the channels": with
 * the list emptied ([EveryoneRemoved]) or left as it was ([EmailTurnedOff]).
 * Each subclass writes, before its server exists, the sessions testers held
 * under the old configuration, then asserts what the starting server did to
 * them before any request touches them. Like AlphaAllowlistRemovalApiTest,
 * it runs inside ApiTestBase, so the server and the seeding both use the test
 * database (TestInfra) and nothing else.
 */
// Closed after each subclass: two more cached servers, each holding pools, took
// the shared test database past max_connections for the classes after them.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class AlphaAllowlistEndedAtStartupApiTest : ApiTestBase() {

    @Autowired private lateinit var redis: StringRedisTemplate

    protected abstract val seeded: Map<String, Seed.Held>

    private fun refresh(token: String) = post("/api/v1/auth/refresh", body = mapOf("refreshToken" to token))

    @Test
    fun `every email-only tester's sessions were ended and audited before any request`() {
        for (name in listOf("wasListed", "neverListed")) {
            val held = seeded.getValue(name)
            // Read before touching the API, so nothing but startup can have done it.
            assertThat(
                db.queryForObject("select revoked_reason from user_sessions where id = ?", String::class.java, held.sessionId),
            ).describedAs(name).isEqualTo(AlphaAllowlistAccess.REASON)
            assertThat(
                db.queryForObject(
                    "select count(*) from refresh_tokens where session_id = ? and revoked_at is null",
                    Int::class.java, held.sessionId,
                ),
            ).describedAs(name).isZero()
            assertThat(redis.hasKey("session:revoked:${held.sessionId}")).describedAs("$name: the access token too").isTrue()
            assertThat(
                db.queryForList(
                    "select diff->>'via' from activity_log where action = ? and entity_id = ?",
                    String::class.java, AlphaAllowlistAccess.AUDIT_ACTION, held.sessionId,
                ),
            ).describedAs(name).containsExactly("startup")

            assertThat(get("/api/v1/me", held.access).statusCode.value()).describedAs(name).isEqualTo(401)
            assertThat(refresh(held.refresh).statusCode.value()).describedAs(name).isEqualTo(401)
        }
    }

    @Test
    fun `phone accounts, with or without an email address, are left alone`() {
        for (name in listOf("phone", "phoneWithEmail")) {
            val held = seeded.getValue(name)
            assertThat(
                db.queryForList("select revoked_reason from user_sessions where id = ?", String::class.java, held.sessionId).single(),
            ).describedAs(name).isNull()
            assertThat(get("/api/v1/me", held.access).statusCode.value()).describedAs(name).isEqualTo(200)
            val rotated = refresh(held.refresh)
            assertThat(rotated.statusCode.value()).describedAs("$name: ${rotated.body}").isEqualTo(200)
        }
    }

    class EveryoneRemoved : AlphaAllowlistEndedAtStartupApiTest() {
        override val seeded get() = sessions
        companion object {
            private val sessions = HashMap<String, Seed.Held>()

            @JvmStatic
            @org.springframework.test.context.DynamicPropertySource
            fun seedBeforeStartup(registry: DynamicPropertyRegistry) {
                registry.add("almira.auth.sign-in-channels") { "phone" }
                registry.add("almira.auth.email-allowlist") { "" }
                sessions.putAll(Seed.sessions("everyone-removed"))
            }
        }
    }

    class EmailTurnedOff : AlphaAllowlistEndedAtStartupApiTest() {
        override val seeded get() = sessions
        companion object {
            private val sessions = HashMap<String, Seed.Held>()

            @JvmStatic
            @org.springframework.test.context.DynamicPropertySource
            fun seedBeforeStartup(registry: DynamicPropertyRegistry) {
                val stillListed = Seed.address("email-off", "wasListed")
                registry.add("almira.auth.sign-in-channels") { "phone" }
                // The list is left as it was: turning email off alone must end the alpha.
                registry.add("almira.auth.email-allowlist") { stillListed }
                sessions.putAll(Seed.sessions("email-off"))
            }
        }
    }

    /** Sessions written straight into the test database, as the old configuration signed them in. */
    object Seed {
        data class Held(val userId: UUID, val sessionId: UUID, val access: String, val refresh: String)

        private val run = System.nanoTime()

        fun address(tag: String, name: String) = "$name.$tag.$run@example.test".lowercase()

        fun sessions(tag: String): Map<String, Held> {
            // A fresh Testcontainers database has no tables yet; on a migrated one this does nothing.
            Flyway.configure().dataSource(TestInfra.dbUrl, TestInfra.dbOwnerUser, TestInfra.dbOwnerPassword)
                .locations("classpath:db/migration").baselineOnMigrate(true).load().migrate()
            val jwt = JwtService(
                AlmiraProperties(
                    db = AlmiraProperties.Db(TestInfra.dbUrl, "u", "p", "u2", "p2"),
                    jwt = AlmiraProperties.Jwt("test-only-secret-that-is-long-enough-for-hmac256-signing"),
                    otp = AlmiraProperties.Otp(),
                    environment = "development",
                ),
            )
            return DriverManager.getConnection(TestInfra.dbUrl, TestInfra.dbOwnerUser, TestInfra.dbOwnerPassword).use { c ->
                fun user(phone: String?, email: String?): UUID = c.prepareStatement(
                    "insert into users (phone, email, auth_provider) values (?, ?, ?) returning id",
                ).use { st ->
                    st.setString(1, phone); st.setString(2, email)
                    st.setString(3, if (phone == null) "email" else "otp")
                    st.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
                }
                fun session(userId: UUID): Held {
                    val expires = Timestamp.from(Instant.now().plus(jwt.refreshTtl))
                    val sessionId = c.prepareStatement(
                        "insert into user_sessions (user_id, device_name, expires_at) values (?, 'before the alpha ended', ?) returning id",
                    ).use { st ->
                        st.setObject(1, userId); st.setTimestamp(2, expires)
                        st.executeQuery().use { it.next(); it.getObject(1, UUID::class.java) }
                    }
                    val refresh = jwt.newRefreshToken()
                    c.prepareStatement("insert into refresh_tokens (session_id, token_hash, expires_at) values (?, ?, ?)").use { st ->
                        st.setObject(1, sessionId); st.setString(2, jwt.hash(refresh)); st.setTimestamp(3, expires)
                        st.executeUpdate()
                    }
                    return Held(userId, sessionId, jwt.issueAccessToken(userId, sessionId), refresh)
                }
                mapOf(
                    "wasListed" to session(user(null, address(tag, "wasListed"))),
                    "neverListed" to session(user(null, address(tag, "neverListed"))),
                    "phone" to session(user(uniquePhone(), null)),
                    "phoneWithEmail" to session(user(uniquePhone(), address(tag, "phoneWithEmail"))),
                )
            }
        }
    }
}
