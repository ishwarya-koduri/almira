package tech.almira.e2e

import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tech.almira.audit.AuditService
import tech.almira.common.ApiException
import tech.almira.household.HouseholdService
import tech.almira.security.RequestUserContext
import java.util.UUID

/**
 * The access chain's ticks (docs/27 §6): "Amma → Ravi → the family advocate",
 * each with a dated "checked with them".
 *
 * The names are sealed values under `key_holder`, `key_holder_2` and
 * `key_holder_3`, written through `/e2e/values` like every other sealed field;
 * the server never sees who they are. What it stores here is only that the
 * person who sealed position N says they checked, and when. Only that person may
 * say it, because nobody else can read who position N names — V95's policy
 * requires the tick's author to be the sealer. Rewriting or removing the name
 * removes the tick with it (a trigger on `sealed_values`).
 */
@Service
class AccessChainService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
    private val audit: AuditService,
    private val userContext: RequestUserContext,
) {

    @Transactional
    fun confirm(householdId: UUID, recordType: String, recordId: UUID, position: Int): ChainTick {
        val userId = userContext.require()
        households.get(householdId)
        val fieldKey = fieldKey(position)
        val slot = slot(householdId, recordType, recordId, fieldKey)
        when {
            slot == null -> throw ApiException.badRequest(
                "nothing_to_confirm", "Write who holds it first; there is nobody at that place in the chain yet.",
            )
            !slot -> throw ApiException.conflict(
                "sealed_by_someone_else",
                "Only the person who wrote this name can say they checked with them. Nobody else can read it.",
            )
        }
        jdbc.update(
            """
            insert into access_chain_confirmations (household_id, record_type, record_id, position, confirmed_by)
            values (:hid, :type, :rid, :position, :uid)
            on conflict (record_type, record_id, position)
              do update set confirmed_at = now(), confirmed_by = excluded.confirmed_by
            """.trimIndent(),
            mapOf("hid" to householdId, "type" to recordType, "rid" to recordId, "position" to position, "uid" to userId),
        )
        audit.record(
            householdId = householdId, actorUserId = userId, action = "continuity.chain.confirm",
            entityType = recordType, entityId = recordId, diff = mapOf("position" to position),
        )
        return jdbc.query(
            """
            select confirmed_at from access_chain_confirmations
            where record_type = :type and record_id = :rid and position = :position
            """.trimIndent(),
            mapOf("type" to recordType, "rid" to recordId, "position" to position),
        ) { rs, _ -> ChainTick(position, rs.getTimestamp("confirmed_at").toInstant(), true) }.first()
    }

    @Transactional
    fun unconfirm(householdId: UUID, recordType: String, recordId: UUID, position: Int) {
        val userId = userContext.require()
        households.get(householdId)
        fieldKey(position)
        if (!recordVisible(householdId, recordType, recordId)) throw ApiException.notFound()
        val removed = jdbc.update(
            """
            delete from access_chain_confirmations
            where household_id = :hid and record_type = :type and record_id = :rid and position = :position
            """.trimIndent(),
            mapOf("hid" to householdId, "type" to recordType, "rid" to recordId, "position" to position),
        )
        if (removed == 0) throw ApiException.notFound()
        audit.record(
            householdId = householdId, actorUserId = userId, action = "continuity.chain.unconfirm",
            entityType = recordType, entityId = recordId, diff = mapOf("position" to position),
        )
    }

    /** null: no value at that position (or no record the caller can see, which reads the same); true: sealed by the caller. */
    private fun slot(householdId: UUID, recordType: String, recordId: UUID, fieldKey: String): Boolean? {
        if (!recordVisible(householdId, recordType, recordId)) throw ApiException.notFound()
        return jdbc.query(
            """
            select sealed_by = app.current_user_id() as mine from sealed_values
            where household_id = :hid and record_type = :type and record_id = :rid and field_key = :field
            """.trimIndent(),
            mapOf("hid" to householdId, "type" to recordType, "rid" to recordId, "field" to fieldKey),
        ) { rs, _ -> rs.getBoolean("mine") }.firstOrNull()
    }

    private fun recordVisible(householdId: UUID, recordType: String, recordId: UUID): Boolean {
        if (recordType !in RECORD_TYPES) throw ApiException.notFound()
        return jdbc.queryForObject(
            "select app.linked_record_visible(:type, :rid)",
            mapOf("type" to recordType, "rid" to recordId),
            Boolean::class.java,
        ) == true && jdbc.queryForObject(
            "select app.is_household_member(:hid)", mapOf("hid" to householdId), Boolean::class.java,
        ) == true
    }

    private fun fieldKey(position: Int): String = when (position) {
        1 -> WhereAndWho.KEY_HOLDER
        2 -> WhereAndWho.KEY_HOLDER_2
        3 -> WhereAndWho.KEY_HOLDER_3
        else -> throw ApiException.badRequest("position_invalid", "A chain has a first, a second and a third.")
    }

    private companion object {
        val RECORD_TYPES = setOf("investment", "liability", "account", "document", "estate_document")
    }
}

@RestController
@RequestMapping("/api/v1/households/{householdId}/where-and-who/{recordType}/{recordId}/chain/{position}/confirmation")
class AccessChainController(private val service: AccessChainService) {

    /** "Checked with them" — today's date on that place in the chain. */
    @PutMapping
    fun confirmChainPosition(
        @PathVariable householdId: UUID,
        @PathVariable recordType: String,
        @PathVariable recordId: UUID,
        @PathVariable position: Int,
    ): ChainTick = service.confirm(householdId, recordType, recordId, position)

    @DeleteMapping
    fun unconfirmChainPosition(
        @PathVariable householdId: UUID,
        @PathVariable recordType: String,
        @PathVariable recordId: UUID,
        @PathVariable position: Int,
    ): ResponseEntity<Void> {
        service.unconfirm(householdId, recordType, recordId, position)
        return ResponseEntity.noContent().build()
    }
}
