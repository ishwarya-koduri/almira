package tech.bhrigu.almira.tax

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.common.IndianNumbers
import tech.bhrigu.almira.household.HouseholdService
import tech.bhrigu.almira.investment.InvestmentService
import tech.bhrigu.almira.returns.TaxLotEngine
import tech.bhrigu.almira.security.RequestUserContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** One lot of one sale, classified the way a CA's capital-gains statement lays it out. */
data class GainLine(
    val investmentId: UUID,
    val title: String,
    val isin: String?,
    val assetClass: String,
    val assetClassLabel: String,
    val acquiredOn: LocalDate,
    val transferredOn: LocalDate,
    /** "short" or "long". */
    val term: String,
    val deemedShortTerm: Boolean,
    /** "111A", "112A", "112", "50AA", "Slab", "115BBH". */
    val section: String,
    /** The same rule's number in the Income-tax Act, 2025, where there is one. */
    val sectionNewAct: String?,
    /** "before" or "from" 23 July 2024. */
    val period: String,
    val quantity: BigDecimal,
    val proceeds: BigDecimal,
    val actualCost: BigDecimal,
    val fmvPerUnit2018: BigDecimal?,
    val fmvTotal2018: BigDecimal?,
    val grandfatheredValue: BigDecimal?,
    val fmvMissing: Boolean,
    val costOfAcquisition: BigDecimal,
    val indexedCost: BigDecimal?,
    val gain: BigDecimal,
    val gainFormatted: String,
    val indexedGain: BigDecimal?,
    val indexationApplied: Boolean,
    /** Percent; null means the slab rate. */
    val ratePercent: BigDecimal?,
    val taxableGain: BigDecimal,
    val taxAtRate: BigDecimal?,
    val basis: String,
    val notes: List<String>,
)

data class GainBucket(
    val section: String,
    val sectionNewAct: String?,
    val period: String,
    val periodLabel: String,
    val label: String,
    val ratePercent: BigDecimal?,
    val lines: Int,
    val proceeds: BigDecimal,
    val gain: BigDecimal,
    val taxableGain: BigDecimal,
    val taxableGainFormatted: String,
    val exemptionApplied: BigDecimal,
    val taxAtRate: BigDecimal?,
    val taxAtRateFormatted: String?,
)

/** A holding that wants a 31 January 2018 value, and what is recorded for it. */
data class GrandfatheringEntry(
    val investmentId: UUID,
    val title: String,
    val isin: String?,
    val fmvPerUnit: BigDecimal?,
    val sourceNote: String?,
    val updatedAt: Instant?,
    /** Lots of this holding bought on or before 31 January 2018 that are still held or were sold. */
    val lotsAffected: Int,
)

data class CapitalGainsSchedule(
    val financialYear: String,
    val memberId: UUID?,
    val lines: List<GainLine>,
    val buckets: List<GainBucket>,
    val exemption112A: BigDecimal?,
    val exemption112AFormatted: String?,
    val netGain: BigDecimal,
    val netGainFormatted: String,
    val netGainInWords: String,
    val grandfathering: List<GrandfatheringEntry>,
    /** Things a CA should look at before relying on the figures. */
    val warnings: List<String>,
    /** What was assumed, so none of it is a hidden premise. */
    val assumptions: List<String>,
    val disclaimer: String,
)

data class SetFmv2018(
    val fmvPerUnit: BigDecimal? = null,
    val sourceNote: String? = null,
)

/**
 * The CA-grade capital-gains statement (docs/tax/capital-gains.md).
 *
 * Reads recorded disposals under the caller's own row-level security, and
 * hands each lot to [CapitalGainsRules]. Nothing is stored: the rules are keyed
 * to the transfer date, so working a line out again gives the same answer, and
 * a 31 January 2018 value entered today corrects last year's statement too —
 * which is what the owner means by entering it.
 */
@Service
class CapitalGainsService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
    private val investments: InvestmentService,
    private val audit: AuditService,
    private val userContext: RequestUserContext,
    private val mapper: ObjectMapper,
) {

    private val disclaimer =
        "Worked out from the units you recorded selling, oldest first, using the rules for the " +
            "date of each sale. Informational only, not tax advice. Check it with your CA before filing."

    @Transactional(readOnly = true)
    fun schedule(householdId: UUID, memberId: UUID?, fy: FinancialYear): CapitalGainsSchedule {
        households.get(householdId)
        if (memberId != null && households.members(householdId).none { it.id == memberId }) {
            throw ApiException.notFound("We couldn't find that person.")
        }

        val rows = disposals(householdId, memberId, fy)
        val investmentIds = rows.map { it.investmentId }.distinct()
        val fmv = fmvFor(investmentIds)
        val splits = splitsSince2018(investmentIds)

        val lines = rows.map { row ->
            val holding = CapitalGainsRules.Holding(row.typeCode, row.categoryCode, row.attributes)
            val line = CapitalGainsRules.line(
                holding,
                CapitalGainsRules.Disposal(
                    quantity = row.quantity, proceeds = row.proceeds, cost = row.cost,
                    acquiredOn = row.acquiredOn, transferredOn = row.disposedOn,
                    unmatched = row.cost.signum() == 0 && row.acquiredOn == row.disposedOn,
                ),
                fmvPerUnit2018 = fmv[row.investmentId]?.first,
                splitFactorSince2018 = splitFactorBefore(splits[row.investmentId].orEmpty(), row),
            )
            GainLine(
                investmentId = row.investmentId,
                title = row.title,
                isin = CapitalGainsRules.isin(holding),
                assetClass = line.assetClass.name.lowercase(),
                assetClassLabel = line.assetClass.label,
                acquiredOn = row.acquiredOn,
                transferredOn = row.disposedOn,
                term = line.term,
                deemedShortTerm = line.deemedShortTerm,
                section = line.section.code,
                sectionNewAct = line.section.newAct,
                period = line.period.name.lowercase(),
                quantity = line.quantity,
                proceeds = line.proceeds,
                actualCost = line.actualCost,
                fmvPerUnit2018 = line.fmvPerUnit2018,
                fmvTotal2018 = line.fmvTotal2018,
                grandfatheredValue = line.grandfatheredValue,
                fmvMissing = line.fmvMissing,
                costOfAcquisition = line.costOfAcquisition,
                indexedCost = line.indexedCost,
                gain = line.gain,
                gainFormatted = IndianNumbers.rupees(line.gain),
                indexedGain = line.indexedGain,
                indexationApplied = line.indexationApplied,
                ratePercent = line.ratePercent,
                taxableGain = line.taxableGain,
                taxAtRate = line.taxAtRate,
                basis = line.basis,
                notes = line.notes,
            ) to line
        }

        val buckets = CapitalGainsRules.buckets(lines.map { it.second }, fy).map { bucket ->
            GainBucket(
                section = bucket.section.code,
                sectionNewAct = bucket.section.newAct,
                period = bucket.period.name.lowercase(),
                periodLabel = bucket.period.label,
                label = bucketLabel(bucket),
                ratePercent = bucket.ratePercent,
                lines = bucket.lines,
                proceeds = bucket.proceeds,
                gain = bucket.gain,
                taxableGain = bucket.taxableGain,
                taxableGainFormatted = IndianNumbers.rupees(bucket.taxableGain),
                exemptionApplied = bucket.exemptionApplied,
                taxAtRate = bucket.taxAtRate,
                taxAtRateFormatted = bucket.taxAtRate?.let(IndianNumbers::rupees),
            )
        }

        val gainLines = lines.map { it.first }
        val net = gainLines.fold(BigDecimal.ZERO) { acc, l -> acc + l.gain }
        val exemption = CapitalGainsRules.exemption112A(fy)

        return CapitalGainsSchedule(
            financialYear = fy.label,
            memberId = memberId,
            lines = gainLines,
            buckets = buckets,
            exemption112A = exemption,
            exemption112AFormatted = exemption?.let(IndianNumbers::rupees),
            netGain = net,
            netGainFormatted = IndianNumbers.rupees(net),
            netGainInWords = "Rupees " + IndianNumbers.words(net.setScale(0, RoundingMode.HALF_UP)),
            grandfathering = grandfathering(householdId, memberId, fmv),
            warnings = warnings(gainLines),
            assumptions = ASSUMPTIONS,
            disclaimer = disclaimer,
        )
    }

    private fun bucketLabel(bucket: CapitalGainsRules.Bucket): String {
        val rate = bucket.ratePercent?.let { " at ${CapitalGainsRules.pct(it)}" } ?: " at slab rate"
        val what = when (bucket.section) {
            CapitalGainsRules.Section.S111A -> "Short-term, listed equity (111A)"
            CapitalGainsRules.Section.S112A -> "Long-term, listed equity (112A)"
            CapitalGainsRules.Section.S112 -> "Long-term, other assets (112)"
            CapitalGainsRules.Section.S50AA -> "Deemed short-term (50AA)"
            CapitalGainsRules.Section.SLAB -> "Short-term, other assets"
            CapitalGainsRules.Section.S115BBH -> "Virtual digital assets (115BBH)"
        }
        return "$what$rate"
    }

    private fun warnings(lines: List<GainLine>): List<String> = buildList {
        val missing = lines.filter { it.fmvMissing }
        if (missing.isNotEmpty()) {
            add(
                "${missing.map { it.title }.distinct().joinToString()} ${if (missing.size == 1) "has a lot" else "have lots"} " +
                    "bought on or before 31 January 2018 without that day's value. The gain is overstated until it is added.",
            )
        }
        if (lines.any { it.notes.any { n -> n.startsWith("Sold more units") } }) {
            add("Some sales are larger than the purchases recorded, so part of their cost is nil.")
        }
        if (lines.any { it.assetClass == "other_fund" }) {
            add("Some mutual funds have no equity or debt category set; they are treated as non-equity funds.")
        }
        if (lines.any { it.notes.any { n -> n.startsWith("The Cost Inflation Index") } }) {
            add("An index year is missing from the Cost Inflation Index table, so some lines are not indexed.")
        }
    }

    // --- grandfathering values ------------------------------------------------

    @Transactional
    fun setFmv2018(householdId: UUID, investmentId: UUID, input: SetFmv2018): GrandfatheringEntry {
        val userId = userContext.require()
        households.get(householdId)
        val investment = investments.get(householdId, investmentId)   // 404 if not visible

        val value = input.fmvPerUnit
            ?: throw ApiException.badRequest("fmv_required", "Enter the value per unit on 31 January 2018.")
        if (value.signum() < 0 || value > BigDecimal("100000000")) {
            throw ApiException.badRequest("fmv_invalid", "That value per unit doesn't look right.")
        }
        if (value.scale() > 4) {
            throw ApiException.badRequest("fmv_invalid", "Use at most four decimal places.")
        }
        val note = input.sourceNote?.trim()?.takeIf { it.isNotEmpty() }
        if (note != null && note.length > 200) {
            throw ApiException.badRequest("fmv_note_too_long", "Keep the note under 200 characters.")
        }
        val klass = CapitalGainsRules.assetClass(
            CapitalGainsRules.Holding(investment.typeCode, investment.categoryCode, investment.attributes),
        )
        if (klass !in GRANDFATHERABLE) {
            throw ApiException.badRequest(
                "fmv_not_applicable",
                "The 31 January 2018 value only applies to listed shares, equity funds and REIT or InvIT units.",
            )
        }

        requireModifiable(investmentId)
        val updated = jdbc.update(
            """
            insert into investment_fmv_2018 (investment_id, fmv_per_unit, source_note, recorded_by)
            values (:id, :fmv, :note, :by)
            on conflict (investment_id) do update
              set fmv_per_unit = excluded.fmv_per_unit, source_note = excluded.source_note,
                  recorded_by = excluded.recorded_by, version = investment_fmv_2018.version + 1
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", investmentId).addValue("fmv", value)
                .addValue("note", note).addValue("by", userId),
        )
        if (updated == 0) throw ApiException.notFound()

        audit.record(
            householdId = householdId, actorUserId = userId, action = "tax.fmv_2018.set",
            entityType = "investment", entityId = investmentId,
        )
        return grandfathering(householdId, null, fmvFor(listOf(investmentId)))
            .firstOrNull { it.investmentId == investmentId }
            ?: GrandfatheringEntry(investmentId, investment.title, null, value, note, Instant.now(), 0)
    }

    @Transactional
    fun clearFmv2018(householdId: UUID, investmentId: UUID) {
        val userId = userContext.require()
        households.get(householdId)
        investments.get(householdId, investmentId)
        requireModifiable(investmentId)
        val removed = jdbc.update(
            "delete from investment_fmv_2018 where investment_id = :id",
            mapOf("id" to investmentId),
        )
        if (removed == 0) throw ApiException.notFound()
        audit.record(
            householdId = householdId, actorUserId = userId, action = "tax.fmv_2018.clear",
            entityType = "investment", entityId = investmentId,
        )
    }

    /**
     * Asked of the same function the write policy uses, so a viewer who can see
     * a holding but not change it is told "not found" rather than meeting a
     * policy violation — a 404, never a 403, as everywhere else.
     */
    private fun requireModifiable(investmentId: UUID) {
        val allowed = jdbc.queryForObject(
            "select app.can_modify_investment(:id)", mapOf("id" to investmentId), Boolean::class.java,
        )
        if (allowed != true) throw ApiException.notFound()
    }

    /**
     * Every visible holding with a lot bought on or before 31 January 2018 in a
     * class grandfathering applies to — sold or still held — with its value if
     * one is recorded. That is the list the owner has to work through once.
     */
    private fun grandfathering(
        householdId: UUID,
        memberId: UUID?,
        known: Map<UUID, Pair<BigDecimal, Pair<String?, Instant>>>,
    ): List<GrandfatheringEntry> {
        val rows = jdbc.query(
            """
            select i.id, i.title, i.attributes, t.code as type_code, c.code as category_code,
                   (select count(*) from tax_lots l
                     where l.investment_id = i.id and l.acquired_on <= :cutoff) +
                   (select count(*) from tax_lot_disposals d
                     where d.investment_id = i.id and d.acquired_on <= :cutoff) as lots
            from investments i
            join investment_types t on t.id = i.type_id
            join asset_categories c on c.id = t.category_id
            where i.household_id = :hid and i.deleted_at is null
              and (cast(:memberId as uuid) is null or exists (
                    select 1 from investment_ownerships o
                    where o.investment_id = i.id and o.member_id = cast(:memberId as uuid)))
            order by lower(i.title)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("hid", householdId).addValue("memberId", memberId)
                .addValue("cutoff", CapitalGainsRules.GRANDFATHERING_DATE),
        ) { rs, _ ->
            val holding = CapitalGainsRules.Holding(
                rs.getString("type_code"), rs.getString("category_code"),
                mapper.readValue(rs.getString("attributes")),
            )
            val id = rs.getObject("id", UUID::class.java)
            val lots = rs.getInt("lots")
            if (lots == 0 || CapitalGainsRules.assetClass(holding) !in GRANDFATHERABLE) {
                null
            } else {
                val recorded = known[id]
                GrandfatheringEntry(
                    investmentId = id,
                    title = rs.getString("title"),
                    isin = CapitalGainsRules.isin(holding),
                    fmvPerUnit = recorded?.first,
                    sourceNote = recorded?.second?.first,
                    updatedAt = recorded?.second?.second,
                    lotsAffected = lots,
                )
            }
        }
        val listed = rows.filterNotNull()
        // Values for holdings not yet in `known` (the schedule only loads values for sold holdings).
        val missing = listed.filter { it.fmvPerUnit == null }.map { it.investmentId }
        if (missing.isEmpty()) return listed
        val more = fmvFor(missing)
        return listed.map { entry ->
            more[entry.investmentId]?.let {
                entry.copy(fmvPerUnit = it.first, sourceNote = it.second.first, updatedAt = it.second.second)
            } ?: entry
        }
    }

    // --- reads ----------------------------------------------------------------

    private data class Row(
        val investmentId: UUID,
        val title: String,
        val typeCode: String,
        val categoryCode: String,
        val attributes: Map<String, Any?>,
        val quantity: BigDecimal,
        val proceeds: BigDecimal,
        val cost: BigDecimal,
        val acquiredOn: LocalDate,
        val disposedOn: LocalDate,
        val sellTxnId: UUID,
    )

    private fun disposals(householdId: UUID, memberId: UUID?, fy: FinancialYear): List<Row> = jdbc.query(
        """
        select d.investment_id, i.title, i.attributes, t.code as type_code, c.code as category_code,
               d.quantity, d.proceeds, d.cost_basis, d.acquired_on, d.disposed_on, d.sell_txn_id
        from tax_lot_disposals d
        join investments i on i.id = d.investment_id
        join investment_types t on t.id = i.type_id
        join asset_categories c on c.id = t.category_id
        where i.household_id = :hid and i.deleted_at is null
          and d.disposed_on between :from and :until
          and (cast(:memberId as uuid) is null or exists (
                select 1 from investment_ownerships o
                where o.investment_id = i.id and o.member_id = cast(:memberId as uuid)))
        order by d.disposed_on, lower(i.title), d.acquired_on
        """.trimIndent(),
        MapSqlParameterSource()
            .addValue("hid", householdId).addValue("memberId", memberId)
            .addValue("from", fy.start).addValue("until", fy.end),
    ) { rs, _ ->
        Row(
            investmentId = rs.getObject("investment_id", UUID::class.java),
            title = rs.getString("title"),
            typeCode = rs.getString("type_code"),
            categoryCode = rs.getString("category_code"),
            attributes = mapper.readValue(rs.getString("attributes")),
            quantity = rs.getBigDecimal("quantity"),
            proceeds = rs.getBigDecimal("proceeds"),
            cost = rs.getBigDecimal("cost_basis"),
            acquiredOn = rs.getDate("acquired_on").toLocalDate(),
            disposedOn = rs.getDate("disposed_on").toLocalDate(),
            sellTxnId = rs.getObject("sell_txn_id", UUID::class.java),
        )
    }

    private fun fmvFor(ids: List<UUID>): Map<UUID, Pair<BigDecimal, Pair<String?, Instant>>> {
        if (ids.isEmpty()) return emptyMap()
        return jdbc.query(
            """
            select investment_id, fmv_per_unit, source_note, updated_at
            from investment_fmv_2018 where investment_id in (:ids)
            """.trimIndent(),
            mapOf("ids" to ids),
        ) { rs, _ ->
            rs.getObject("investment_id", UUID::class.java) to (
                rs.getBigDecimal("fmv_per_unit") to
                    (rs.getString("source_note") to rs.getTimestamp("updated_at").toInstant())
                )
        }.toMap()
    }

    private data class Split(val id: UUID, val date: LocalDate, val factor: BigDecimal)

    /** Splits after 31 January 2018, each of which turns one unit into this many. */
    private fun splitsSince2018(ids: List<UUID>): Map<UUID, List<Split>> {
        if (ids.isEmpty()) return emptyMap()
        return jdbc.query(
            """
            select id, investment_id, txn_date, ratio from transactions
            where investment_id in (:ids) and txn_type = 'split' and txn_date > :cutoff
            """.trimIndent(),
            mapOf("ids" to ids, "cutoff" to CapitalGainsRules.GRANDFATHERING_DATE),
        ) { rs, _ ->
            val factor = TaxLotEngine.ratioFactor(rs.getString("ratio"))
            rs.getObject("investment_id", UUID::class.java) to factor?.let {
                Split(rs.getObject("id", UUID::class.java), rs.getDate("txn_date").toLocalDate(), it)
            }
        }.groupBy({ it.first }, { it.second }).mapValues { (_, splits) -> splits.filterNotNull() }
    }

    /**
     * How many units on the sale date one unit of 31 January 2018 had become.
     * A disposal is stored in the units of its sale day, so only splits the
     * lot engine replayed before that sale count — the same date-then-id
     * order TaxLotEngine uses — and a split recorded after it does not.
     */
    private fun splitFactorBefore(splits: List<Split>, row: Row): BigDecimal = splits
        .filter {
            it.date < row.disposedOn ||
                (it.date == row.disposedOn && it.id.toString() < row.sellTxnId.toString())
        }
        .fold(BigDecimal.ONE) { acc, split -> acc.multiply(split.factor) }

    private companion object {
        val GRANDFATHERABLE = setOf(
            CapitalGainsRules.AssetClass.LISTED_EQUITY,
            CapitalGainsRules.AssetClass.EQUITY_FUND,
            CapitalGainsRules.AssetClass.BUSINESS_TRUST,
        )

        val ASSUMPTIONS = listOf(
            "Units are matched to sales oldest first, as Indian rules require for shares and fund units.",
            "Listed shares, equity funds and REIT or InvIT units are assumed to have had STT paid.",
            "Land and buildings bought before 23 July 2024 assume a resident individual or HUF.",
            "Joint holdings are shown in full for each owner; the split between owners is for your CA.",
            "Transfer expenses, set-off of losses, surcharge, cess, the 87A rebate and exemptions " +
                "under sections 54 to 54F are not applied.",
            "Rates are the ones in force on the date of each sale. From 1 April 2026 the same rules sit " +
                "in the Income-tax Act, 2025 (sections 196, 197, 198 and 76).",
        )
    }
}
