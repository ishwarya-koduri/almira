package tech.almira.guidance

import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tech.almira.audit.AuditService
import tech.almira.common.ApiException
import tech.almira.household.HouseholdService
import tech.almira.security.RequestUserContext
import java.util.UUID

/**
 * "The 15 things most Indian families have" — the shelves of the first session
 * (P-10, docs/03 §1.2).
 *
 * The order is the design: what is quick to add comes first (a savings account,
 * a deposit, the gold), and what takes a real conversation comes last (the
 * house, the will). Whether a shelf has something on it is counted, never
 * stored, from the records the caller can see — row-level security decides
 * that, so a shelf can never tick itself from someone else's private record.
 *
 * When the setup is for someone else (X-32), a shelf counts only that person's
 * records: "Amma's gold", not the household's.
 */
enum class Shelf(
    val code: String,
    /** Investment type codes that put something on this shelf. */
    val investmentTypes: Set<String> = emptySet(),
    /** Account kinds that do. */
    val accountKinds: Set<String> = emptySet(),
    val anyLiability: Boolean = false,
    val estateKind: String? = null,
) {
    SAVINGS_ACCOUNT("savings_account", investmentTypes = setOf("savings_buffer"), accountKinds = setOf("savings")),
    FIXED_DEPOSIT("fixed_deposit", investmentTypes = setOf("fd", "rd", "tax_saver_fd")),
    GOLD("gold", investmentTypes = setOf("gold_physical", "gold_jewelry", "gold_digital", "gold_sgb", "silver")),
    HEALTH_INSURANCE("health_insurance", investmentTypes = setOf("insurance_health")),
    LIFE_INSURANCE("life_insurance", investmentTypes = setOf("insurance_term", "insurance_endowment", "insurance_ulip")),
    PPF("ppf", investmentTypes = setOf("ppf")),
    EPF("epf", investmentTypes = setOf("epf")),
    MUTUAL_FUNDS("mutual_funds", investmentTypes = setOf("mf_sip", "mf_lumpsum")),
    SHARES("shares", investmentTypes = setOf("stock_listed", "stock_unlisted", "esop_rsu"), accountKinds = setOf("demat")),
    SMALL_SAVINGS("small_savings", investmentTypes = setOf("nsc_kvp", "ssy", "scss")),
    NPS("nps", investmentTypes = setOf("nps")),
    LOANS("loans", anyLiability = true),
    LOCKER("locker", accountKinds = setOf("locker")),
    PROPERTY("property", investmentTypes = setOf("property")),
    WILL("will", estateKind = "will"),
    ;

    companion object {
        fun of(code: String) = entries.firstOrNull { it.code == code }
    }
}

data class FirstSessionShelf(val code: String, val done: Boolean, val skipped: Boolean)

data class FirstSessionResponse(
    /** "me", or "someone" when an adult child is setting this up for a parent. */
    val settingUpFor: String,
    val someoneMemberId: UUID?,
    /** The roster's name for that person, so the copy can speak about them. */
    val someoneName: String?,
    /** True when that person has no login of their own yet: they can be invited to confirm. */
    val someoneCanBeInvited: Boolean,
    val shelves: List<FirstSessionShelf>,
    val done: Int,
    val skipped: Int,
    val total: Int,
    /** Done out of the shelves not skipped, rounded down; 100 only when every one left is done. */
    val percent: Int,
)

data class FirstSessionBody(
    @field:Pattern(regexp = "me|someone") val settingUpFor: String? = null,
    val someoneMemberId: UUID? = null,
    @field:Size(max = 15) val skippedShelves: List<String>? = null,
)

@Service
class FirstSessionService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
    private val userContext: RequestUserContext,
    private val audit: AuditService,
) {
    private data class Progress(val settingUpFor: String, val someoneMemberId: UUID?, val skipped: Set<String>)

    @Transactional(readOnly = true)
    fun get(householdId: UUID): FirstSessionResponse {
        households.get(householdId)
        val progress = progress(householdId)
        val member = progress.someoneMemberId?.let { id ->
            households.members(householdId).firstOrNull { it.id == id }
        }
        val present = present(householdId, member?.id)
        val shelves = Shelf.entries.map { shelf ->
            FirstSessionShelf(shelf.code, done = shelf in present, skipped = shelf.code in progress.skipped && shelf !in present)
        }
        val skipped = shelves.count { it.skipped }
        val done = shelves.count { it.done }
        val counted = shelves.size - skipped
        return FirstSessionResponse(
            settingUpFor = progress.settingUpFor,
            someoneMemberId = member?.id,
            someoneName = member?.displayName,
            someoneCanBeInvited = member?.isManaged == true && member.isMinor.not(),
            shelves = shelves,
            done = done,
            skipped = skipped,
            total = shelves.size,
            percent = if (counted == 0) 100 else (done * 100) / counted,
        )
    }

    @Transactional
    fun update(householdId: UUID, body: FirstSessionBody): FirstSessionResponse {
        val userId = userContext.require()
        households.get(householdId)
        val current = progress(householdId)

        val skipped = body.skippedShelves?.let { codes ->
            val unknown = codes.filter { Shelf.of(it) == null }
            if (unknown.isNotEmpty()) {
                throw ApiException.badRequest("shelf_unknown", "That isn't one of the shelves.")
            }
            codes.toSet()
        } ?: current.skipped

        val settingUpFor = body.settingUpFor ?: current.settingUpFor
        val someone = when {
            settingUpFor == "me" -> null
            body.someoneMemberId != null -> body.someoneMemberId
            else -> current.someoneMemberId
        }
        if (someone != null) {
            // Not found, not forbidden: a member id from another household is
            // simply not a member of this one.
            households.members(householdId).firstOrNull { it.id == someone && !it.isMe }
                ?: throw ApiException.notFound("We couldn't find that person.")
        }

        try {
            jdbc.update(
                """
                insert into first_session_progress
                  (user_id, household_id, setting_up_for, someone_member_id, skipped_shelves)
                values (:u, :hid, :for, :someone, :skipped)
                on conflict (user_id, household_id) do update set
                  setting_up_for = excluded.setting_up_for,
                  someone_member_id = excluded.someone_member_id,
                  skipped_shelves = excluded.skipped_shelves
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("u", userId).addValue("hid", householdId)
                    .addValue("for", settingUpFor).addValue("someone", someone)
                    .addValue("skipped", skipped.sorted().toTypedArray()),
            )
        } catch (e: DataIntegrityViolationException) {
            throw ApiException.notFound("We couldn't find that person.")
        }

        if (settingUpFor != current.settingUpFor || someone != current.someoneMemberId) {
            // Who a setup is about is a statement about another person; it is
            // written down the way adding them to the roster is.
            audit.record(
                householdId = householdId, actorUserId = userId, action = "first_session.setting_up_for",
                entityType = "member", entityId = someone, diff = mapOf("settingUpFor" to settingUpFor),
            )
        }
        return get(householdId)
    }

    private fun progress(householdId: UUID): Progress = jdbc.query(
        "select setting_up_for, someone_member_id, skipped_shelves from first_session_progress where household_id = :hid",
        mapOf("hid" to householdId),
    ) { rs, _ ->
        @Suppress("UNCHECKED_CAST")
        val skipped = (rs.getArray("skipped_shelves")?.array as? Array<String>)?.toSet() ?: emptySet()
        Progress(rs.getString("setting_up_for"), rs.getObject("someone_member_id", UUID::class.java), skipped)
    }.firstOrNull() ?: Progress("me", null, emptySet())

    /**
     * Which shelves have something on them, as the caller can see it. Every
     * statement reads through row-level security; nothing here is the owner.
     */
    private fun present(householdId: UUID, memberId: UUID?): Set<Shelf> {
        val params = MapSqlParameterSource().addValue("hid", householdId).addValue("mid", memberId)
        val types = jdbc.queryForList(
            """
            select distinct it.code from investments i
            join investment_types it on it.id = i.type_id
            where i.household_id = :hid and i.deleted_at is null
              and (cast(:mid as uuid) is null or exists (
                    select 1 from investment_ownerships o
                    where o.investment_id = i.id and o.member_id = cast(:mid as uuid)))
            """.trimIndent(),
            params, String::class.java,
        ).toSet()
        val kinds = jdbc.queryForList(
            """
            select distinct a.account_kind from accounts a
            where a.household_id = :hid and a.deleted_at is null
              and (cast(:mid as uuid) is null or exists (
                    select 1 from account_holders h
                    where h.account_id = a.id and h.member_id = cast(:mid as uuid)))
            """.trimIndent(),
            params, String::class.java,
        ).toSet()
        val anyLiability = jdbc.queryForObject(
            """
            select exists (
              select 1 from liabilities l
              where l.household_id = :hid and l.deleted_at is null
                and (cast(:mid as uuid) is null or exists (
                      select 1 from liability_holders h
                      where h.liability_id = l.id and h.member_id = cast(:mid as uuid))))
            """.trimIndent(),
            params, Boolean::class.java,
        ) == true
        val estateKinds = jdbc.queryForList(
            """
            select distinct e.kind from estate_documents e
            where e.household_id = :hid and e.deleted_at is null
              and e.status <> 'revoked'
              and (cast(:mid as uuid) is null or e.member_id = cast(:mid as uuid))
            """.trimIndent(),
            params, String::class.java,
        ).toSet()

        return Shelf.entries.filter { shelf ->
            shelf.investmentTypes.any { it in types } ||
                shelf.accountKinds.any { it in kinds } ||
                (shelf.anyLiability && anyLiability) ||
                (shelf.estateKind != null && shelf.estateKind in estateKinds)
        }.toSet()
    }
}

@RestController
@RequestMapping("/api/v1/households/{householdId}/first-session")
class FirstSessionController(private val service: FirstSessionService) {

    @GetMapping
    fun firstSession(@PathVariable householdId: UUID): FirstSessionResponse = service.get(householdId)

    @PutMapping
    fun updateFirstSession(
        @PathVariable householdId: UUID,
        @Valid @RequestBody body: FirstSessionBody,
    ): FirstSessionResponse = service.update(householdId, body)
}
