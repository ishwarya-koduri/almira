package tech.bhrigu.almira.e2e

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tech.bhrigu.almira.household.HouseholdService
import tech.bhrigu.almira.security.RequestUserContext
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
)

data class WhereAndWhoRecord(
    val recordType: String,
    val recordId: UUID,
    /** Not sealed: titles drive lists, totals and server search (docs/12 §1). */
    val title: String,
    val originalLocation: WhereAndWhoValue?,
    val keyHolder: WhereAndWhoValue?,
)

data class WhereAndWhoIndex(
    /** The field keys a client seals under, so no client has to guess them. */
    val fieldKeys: Map<String, String> = mapOf(
        "originalLocation" to WhereAndWho.ORIGINAL_LOCATION,
        "keyHolder" to WhereAndWho.KEY_HOLDER,
    ),
    val records: List<WhereAndWhoRecord>,
    val caveats: List<String> = WhereAndWho.CAVEATS,
)

object WhereAndWho {
    const val ORIGINAL_LOCATION = "original_location"
    const val KEY_HOLDER = "key_holder"

    val CAVEATS = listOf(
        "Where the original is and who holds the key are sealed with your passphrase. " +
            "We store them and cannot read them.",
        "Search happens on this device, after you unlock, over what you can open. " +
            "Nothing is searched on the server, and nothing is searched while locked.",
        "Only the person who sealed a value can open it. Someone who can see the record " +
            "sees that a location exists, not what it says.",
        "Your family can't read these after you're gone unless they have the passphrase. " +
            "Decide now how they will get it.",
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
) {

    @Transactional(readOnly = true)
    fun index(householdId: UUID, recordType: String? = null, recordId: UUID? = null): WhereAndWhoIndex {
        val userId = userContext.require()
        households.get(householdId)

        // The sealed value's household must match the record's: the AAD binds
        // the household, so a value filed under another household could never
        // open here and would only be noise.
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
                   kh.ciphertext as kh_ciphertext, kh.key_version as kh_key_version,
                   kh.updated_at as kh_updated_at, kh.sealed_by = :uid as kh_mine
            from records r
            left join sealed_values loc
              on loc.record_type = r.record_type and loc.record_id = r.id
             and loc.household_id = r.household_id and loc.field_key = :location
            left join sealed_values kh
              on kh.record_type = r.record_type and kh.record_id = r.id
             and kh.household_id = r.household_id and kh.field_key = :holder
            where (cast(:type as text) is null or r.record_type = cast(:type as text))
              and (cast(:rid as uuid) is null or r.id = cast(:rid as uuid))
            order by r.record_type, lower(r.title), r.id
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("hid", householdId).addValue("uid", userId)
                .addValue("type", recordType).addValue("rid", recordId)
                .addValue("location", WhereAndWho.ORIGINAL_LOCATION)
                .addValue("holder", WhereAndWho.KEY_HOLDER),
        ) { rs, _ ->
            WhereAndWhoRecord(
                recordType = rs.getString("record_type"),
                recordId = rs.getObject("id", UUID::class.java),
                title = rs.getString("title"),
                originalLocation = slot(rs, "loc"),
                keyHolder = slot(rs, "kh"),
            )
        }
        return WhereAndWhoIndex(records = records)
    }

    private fun slot(rs: ResultSet, prefix: String): WhereAndWhoValue? {
        val ciphertext = rs.getString("${prefix}_ciphertext") ?: return null
        return WhereAndWhoValue(
            ciphertext = ciphertext,
            keyVersion = rs.getInt("${prefix}_key_version"),
            updatedAt = rs.getTimestamp("${prefix}_updated_at").toInstant(),
            sealedByMe = rs.getBoolean("${prefix}_mine"),
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
