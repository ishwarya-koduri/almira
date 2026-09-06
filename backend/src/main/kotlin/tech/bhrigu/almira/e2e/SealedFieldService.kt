package tech.bhrigu.almira.e2e

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.household.HouseholdService
import tech.bhrigu.almira.security.RequestUserContext
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * What the client stores so it can derive the same key again on another device.
 * None of it is useful without the passphrase, which never leaves the browser.
 */
data class E2eKeyEnvelope(
    val kdf: String = "PBKDF2-SHA256",
    val kdfSalt: String,
    val iterations: Int,
    val wrapAlgorithm: String = "AES-GCM-256",
    val wrappedKey: String,
    val verifier: String,
    val keyVersion: Int = 1,
    val updatedAt: Instant? = null,
)

data class SealedField(
    val recordType: String,
    val recordId: UUID,
    val fieldKey: String,
    val ciphertext: String,
    val algorithm: String,
    val keyVersion: Int,
    val updatedAt: Instant,
)

/**
 * Zero-knowledge fields (docs/05 §4, docs/12).
 *
 * This service is deliberately incurious. It stores an opaque string, hands it
 * back to whoever may read the record it belongs to, and has no way to open it:
 * the key is derived in the browser from a passphrase that is never sent. The
 * only judgement it makes is structural — that the thing being stored is
 * well-formed base64 of a plausible length — because checking anything more
 * would mean understanding the contents, which is the property being sold.
 *
 * Two consequences the product states rather than hides: a sealed field cannot
 * be searched, sorted or OCR'd on the server, and a forgotten passphrase is
 * final. Both are what "the server cannot read it" costs when it is true.
 */
@Service
class SealedFieldService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
    private val audit: AuditService,
    private val userContext: RequestUserContext,
) {

    @Transactional
    fun storeKey(householdId: UUID, envelope: E2eKeyEnvelope): E2eKeyEnvelope {
        val userId = userContext.require()
        households.get(householdId)

        // A weak KDF here would make the whole scheme theatre, so the floor is
        // enforced on the server as well as chosen by the client.
        if (envelope.iterations < MIN_ITERATIONS) {
            throw ApiException.badRequest(
                "kdf_too_weak",
                "That passphrase stretch is too short to be worth doing — use at least " +
                    "$MIN_ITERATIONS iterations.",
            )
        }
        requireBase64("kdfSalt", envelope.kdfSalt, minBytes = 16)
        requireBase64("wrappedKey", envelope.wrappedKey, minBytes = 28)
        requireBase64("verifier", envelope.verifier, minBytes = 16)

        val existing = findKey(householdId, userId)
        jdbc.update(
            """
            insert into e2e_keys (household_id, user_id, kdf, kdf_salt, iterations,
                                  wrap_algorithm, wrapped_key, verifier, key_version)
            values (:hid, :uid, :kdf, :salt, :iterations, :wrapAlg, :wrapped, :verifier, :version)
            on conflict (household_id, user_id) do update set
              kdf = excluded.kdf, kdf_salt = excluded.kdf_salt, iterations = excluded.iterations,
              wrap_algorithm = excluded.wrap_algorithm, wrapped_key = excluded.wrapped_key,
              verifier = excluded.verifier, key_version = excluded.key_version
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("hid", householdId).addValue("uid", userId)
                .addValue("kdf", envelope.kdf).addValue("salt", envelope.kdfSalt)
                .addValue("iterations", envelope.iterations)
                .addValue("wrapAlg", envelope.wrapAlgorithm)
                .addValue("wrapped", envelope.wrappedKey).addValue("verifier", envelope.verifier)
                .addValue("version", envelope.keyVersion),
        )

        audit.record(
            householdId = householdId, actorUserId = userId,
            action = if (existing == null) "e2e.key.create" else "e2e.key.rotate",
            entityType = "household", entityId = householdId,
            diff = mapOf("keyVersion" to envelope.keyVersion),
        )
        return findKey(householdId, userId)!!
    }

    @Transactional(readOnly = true)
    fun key(householdId: UUID): E2eKeyEnvelope? {
        households.get(householdId)
        return findKey(householdId, userContext.require())
    }

    private fun findKey(householdId: UUID, userId: UUID): E2eKeyEnvelope? = jdbc.query(
        """
        select * from e2e_keys where household_id = :hid and user_id = :uid
        """.trimIndent(),
        mapOf("hid" to householdId, "uid" to userId),
    ) { rs, _ ->
        E2eKeyEnvelope(
            kdf = rs.getString("kdf"),
            kdfSalt = rs.getString("kdf_salt"),
            iterations = rs.getInt("iterations"),
            wrapAlgorithm = rs.getString("wrap_algorithm"),
            wrappedKey = rs.getString("wrapped_key"),
            verifier = rs.getString("verifier"),
            keyVersion = rs.getInt("key_version"),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
        )
    }.firstOrNull()

    @Transactional
    fun seal(
        householdId: UUID,
        recordType: String,
        recordId: UUID,
        fieldKey: String,
        ciphertext: String,
        keyVersion: Int,
    ): SealedField {
        val userId = userContext.require()
        households.get(householdId)
        requireRecordType(recordType)
        if (fieldKey.isBlank() || fieldKey.length > 64) {
            throw ApiException.badRequest("field_invalid", "That field name won't do.")
        }
        requireBase64("ciphertext", ciphertext, minBytes = 17)
        if (ciphertext.length > MAX_CIPHERTEXT) {
            throw ApiException.badRequest(
                "ciphertext_too_large",
                "A sealed field holds a note, not a file — attach documents instead.",
            )
        }
        if (findKey(householdId, userId) == null) {
            throw ApiException.badRequest(
                "no_key", "Set up a passphrase for this household before sealing anything.",
            )
        }

        val written = jdbc.update(
            """
            insert into sealed_values (household_id, record_type, record_id, field_key,
                                       ciphertext, key_version, sealed_by)
            values (:hid, :type, :rid, :field, :ciphertext, :version, :by)
            on conflict (record_type, record_id, field_key) do update set
              ciphertext = excluded.ciphertext, key_version = excluded.key_version
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("hid", householdId).addValue("type", recordType)
                .addValue("rid", recordId).addValue("field", fieldKey)
                .addValue("ciphertext", ciphertext).addValue("version", keyVersion)
                .addValue("by", userId),
        )
        if (written == 0) throw ApiException.notFound()

        // The action is logged; the content cannot be, which is rather the point.
        audit.record(
            householdId = householdId, actorUserId = userId, action = "e2e.seal",
            entityType = recordType, entityId = recordId, diff = mapOf("field" to fieldKey),
        )
        return list(householdId, recordType, recordId).first { it.fieldKey == fieldKey }
    }

    @Transactional(readOnly = true)
    fun list(householdId: UUID, recordType: String?, recordId: UUID?): List<SealedField> {
        households.get(householdId)
        val sql = buildString {
            append("select * from sealed_values where household_id = :hid")
            if (recordType != null) append(" and record_type = :type")
            if (recordId != null) append(" and record_id = :rid")
            append(" order by updated_at desc")
        }
        return jdbc.query(
            sql,
            MapSqlParameterSource()
                .addValue("hid", householdId).addValue("type", recordType).addValue("rid", recordId),
        ) { rs, _ ->
            SealedField(
                recordType = rs.getString("record_type"),
                recordId = rs.getObject("record_id", UUID::class.java),
                fieldKey = rs.getString("field_key"),
                ciphertext = rs.getString("ciphertext"),
                algorithm = rs.getString("algorithm"),
                keyVersion = rs.getInt("key_version"),
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
            )
        }
    }

    @Transactional
    fun unseal(householdId: UUID, recordType: String, recordId: UUID, fieldKey: String) {
        val userId = userContext.require()
        households.get(householdId)
        val removed = jdbc.update(
            """
            delete from sealed_values
            where household_id = :hid and record_type = :type and record_id = :rid
              and field_key = :field
            """.trimIndent(),
            mapOf("hid" to householdId, "type" to recordType, "rid" to recordId, "field" to fieldKey),
        )
        if (removed == 0) throw ApiException.notFound()
        audit.record(
            householdId = householdId, actorUserId = userId, action = "e2e.unseal",
            entityType = recordType, entityId = recordId, diff = mapOf("field" to fieldKey),
        )
    }

    /**
     * The only inspection performed: is this the right *shape*? Refusing
     * something that is plainly not ciphertext catches a client bug that would
     * otherwise write a note in the clear and call it sealed — which is the one
     * failure mode this feature must not have.
     */
    private fun requireBase64(field: String, value: String, minBytes: Int) {
        val decoded = runCatching { Base64.getUrlDecoder().decode(value) }
            .recoverCatching { Base64.getDecoder().decode(value) }
            .getOrElse {
                throw ApiException.badRequest(
                    "not_ciphertext", "$field has to be base64 — this looks like plain text.",
                )
            }
        if (decoded.size < minBytes) {
            throw ApiException.badRequest(
                "not_ciphertext", "$field is too short to be what it claims to be.",
            )
        }
    }

    private fun requireRecordType(value: String) {
        if (value !in RECORD_TYPES) {
            throw ApiException.badRequest("record_type_invalid", "That isn't something you can seal.")
        }
    }

    private companion object {
        const val MIN_ITERATIONS = 100_000
        const val MAX_CIPHERTEXT = 64_000
        val RECORD_TYPES = setOf("investment", "liability", "account", "member", "estate_document")
    }
}
