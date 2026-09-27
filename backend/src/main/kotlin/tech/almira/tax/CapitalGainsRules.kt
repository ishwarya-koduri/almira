package tech.almira.tax

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * How a sale is taxed in India, one lot at a time.
 *
 * *Informational, not tax advice* (docs/01 §9, docs/tax/capital-gains.md). This
 * is the arithmetic a CA does on a capital-gains statement, written down in one
 * place so it can be checked line by line and corrected when the law moves —
 * which it did on 23 July 2024, and will again.
 *
 * Deliberately pure: a disposal and a few facts about the holding in, a
 * classified line out. No database, no clock. Every rule is keyed to the date
 * of the transfer, never to today, so a statement for FY 2023-24 printed in
 * 2027 still applies FY 2023-24's rules.
 *
 * What is encoded, and where it comes from:
 *
 *  - **Holding period** — section 2(42A) of the Income-tax Act, 1961, as amended
 *    by the Finance (No. 2) Act, 2024. Before 23 July 2024: 12 months for listed
 *    securities other than units, equity-oriented fund units and UTI units;
 *    24 months for unlisted shares and land or building; 36 months for
 *    everything else. On or after: 12 months for listed securities (units
 *    included) and equity-oriented fund units; 24 months for everything else.
 *  - **Short-term, listed equity** — section 111A: 15% before 23 July 2024, 20%
 *    on or after.
 *  - **Long-term, listed equity** — section 112A: 10% before, 12.5% on or after,
 *    above an exemption of ₹1,00,000 a year (₹1,25,000 from FY 2024-25).
 *  - **Grandfathering** — section 55(2)(ac): for an equity share, equity fund
 *    unit or business-trust unit acquired on or before 31 January 2018, the
 *    cost is the HIGHER of the actual cost and the LOWER of (fair market value
 *    on 31 January 2018) and (the sale value). Nothing offline knows that
 *    price, so the owner enters it once per holding.
 *  - **Other long-term gains** — section 112: 20% after indexation before
 *    23 July 2024 (10% without indexation for listed bonds, and the lower of the
 *    two for other listed securities); 12.5% without indexation on or after.
 *    Bonds and debentures never got indexation (section 48, third proviso).
 *  - **Land or building bought before 23 July 2024, sold on or after** — a
 *    resident individual or HUF pays the lower of 12.5% without indexation and
 *    20% with it (section 112(1)(a), proviso; section 197 of the 2025 Act).
 *    This assumes the taxpayer is a resident individual or HUF, and says so.
 *  - **Deemed short-term** — section 50AA: units of a specified (debt) mutual
 *    fund acquired on or after 1 April 2023, and unlisted bonds or debentures
 *    transferred on or after 23 July 2024, are short-term whatever the holding
 *    period, taxed at the slab rate.
 *  - **Virtual digital assets** — section 115BBH: 30%, no holding period, no
 *    deduction but cost.
 *
 * From 1 April 2026 the same rules sit in the Income-tax Act, 2025: section 196
 * for 111A, 197 for 112, 198 for 112A and 76 for 50AA. The rates did not change.
 *
 * Not encoded, and flagged instead of guessed: surcharge, cess, the 87A rebate,
 * set-off of losses across heads and years, the basic exemption limit, fair
 * market value on 1 April 2001 for older assets, transfer expenses, and
 * exemptions under sections 54 to 54F.
 */
object CapitalGainsRules {

    /** The Finance (No. 2) Act, 2024 changes apply to transfers on or after this date. */
    val REGIME_CHANGE: LocalDate = LocalDate.of(2024, 7, 23)

    /** Acquired on or before this date: section 55(2)(ac) grandfathering applies. */
    val GRANDFATHERING_DATE: LocalDate = LocalDate.of(2018, 1, 31)

    /** Specified (debt) fund units acquired on or after this date are deemed short-term. */
    val SPECIFIED_FUND_FROM: LocalDate = LocalDate.of(2023, 4, 1)

    /** The Income-tax Act, 2025 applies to transfers on or after this date. */
    val NEW_ACT_FROM: LocalDate = LocalDate.of(2026, 4, 1)

    enum class AssetClass(val label: String) {
        LISTED_EQUITY("Listed shares"),
        EQUITY_FUND("Equity mutual fund"),
        BUSINESS_TRUST("REIT / InvIT units"),
        DEBT_FUND("Debt mutual fund"),
        OTHER_FUND("Mutual fund (category not set, or hybrid)"),
        UNLISTED_SHARES("Unlisted shares"),
        SGB("Sovereign Gold Bond"),
        LISTED_BOND("Listed bond"),
        UNLISTED_BOND("Unlisted bond or debenture"),
        GOLD("Gold and silver"),
        PROPERTY("Land or building"),
        VDA("Virtual digital asset"),
        OTHER("Other capital asset"),
    }

    enum class Section(val code: String, val newAct: String?) {
        S111A("111A", "196"),
        S112A("112A", "198"),
        S112("112", "197"),
        S50AA("50AA", "76"),
        SLAB("Slab", null),
        S115BBH("115BBH", null),
    }

    /** Which side of 23 July 2024 a transfer fell. Schedule 112A calls these BE and AE. */
    enum class Period(val label: String) {
        BEFORE("Before 23 July 2024"),
        FROM("On or after 23 July 2024"),
        ;

        companion object {
            fun of(transferredOn: LocalDate) = if (transferredOn.isBefore(REGIME_CHANGE)) BEFORE else FROM
        }
    }

    /** What the rules need to know about the holding a lot came from. */
    data class Holding(
        val typeCode: String,
        val categoryCode: String,
        val attributes: Map<String, Any?>,
    )

    /** One lot of one sale, as recorded by the lot engine. */
    data class Disposal(
        val quantity: BigDecimal,
        val proceeds: BigDecimal,
        val cost: BigDecimal,
        val acquiredOn: LocalDate,
        val transferredOn: LocalDate,
        /** True when the sale outran the recorded buys and the engine assumed nil cost. */
        val unmatched: Boolean = false,
    )

    data class Line(
        val assetClass: AssetClass,
        val term: String,
        val deemedShortTerm: Boolean,
        val section: Section,
        val period: Period,
        val quantity: BigDecimal,
        val proceeds: BigDecimal,
        /** What the units actually cost (Schedule 112A column 8). */
        val actualCost: BigDecimal,
        /** Per unit, adjusted for splits since; null when not applicable or not entered. */
        val fmvPerUnit2018: BigDecimal?,
        /** Column 11: units × FMV. */
        val fmvTotal2018: BigDecimal?,
        /** Column 9: the lower of column 11 and the sale value. */
        val grandfatheredValue: BigDecimal?,
        /** True when this line needed a 31 January 2018 value and none was entered. */
        val fmvMissing: Boolean,
        /** Column 7: the cost the gain is measured against, before any indexation. */
        val costOfAcquisition: BigDecimal,
        val indexedCost: BigDecimal?,
        val gain: BigDecimal,
        val indexedGain: BigDecimal?,
        /** Whether the rate below applies to the indexed gain. */
        val indexationApplied: Boolean,
        /** The rate, as a percentage. Null means the slab rate. */
        val ratePercent: BigDecimal?,
        /** The gain the rate applies to. */
        val taxableGain: BigDecimal,
        /** Rate × taxable gain, before set-off, exemption, surcharge, cess or rebate. Null for slab. */
        val taxAtRate: BigDecimal?,
        /** How the figure was reached, in a sentence a CA can check. */
        val basis: String,
        val notes: List<String>,
    )

    // --- classification -------------------------------------------------------

    fun assetClass(holding: Holding): AssetClass = when (holding.typeCode) {
        "stock_listed", "ipo_application" -> AssetClass.LISTED_EQUITY
        "reit" -> AssetClass.BUSINESS_TRUST
        "stock_unlisted", "esop_rsu", "business_equity" -> AssetClass.UNLISTED_SHARES
        "gold_sgb" -> AssetClass.SGB
        "bond" -> if (isin(holding) != null) AssetClass.LISTED_BOND else AssetClass.UNLISTED_BOND
        "gold_physical", "gold_jewelry", "gold_digital", "silver" -> AssetClass.GOLD
        "property" -> AssetClass.PROPERTY
        "crypto" -> AssetClass.VDA
        "mf_sip", "mf_lumpsum" -> fundClass(holding.attributes)
        else -> when (holding.categoryCode) {
            "mutual_funds" -> fundClass(holding.attributes)
            "equity", "ipo" -> AssetClass.LISTED_EQUITY
            "real_estate" -> AssetClass.PROPERTY
            "gold" -> AssetClass.GOLD
            else -> AssetClass.OTHER
        }
    }

    private fun fundClass(attributes: Map<String, Any?>): AssetClass =
        when (attributes["scheme_category"] as? String) {
            "equity", "elss", "index" -> AssetClass.EQUITY_FUND
            "debt", "liquid" -> AssetClass.DEBT_FUND
            else -> AssetClass.OTHER_FUND
        }

    /** The recorded ISIN, when it has the shape of one. */
    fun isin(holding: Holding): String? =
        (holding.attributes["isin"] as? String)?.trim()?.uppercase()
            ?.takeIf { ISIN.matches(it) }

    private val ISIN = Regex("^IN[A-Z0-9]{10}$")

    /** Months after which a gain is long-term, for a transfer on this date. */
    fun holdingMonths(assetClass: AssetClass, transferredOn: LocalDate): Int =
        if (Period.of(transferredOn) == Period.BEFORE) {
            when (assetClass) {
                AssetClass.LISTED_EQUITY, AssetClass.EQUITY_FUND,
                AssetClass.SGB, AssetClass.LISTED_BOND -> 12
                AssetClass.UNLISTED_SHARES, AssetClass.PROPERTY -> 24
                else -> 36
            }
        } else {
            when (assetClass) {
                AssetClass.LISTED_EQUITY, AssetClass.EQUITY_FUND, AssetClass.BUSINESS_TRUST,
                AssetClass.SGB, AssetClass.LISTED_BOND -> 12
                else -> 24
            }
        }

    /** "Held for more than N months" — measured in months, as the rule is written. */
    fun term(assetClass: AssetClass, acquiredOn: LocalDate, transferredOn: LocalDate): String =
        if (transferredOn.isAfter(acquiredOn.plusMonths(holdingMonths(assetClass, transferredOn).toLong()))) {
            "long"
        } else {
            "short"
        }

    /** The yearly 112A exemption, or null for years before 112A existed. */
    fun exemption112A(fy: FinancialYear): BigDecimal? = when {
        fy.startYear < 2018 -> null
        fy.startYear < 2024 -> BigDecimal(100_000)
        else -> BigDecimal(125_000)
    }

    private fun equityLike(assetClass: AssetClass) = assetClass in setOf(
        AssetClass.LISTED_EQUITY, AssetClass.EQUITY_FUND, AssetClass.BUSINESS_TRUST,
    )

    // --- one line -------------------------------------------------------------

    /**
     * Classifies and measures one lot of one sale.
     *
     * @param fmvPerUnit2018 the owner's figure for 31 January 2018, per unit as
     *   the share stood that day; used only where grandfathering applies.
     * @param splitFactorSince2018 how many of today's units one 2018 unit became
     *   (2 after a 2:1 split). Lots are split-adjusted by the engine, so the
     *   2018 price has to be as well or the comparison is between two scales.
     */
    fun line(
        holding: Holding,
        disposal: Disposal,
        fmvPerUnit2018: BigDecimal? = null,
        splitFactorSince2018: BigDecimal = BigDecimal.ONE,
    ): Line {
        val assetClass = assetClass(holding)
        val transferred = disposal.transferredOn
        val period = Period.of(transferred)
        val notes = mutableListOf<String>()

        val deemedShort = when (assetClass) {
            AssetClass.DEBT_FUND -> !disposal.acquiredOn.isBefore(SPECIFIED_FUND_FROM)
            AssetClass.UNLISTED_BOND -> period == Period.FROM
            else -> false
        }
        val term = if (deemedShort) "short" else term(assetClass, disposal.acquiredOn, transferred)

        val section = when {
            assetClass == AssetClass.VDA -> Section.S115BBH
            deemedShort -> Section.S50AA
            term == "short" && equityLike(assetClass) -> Section.S111A
            term == "short" -> Section.SLAB
            equityLike(assetClass) -> Section.S112A
            else -> Section.S112
        }

        // --- grandfathering (112A only) ---
        val grandfatherable = section == Section.S112A && !disposal.acquiredOn.isAfter(GRANDFATHERING_DATE)
        val adjustedFmv = if (grandfatherable && fmvPerUnit2018 != null) {
            fmvPerUnit2018.divide(splitFactorSince2018, 4, RoundingMode.HALF_UP)
        } else {
            null
        }
        val fmvTotal = adjustedFmv?.multiply(disposal.quantity)?.setScale(0, RoundingMode.HALF_UP)
        val grandfatheredValue = fmvTotal?.min(disposal.proceeds.setScale(0, RoundingMode.HALF_UP))
        val fmvMissing = grandfatherable && fmvPerUnit2018 == null
        val cost = if (grandfatheredValue != null) disposal.cost.max(grandfatheredValue) else disposal.cost
        val gain = disposal.proceeds - cost

        // --- indexation ---
        val indexable = section == Section.S112 && when (assetClass) {
            AssetClass.LISTED_BOND, AssetClass.UNLISTED_BOND -> false
            else -> true
        }
        val prePropertyProviso = section == Section.S112 && period == Period.FROM &&
            assetClass == AssetClass.PROPERTY && disposal.acquiredOn.isBefore(REGIME_CHANGE)
        val wantsIndex = indexable && (period == Period.BEFORE || prePropertyProviso)
        val indexedCost = if (wantsIndex) {
            CostInflationIndex.indexedCost(disposal.cost, disposal.acquiredOn, transferred)
        } else {
            null
        }
        val indexedGain = indexedCost?.let { disposal.proceeds - it }
        if (wantsIndex && indexedCost == null) {
            notes += "The Cost Inflation Index for ${FinancialYear.containing(transferred).label} " +
                "isn't in our table yet, so this is shown without indexation."
        }
        if (wantsIndex && disposal.acquiredOn.isBefore(LocalDate.of(2001, 4, 1))) {
            notes += "Bought before 1 April 2001: the fair market value on that date may be used " +
                "as the cost instead (section 55(2)(b)). This uses what you paid."
        }

        // --- rate ---
        var ratePercent: BigDecimal?
        var taxable = gain
        var indexationApplied = false
        val basis: String

        when (section) {
            Section.S111A -> {
                ratePercent = if (period == Period.BEFORE) P15 else P20
                basis = "Short-term, listed equity with STT paid: ${pct(ratePercent)}."
            }
            Section.S112A -> {
                ratePercent = if (period == Period.BEFORE) P10 else P12_5
                basis = if (grandfatheredValue != null) {
                    "Long-term, listed equity: ${pct(ratePercent)} above the yearly exemption. Cost is " +
                        "the higher of what was paid and the lower of the 31 January 2018 value and the sale value."
                } else {
                    "Long-term, listed equity: ${pct(ratePercent)} above the yearly exemption."
                }
                if (FinancialYear.containing(transferred).startYear < 2018) {
                    ratePercent = BigDecimal.ZERO
                    notes += "Sold before 1 April 2018, when this gain was exempt under section 10(38) if STT was paid."
                }
            }
            Section.S112 -> {
                if (period == Period.BEFORE) {
                    when {
                        assetClass == AssetClass.LISTED_BOND -> {
                            ratePercent = P10
                            basis = "Long-term, listed bond, before 23 July 2024: 10% without indexation."
                        }
                        assetClass == AssetClass.UNLISTED_BOND -> {
                            ratePercent = P20
                            basis = "Long-term, unlisted bond, before 23 July 2024: 20% (bonds get no indexation)."
                        }
                        assetClass == AssetClass.SGB && indexedGain != null -> {
                            // A listed security may choose 10% without indexation instead.
                            val withIndex = taxAt(P20, indexedGain)
                            val without = taxAt(P10, gain)
                            if (withIndex <= without) {
                                ratePercent = P20; taxable = indexedGain; indexationApplied = true
                            } else {
                                ratePercent = P10
                            }
                            basis = "Long-term, listed, before 23 July 2024: the lower of 20% with indexation " +
                                "and 10% without."
                        }
                        indexedGain != null -> {
                            ratePercent = P20; taxable = indexedGain; indexationApplied = true
                            basis = "Long-term, before 23 July 2024: 20% after indexation."
                        }
                        else -> {
                            ratePercent = P20
                            basis = "Long-term, before 23 July 2024: 20%."
                        }
                    }
                } else if (prePropertyProviso && indexedGain != null) {
                    val without = taxAt(P12_5, gain)
                    val withIndex = taxAt(P20, indexedGain)
                    if (withIndex < without) {
                        ratePercent = P20; taxable = indexedGain; indexationApplied = true
                    } else {
                        ratePercent = P12_5
                    }
                    basis = "Land or building bought before 23 July 2024: the lower of 12.5% without " +
                        "indexation and 20% with it (resident individual or HUF)."
                    notes += "Assumes the owner is a resident individual or HUF. A loss worked out with " +
                        "indexation cannot be set off."
                } else {
                    ratePercent = P12_5
                    basis = "Long-term, on or after 23 July 2024: 12.5% without indexation."
                }
            }
            Section.S50AA -> {
                ratePercent = null
                basis = if (assetClass == AssetClass.DEBT_FUND) {
                    "Debt fund units bought on or after 1 April 2023 are short-term however long they " +
                        "were held (section 50AA). Taxed at your slab rate."
                } else {
                    "Unlisted bonds and debentures sold on or after 23 July 2024 are short-term " +
                        "(section 50AA). Taxed at your slab rate."
                }
            }
            Section.SLAB -> {
                ratePercent = null
                basis = "Short-term: added to income and taxed at your slab rate."
            }
            Section.S115BBH -> {
                ratePercent = P30
                basis = "Virtual digital asset: 30% on the gain, whatever the holding period. " +
                    "A loss cannot be set off."
            }
        }

        // --- things a CA should look at ---
        if (fmvMissing) {
            notes += "Bought on or before 31 January 2018. Add that day's value to use it as the cost; " +
                "until then this uses what you paid, which can only overstate the gain."
        }
        if (disposal.unmatched) {
            notes += "Sold more units than we have a record of buying; the cost here is nil."
        }
        when (assetClass) {
            AssetClass.OTHER_FUND -> notes += "The fund's category isn't set to equity or debt, so this " +
                "treats it as a non-equity fund. Set the category to be sure."
            AssetClass.LISTED_BOND -> notes += "Treated as listed because it has an ISIN."
            AssetClass.UNLISTED_BOND -> notes += "Treated as unlisted because no ISIN is recorded."
            AssetClass.SGB -> notes += "Redemption by the original subscriber at maturity can be exempt; " +
                "check how this was sold."
            AssetClass.UNLISTED_SHARES -> if (holding.typeCode == "esop_rsu") {
                notes += "ESOP or RSU: if the company is listed, these are listed shares, and the cost is " +
                    "the value taxed as salary when they were allotted."
            }
            else -> Unit
        }
        if (transferred >= NEW_ACT_FROM && section.newAct != null) {
            notes += "Tax year ${FinancialYear.containing(transferred).label}: section ${section.newAct} " +
                "of the Income-tax Act, 2025."
        }

        val taxAtRate = ratePercent?.let { taxAt(it, taxable) }

        return Line(
            assetClass = assetClass,
            term = term,
            deemedShortTerm = deemedShort,
            section = section,
            period = period,
            quantity = disposal.quantity,
            proceeds = disposal.proceeds,
            actualCost = disposal.cost,
            fmvPerUnit2018 = adjustedFmv,
            fmvTotal2018 = fmvTotal,
            grandfatheredValue = grandfatheredValue,
            fmvMissing = fmvMissing,
            costOfAcquisition = cost,
            indexedCost = indexedCost,
            gain = gain,
            indexedGain = indexedGain,
            indexationApplied = indexationApplied,
            ratePercent = ratePercent,
            taxableGain = taxable,
            taxAtRate = taxAtRate,
            basis = basis,
            notes = notes,
        )
    }

    // --- the year -------------------------------------------------------------

    data class Bucket(
        val section: Section,
        val period: Period,
        val ratePercent: BigDecimal?,
        val lines: Int,
        val proceeds: BigDecimal,
        val gain: BigDecimal,
        /** Gains net of losses in this bucket, at the measure the rate applies to. */
        val taxableGain: BigDecimal,
        /** The part of the 112A exemption used here. Zero elsewhere. */
        val exemptionApplied: BigDecimal,
        val taxAtRate: BigDecimal?,
    )

    /**
     * Groups lines by section, side of 23 July 2024, and rate.
     *
     * Losses offset gains within a bucket and nowhere else — the set-off rules
     * across buckets and years are a CA's decision, not ours. The 112A
     * exemption is used against the 12.5% gains first, then the 10% ones, which
     * is the order that costs least; a CA may allocate it differently.
     */
    fun buckets(lines: List<Line>, fy: FinancialYear): List<Bucket> {
        val grouped = lines.groupBy { Triple(it.section, it.period, it.ratePercent) }
        var exemptionLeft = exemption112A(fy) ?: BigDecimal.ZERO

        val ordered = grouped.entries.sortedWith(
            compareBy({ it.key.first.ordinal }, { if (it.key.second == Period.FROM) 0 else 1 }),
        )

        return ordered.map { (key, group) ->
            val (section, period, rate) = key
            val taxable = group.fold(BigDecimal.ZERO) { acc, l -> acc + l.taxableGain }
            var exemption = BigDecimal.ZERO
            if (section == Section.S112A && taxable.signum() > 0 && exemptionLeft.signum() > 0) {
                exemption = taxable.min(exemptionLeft)
                exemptionLeft -= exemption
            }
            Bucket(
                section = section,
                period = period,
                ratePercent = rate,
                lines = group.size,
                proceeds = group.fold(BigDecimal.ZERO) { acc, l -> acc + l.proceeds },
                gain = group.fold(BigDecimal.ZERO) { acc, l -> acc + l.gain },
                taxableGain = taxable,
                exemptionApplied = exemption,
                taxAtRate = rate?.let { taxAt(it, taxable - exemption) },
            )
        }.sortedWith(compareBy({ it.section.ordinal }, { it.period.ordinal }))
    }

    private fun taxAt(ratePercent: BigDecimal, amount: BigDecimal): BigDecimal =
        amount.max(BigDecimal.ZERO).multiply(ratePercent)
            .divide(BigDecimal(100), 0, RoundingMode.HALF_UP)

    fun pct(rate: BigDecimal): String = rate.stripTrailingZeros().toPlainString() + "%"

    private val P10 = BigDecimal("10")
    private val P12_5 = BigDecimal("12.5")
    private val P15 = BigDecimal("15")
    private val P20 = BigDecimal("20")
    private val P30 = BigDecimal("30")
}
