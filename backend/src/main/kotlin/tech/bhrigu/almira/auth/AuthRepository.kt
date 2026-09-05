package tech.bhrigu.almira.auth

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

data class UserRow(
    val id: UUID,
    val phone: String?,
    val email: String?,
    val fullName: String?,
    val defaultVisibility: String,
    val locale: String,
    val currencyPref: String,
    val createdAt: Instant,
)

data class SessionRow(
    val id: UUID,
    val userId: UUID,
    val deviceName: String?,
    val createdAt: Instant,
    val lastUsedAt: Instant,
    val expiresAt: Instant,
    val revokedAt: Instant?,
)

data class RefreshRow(
    val id: UUID,
    val sessionId: UUID,
    val userId: UUID,
    val expiresAt: Instant,
    val rotatedTo: UUID?,
    val revokedAt: Instant?,
    val sessionRevokedAt: Instant?,
)

@Repository
class AuthRepository(private val jdbc: NamedParameterJdbcTemplate) {

    private val userMapper = { rs: java.sql.ResultSet, _: Int ->
        UserRow(
            id = rs.getObject("id", UUID::class.java),
            phone = rs.getString("phone"),
            email = rs.getString("email"),
            fullName = rs.getString("full_name"),
            defaultVisibility = rs.getString("default_visibility"),
            locale = rs.getString("locale"),
            currencyPref = rs.getString("currency_pref"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )
    }

    fun findByPhone(phone: String): UserRow? = jdbc.query(
        "select * from users where phone = :phone and deleted_at is null",
        mapOf("phone" to phone), userMapper,
    ).firstOrNull()

    fun findById(id: UUID): UserRow? = jdbc.query(
        "select * from users where id = :id and deleted_at is null",
        mapOf("id" to id), userMapper,
    ).firstOrNull()

    fun createWithPhone(phone: String): UserRow = jdbc.queryForObject(
        """
        insert into users (phone, auth_provider) values (:phone, 'otp')
        returning *
        """.trimIndent(),
        mapOf("phone" to phone), userMapper,
    )!!

    fun markLogin(userId: UUID) = jdbc.update(
        "update users set last_login_at = now() where id = :id", mapOf("id" to userId),
    )

    fun updatePreferences(userId: UUID, fullName: String?, defaultVisibility: String?): UserRow =
        jdbc.queryForObject(
            """
            update users set
              full_name          = coalesce(:fullName, full_name),
              default_visibility = coalesce(:visibility, default_visibility)
            where id = :id
            returning *
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", userId)
                .addValue("fullName", fullName)
                .addValue("visibility", defaultVisibility),
            userMapper,
        )!!

    // --- sessions ------------------------------------------------------------

    fun createSession(
        userId: UUID,
        deviceName: String?,
        userAgent: String?,
        ip: String?,
        expiresAt: Instant,
    ): UUID = jdbc.queryForObject(
        """
        insert into user_sessions (user_id, device_name, user_agent, ip, expires_at)
        values (:userId, :device, :agent, cast(:ip as inet), :expiresAt)
        returning id
        """.trimIndent(),
        MapSqlParameterSource()
            .addValue("userId", userId)
            .addValue("device", deviceName)
            .addValue("agent", userAgent?.take(500))
            .addValue("ip", ip)
            .addValue("expiresAt", java.sql.Timestamp.from(expiresAt)),
        UUID::class.java,
    )!!

    fun listSessions(userId: UUID): List<SessionRow> = jdbc.query(
        """
        select * from user_sessions
        where user_id = :userId and revoked_at is null and expires_at > now()
        order by last_used_at desc
        """.trimIndent(),
        mapOf("userId" to userId),
    ) { rs, _ ->
        SessionRow(
            id = rs.getObject("id", UUID::class.java),
            userId = rs.getObject("user_id", UUID::class.java),
            deviceName = rs.getString("device_name"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            lastUsedAt = rs.getTimestamp("last_used_at").toInstant(),
            expiresAt = rs.getTimestamp("expires_at").toInstant(),
            revokedAt = rs.getTimestamp("revoked_at")?.toInstant(),
        )
    }

    fun revokeSession(userId: UUID, sessionId: UUID, reason: String): Int = jdbc.update(
        """
        update user_sessions set revoked_at = now(), revoked_reason = :reason
        where id = :id and user_id = :userId and revoked_at is null
        """.trimIndent(),
        mapOf("id" to sessionId, "userId" to userId, "reason" to reason),
    )

    fun touchSession(sessionId: UUID) = jdbc.update(
        "update user_sessions set last_used_at = now() where id = :id",
        mapOf("id" to sessionId),
    )

    // --- refresh tokens ------------------------------------------------------

    fun storeRefresh(sessionId: UUID, tokenHash: String, expiresAt: Instant): UUID =
        jdbc.queryForObject(
            """
            insert into refresh_tokens (session_id, token_hash, expires_at)
            values (:sessionId, :hash, :expiresAt)
            returning id
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("sessionId", sessionId)
                .addValue("hash", tokenHash)
                .addValue("expiresAt", java.sql.Timestamp.from(expiresAt)),
            UUID::class.java,
        )!!

    fun findRefresh(tokenHash: String): RefreshRow? = jdbc.query(
        """
        select r.id, r.session_id, r.expires_at, r.rotated_to, r.revoked_at,
               s.user_id, s.revoked_at as session_revoked_at
        from refresh_tokens r join user_sessions s on s.id = r.session_id
        where r.token_hash = :hash
        """.trimIndent(),
        mapOf("hash" to tokenHash),
    ) { rs, _ ->
        RefreshRow(
            id = rs.getObject("id", UUID::class.java),
            sessionId = rs.getObject("session_id", UUID::class.java),
            userId = rs.getObject("user_id", UUID::class.java),
            expiresAt = rs.getTimestamp("expires_at").toInstant(),
            rotatedTo = rs.getObject("rotated_to", UUID::class.java),
            revokedAt = rs.getTimestamp("revoked_at")?.toInstant(),
            sessionRevokedAt = rs.getTimestamp("session_revoked_at")?.toInstant(),
        )
    }.firstOrNull()

    fun markRotated(oldId: UUID, newId: UUID) = jdbc.update(
        "update refresh_tokens set rotated_to = :new, used_at = now() where id = :old",
        mapOf("old" to oldId, "new" to newId),
    )

    /** Reuse detection: burn every token in the session, not just this one. */
    fun revokeAllForSession(sessionId: UUID) = jdbc.update(
        """
        update refresh_tokens set revoked_at = now()
        where session_id = :sid and revoked_at is null
        """.trimIndent(),
        mapOf("sid" to sessionId),
    )

    fun revokeSessionById(sessionId: UUID, reason: String) = jdbc.update(
        """
        update user_sessions set revoked_at = now(), revoked_reason = :reason
        where id = :sid and revoked_at is null
        """.trimIndent(),
        mapOf("sid" to sessionId, "reason" to reason),
    )
}
