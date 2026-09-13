package tech.bhrigu.almira.continuity

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.e2e.WhereAndWho
import tech.bhrigu.almira.household.HouseholdService
import tech.bhrigu.almira.security.RequestUserContext
import java.math.BigInteger
import java.util.UUID

data class ReadinessCheck(
    /** `nominee` | `document` | `location` | `trusted_contact` */
    val code: String,
    val label: String,
    val done: Int,
    val applicable: Int,
    /** Rounded down. Null when nothing counted applies to this check. */
    val percent: Int?,
)

/**
 * One thing missing, named. [recordType]/[recordId] are the deep link; a gap
 * about the viewer themselves (the trusted contact) has neither.
 */
data class ReadinessGap(
    val check: String,
    val reason: String,
    val recordType: String?,
    val recordId: UUID?,
    val title: String?,
    /** English; clients translate by [reason] and fall back to this (Doc 14). */
    val fix: String,
)

data class HandoverReadiness(
    /** 0–100, rounded down, or null when the data cannot earn a number (docs/22 §1). */
    val score: Int?,
    /** Always a sentence: how the number was made, or why there is none. */
    val scoreExplanation: String,
    /** True only when there is a score and nothing is missing. */
    val complete: Boolean,
    val recordCount: Int,
    val leftOutCount: Int,
    val checks: List<ReadinessCheck>,
    val gaps: List<ReadinessGap>,
    val caveats: List<String>,
)

/** Which of the three record checks apply to a kind of holding (docs/22 §3). */
data class Applies(val nominee: Boolean, val document: Boolean, val location: Boolean)

object HandoverChecks {
    const val NOMINEE = "nominee"
    const val DOCUMENT = "document"
    const val LOCATION = "location"
    const val TRUSTED_CONTACT = "trusted_contact"

    private fun a(nominee: Boolean, document: Boolean, location: Boolean) = Applies(nominee, document, location)
    private const val Y = true
    private const val N = false

    /** docs/22 §3, the first table. `HandoverReadinessDocTest` holds the two together. */
    val TYPES: Map<String, Applies> = linkedMapOf(
        "gold_physical" to a(N, Y, Y),
        "gold_jewelry" to a(N, Y, Y),
        "gold_digital" to a(N, Y, N),
        "gold_sgb" to a(Y, Y, N),
        "silver" to a(N, Y, Y),
        "fd" to a(Y, Y, N),
        "rd" to a(Y, Y, N),
        "tax_saver_fd" to a(Y, Y, N),
        "mf_sip" to a(Y, Y, N),
        "mf_lumpsum" to a(Y, Y, N),
        "stock_listed" to a(Y, Y, N),
        "stock_unlisted" to a(N, Y, Y),
        "esop_rsu" to a(N, Y, N),
        "ipo_application" to a(N, N, N),
        "bond" to a(Y, Y, N),
        "ppf" to a(Y, Y, Y),
        "epf" to a(Y, Y, N),
        "nps" to a(Y, Y, N),
        "ssy" to a(N, Y, Y),
        "nsc_kvp" to a(Y, Y, Y),
        "scss" to a(Y, Y, N),
        "insurance_term" to a(Y, Y, Y),
        "insurance_endowment" to a(Y, Y, Y),
        "insurance_ulip" to a(Y, Y, Y),
        "insurance_health" to a(N, Y, N),
        "property" to a(N, Y, Y),
        "reit" to a(Y, Y, N),
        "chit_fund" to a(N, Y, N),
        // The recovery phrase's whereabouts is the whole handover; a scan of it
        // would be the worst file this product could hold.
        "crypto" to a(N, N, Y),
        "business_equity" to a(N, Y, Y),
        "collectible" to a(N, Y, Y),
        "p2p_lending" to a(N, Y, N),
        "savings_buffer" to a(Y, N, N),
        "cash_on_hand" to a(N, N, Y),
        "loan_given" to a(N, Y, Y),
        "universal" to a(N, Y, Y),
    )

    /** docs/22 §3, the second table: a household's own type takes its category's row. */
    val CATEGORIES: Map<String, Applies> = linkedMapOf(
        "gold" to a(N, Y, Y),
        "deposits" to a(Y, Y, N),
        "mutual_funds" to a(Y, Y, N),
        "equity" to a(Y, Y, N),
        "ipo" to a(N, N, N),
        "bonds" to a(Y, Y, N),
        "retirement" to a(Y, Y, N),
        "insurance" to a(Y, Y, Y),
        "real_estate" to a(N, Y, Y),
        "alternatives" to a(N, Y, Y),
        "cash" to a(N, N, Y),
        "universal" to a(N, Y, Y),
    )

    private val FALLBACK = a(N, Y, Y)

    /** Wills, POAs and trusts: a scan and where the original is; no nominee. */
    val ESTATE_DOCUMENT = a(N, Y, Y)

    fun forInvestment(typeCode: String, categoryCode: String): Applies =
        TYPES[typeCode] ?: CATEGORIES[categoryCode] ?: FALLBACK
}

/** The arithmetic, apart from the database, so it can be held to its promise. */
object ReadinessScore {

    /**
     * floor(100 × mean of done/applicable over the checks that apply), in exact
     * integer arithmetic. Rounding down is the guarantee: with anything missing,
     * the mean is below one, so the result is at most 99 — 199 of 200 does not
     * become 100 the way rounding to nearest would make it.
     *
     * Null when no check applies.
     */
    fun of(checks: List<Pair<Int, Int>>): Int? {
        val applying = checks.filter { (_, applicable) -> applicable > 0 }
        if (applying.isEmpty()) return null
        applying.forEach { (done, applicable) -> require(done in 0..applicable) { "done out of range" } }

        val product = applying.fold(BigInteger.ONE) { acc, (_, applicable) -> acc * applicable.toBigInteger() }
        val numerator = applying.fold(BigInteger.ZERO) { acc, (done, applicable) ->
            acc + done.toBigInteger() * (product / applicable.toBigInteger())
        }
        val denominator = product * applying.size.toBigInteger()
        return (numerator * BigInteger.valueOf(100) / denominator).toInt()
    }

    fun percent(done: Int, applicable: Int): Int? =
        if (applicable == 0) null else (done.toLong() * 100 / applicable).toInt()
}

/**
 * "If you could not explain anything tomorrow, how much of it could your family
 * use?" (docs/22)
 *
 * Every read is on the runtime connection, as the viewer, so the score is a
 * fact about what this person can see and would hand over — never about a
 * record their row-level security hides. Records count only by ordinary sight
 * (`app.can_read_record` with the ownership predicate), which leaves out what an
 * open emergency window lets someone see: that was handed to them, not by them.
 */
@Service
class HandoverReadinessService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
    private val userContext: RequestUserContext,
) {

    @Transactional(readOnly = true)
    fun readiness(householdId: UUID): HandoverReadiness {
        userContext.require()
        // A guest link has no handover to prepare, and a score over a clamped
        // scope would be a number about nothing in particular.
        if (userContext.currentGuestShareId() != null) throw ApiException.forbidden()
        households.get(householdId)

        val params = mapOf("hid" to householdId, "fieldKey" to WhereAndWho.ORIGINAL_LOCATION)
        val investments = jdbc.query(INVESTMENTS, params) { rs, _ ->
            Row(
                recordType = "investment",
                recordId = rs.getObject("id", UUID::class.java),
                title = rs.getString("title"),
                inContinuity = rs.getBoolean("is_in_continuity"),
                applies = HandoverChecks.forInvestment(rs.getString("type_code"), rs.getString("category_code")),
                hasNominee = rs.getBoolean("has_nominee"),
                hasDocument = rs.getBoolean("has_document"),
                hasSealedLocation = rs.getBoolean("has_sealed_location"),
                hasUnsealedNote = rs.getBoolean("has_unsealed_note"),
            )
        }
        val instruments = jdbc.query(ESTATE_DOCUMENTS, params) { rs, _ ->
            Row(
                recordType = "estate_document",
                recordId = rs.getObject("id", UUID::class.java),
                title = rs.getString("title"),
                inContinuity = true,
                applies = HandoverChecks.ESTATE_DOCUMENT,
                hasNominee = false,
                hasDocument = rs.getBoolean("has_document"),
                hasSealedLocation = rs.getBoolean("has_sealed_location"),
                hasUnsealedNote = rs.getBoolean("has_unsealed_note"),
            )
        }

        val all = investments + instruments
        val counted = all.filter { it.inContinuity }
        val leftOut = all.size - counted.size

        val gaps = mutableListOf<ReadinessGap>()
        val trusted = trustedContact(householdId)
        trusted.gap?.let(gaps::add)

        var nominee = 0 to 0
        var document = 0 to 0
        var location = 0 to 0
        var sealedLocationCounted = false
        for (row in counted.sortedWith(compareBy({ it.title.lowercase() }, { it.recordId.toString() }))) {
            if (row.applies.nominee) {
                nominee = nominee.first + (if (row.hasNominee) 1 else 0) to nominee.second + 1
                if (!row.hasNominee) gaps += row.gap(HandoverChecks.NOMINEE, "no_nominee", FIX_NOMINEE)
            }
            if (row.applies.document) {
                document = document.first + (if (row.hasDocument) 1 else 0) to document.second + 1
                if (!row.hasDocument) gaps += row.gap(HandoverChecks.DOCUMENT, "no_document", FIX_DOCUMENT)
            }
            if (row.applies.location) {
                location = location.first + (if (row.hasSealedLocation) 1 else 0) to location.second + 1
                when {
                    row.hasSealedLocation -> sealedLocationCounted = true
                    // The unsealed note is the sentence docs/20 is retiring. It
                    // does not count, and the fix is one tap on the same card.
                    row.hasUnsealedNote ->
                        gaps += row.gap(HandoverChecks.LOCATION, "location_unsealed", FIX_LOCATION_UNSEALED)
                    else -> gaps += row.gap(HandoverChecks.LOCATION, "no_location", FIX_LOCATION)
                }
            }
        }

        val checks = listOf(
            check(HandoverChecks.NOMINEE, "A nominee recorded", nominee),
            check(HandoverChecks.DOCUMENT, "A scan attached", document),
            check(HandoverChecks.LOCATION, "Where the original is, sealed", location),
            check(HandoverChecks.TRUSTED_CONTACT, "Someone who can ask for access", trusted.counts),
        )

        // A number earned by the household check alone, with nothing handed
        // over, is not a fact about the handover (docs/22 §1).
        val recordItems = nominee.second + document.second + location.second
        val (score, explanation) = when {
            all.isEmpty() -> null to NOTHING_RECORDED
            counted.isEmpty() -> null to ALL_LEFT_OUT
            recordItems == 0 -> null to NOTHING_APPLIES
            else -> ReadinessScore.of(checks.map { it.done to it.applicable }) to HOW_IT_IS_COUNTED
        }

        val caveats = buildList {
            add(CAVEAT_RECORDED_HERE)
            add(CAVEAT_WHAT_YOU_CAN_SEE)
            if (sealedLocationCounted) add(WhereAndWho.CAVEATS.last())
            if (leftOut > 0) {
                add(
                    "$leftOut ${if (leftOut == 1) "record is" else "records are"} left out of the " +
                        "family summary on purpose, and not scored.",
                )
            }
            add(CAVEAT_STILL_TRUE)
        }

        return HandoverReadiness(
            score = score,
            scoreExplanation = explanation,
            complete = score != null && gaps.isEmpty(),
            recordCount = counted.size,
            leftOutCount = leftOut,
            checks = checks,
            gaps = gaps,
            caveats = caveats,
        )
    }

    private data class Trusted(val counts: Pair<Int, Int>, val gap: ReadinessGap?)

    /**
     * Someone who can actually ask. A named member with no login, or one who has
     * left, could never make the request, so naming them does not count.
     */
    private fun trustedContact(householdId: UUID): Trusted {
        val row = jdbc.queryForMap(
            """
            with me as (
              select m.id from members m
              where m.household_id = :hid and m.user_id = app.current_user_id() and m.deleted_at is null
            ),
            can_ask as (
              select m.id from members m
              join household_memberships hm
                on hm.household_id = m.household_id and hm.user_id = m.user_id and hm.status = 'active'
              where m.household_id = :hid and m.deleted_at is null and m.user_id is not null
            )
            select exists (select 1 from me) as is_member,
                   exists (select 1 from emergency_contacts ec
                           where ec.household_id = :hid and ec.member_id in (select id from me)
                             and ec.trusted_member_id in (select id from can_ask)) as named_can_ask,
                   exists (select 1 from emergency_contacts ec
                           where ec.household_id = :hid and ec.member_id in (select id from me)) as named_any,
                   exists (select 1 from can_ask where id not in (select id from me)) as someone_to_name
            """.trimIndent(),
            mapOf("hid" to householdId),
        )
        if (row["is_member"] != true) return Trusted(0 to 0, null)
        if (row["named_can_ask"] == true) return Trusted(1 to 1, null)
        val (reason, fix) = when {
            row["named_any"] == true -> "trusted_contact_cannot_ask" to FIX_TRUSTED_CANNOT_ASK
            row["someone_to_name"] == true -> "no_trusted_contact" to FIX_TRUSTED
            else -> "nobody_to_name" to FIX_NOBODY_TO_NAME
        }
        return Trusted(0 to 1, ReadinessGap(HandoverChecks.TRUSTED_CONTACT, reason, null, null, null, fix))
    }

    private fun check(code: String, label: String, counts: Pair<Int, Int>) = ReadinessCheck(
        code = code, label = label, done = counts.first, applicable = counts.second,
        percent = ReadinessScore.percent(counts.first, counts.second),
    )

    private data class Row(
        val recordType: String,
        val recordId: UUID,
        val title: String,
        val inContinuity: Boolean,
        val applies: Applies,
        val hasNominee: Boolean,
        val hasDocument: Boolean,
        val hasSealedLocation: Boolean,
        val hasUnsealedNote: Boolean,
    ) {
        fun gap(check: String, reason: String, fix: String) =
            ReadinessGap(check, reason, recordType, recordId, title, fix)
    }

    private companion object {
        // Ordinary sight only: the policy's emergency branch is deliberately not
        // repeated here (docs/22 §5). Row-level security still applies on top.
        val INVESTMENTS = """
            select i.id, i.title, i.is_in_continuity, t.code as type_code, c.code as category_code,
                   exists (select 1 from investment_nominees n where n.investment_id = i.id) as has_nominee,
                   exists (select 1 from document_links dl
                           join documents d on d.id = dl.document_id and d.deleted_at is null
                           where dl.entity_type = 'investment' and dl.entity_id = i.id) as has_document,
                   exists (select 1 from sealed_values sv
                           where sv.household_id = i.household_id and sv.record_type = 'investment'
                             and sv.record_id = i.id and sv.field_key = :fieldKey) as has_sealed_location,
                   coalesce(btrim(i.storage_location), '') <> '' as has_unsealed_note
            from investments i
            join investment_types t on t.id = i.type_id
            join asset_categories c on c.id = t.category_id
            where i.household_id = :hid
              and i.deleted_at is null
              and i.status in ('active','matured')
              and app.can_read_record(i.household_id, i.visibility, 'investment', i.id, app.owns_investment(i.id))
              and not exists (select 1 from investments s
                              where s.rolled_from_id = i.id and s.deleted_at is null)
        """.trimIndent()

        val ESTATE_DOCUMENTS = """
            select e.id, e.title,
                   exists (select 1 from documents d
                           where d.id = e.document_id and d.deleted_at is null)
                   or exists (select 1 from document_links dl
                              join documents d on d.id = dl.document_id and d.deleted_at is null
                              where dl.entity_type = 'estate' and dl.entity_id = e.id) as has_document,
                   exists (select 1 from sealed_values sv
                           where sv.household_id = e.household_id and sv.record_type = 'estate_document'
                             and sv.record_id = e.id and sv.field_key = :fieldKey) as has_sealed_location,
                   coalesce(btrim(e.location), '') <> '' as has_unsealed_note
            from estate_documents e
            where e.household_id = :hid
              and e.deleted_at is null
              and e.status = 'executed'
              and app.can_read_record(e.household_id, e.visibility, 'estate_document', e.id,
                                      app.owns_estate_document(e.id))
        """.trimIndent()

        const val HOW_IT_IS_COUNTED =
            "Each check that applies counts equally, and within a check it is the share of your " +
                "records that have it. Rounded down, so 100 means nothing is missing."
        const val NOTHING_RECORDED = "Nothing is recorded for your family yet, so there is nothing to score."
        const val ALL_LEFT_OUT =
            "Everything you can see is left out of the family summary, so there is nothing to score."
        const val NOTHING_APPLIES = "None of the checks applies to what is recorded, so there is no honest score."

        const val FIX_NOMINEE =
            "Record who the institution pays. A nominee is not the same as who inherits it."
        const val FIX_DOCUMENT = "Attach a scan, so nobody has to hunt for the paper."
        const val FIX_LOCATION = "Say where the original is. It is sealed with your passphrase."
        const val FIX_LOCATION_UNSEALED =
            "Where it is is written in an unsealed note. Seal it on the record, so only you can read it."
        const val FIX_TRUSTED =
            "Name someone who can ask to see what's marked for the family if you can't be reached."
        const val FIX_TRUSTED_CANNOT_ASK =
            "Nobody you named can sign in, so nobody could ask. Name someone who can."
        const val FIX_NOBODY_TO_NAME =
            "Nobody else in this household can sign in. Invite someone you trust, then name them."

        const val CAVEAT_RECORDED_HERE =
            "This counts what is recorded here. A nominee recorded here is not proof the institution " +
                "has one on file."
        const val CAVEAT_WHAT_YOU_CAN_SEE =
            "Only records you can see are counted. Another member's private records are theirs to prepare."
        const val CAVEAT_STILL_TRUE =
            "Whether a record is still true does not change this score; the Still true? card asks that."
    }
}

@RestController
@RequestMapping("/api/v1/households/{householdId}/continuity")
class HandoverReadinessController(private val service: HandoverReadinessService) {

    /** One number for the handover, and every gap in it named (docs/22). */
    @GetMapping("/readiness")
    fun readiness(@PathVariable householdId: UUID): HandoverReadiness = service.readiness(householdId)
}
