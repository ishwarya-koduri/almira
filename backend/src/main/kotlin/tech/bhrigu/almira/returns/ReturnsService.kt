package tech.bhrigu.almira.returns

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.common.IndianNumbers
import tech.bhrigu.almira.household.HouseholdService
import tech.bhrigu.almira.investment.InvestmentService
import tech.bhrigu.almira.security.RequestUserContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

data class Performance(
    val investmentId: UUID?,
    val label: String,
    val invested: BigDecimal?,
    val currentValue: BigDecimal?,
    val currentValueFormatted: String?,
    val realizedGain: BigDecimal?,
    val realizedGainFormatted: String?,
    val unrealizedGain: BigDecimal?,
    val unrealizedGainFormatted: String?,
    /** Percent, or null where the data does not support one. */
    val absoluteReturn: BigDecimal?,
    val cagr: BigDecimal?,
    val xirr: BigDecimal?,
    /**
     * Why a figure is missing, in plain words. Never a bare blank — a blank
     * invites the reader to assume zero.
     */
    val note: String?,
)

/**
 * Returns, computed only where the data supports them (docs/01 §8).
 *
 * The rule this service exists to enforce: **never show a number that was not
 * earned by the data.** A holding with no valuation has no return, and saying
 * "add a valuation to see this" is more useful than a confident 0.00% — which a
 * reader would take as "it went nowhere" rather than "we don't know".
 *
 * No forecasts, no projections, no buy/sell signals. The moment this product
 * recommends anything it invites advisory regulation (docs/08 §5-6).
 */
@Service
class ReturnsService(
    private val repo: TransactionRepository,
    private val jdbc: NamedParameterJdbcTemplate,
    private val investments: InvestmentService,
    private val households: HouseholdService,
    private val audit: AuditService,
    private val userContext: RequestUserContext,
) {
    private val quantityTypes = setOf("buy", "sell", "split", "bonus")
    private val txnTypes = setOf(
        "buy", "sell", "contribution", "withdrawal", "interest", "dividend", "fee", "split", "bonus",
    )

    // --- recording ------------------------------------------------------------

    @Transactional
    fun record(
        householdId: UUID,
        investmentId: UUID,
        txnType: String,
        amount: BigDecimal?,
        quantity: BigDecimal?,
        price: BigDecimal?,
        txnDate: LocalDate,
        fromAccountId: UUID?,
        ratio: String?,
        notes: String?,
    ): List<TransactionRow> {
        val userId = userContext.require()
        households.get(householdId)
        investments.get(householdId, investmentId)   // visibility check

        if (txnType !in txnTypes) {
            throw ApiException.badRequest("txn_type_invalid", "Choose one of: ${txnTypes.joinToString()}.")
        }
        if (txnDate.isAfter(LocalDate.now())) {
            throw ApiException.badRequest("txn_future", "That date is in the future.")
        }
        if (txnType in setOf("buy", "sell") && (quantity == null || quantity.signum() <= 0)) {
            throw ApiException.badRequest(
                "quantity_required",
                "A buy or sell needs a quantity — without it we can't work out what was sold, " +
                    "or what it cost.",
            )
        }
        if (txnType in setOf("split", "bonus") && ratio.isNullOrBlank() && quantity == null) {
            throw ApiException.badRequest(
                "ratio_required", "Give a ratio like 2:1, or the number of extra units.",
            )
        }

        repo.insert(
            id = UUID.randomUUID(), investmentId = investmentId, txnType = txnType,
            amount = amount, quantity = quantity, price = price, txnDate = txnDate,
            fromAccountId = fromAccountId, ratio = ratio, notes = notes, createdBy = userId,
        )
        rebuild(householdId, investmentId)

        audit.record(
            householdId = householdId, actorUserId = userId, action = "transaction.record",
            entityType = "investment", entityId = investmentId, diff = mapOf("type" to txnType),
        )
        return repo.list(investmentId)
    }

    @Transactional
    fun remove(householdId: UUID, investmentId: UUID, transactionId: UUID): List<TransactionRow> {
        val userId = userContext.require()
        households.get(householdId)
        investments.get(householdId, investmentId)

        if (repo.delete(transactionId) == 0) throw ApiException.notFound()
        rebuild(householdId, investmentId)
        audit.record(
            householdId = householdId, actorUserId = userId, action = "transaction.delete",
            entityType = "investment", entityId = investmentId,
        )
        return repo.list(investmentId)
    }

    @Transactional(readOnly = true)
    fun transactions(householdId: UUID, investmentId: UUID): List<TransactionRow> {
        investments.get(householdId, investmentId)
        return repo.list(investmentId)
    }

    @Transactional(readOnly = true)
    fun lots(householdId: UUID, investmentId: UUID): List<LotRow> {
        investments.get(householdId, investmentId)
        return repo.lots(investmentId)
    }

    /**
     * Rebuilds lots and disposals from the movements.
     *
     * Wholesale, every time, rather than incrementally. A back-dated purchase
     * entered late changes which units a past sale consumed, and therefore its
     * gain — incremental maintenance would leave the two disagreeing, and nobody
     * would notice until a tax figure was already filed.
     */
    fun rebuild(householdId: UUID, investmentId: UUID) {
        val investment = investments.get(householdId, investmentId)
        val transactions = repo.forReplay(investmentId).map {
            TaxLotEngine.Txn(
                id = it.id, type = it.txnType, date = it.txnDate,
                quantity = it.quantity, amount = it.amount, price = it.price, ratio = it.ratio,
            )
        }
        val replay = TaxLotEngine.replay(
            transactions = transactions,
            costBasisMethod = investment.costBasisMethod,
            holdingPeriodMonths = HoldingPeriod.monthsFor(investment.categoryCode, investment.attributes),
        )
        repo.replaceLots(investmentId, replay.lots)
        repo.replaceDisposals(investmentId, replay.disposals)
    }

    // --- reporting ------------------------------------------------------------

    @Transactional(readOnly = true)
    fun forInvestment(householdId: UUID, investmentId: UUID): Performance {
        val investment = investments.get(householdId, investmentId)
        val flows = repo.cashFlows(investmentId)
        val disposals = repo.disposals(householdId, null, null).filter { it.investmentId == investmentId }
        val lots = repo.lots(investmentId)

        return performance(
            investmentId = investmentId,
            label = investment.title,
            invested = investedFor(lots, flows, investment.investedAmount),
            costOfHoldings = costOfHoldings(lots),
            currentValue = investment.effectiveValue,
            valueBasis = investment.valueBasis,
            startDate = investment.startDate,
            realized = disposals.fold(BigDecimal.ZERO) { acc, d -> acc + d.gain }
                .takeIf { disposals.isNotEmpty() },
            flows = flows,
        )
    }

    /**
     * What the units still held cost.
     *
     * This, not "total invested", is what an unrealized gain is measured
     * against. Once part of a holding has been sold, comparing today's value
     * against everything ever put in double-counts the sale: the proceeds
     * already left as a realized gain. The earlier version did exactly that and
     * reported a healthy position as a large loss.
     */
    private fun costOfHoldings(lots: List<LotRow>): BigDecimal? =
        lots.takeIf { it.isNotEmpty() }
            ?.fold(BigDecimal.ZERO) { acc, lot ->
                acc + lot.remainingQty.multiply(lot.unitCost)
            }
            ?.setScale(4, RoundingMode.HALF_UP)

    /**
     * What was put in. Transactions win where they exist: `invested_amount` is
     * the quick-capture figure for people who never record movements, and once
     * movements exist they are the record.
     */
    private fun investedFor(
        lots: List<LotRow>,
        flows: List<Pair<LocalDate, BigDecimal>>,
        captured: BigDecimal?,
    ): BigDecimal? {
        if (lots.isEmpty() && flows.isEmpty()) return captured
        val paidIn = flows.filter { it.second.signum() < 0 }
            .fold(BigDecimal.ZERO) { acc, f -> acc + f.second.abs() }
        return paidIn.takeIf { it.signum() > 0 } ?: captured
    }

    /**
     * Portfolio-level performance, sliced by category, member, or the whole
     * household. Each slice is computed from the same primitives, so a category
     * total and the sum of its holdings can never disagree.
     */
    @Transactional(readOnly = true)
    fun portfolio(householdId: UUID, groupBy: String): List<Performance> {
        households.get(householdId)
        if (groupBy !in setOf("total", "category", "member", "investment")) {
            throw ApiException.badRequest(
                "group_by_invalid", "Group by total, category, member or investment.",
            )
        }

        val rows = jdbc.query(
            """
            select i.id, i.title, i.invested_amount, i.start_date,
                   c.code as category_code, c.label as category_label,
                   v.effective_value, v.value_basis,
                   m.id as member_id, m.display_name as member_name,
                   o.share_pct
            from investments i
            join investment_types t on t.id = i.type_id
            join asset_categories c on c.id = t.category_id
            join investment_ownerships o on o.investment_id = i.id
            join members m on m.id = o.member_id
            left join investment_value v on v.investment_id = i.id
            where i.household_id = :hid and i.deleted_at is null
              and i.status in ('active','matured')
            """.trimIndent(),
            mapOf("hid" to householdId),
        ) { rs, _ ->
            Slice(
                investmentId = rs.getObject("id", UUID::class.java),
                title = rs.getString("title"),
                categoryCode = rs.getString("category_code"),
                categoryLabel = rs.getString("category_label"),
                memberId = rs.getObject("member_id", UUID::class.java),
                memberName = rs.getString("member_name"),
                share = rs.getBigDecimal("share_pct"),
                invested = rs.getBigDecimal("invested_amount"),
                currentValue = rs.getBigDecimal("effective_value"),
                valueBasis = rs.getString("value_basis") ?: "unknown",
                startDate = rs.getDate("start_date")?.toLocalDate(),
            )
        }

        val disposals = repo.disposals(householdId, null, null).groupBy { it.investmentId }

        return when (groupBy) {
            "investment" -> rows.distinctBy { it.investmentId }.map { slice ->
                val flows = repo.cashFlows(slice.investmentId)
                val lots = repo.lots(slice.investmentId)
                performance(
                    investmentId = slice.investmentId,
                    label = slice.title,
                    invested = investedFor(lots, flows, slice.invested),
                    costOfHoldings = costOfHoldings(lots),
                    currentValue = slice.currentValue,
                    valueBasis = slice.valueBasis,
                    startDate = slice.startDate,
                    realized = disposals[slice.investmentId]?.fold(BigDecimal.ZERO) { a, d -> a + d.gain },
                    flows = flows,
                )
            }.sortedByDescending { it.currentValue ?: BigDecimal.ZERO }

            "category" -> aggregate(rows, disposals) { it.categoryCode to it.categoryLabel }
            "member" -> aggregate(rows, disposals) { it.memberId.toString() to (it.memberName ?: "—") }
            else -> aggregate(rows, disposals) { "total" to "Everything" }
        }
    }

    private fun aggregate(
        rows: List<Slice>,
        disposals: Map<UUID, List<DisposalRow>>,
        key: (Slice) -> Pair<String, String>,
    ): List<Performance> = rows
        .groupBy { key(it) }
        .map { (label, group) ->
            // Attributed by share, so a joint holding is not counted twice in a
            // household roll-up.
            val invested = group.fold(BigDecimal.ZERO) { acc, s ->
                acc + (s.invested ?: BigDecimal.ZERO).multiply(s.share).divide(HUNDRED, 4, RoundingMode.HALF_UP)
            }
            val value = group.fold(BigDecimal.ZERO) { acc, s ->
                acc + (s.currentValue ?: BigDecimal.ZERO).multiply(s.share).divide(HUNDRED, 4, RoundingMode.HALF_UP)
            }
            val realized = group.distinctBy { it.investmentId }.fold(BigDecimal.ZERO) { acc, s ->
                acc + (disposals[s.investmentId]?.fold(BigDecimal.ZERO) { a, d -> a + d.gain } ?: BigDecimal.ZERO)
            }
            // Flows are per investment, so a group's XIRR needs them merged and
            // attributed by share, exactly like the values.
            val flows = group.flatMap { slice ->
                repo.cashFlows(slice.investmentId).map { (date, amount) ->
                    date to amount.multiply(slice.share).divide(HUNDRED, 4, RoundingMode.HALF_UP)
                }
            }
            val everythingValued = group.all { it.valueBasis != "unknown" }

            val heldCost = group.distinctBy { it.investmentId }.fold(BigDecimal.ZERO) { acc, s ->
                acc + (costOfHoldings(repo.lots(s.investmentId)) ?: BigDecimal.ZERO)
                    .multiply(s.share).divide(HUNDRED, 4, RoundingMode.HALF_UP)
            }

            performance(
                investmentId = null,
                label = label.second,
                invested = invested.takeIf { it.signum() > 0 },
                costOfHoldings = heldCost.takeIf { it.signum() > 0 },
                currentValue = value.takeIf { it.signum() > 0 },
                valueBasis = if (everythingValued) "valued" else "unknown",
                startDate = group.mapNotNull { it.startDate }.minOrNull(),
                realized = realized.takeIf { it.signum() != 0 },
                flows = flows,
            )
        }
        .sortedByDescending { it.currentValue ?: BigDecimal.ZERO }

    private fun performance(
        investmentId: UUID?,
        label: String,
        invested: BigDecimal?,
        costOfHoldings: BigDecimal?,
        currentValue: BigDecimal?,
        valueBasis: String,
        startDate: LocalDate?,
        realized: BigDecimal?,
        flows: List<Pair<LocalDate, BigDecimal>>,
    ): Performance {
        // A holding valued "at cost" has, by definition, a return of exactly
        // zero — which is not a measurement, it is the absence of one.
        val measurable = valueBasis == "valued" && currentValue != null

        // Against what the REMAINING units cost, falling back to the total put
        // in only when nothing has been sold and there is nothing more precise.
        val basis = costOfHoldings ?: invested
        val unrealized = if (measurable && basis != null) currentValue!! - basis else null

        // The final flow is what it is worth today: without it, XIRR sees only
        // money leaving and has nothing to solve for.
        val xirrFlows = flows.map {
            ReturnsMath.CashFlow(it.first, it.second.toDouble())
        } + if (measurable) {
            listOf(ReturnsMath.CashFlow(LocalDate.now(), currentValue!!.toDouble()))
        } else {
            emptyList()
        }

        val note = when {
            currentValue == null -> "Add a value to see how this is doing."
            valueBasis == "at_cost" ->
                "Showing what you paid. Add today's value to see the return."
            valueBasis == "custom_field" ->
                "Using the figure you entered. Add a dated valuation to see the return."
            flows.isEmpty() && measurable ->
                "Add the purchases and sales to see an annualised return."
            else -> null
        }

        return Performance(
            investmentId = investmentId,
            label = label,
            invested = invested,
            currentValue = currentValue,
            currentValueFormatted = currentValue?.let(IndianNumbers::rupees),
            realizedGain = realized,
            realizedGainFormatted = realized?.let(IndianNumbers::rupees),
            unrealizedGain = unrealized,
            unrealizedGainFormatted = unrealized?.let(IndianNumbers::rupees),
            absoluteReturn = if (measurable) ReturnsMath.absoluteReturn(invested, currentValue) else null,
            cagr = if (measurable) ReturnsMath.cagr(invested, currentValue, startDate, LocalDate.now()) else null,
            xirr = if (measurable) ReturnsMath.xirr(xirrFlows) else null,
            note = note,
        )
    }

    private data class Slice(
        val investmentId: UUID,
        val title: String,
        val categoryCode: String,
        val categoryLabel: String,
        val memberId: UUID,
        val memberName: String?,
        val share: BigDecimal,
        val invested: BigDecimal?,
        val currentValue: BigDecimal?,
        val valueBasis: String,
        val startDate: LocalDate?,
    )

    private companion object {
        val HUNDRED: BigDecimal = BigDecimal(100)
    }
}
