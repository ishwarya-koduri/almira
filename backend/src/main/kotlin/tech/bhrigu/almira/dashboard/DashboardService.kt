package tech.bhrigu.almira.dashboard

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.common.IndianNumbers
import tech.bhrigu.almira.household.HouseholdService
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** One owner's slice of one holding — the grain everything else is built from. */
private data class Slice(
    val investmentId: UUID,
    val title: String,
    val categoryCode: String,
    val categoryLabel: String,
    val color: String,
    val typeLabel: String,
    val memberId: UUID,
    val memberName: String?,
    val institutionName: String?,
    val effectiveValue: BigDecimal?,
    val valueBasis: String,
    val attributedValue: BigDecimal,
    val maturityDate: LocalDate?,
    val lastVerified: LocalDate?,
    val hasInstitution: Boolean,
    val hasAccount: Boolean,
    val categoryExpectsAccount: Boolean,
)

/** One holder's share of one debt — the mirror of [Slice]. */
private data class DebtSlice(
    val liabilityId: UUID,
    val title: String,
    val kind: String,
    val kindLabel: String,
    val memberId: UUID,
    val memberName: String?,
    val outstanding: BigDecimal,
    val attributedOutstanding: BigDecimal,
    val emiAmount: BigDecimal?,
    val emiDay: Int?,
)

data class Breakdown(
    val key: String,
    val label: String,
    val color: String?,
    val value: BigDecimal,
    val valueFormatted: String,
    val percentage: BigDecimal,
    val count: Int,
)

data class UpcomingItem(
    val investmentId: UUID,
    val title: String,
    val kind: String,
    val date: LocalDate,
    val daysAway: Long,
    val value: BigDecimal?,
)

data class AttentionItem(
    val code: String,
    val label: String,
    val count: Int,
    val investmentIds: List<UUID>,
)

data class ValueConfidence(
    val valued: Int,
    val atCost: Int,
    val fromCustomField: Int,
    val unknown: Int,
)

data class Dashboard(
    val scope: String,
    val scopeLabel: String,
    /**
     * assets − liabilities. This is the headline: a figure called "net worth"
     * that quietly ignored a home loan would be worse than no figure at all
     * (docs/01 §6). Assets and liabilities are reported alongside it so the
     * number is never a black box.
     */
    val netWorth: BigDecimal,
    val netWorthFormatted: String,
    val netWorthInWords: String,
    val totalAssets: BigDecimal,
    val totalAssetsFormatted: String,
    val totalLiabilities: BigDecimal,
    val totalLiabilitiesFormatted: String,
    val currency: String,
    val holdingCount: Int,
    val liabilityCount: Int,
    val valueConfidence: ValueConfidence,
    val byCategory: List<Breakdown>,
    val byMember: List<Breakdown>,
    val byInstitution: List<Breakdown>,
    val byLiabilityKind: List<Breakdown>,
    val upcoming: List<UpcomingItem>,
    val attention: List<AttentionItem>,
    val disclaimer: String,
)

@Service
class DashboardService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
) {

    /**
     * Every figure here is computed through the caller's own row-level security,
     * so it already reflects only what they may see. There is no separate
     * "filter the totals" step to forget — a private record contributes nothing,
     * not even its amount, because the rows were never returned (docs/05 §3.3).
     */
    @Transactional(readOnly = true)
    fun build(householdId: UUID, scope: String, memberId: UUID?): Dashboard {
        val household = households.get(householdId)
        val members = households.members(householdId)
        val myMemberIds = members.filter { it.isMe }.map { it.id }.toSet()

        val slices = loadSlices(householdId)
        val debts = loadDebtSlices(householdId)

        // Both sides are filtered by the same scope, so a member lens shows what
        // that person owns AND what they are responsible for. Netting one
        // against a differently-scoped other would produce a number that means
        // nothing.
        val (selected, selectedDebts, scopeLabel) = when (scope) {
            "household" -> Triple(slices, debts, household.name)
            "me" -> Triple(
                slices.filter { it.memberId in myMemberIds },
                debts.filter { it.memberId in myMemberIds },
                "Me",
            )
            "member" -> {
                val target = memberId ?: throw ApiException.badRequest(
                    "member_required", "Choose whose holdings to show.",
                )
                val member = members.firstOrNull { it.id == target }
                    ?: throw ApiException.notFound("We couldn't find that person.")
                Triple(
                    slices.filter { it.memberId == target },
                    debts.filter { it.memberId == target },
                    member.displayName,
                )
            }
            else -> throw ApiException.badRequest(
                "scope_invalid", "Scope must be me, household or member.",
            )
        }

        val total = selected.fold(BigDecimal.ZERO) { acc, s -> acc + s.attributedValue }
        val owed = selectedDebts.fold(BigDecimal.ZERO) { acc, d -> acc + d.attributedOutstanding }
        val netWorth = total - owed
        val distinct = selected.distinctBy { it.investmentId }

        return Dashboard(
            scope = scope,
            scopeLabel = scopeLabel,
            netWorth = netWorth.setScale(2, RoundingMode.HALF_UP),
            netWorthFormatted = IndianNumbers.rupees(netWorth),
            netWorthInWords = IndianNumbers.words(netWorth),
            totalAssets = total.setScale(2, RoundingMode.HALF_UP),
            totalAssetsFormatted = IndianNumbers.rupees(total),
            totalLiabilities = owed.setScale(2, RoundingMode.HALF_UP),
            totalLiabilitiesFormatted = IndianNumbers.rupees(owed),
            currency = household.baseCurrency,
            holdingCount = distinct.size,
            liabilityCount = selectedDebts.distinctBy { it.liabilityId }.size,
            valueConfidence = ValueConfidence(
                valued = distinct.count { it.valueBasis == "valued" },
                atCost = distinct.count { it.valueBasis == "at_cost" },
                fromCustomField = distinct.count { it.valueBasis == "custom_field" },
                unknown = distinct.count { it.valueBasis == "unknown" },
            ),
            byCategory = group(selected, total) { Triple(it.categoryCode, it.categoryLabel, it.color) },
            byMember = group(selected, total) {
                Triple(it.memberId.toString(), it.memberName ?: "Unassigned", null)
            },
            byInstitution = group(selected.filter { it.hasInstitution }, total) {
                Triple(it.institutionName!!, it.institutionName, null)
            },
            byLiabilityKind = groupDebts(selectedDebts, owed),
            upcoming = upcoming(distinct, selectedDebts.distinctBy { it.liabilityId }),
            attention = attention(distinct),
            disclaimer = DISCLAIMER,
        )
    }

    private fun loadSlices(householdId: UUID): List<Slice> = jdbc.query(
        """
        select i.id, i.title, i.maturity_date, i.last_verified_at,
               c.code as category_code, c.label as category_label,
               coalesce(t.color, c.color) as color, t.label as type_label,
               o.member_id, m.display_name as member_name,
               coalesce(inst.name, acct_inst.name) as institution_name,
               ov.effective_value, ov.value_basis, ov.attributed_value,
               i.account_id,
               coalesce(i.last_verified_at, i.created_at) as freshness
        from investments i
        join investment_types t   on t.id = i.type_id
        join asset_categories c   on c.id = t.category_id
        join investment_owner_value ov on ov.investment_id = i.id
        join investment_ownerships o
          on o.investment_id = i.id and o.member_id = ov.member_id
        left join members m       on m.id = o.member_id
        left join institutions inst on inst.id = i.institution_id
        left join accounts acct on acct.id = i.account_id
        left join institutions acct_inst on acct_inst.id = acct.institution_id
        where i.household_id = :hid
          and i.deleted_at is null
          and i.status in ('active','matured')
          -- A matured FD that is still sitting there is real money and counts.
          -- One that was renewed is not: its money is in the record that
          -- replaced it, and counting both would double the household's worth
          -- on the day of a renewal. Under the caller's own RLS, a viewer who
          -- cannot see the renewal still sees exactly one of the two.
          and not exists (select 1 from investments s
                          where s.rolled_from_id = i.id and s.deleted_at is null)
        """.trimIndent(),
        mapOf("hid" to householdId),
    ) { rs, _ ->
        Slice(
            investmentId = rs.getObject("id", UUID::class.java),
            title = rs.getString("title"),
            categoryCode = rs.getString("category_code"),
            categoryLabel = rs.getString("category_label"),
            color = rs.getString("color"),
            typeLabel = rs.getString("type_label"),
            memberId = rs.getObject("member_id", UUID::class.java),
            memberName = rs.getString("member_name"),
            institutionName = rs.getString("institution_name"),
            effectiveValue = rs.getBigDecimal("effective_value"),
            valueBasis = rs.getString("value_basis") ?: "unknown",
            attributedValue = rs.getBigDecimal("attributed_value") ?: BigDecimal.ZERO,
            maturityDate = rs.getDate("maturity_date")?.toLocalDate(),
            lastVerified = rs.getTimestamp("freshness")
                ?.toInstant()?.atZone(java.time.ZoneOffset.UTC)?.toLocalDate(),
            hasInstitution = rs.getString("institution_name") != null,
            hasAccount = rs.getObject("account_id") != null,
            categoryExpectsAccount = rs.getString("category_code") in CATEGORIES_WITH_ACCOUNTS,
        )
    }

    /**
     * Read through the caller's own RLS, exactly like assets. A private debt
     * therefore contributes nothing to anyone else's net worth — not its
     * amount, not its existence — because the rows are never returned.
     */
    private fun loadDebtSlices(householdId: UUID): List<DebtSlice> = jdbc.query(
        """
        select l.id, l.title, l.kind, l.emi_amount, l.emi_day,
               h.member_id, m.display_name as member_name,
               hv.outstanding, hv.attributed_outstanding
        from liabilities l
        join liability_holder_value hv on hv.liability_id = l.id
        join liability_holders h
          on h.liability_id = l.id and h.member_id = hv.member_id
        left join members m on m.id = h.member_id
        where l.household_id = :hid
          and l.deleted_at is null
          and l.status = 'active'
        """.trimIndent(),
        mapOf("hid" to householdId),
    ) { rs, _ ->
        val kind = rs.getString("kind")
        DebtSlice(
            liabilityId = rs.getObject("id", UUID::class.java),
            title = rs.getString("title"),
            kind = kind,
            kindLabel = LIABILITY_LABELS[kind] ?: kind.replace('_', ' ').replaceFirstChar { it.uppercase() },
            memberId = rs.getObject("member_id", UUID::class.java),
            memberName = rs.getString("member_name"),
            outstanding = rs.getBigDecimal("outstanding") ?: BigDecimal.ZERO,
            attributedOutstanding = rs.getBigDecimal("attributed_outstanding") ?: BigDecimal.ZERO,
            emiAmount = rs.getBigDecimal("emi_amount"),
            emiDay = rs.getObject("emi_day")?.let { rs.getInt("emi_day") },
        )
    }

    private fun groupDebts(debts: List<DebtSlice>, total: BigDecimal): List<Breakdown> = debts
        .groupBy { it.kind to it.kindLabel }
        .map { (key, group) ->
            val value = group.fold(BigDecimal.ZERO) { acc, d -> acc + d.attributedOutstanding }
            Breakdown(
                key = key.first,
                label = key.second,
                // Debt uses the caution family throughout, so owe and own never
                // read as the same thing at a glance (docs/02 §2.4).
                color = "var(--caution)",
                value = value.setScale(2, RoundingMode.HALF_UP),
                valueFormatted = IndianNumbers.rupees(value),
                percentage = if (total.signum() == 0) BigDecimal.ZERO
                else value.multiply(BigDecimal(100)).divide(total, 1, RoundingMode.HALF_UP),
                count = group.distinctBy { it.liabilityId }.size,
            )
        }
        .sortedByDescending { it.value }

    private fun group(
        slices: List<Slice>,
        total: BigDecimal,
        key: (Slice) -> Triple<String, String, String?>,
    ): List<Breakdown> = slices
        .groupBy { key(it) }
        .map { (k, group) ->
            val value = group.fold(BigDecimal.ZERO) { acc, s -> acc + s.attributedValue }
            Breakdown(
                key = k.first,
                label = k.second,
                color = k.third,
                value = value.setScale(2, RoundingMode.HALF_UP),
                valueFormatted = IndianNumbers.rupees(value),
                percentage = if (total.signum() == 0) BigDecimal.ZERO
                else value.multiply(BigDecimal(100)).divide(total, 1, RoundingMode.HALF_UP),
                count = group.distinctBy { it.investmentId }.size,
            )
        }
        .sortedByDescending { it.value }

    private fun upcoming(slices: List<Slice>, debts: List<DebtSlice>): List<UpcomingItem> {
        val today = LocalDate.now()
        val horizon = today.plusDays(90)

        val maturities = slices.mapNotNull { s ->
            s.maturityDate
                ?.takeIf { !it.isBefore(today) && !it.isAfter(horizon) }
                ?.let {
                    UpcomingItem(
                        investmentId = s.investmentId, title = s.title, kind = "maturity",
                        date = it, daysAway = ChronoUnit.DAYS.between(today, it),
                        value = s.effectiveValue,
                    )
                }
        }

        // Money going out belongs in the same list as money coming in — that is
        // what makes it a cash-flow view rather than two half-views.
        val emis = debts.mapNotNull { debt ->
            debt.emiDay?.let { day ->
                UpcomingItem(
                    investmentId = debt.liabilityId, title = debt.title, kind = "emi",
                    date = nextOccurrence(day, today), value = debt.emiAmount,
                    daysAway = ChronoUnit.DAYS.between(today, nextOccurrence(day, today)),
                )
            }
        }

        return (maturities + emis).sortedBy { it.date }
    }

    /**
     * The next time a monthly due date falls.
     *
     * A loan due on the 31st still has to be due in February. Clamping to the
     * month's last day is what people's banks actually do, and getting it wrong
     * means a reminder that silently never fires (docs/07 §1).
     */
    private fun nextOccurrence(dayOfMonth: Int, from: LocalDate): LocalDate {
        val thisMonth = from.withDayOfMonth(minOf(dayOfMonth, from.lengthOfMonth()))
        if (!thisMonth.isBefore(from)) return thisMonth
        val next = from.plusMonths(1)
        return next.withDayOfMonth(minOf(dayOfMonth, next.lengthOfMonth()))
    }

    /**
     * The "Attention needed" cards. Each is a fact about the record's
     * completeness, never a judgement about the investment itself — Almira
     * records, it does not advise (docs/08 §6).
     */
    private fun attention(slices: List<Slice>): List<AttentionItem> {
        val staleBefore = LocalDate.now().minusMonths(6)
        val items = mutableListOf<AttentionItem>()

        slices.filter { it.valueBasis == "unknown" }.let {
            if (it.isNotEmpty()) items += AttentionItem(
                "no_value", "No value recorded yet", it.size, it.map { s -> s.investmentId },
            )
        }
        // "Which bank funds which SIP" is the linkage question docs/01 §4 is
        // about, and it only makes sense where an account exists to link to.
        // Asking it of physical gold or a flat would be noise, and noise is how
        // an attention list stops being read (docs/08 §5).
        slices.filter { it.categoryExpectsAccount && !it.hasAccount }.let {
            if (it.isNotEmpty()) items += AttentionItem(
                "no_account", "Not linked to an account",
                it.size, it.map { s -> s.investmentId },
            )
        }
        slices.filter { !it.hasInstitution }.let {
            if (it.isNotEmpty()) items += AttentionItem(
                "no_institution", "No bank or fund house recorded",
                it.size, it.map { s -> s.investmentId },
            )
        }
        // A record entered today is not stale. Treating an unconfirmed-but-new
        // record as overdue would greet every new user with a list of problems
        // they have not had time to have — which is how an attention list
        // teaches people to ignore it (docs/08 §5).
        slices.filter { it.lastVerified != null && it.lastVerified.isBefore(staleBefore) }.let {
            if (it.isNotEmpty()) items += AttentionItem(
                "not_verified", "Not confirmed in over six months",
                it.size, it.map { s -> s.investmentId },
            )
        }
        return items
    }

    private companion object {
        /**
         * Categories where a holding is normally funded from, or held in, an
         * account: a deposit, a fund folio, a demat holding. Gold in a locker
         * and a flat in Kakinada have no account to link, so they are not
         * flagged for lacking one.
         */
        val CATEGORIES_WITH_ACCOUNTS = setOf(
            "deposits", "mutual_funds", "equity", "ipo", "bonds", "retirement",
        )

        val LIABILITY_LABELS = mapOf(
            "home" to "Home loan", "car" to "Car loan", "personal" to "Personal loan",
            "education" to "Education loan", "gold" to "Gold loan",
            "credit_card" to "Credit card", "lap" to "Loan against property",
            "las" to "Loan against securities",
            "loan_against_insurance" to "Loan against insurance",
            "family" to "Family loan", "other" to "Other",
        )

        const val DISCLAIMER =
            "These figures reflect what you've recorded and what you're permitted to see. " +
                "Informational only — not financial advice."
    }
}
