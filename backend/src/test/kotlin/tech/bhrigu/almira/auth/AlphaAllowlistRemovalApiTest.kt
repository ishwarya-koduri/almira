package tech.bhrigu.almira.auth

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.config.AlmiraProperties
import tech.bhrigu.almira.security.JwtService
import tech.bhrigu.almira.security.SessionRevocationCache
import tech.bhrigu.almira.support.ApiTestBase
import tech.bhrigu.almira.support.TestInfra
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Taking a tester off the email allowlist ends their access (owner's decision,
 * 2026-09), over real HTTP.
 *
 * The allowlist is configuration, so "removal" is a server starting with a list
 * that no longer has the address. This class makes that literal: before its
 * server starts ([seedBeforeStartup]) it writes, straight into the database,
 * sessions for an address the server's list will not contain — as if they were
 * signed in under the old list — and then asserts what the starting server did
 * to them, before any request touches them.
 *
 * And the two checks that do not depend on a restart having happened: a
 * session the startup pass never saw (signed in on an old server during a
 * rolling deploy) is ended by its first request, or its first refresh.
 */
@DisplayName("Taking a tester off the email allowlist")
class AlphaAllowlistRemovalApiTest : ApiTestBase() {

    @Autowired private lateinit var repo: AuthRepository
    @Autowired private lateinit var jwt: JwtService
    @Autowired private lateinit var redis: StringRedisTemplate
    @Autowired private lateinit var revoker: SessionRevoker
    @Autowired private lateinit var audit: AuditService
    @Autowired private lateinit var revocations: SessionRevocationCache
    @Autowired @Qualifier("systemJdbcBypassingRls") private lateinit var system: NamedParameterJdbcTemplate

    private data class Held(val userId: UUID, val sessionId: UUID, val access: String, val refresh: String)

    /** A session as sign-in would have made it, for any account, without asking the allowlist. */
    private fun sessionFor(user: UserRow): Held {
        val sessionId = repo.createSession(user.id, "test", "test", "127.0.0.1", Instant.now().plus(jwt.refreshTtl))
        val refresh = jwt.newRefreshToken()
        repo.storeRefresh(sessionId, jwt.hash(refresh), Instant.now().plus(jwt.refreshTtl))
        return Held(user.id, sessionId, jwt.issueAccessToken(user.id, sessionId), refresh)
    }

    private fun refresh(token: String) = post("/api/v1/auth/refresh", body = mapOf("refreshToken" to token))

    private fun revokedReason(sessionId: UUID): String? = db.queryForList(
        "select revoked_reason from user_sessions where id = ?", String::class.java, sessionId,
    ).single()

    private fun liveRefreshTokens(sessionId: UUID): Int = db.queryForObject(
        "select count(*) from refresh_tokens where session_id = ? and revoked_at is null", Int::class.java, sessionId,
    )!!

    private fun auditedVia(sessionId: UUID): List<String> = db.queryForList(
        "select diff->>'via' from activity_log where action = ? and entity_id = ?",
        String::class.java, AlphaAllowlistAccess.AUDIT_ACTION, sessionId,
    )

    // --- restart ------------------------------------------------------------------

    @Test
    fun `a server starting without the address has ended that tester's sessions before any request`() {
        val gone = seeded.getValue("gone")
        // Read before touching the API, so nothing but startup can have done it.
        assertThat(revokedReason(gone.sessionId)).isEqualTo(AlphaAllowlistAccess.REASON)
        assertThat(liveRefreshTokens(gone.sessionId)).isZero()
        assertThat(redis.hasKey("session:revoked:${gone.sessionId}")).describedAs("the access token too").isTrue()
        assertThat(auditedVia(gone.sessionId)).containsExactly("startup")

        assertThat(get("/api/v1/me", gone.access).statusCode.value()).isEqualTo(401)
        assertThat(refresh(gone.refresh).statusCode.value()).isEqualTo(401)
    }

    @Test
    fun `the same startup leaves a listed tester, a phone account, and a phone account with an unlisted email alone`() {
        for (name in listOf("kept", "phone", "phoneWithUnlistedEmail")) {
            val held = seeded.getValue(name)
            assertThat(revokedReason(held.sessionId)).describedAs(name).isNull()
            assertThat(redis.hasKey("session:revoked:${held.sessionId}")).describedAs(name).isFalse()
            assertThat(get("/api/v1/me", held.access).statusCode.value()).describedAs(name).isEqualTo(200)
            val rotated = refresh(held.refresh)
            assertThat(rotated.statusCode.value()).describedAs("$name: ${rotated.body}").isEqualTo(200)
            assertThat(get("/api/v1/me", rotated.json().path("accessToken").asText()).statusCode.value())
                .describedAs(name).isEqualTo(200)
        }
    }

    @Test
    fun `the startup pass reads through the owner connection it asks for`() {
        assertThat(system.queryForObject("select current_user", emptyMap<String, Any>(), String::class.java))
            .isEqualTo(TestInfra.dbOwnerUser)
    }

    // --- no restart needed: request and refresh -----------------------------------

    @Test
    fun `a removed tester's session the startup never saw ends on its first request`() {
        val held = sessionFor(repo.createWithEmail("rolling-request.$run@example.test"))
        assertThat(get("/api/v1/me", held.access).statusCode.value()).isEqualTo(401)
        assertThat(revokedReason(held.sessionId)).isEqualTo(AlphaAllowlistAccess.REASON)
        assertThat(liveRefreshTokens(held.sessionId)).isZero()
        assertThat(auditedVia(held.sessionId)).containsExactly("request")
        assertThat(refresh(held.refresh).statusCode.value()).isEqualTo(401)
        // Refused again, audited once.
        assertThat(get("/api/v1/me", held.access).statusCode.value()).isEqualTo(401)
        assertThat(auditedVia(held.sessionId)).hasSize(1)
    }

    @Test
    fun `a removed tester's refresh token stops working even if it is the first thing they send`() {
        val held = sessionFor(repo.createWithEmail("rolling-refresh.$run@example.test"))
        val r = refresh(held.refresh)
        assertThat(r.statusCode.value()).isEqualTo(401)
        assertThat(r.body).doesNotContain("rolling-refresh")
        assertThat(revokedReason(held.sessionId)).isEqualTo(AlphaAllowlistAccess.REASON)
        assertThat(auditedVia(held.sessionId)).containsExactly("refresh")
        assertThat(get("/api/v1/me", held.access).statusCode.value()).isEqualTo(401)
    }

    @Test
    fun `a tester still on the list signs in by email and keeps working, through a refresh`() {
        val address = keptAddress
        val request = post("/api/v1/auth/otp/email/request", body = mapOf("email" to address))
        assertThat(request.statusCode.value()).describedAs(request.body).isEqualTo(200)
        // Development with the sandbox email sender: the code is echoed for a listed address.
        val code = request.json().path("developmentCode").asText()
        val login = post("/api/v1/auth/otp/email/verify", body = mapOf("email" to address, "code" to code))
        assertThat(login.statusCode.value()).describedAs(login.body).isEqualTo(200)
        assertThat(get("/api/v1/me", login.json().path("accessToken").asText()).statusCode.value()).isEqualTo(200)
        val rotated = refresh(login.json().path("refreshToken").asText())
        assertThat(rotated.statusCode.value()).describedAs(rotated.body).isEqualTo(200)
        assertThat(get("/api/v1/me", rotated.json().path("accessToken").asText()).statusCode.value()).isEqualTo(200)
    }

    @Test
    fun `a phone account signed in after startup is never looked at by the allowlist`() {
        val token = signIn()
        assertThat(get("/api/v1/me", token).statusCode.value()).isEqualTo(200)
        val id = UUID.fromString(get("/api/v1/me", token).json().path("id").asText())
        // Giving it an unlisted address does not make it an email account.
        db.update("update users set email = ? where id = ?", "phone-plus-email.$run@example.test", id)
        val held = sessionFor(repo.findById(id)!!)
        assertThat(get("/api/v1/me", held.access).statusCode.value()).isEqualTo(200)
        assertThat(refresh(held.refresh).statusCode.value()).isEqualTo(200)
        assertThat(revokedReason(held.sessionId)).isNull()
    }

    /**
     * The same database and cache, judged by a server configured differently.
     * Never `sweep()` through one here: it would end this class's own seeded
     * listed tester, whom another test expects alive.
     */
    private fun accessUnder(channels: List<String>, allowlist: List<String>) = AlphaAllowlistAccess(
        SignInChannels(
            AlmiraProperties(
                db = AlmiraProperties.Db("jdbc:postgresql://x/y", "u", "p", "u2", "p2"),
                jwt = AlmiraProperties.Jwt("test-only-secret-that-is-long-enough-for-hmac256-signing"),
                otp = AlmiraProperties.Otp(),
                auth = AlmiraProperties.Auth(signInChannels = channels, emailAllowlist = allowlist),
            ),
        ),
        repo, revoker, audit, revocations, system,
    )

    private fun assertEndedAndAudited(held: Held, via: String) {
        assertThat(revokedReason(held.sessionId)).describedAs(via).isEqualTo(AlphaAllowlistAccess.REASON)
        assertThat(liveRefreshTokens(held.sessionId)).describedAs(via).isZero()
        assertThat(redis.hasKey("session:revoked:${held.sessionId}")).describedAs("$via: the access token too").isTrue()
        assertThat(auditedVia(held.sessionId)).containsExactly(via)
    }

    @Test
    fun `taking the last tester off, which leaves email off, ends that tester's sessions on request and refresh`() {
        // Email on with nobody listed refuses to start, so a list with nobody
        // left is a server with email taken out: that is what "everyone
        // removed" can be.
        val nobody = accessUnder(listOf("phone"), emptyList())
        val address = "last.tester.$run@example.test"
        assertThat(nobody.shutsOut(null, address)).isTrue()
        assertThat(nobody.shutsOut(uniquePhone(), address)).describedAs("a phone account").isFalse()

        val onRequest = sessionFor(repo.createWithEmail("last.request.$run@example.test"))
        assertThat(nobody.allowsRequest(onRequest.userId, onRequest.sessionId)).isFalse()
        assertEndedAndAudited(onRequest, "request")

        val onRefresh = sessionFor(repo.createWithEmail("last.refresh.$run@example.test"))
        assertThat(nobody.allowsRefresh(onRefresh.userId, onRefresh.sessionId)).isFalse()
        assertEndedAndAudited(onRefresh, "refresh")

        val phone = sessionFor(repo.findById(UUID.fromString(get("/api/v1/me", signIn()).json().path("id").asText()))!!)
        assertThat(nobody.allowsRequest(phone.userId, phone.sessionId)).describedAs("a phone account").isTrue()
        assertThat(nobody.allowsRefresh(phone.userId, phone.sessionId)).describedAs("a phone account").isTrue()
        assertThat(revokedReason(phone.sessionId)).describedAs("a phone account").isNull()
        // At startup: AlphaAllowlistEndedAtStartupApiTest, which starts a server so configured.
    }

    @Test
    fun `turning email sign-in off ends a tester's sessions on request and refresh, even while their address is still listed`() {
        val address = "listed.but.email.off.$run@example.test"
        val emailOff = accessUnder(listOf("phone"), listOf(address))
        assertThat(emailOff.shutsOut(null, address)).isTrue()

        val onRequest = sessionFor(repo.createWithEmail("off.request.$run@example.test"))
        assertThat(emailOff.allowsRequest(onRequest.userId, onRequest.sessionId)).isFalse()
        assertEndedAndAudited(onRequest, "request")

        val listed = sessionFor(repo.createWithEmail(address))
        assertThat(emailOff.allowsRefresh(listed.userId, listed.sessionId)).isFalse()
        assertEndedAndAudited(listed, "refresh")
        assertThat(get("/api/v1/me", listed.access).statusCode.value()).isEqualTo(401)
    }

    companion object {
        private val run = System.nanoTime()
        private val keptAddress = "still.listed.$run@example.test"
        private val seeded = HashMap<String, Held>()

        /**
         * Runs as the server is being built, before it starts: the sessions a
         * tester already held when their address was taken off the list.
         */
        @JvmStatic
        @DynamicPropertySource
        fun seedBeforeStartup(registry: DynamicPropertyRegistry) {
            registry.add("almira.auth.sign-in-channels") { "phone,email" }
            registry.add("almira.auth.email-allowlist") { keptAddress }

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
            DriverManager.getConnection(TestInfra.dbUrl, TestInfra.dbOwnerUser, TestInfra.dbOwnerPassword).use { c ->
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
                        "insert into user_sessions (user_id, device_name, expires_at) values (?, 'before removal', ?) returning id",
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
                seeded["gone"] = session(user(null, "taken.off.$run@example.test"))
                seeded["kept"] = session(user(null, keptAddress))
                seeded["phone"] = session(user(uniquePhone(), null))
                seeded["phoneWithUnlistedEmail"] = session(user(uniquePhone(), "phone.and.unlisted.$run@example.test"))
            }
        }
    }
}
