package tech.almira.e2e

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tech.almira.household.HouseholdService
import tech.almira.security.RequestUserContext
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/**
 * One of the two sealed values, as the server holds it: bytes it cannot open,
 * and the metadata it already has (docs/12 §9, "Metadata").
 *
 * [sealedByMe] is there because each person's content key is their own. A value
 * a co-owner sealed arrives here too, because the record is visible — and no
 * passphrase this caller has will open it. Without this flag a client could not
 * tell "sealed by someone else" from "corrupt", and would have to say one of
 * them wrongly.
 */
data class WhereAndWhoValue(
    val ciphertext: String,
    val keyVersion: Int,
    val updatedAt: Instant,
    val sealedByMe: Boolean,
    /** Who sealed it and who could open it — "Sealed by Amma · Ravi keeps the recovery sheet" (docs/12 §10.5). */
    val access: SealedLine? = null,
)

data class WhereAndWhoRecord(
    val recordType: String,
    val recordId: UUID,
    /** Not sealed: titles drive lists, totals and server search (docs/12 §1). */
    val title: String,
    val originalLocation: WhereAndWhoValue?,
    val keyHolder: WhereAndWhoValue?,
    /** Who to go to if the key holder can't be reached — sealed, like the first (docs/27 §6). */
    val keyHolder2: WhereAndWhoValue? = null,
    /** And after them. */
    val keyHolder3: WhereAndWhoValue? = null,
    /** A dated tick per position in the chain that the person who sealed it has confirmed. */
    val chain: List<ChainTick> = emptyList(),
)

/** "Checked with them": plaintext is only the position and the date; the name stays sealed. */
data class ChainTick(
    /** 1 is [WhereAndWhoRecord.keyHolder], 2 and 3 the backups. */
    val position: Int,
    val confirmedAt: Instant,
    val confirmedByMe: Boolean,
)

data class WhereAndWhoIndex(
    /** The field keys a client seals under, so no client has to guess them. */
    val fieldKeys: Map<String, String> = mapOf(
        "originalLocation" to WhereAndWho.ORIGINAL_LOCATION,
        "keyHolder" to WhereAndWho.KEY_HOLDER,
        "keyHolder2" to WhereAndWho.KEY_HOLDER_2,
        "keyHolder3" to WhereAndWho.KEY_HOLDER_3,
    ),
    val records: List<WhereAndWhoRecord>,
    val caveats: List<String> = WhereAndWho.CAVEATS,
)

object WhereAndWho {
    const val ORIGINAL_LOCATION = "original_location"
    const val KEY_HOLDER = "key_holder"

    /**
     * The access chain (docs/27 §6): who to go to when the key holder can't be
     * reached, then who after them. Sealed exactly like [KEY_HOLDER], because
     * where holder names are sealed the chain must be too.
     */
    const val KEY_HOLDER_2 = "key_holder_2"
    const val KEY_HOLDER_3 = "key_holder_3"

    /** Position in the chain for a field key, or null for one that is not in it. */
    fun chainPosition(fieldKey: String): Int? = when (fieldKey) {
        KEY_HOLDER -> 1
        KEY_HOLDER_2 -> 2
        KEY_HOLDER_3 -> 3
        else -> null
    }

    val CAVEATS = listOf(
        "Where the original is and who holds the key are sealed with your passphrase. " +
            "We store them and cannot read them.",
        "Search happens on this device, after you unlock, over what you can open. " +
            "Nothing is searched on the server, and nothing is searched while locked.",
        "Only the person who sealed a value can open it. Someone who can see the record " +
            "sees that a location exists, not what it says.",
        "Your family can't read these after you're gone unless they have your passphrase, " +
            "your recovery sheet, or two of your recovery shares. Decide now which they will have.",
    )
}

/**
 * "Where is the original, and who holds the key" — for every record that has a
 * physical original (docs/20).
 *
 * This is an index, not a store. The values are ordinary sealed values under
 * the fixed field keys [WhereAndWho.ORIGINAL_LOCATION] and
 * [WhereAndWho.KEY_HOLDER], written through the existing `/e2e/values` endpoint
 * and rotated by the existing `/e2e/key` path. What this adds is one read that
 * returns every record the caller can see with its two sealed slots attached, so
 * a client can search across all of them — on the device, after unlocking,
 * because nowhere else can read them.
 *
 * Every row comes through the caller's row-level security: the records through
 * their own tables' policies, the sealed values through V22's policy that
 * follows the record. A private record is not in the index at all.
 */
@Service
class WhereAndWhoService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
    private val userContext: RequestUserContext,
    private val sealedAccess: SealedAccessService,
    private val mapper: com.fasterxml.jackson.databind.ObjectMapper,
) {

    @Transactional(readOnly = true)
    fun index(householdId: UUID, recordType: String? = null, recordId: UUID? = null): WhereAndWhoIndex {
        val userId = userContext.require()
        households.get(householdId)

        // The sealed value's household must match the record's: the AAD binds
        // the household, so a value filed under another household could never
        // open here and would only be noise.
        val recovery = sealedAccess.presence(householdId)
        val records = jdbc.query(
            """
            with records as (
              select 'investment' as record_type, id, household_id, title from investments
                where household_id = :hid and deleted_at is null
              union all
              select 'liability', id, household_id, title from liabilities
                where household_id = :hid and deleted_at is null
              union all
              select 'account', id, household_id, label from accounts
                where household_id = :hid and deleted_at is null
              union all
              select 'document', id, household_id, file_name from documents
                where household_id = :hid and deleted_at is null
              union all
              select 'estate_document', id, household_id, title from estate_documents
                where household_id = :hid and deleted_at is null
            )
            select r.record_type, r.id, r.title,
                   loc.ciphertext as loc_ciphertext, loc.key_version as loc_key_version,
                   loc.updated_at as loc_updated_at, loc.sealed_by = :uid as loc_mine,
                   loc.sealed_by as loc_sealed_by, locm.id as loc_member_id, locm.display_name as loc_name,
                   kh.ciphertext as kh_ciphertext, kh.key_version as kh_key_version,
                   kh.updated_at as kh_updated_at, kh.sealed_by = :uid as kh_mine,
                   kh.sealed_by as kh_sealed_by, khm.id as kh_member_id, khm.display_name as kh_name,
                   kh2.ciphertext as kh2_ciphertext, kh2.key_version as kh2_key_version,
                   kh2.updated_at as kh2_updated_at, kh2.sealed_by = :uid as kh2_mine,
                   kh2.sealed_by as kh2_sealed_by, kh2m.id as kh2_member_id, kh2m.display_name as kh2_name,
                   kh3.ciphertext as kh3_ciphertext, kh3.key_version as kh3_key_version,
                   kh3.updated_at as kh3_updated_at, kh3.sealed_by = :uid as kh3_mine,
                   kh3.sealed_by as kh3_sealed_by, kh3m.id as kh3_member_id, kh3m.display_name as kh3_name,
                   (select coalesce(json_agg(json_build_object(
                             'position', t.position, 'confirmedAt', t.confirmed_at, 'mine', t.confirmed_by = :uid)
                             order by t.position)::text, '[]')
                      from access_chain_confirmations t
                     where t.record_type = r.record_type and t.record_id = r.id
                       and t.household_id = r.household_id) as chain
            from records r
            left join sealed_values loc
              on loc.record_type = r.record_type and loc.record_id = r.id
             and loc.household_id = r.household_id and loc.field_key = :location
            left join members locm
              on locm.household_id = r.household_id and locm.user_id = loc.sealed_by and locm.deleted_at is null
            left join sealed_values kh
              on kh.record_type = r.record_type and kh.record_id = r.id
             and kh.household_id = r.household_id and kh.field_key = :holder
            left join members khm
              on khm.household_id = r.household_id and khm.user_id = kh.sealed_by and khm.deleted_at is null
            left join sealed_values kh2
              on kh2.record_type = r.record_type and kh2.record_id = r.id
             and kh2.household_id = r.household_id and kh2.field_key = :holder2
            left join members kh2m
              on kh2m.household_id = r.household_id and kh2m.user_id = kh2.sealed_by and kh2m.deleted_at is null
            left join sealed_values kh3
              on kh3.record_type = r.record_type and kh3.record_id = r.id
             and kh3.household_id = r.household_id and kh3.field_key = :holder3
            left join members kh3m
              on kh3m.household_id = r.household_id and kh3m.user_id = kh3.sealed_by and kh3m.deleted_at is null
            where (cast(:type as text) is null or r.record_type = cast(:type as text))
              and (cast(:rid as uuid) is null or r.id = cast(:rid as uuid))
            order by r.record_type, lower(r.title), r.id
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("hid", householdId).addValue("uid", userId)
                .addValue("type", recordType).addValue("rid", recordId)
                .addValue("location", WhereAndWho.ORIGINAL_LOCATION)
                .addValue("holder", WhereAndWho.KEY_HOLDER)
                .addValue("holder2", WhereAndWho.KEY_HOLDER_2)
                .addValue("holder3", WhereAndWho.KEY_HOLDER_3),
        ) { rs, _ ->
            WhereAndWhoRecord(
                recordType = rs.getString("record_type"),
                recordId = rs.getObject("id", UUID::class.java),
                title = rs.getString("title"),
                originalLocation = slot(rs, "loc", WhereAndWho.ORIGINAL_LOCATION, recovery),
                keyHolder = slot(rs, "kh", WhereAndWho.KEY_HOLDER, recovery),
                keyHolder2 = slot(rs, "kh2", WhereAndWho.KEY_HOLDER_2, recovery),
                keyHolder3 = slot(rs, "kh3", WhereAndWho.KEY_HOLDER_3, recovery),
                chain = chain(rs.getString("chain")),
            )
        }
        return WhereAndWhoIndex(records = records)
    }

    private fun chain(json: String?): List<ChainTick> {
        if (json.isNullOrBlank()) return emptyList()
        return mapper.readTree(json).map {
            ChainTick(
                position = it.path("position").asInt(),
                // json_build_object writes a timestamptz as ISO-8601 with an offset.
                confirmedAt = java.time.OffsetDateTime.parse(it.path("confirmedAt").asText()).toInstant(),
                confirmedByMe = it.path("mine").asBoolean(),
            )
        }
    }

    private fun slot(
        rs: ResultSet,
        prefix: String,
        fieldKey: String,
        recovery: Map<UUID, RecoveryPresence>,
    ): WhereAndWhoValue? {
        val ciphertext = rs.getString("${prefix}_ciphertext") ?: return null
        val mine = rs.getBoolean("${prefix}_mine")
        return WhereAndWhoValue(
            ciphertext = ciphertext,
            keyVersion = rs.getInt("${prefix}_key_version"),
            updatedAt = rs.getTimestamp("${prefix}_updated_at").toInstant(),
            sealedByMe = mine,
            access = sealedAccess.line(
                fieldKey,
                rs.getObject("${prefix}_member_id", UUID::class.java),
                rs.getString("${prefix}_name"),
                mine,
                recovery[rs.getObject("${prefix}_sealed_by", UUID::class.java)],
            ),
        )
    }
}

@RestController
@RequestMapping("/api/v1/households/{householdId}/where-and-who")
class WhereAndWhoController(private val service: WhereAndWhoService) {

    /**
     * The whole index, or one record's entry when [recordType] and [recordId]
     * are given — which is what a record's own screen asks for.
     */
    @GetMapping
    fun index(
        @PathVariable householdId: UUID,
        @RequestParam(required = false) recordType: String?,
        @RequestParam(required = false) recordId: UUID?,
    ): WhereAndWhoIndex = service.index(householdId, recordType, recordId)
}
