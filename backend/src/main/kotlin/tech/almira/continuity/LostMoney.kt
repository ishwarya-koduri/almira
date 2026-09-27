package tech.almira.continuity

import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tech.almira.audit.AuditService
import tech.almira.common.ApiException
import tech.almira.household.HouseholdService
import tech.almira.investment.CreateInvestment
import tech.almira.investment.InvestmentService
import tech.almira.investment.OwnerInput
import tech.almira.security.RequestUserContext
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** A public portal that finds forgotten money. Reference data: links and words, nothing fetched. */
data class LostMoneyPortal(
    val code: String,
    val name: String,
    val finds: String,
    /** Opened by the person, in their own browser. Almira never calls it. */
    val url: String,
    val howToSearch: List<String>,
    val youNeed: List<String>,
    /** How to claim what turns up. Becomes the record's playbook once something is found. */
    val claim: List<TransmissionStep>,
)

data class LostMoneyCheck(
    val id: UUID,
    val memberId: UUID,
    val memberName: String?,
    val portal: String,
    /** checked | found | nothing */
    val status: String,
    val checkedOn: LocalDate,
    val investmentId: UUID?,
    /** The record made from what was found, if the caller can see it. */
    val recordTitle: String?,
)

data class LostMoneySweep(
    val portals: List<LostMoneyPortal>,
    val checks: List<LostMoneyCheck>,
    val note: String = SWEEP_NOTE,
)

data class RecordLostMoneyCheckBody(
    val memberId: UUID,
    val status: String,
    /** Defaults to today. */
    val checkedOn: LocalDate? = null,
)

data class RecordFoundMoneyBody(
    val memberId: UUID,
    @field:Size(max = 160) val title: String,
    val amount: BigDecimal? = null,
    @field:Size(max = 120) val whereFound: String? = null,
)

data class FoundMoneyRecorded(val check: LostMoneyCheck, val investmentId: UUID, val visibleToYou: Boolean)

private const val SWEEP_NOTE =
    "Almira only links to these government portals. It does not search them for you, and nothing you type " +
        "there comes back here. Record what you found, and the date you looked."

/**
 * The lost-money sweep (P-25, docs/03 §8.5).
 *
 * India holds tens of thousands of crores that families have forgotten: bank
 * deposits moved to RBI's DEA Fund after ten silent years, dividends and shares
 * moved to the IEPF, provident fund left with an old employer. Each has a
 * public search. This is a checklist of those three searches, per person: open
 * the portal, look, and come back to say what happened and when. A find
 * becomes an ordinary record, marked for the family plan, with the claim steps
 * in its notes — so it is in the handbook the day it is found.
 *
 * Links only. No scraping, no automated calls, no credentials — this adds no
 * outbound traffic at all.
 */
@Service
class LostMoneyService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
    private val investments: InvestmentService,
    private val audit: AuditService,
    private val userContext: RequestUserContext,
) {

    @Transactional(readOnly = true)
    fun sweep(householdId: UUID): LostMoneySweep {
        households.get(householdId)
        return LostMoneySweep(portals = PORTALS, checks = checks(householdId))
    }

    @Transactional
    fun record(householdId: UUID, portal: String, input: RecordLostMoneyCheckBody): LostMoneyCheck {
        val userId = userContext.require()
        households.get(householdId)
        requirePortal(portal)
        if (input.status !in STATUSES) {
            throw ApiException.badRequest("status_invalid", "Say whether you found something, found nothing, or are still looking.")
        }
        if (households.members(householdId).none { it.id == input.memberId }) {
            throw ApiException.notFound("We couldn't find that person.")
        }
        val today = LocalDate.now(INDIA)
        val checkedOn = input.checkedOn ?: today
        if (checkedOn.isAfter(today) || checkedOn.isBefore(today.minusYears(20))) {
            throw ApiException.badRequest("checked_on_invalid", "Use the date you looked: today or earlier.")
        }
        jdbc.update(
            """
            insert into lost_money_checks (household_id, member_id, portal, status, checked_on, created_by)
            values (:hid, :member, :portal, :status, :checkedOn, :me)
            on conflict (household_id, member_id, portal)
              do update set status = excluded.status, checked_on = excluded.checked_on
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("hid", householdId).addValue("member", input.memberId).addValue("portal", portal)
                .addValue("status", input.status).addValue("checkedOn", checkedOn).addValue("me", userId),
        )
        audit.record(
            householdId = householdId, actorUserId = userId, action = "lost_money.check",
            entityType = "member", entityId = input.memberId,
            diff = mapOf("portal" to portal, "status" to input.status),
        )
        return checks(householdId).firstOrNull { it.memberId == input.memberId && it.portal == portal }
            ?: throw ApiException.notFound()
    }

    /** Something turned up: a record, marked for the family plan, with how to claim it. */
    @Transactional
    fun found(householdId: UUID, portal: String, input: RecordFoundMoneyBody): FoundMoneyRecorded {
        val userId = userContext.require()
        val source = requirePortal(portal)
        val title = input.title.trim()
        if (title.isEmpty()) throw ApiException.badRequest("title_required", "Give it a name you'll recognise.")
        if (input.amount != null && input.amount.signum() < 0) {
            throw ApiException.badRequest("amount_invalid", "An amount found can't be less than nothing.")
        }
        record(householdId, portal, RecordLostMoneyCheckBody(input.memberId, "found"))

        val typeId = jdbc.queryForObject(
            "select id from investment_types where code = 'universal' and household_id is null",
            emptyMap<String, Any>(), UUID::class.java,
        )!!
        val created = investments.create(
            householdId,
            CreateInvestment(
                typeId = typeId,
                title = title,
                investedAmount = input.amount,
                owners = listOf(OwnerInput(input.memberId, BigDecimal(100))),
                attributes = mapOf("what_it_is" to "Found on ${source.name}${input.whereFound?.trim()?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""}"),
                isInContinuity = true,
                notes = "How to claim it:\n" + source.claim.mapIndexed { i, step -> "${i + 1}. ${step.step}: ${step.detail}" }.joinToString("\n"),
            ),
        )
        jdbc.update(
            "update lost_money_checks set investment_id = :inv where household_id = :hid and member_id = :member and portal = :portal",
            mapOf("inv" to created.id, "hid" to householdId, "member" to input.memberId, "portal" to portal),
        )
        audit.record(
            householdId = householdId, actorUserId = userId, action = "lost_money.found",
            entityType = "investment", entityId = created.id, diff = mapOf("portal" to portal),
        )
        val check = checks(householdId).first { it.memberId == input.memberId && it.portal == portal }
        return FoundMoneyRecorded(check, created.id, created.visibleToYou)
    }

    private fun checks(householdId: UUID): List<LostMoneyCheck> = jdbc.query(
        """
        select c.id, c.member_id, m.display_name, c.portal, c.status, c.checked_on, c.investment_id,
               i.title as record_title
        from lost_money_checks c
        left join members m on m.id = c.member_id
        left join investments i on i.id = c.investment_id and i.deleted_at is null
        where c.household_id = :hid
        order by m.display_name, c.portal
        """.trimIndent(),
        mapOf("hid" to householdId),
    ) { rs, _ ->
        LostMoneyCheck(
            id = rs.getObject("id", UUID::class.java),
            memberId = rs.getObject("member_id", UUID::class.java),
            memberName = rs.getString("display_name"),
            portal = rs.getString("portal"),
            status = rs.getString("status"),
            checkedOn = rs.getDate("checked_on").toLocalDate(),
            investmentId = rs.getObject("investment_id", UUID::class.java),
            recordTitle = rs.getString("record_title"),
        )
    }

    private fun requirePortal(code: String) = PORTALS.firstOrNull { it.code == code }
        ?: throw ApiException.notFound("We don't know that portal.")

    companion object {
        private val INDIA: ZoneId = ZoneId.of("Asia/Kolkata")
        private val STATUSES = setOf("checked", "found", "nothing")

        val PORTALS = listOf(
            LostMoneyPortal(
                code = "udgam",
                name = "RBI UDGAM",
                finds = "Bank deposits nobody has touched for ten years, which the bank has moved to RBI's " +
                    "Depositor Education and Awareness Fund.",
                url = "https://udgam.rbi.org.in/",
                howToSearch = listOf(
                    "Register with a mobile number, then choose the banks to search, or all of them.",
                    "Search by the account holder's name with their PAN, or date of birth, or another ID.",
                    "Try the name as the bank would have written it, with and without initials.",
                ),
                youNeed = listOf("The account holder's name as the bank knew it", "Their PAN or date of birth"),
                claim = listOf(
                    TransmissionStep("Go to the bank named in the result", "Any branch of that bank. The money is claimed from the bank, not from RBI."),
                    TransmissionStep("Take KYC and proof", "The holder's ID, or for someone who has died, the death certificate and your own KYC."),
                    TransmissionStep("Ask for the claim form", "The bank pays it back with interest where the deposit earned it, and reclaims it from RBI itself."),
                ),
            ),
            LostMoneyPortal(
                code = "iepf",
                name = "IEPF Authority",
                finds = "Dividends not claimed for seven years, and the shares behind them, which companies " +
                    "move to the Investor Education and Protection Fund.",
                url = "https://www.iepf.gov.in/",
                howToSearch = listOf(
                    "Open 'Search Unclaimed/Unpaid Amount' on the IEPF site.",
                    "Search by the investor's name, and narrow by company or the folio if you know it.",
                    "Search each name the shares may have been bought in, including joint names.",
                ),
                youNeed = listOf("The investor's name", "A company name, folio or DP ID if there is one"),
                claim = listOf(
                    TransmissionStep("Fill in form IEPF-5 online", "On the MCA portal. Note the SRN it gives you."),
                    TransmissionStep("Send the documents to the company", "The signed form, an indemnity bond, share certificates or demat details, and KYC, to the company's nodal officer."),
                    TransmissionStep("Wait for verification", "The company verifies and the IEPF Authority releases it. It can take months; the SRN lets you follow it."),
                ),
            ),
            LostMoneyPortal(
                code = "epfo",
                name = "EPFO member passbook",
                finds = "Provident fund left with a previous employer, including accounts that were never merged.",
                url = "https://passbook.epfindia.gov.in/",
                howToSearch = listOf(
                    "Sign in with the UAN and its password, or use 'Know your UAN' on the member portal first.",
                    "Open the passbook for each member ID listed under the UAN.",
                    "An old job before UAN existed may have its own account: ask that employer for the PF number.",
                ),
                youNeed = listOf("The UAN, or the mobile number linked to it", "An old employer's name and PF number, if any"),
                claim = listOf(
                    TransmissionStep("Transfer or withdraw online", "On the EPFO member portal, once the UAN has KYC linked."),
                    TransmissionStep("For someone who has died", "Form 20 for the fund, Form 10D for the pension and Form 5(IF) for the insurance, through the last employer or the EPFO office."),
                    TransmissionStep("If the employer has closed", "The EPFO regional office attests the claim instead."),
                ),
            ),
        )
    }
}

@RestController
@RequestMapping("/api/v1/households/{householdId}/lost-money")
class LostMoneyController(private val service: LostMoneyService) {

    @GetMapping
    fun lostMoneySweep(@PathVariable householdId: UUID): LostMoneySweep = service.sweep(householdId)

    /** Checked, found, or nothing — and the date. */
    @PutMapping("/{portal}")
    fun recordLostMoneyCheck(
        @PathVariable householdId: UUID,
        @PathVariable portal: String,
        @RequestBody @Valid body: RecordLostMoneyCheckBody,
    ): LostMoneyCheck = service.record(householdId, portal, body)

    @PostMapping("/{portal}/found")
    @ResponseStatus(HttpStatus.CREATED)
    fun recordFoundMoney(
        @PathVariable householdId: UUID,
        @PathVariable portal: String,
        @RequestBody @Valid body: RecordFoundMoneyBody,
    ): FoundMoneyRecorded = service.found(householdId, portal, body)
}
