package tech.almira.auth

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

class TotpRow(
    val userId: UUID,
    val secretEnc: ByteArray,
    val confirmedAt: Instant?,
    val lastUsedStep: Long?,
)

class RecoveryCodeRow(val id: UUID, val salt: ByteArray, val hash: ByteArray)

data class PasskeyRow(
    val id: UUID,
    val userId: UUID,
    val credentialId: ByteArray,
    val publicKeyCose: ByteArray,
    val signatureCount: Long,
    val name: String,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
) {
    // Arrays compare by identity in a data class; nothing compares these.
    override fun equals(other: Any?) = this === other
    override fun hashCode() = id.hashCode()
    override fun toString() = "PasskeyRow(id=$id, name=$name)"
}

/**
 * The second-factor tables (V50). Every statement runs under row-level
 * security as the person it is about: none of them filters by anything but
 * `user_id`, and none would see another person's row if it tried. The caller
 * provides the identity — SecondFactorService.asUser.
 */
@Repository
class SecondFactorRepository(private val jdbc: NamedParameterJdbcTemplate) {

    fun totp(userId: UUID): TotpRow? = jdbc.query(
        "select user_id, secret_enc, confirmed_at, last_used_step from user_totp_factors where user_id = :uid",
        mapOf("uid" to userId),
    ) { rs, _ ->
        TotpRow(
            userId = rs.getObject("user_id", UUID::class.java),
            secretEnc = rs.getBytes("secret_enc"),
            confirmedAt = rs.getTimestamp("confirmed_at")?.toInstant(),
            lastUsedStep = rs.getLong("last_used_step").takeUnless { rs.wasNull() },
        )
    }.firstOrNull()

    /** Replaces whatever authenticator was there: confirming a new one is how a lost one is replaced. */
    fun saveConfirmedTotp(userId: UUID, secretEnc: ByteArray, kekId: String, step: Long) = jdbc.update(
        """
        insert into user_totp_factors (user_id, secret_enc, kek_id, confirmed_at, last_used_step)
        values (:uid, :secret, :kek, now(), :step)
        on conflict (user_id) do update
           set secret_enc = excluded.secret_enc, kek_id = excluded.kek_id,
               confirmed_at = excluded.confirmed_at, last_used_step = excluded.last_used_step
        """.trimIndent(),
        MapSqlParameterSource()
            .addValue("uid", userId).addValue("secret", secretEnc)
            .addValue("kek", kekId).addValue("step", step),
    )

    /**
     * 1 when [step] is later than any code this authenticator has accepted, and
     * is now the last one; 0 otherwise. One statement, so two requests racing
     * with the same code cannot both be accepted.
     */
    fun claimTotpStep(userId: UUID, step: Long): Int = jdbc.update(
        """
        update user_totp_factors set last_used_step = :step
         where user_id = :uid and confirmed_at is not null
           and (last_used_step is null or last_used_step < :step)
        """.trimIndent(),
        mapOf("uid" to userId, "step" to step),
    )

    fun deleteTotp(userId: UUID): Int =
        jdbc.update("delete from user_totp_factors where user_id = :uid", mapOf("uid" to userId))

    // --- recovery codes ----------------------------------------------------------

    fun unusedRecoveryCodes(userId: UUID): List<RecoveryCodeRow> = jdbc.query(
        "select id, salt, code_hash from user_recovery_codes where user_id = :uid and used_at is null",
        mapOf("uid" to userId),
    ) { rs, _ -> RecoveryCodeRow(rs.getObject("id", UUID::class.java), rs.getBytes("salt"), rs.getBytes("code_hash")) }

    fun replaceRecoveryCodes(userId: UUID, codes: List<RecoveryCodes.Stored>) {
        deleteRecoveryCodes(userId)
        codes.forEach {
            jdbc.update(
                "insert into user_recovery_codes (user_id, salt, code_hash) values (:uid, :salt, :hash)",
                MapSqlParameterSource().addValue("uid", userId).addValue("salt", it.salt).addValue("hash", it.hash),
            )
        }
    }

    /** 1 only for the call that used it. */
    fun useRecoveryCode(userId: UUID, id: UUID): Int = jdbc.update(
        "update user_recovery_codes set used_at = now() where id = :id and user_id = :uid and used_at is null",
        mapOf("id" to id, "uid" to userId),
    )

    fun deleteRecoveryCodes(userId: UUID): Int =
        jdbc.update("delete from user_recovery_codes where user_id = :uid", mapOf("uid" to userId))

    // --- passkeys -----------------------------------------------------------------

    private val passkeyMapper = { rs: java.sql.ResultSet, _: Int ->
        PasskeyRow(
            id = rs.getObject("id", UUID::class.java),
            userId = rs.getObject("user_id", UUID::class.java),
            credentialId = rs.getBytes("credential_id"),
            publicKeyCose = rs.getBytes("public_key_cose"),
            signatureCount = rs.getLong("signature_count"),
            name = rs.getString("name"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            lastUsedAt = rs.getTimestamp("last_used_at")?.toInstant(),
        )
    }

    fun passkeys(userId: UUID): List<PasskeyRow> = jdbc.query(
        "select * from user_passkeys where user_id = :uid order by created_at",
        mapOf("uid" to userId), passkeyMapper,
    )

    fun passkeyByCredential(userId: UUID, credentialId: ByteArray): PasskeyRow? = jdbc.query(
        "select * from user_passkeys where user_id = :uid and credential_id = :cid",
        MapSqlParameterSource().addValue("uid", userId).addValue("cid", credentialId), passkeyMapper,
    ).firstOrNull()

    fun insertPasskey(userId: UUID, credentialId: ByteArray, publicKeyCose: ByteArray, signatureCount: Long, name: String): UUID =
        jdbc.queryForObject(
            """
            insert into user_passkeys (id, user_id, credential_id, public_key_cose, signature_count, name)
            values (gen_random_uuid(), :uid, :cid, :key, :count, :name)
            returning id
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("uid", userId).addValue("cid", credentialId).addValue("key", publicKeyCose)
                .addValue("count", signatureCount).addValue("name", name),
            UUID::class.java,
        )!!

    fun markPasskeyUsed(userId: UUID, credentialId: ByteArray, signatureCount: Long) = jdbc.update(
        """
        update user_passkeys set signature_count = greatest(signature_count, :count), last_used_at = now()
         where user_id = :uid and credential_id = :cid
        """.trimIndent(),
        MapSqlParameterSource().addValue("uid", userId).addValue("cid", credentialId).addValue("count", signatureCount),
    )

    fun deletePasskey(userId: UUID, id: UUID): Int =
        jdbc.update("delete from user_passkeys where id = :id and user_id = :uid", mapOf("id" to id, "uid" to userId))
}
