package tech.almira.tax

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * The Cost Inflation Index, base year 2001-02 = 100.
 *
 * *Informational, not tax advice* (docs/01 §9). A table, not a formula: the
 * index is whatever the Central Government notifies, and a figure that was
 * "about right" would move somebody's indexed cost by lakhs on a house.
 *
 * Sources, so the next person to add a year can check the last one:
 *
 *  - 2001-02 to 2017-18 — Notification No. 44/2017, S.O. 1790(E), 5 June 2017,
 *    under section 48 of the Income-tax Act, 1961 (the principal notification
 *    that moved the base year to 2001-02). Each later year up to 2025-26 was
 *    added by an amendment to S.O. 1790(E).
 *  - 2024-25 = 363 — Notification No. 44/2024, S.O. 2103(E), 24 May 2024.
 *  - 2025-26 = 376 — Notification No. 70/2025, S.O. 2954(E), 1 July 2025.
 *  - 2026-27 = 384 — Notification No. 85/2026, S.O. 3889(E), 15 July 2026,
 *    under section 72(8)(a) of the Income-tax Act, 2025 ("tax year 2026-27").
 *
 * The published list is kept at incometaxindia.gov.in (Cost Inflation Index).
 * A year missing here is reported as missing rather than guessed — see
 * [forYear], and the warning [CapitalGainsRules] raises when it is null.
 *
 * Where it is still used: since 23 July 2024 indexation is gone for almost
 * every transfer, but it survives for transfers before that date and in the
 * comparison a resident individual or HUF may make for land or a building
 * bought before it (section 112(1)(a), first proviso; section 197 of the 2025
 * Act). So the table keeps growing, and so does this file.
 */
object CostInflationIndex {

    /** Financial-year start year -> index. */
    private val TABLE: Map<Int, Int> = mapOf(
        2001 to 100, 2002 to 105, 2003 to 109, 2004 to 113, 2005 to 117,
        2006 to 122, 2007 to 129, 2008 to 137, 2009 to 148, 2010 to 167,
        2011 to 184, 2012 to 200, 2013 to 220, 2014 to 240, 2015 to 254,
        2016 to 264, 2017 to 272, 2018 to 280, 2019 to 289, 2020 to 301,
        2021 to 317, 2022 to 331, 2023 to 348, 2024 to 363, 2025 to 376,
        2026 to 384,
    )

    const val BASE_YEAR = 2001

    /** The index for a financial year, or null when it has not been notified (or added here). */
    fun forYear(fy: FinancialYear): Int? = TABLE[fy.startYear]

    /**
     * The index for the year an asset was acquired. Anything bought before
     * 1 April 2001 is indexed from the base year — the Act lets the owner use
     * the fair market value on that date as the cost instead (section 55(2)(b)),
     * which this cannot know, so the caller flags it.
     */
    fun forAcquisition(acquiredOn: LocalDate): Int? {
        val fy = FinancialYear.containing(acquiredOn)
        return if (fy.startYear < BASE_YEAR) TABLE[BASE_YEAR] else forYear(fy)
    }

    /**
     * cost × CII(year of transfer) ÷ CII(year of acquisition), or null when
     * either index is unknown. Rounded to the rupee, which is how a return
     * carries it.
     */
    fun indexedCost(cost: BigDecimal, acquiredOn: LocalDate, transferredOn: LocalDate): BigDecimal? {
        val from = forAcquisition(acquiredOn) ?: return null
        val to = forYear(FinancialYear.containing(transferredOn)) ?: return null
        return cost.multiply(BigDecimal(to)).divide(BigDecimal(from), 0, RoundingMode.HALF_UP)
    }

    /** The whole table, for the assumptions page of the CA pack. */
    fun table(): List<Pair<String, Int>> =
        TABLE.toSortedMap().map { (year, index) -> FinancialYear(year).label to index }
}
