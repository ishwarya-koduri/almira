package tech.bhrigu.almira.tax

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import tech.bhrigu.almira.tax.CapitalGainsRules.AssetClass
import tech.bhrigu.almira.tax.CapitalGainsRules.Disposal
import tech.bhrigu.almira.tax.CapitalGainsRules.Holding
import tech.bhrigu.almira.tax.CapitalGainsRules.Period
import tech.bhrigu.almira.tax.CapitalGainsRules.Section
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The worked examples in docs/tax/capital-gains.md, as tests. If one of these
 * changes, the document changes in the same commit — a CA reading the document
 * should be able to redo every figure here by hand.
 */
@DisplayName("Indian capital-gains rules, lot by lot")
class CapitalGainsRulesTest {

    private val listed = Holding("stock_listed", "equity", mapOf("isin" to "INE009A01021"))
    private val equityFund = Holding("mf_lumpsum", "mutual_funds", mapOf("scheme_category" to "equity"))
    private val debtFund = Holding("mf_lumpsum", "mutual_funds", mapOf("scheme_category" to "debt"))
    private val flat = Holding("property", "real_estate", emptyMap())
    private val gold = Holding("gold_physical", "gold", emptyMap())
    private val reit = Holding("reit", "real_estate", emptyMap())
    private val crypto = Holding("crypto", "alternatives", emptyMap())

    private fun d(value: String) = LocalDate.parse(value)
    private fun n(value: Long) = BigDecimal(value)

    private fun sale(qty: Long, cost: Long, proceeds: Long, bought: String, sold: String) =
        Disposal(n(qty), n(proceeds), n(cost), d(bought), d(sold))

    @Nested
    @DisplayName("grandfathering, section 55(2)(ac)")
    inner class Grandfathering {

        /** Example 1: the 2018 value is between cost and sale value, so it becomes the cost. */
        @Test
        fun `the 31 January 2018 value replaces a lower cost`() {
            val line = CapitalGainsRules.line(
                listed, sale(100, 50_000, 150_000, "2016-06-10", "2024-09-15"),
                fmvPerUnit2018 = n(1_200),
            )
            assertThat(line.section).isEqualTo(Section.S112A)
            assertThat(line.period).isEqualTo(Period.FROM)
            assertThat(line.fmvTotal2018).isEqualByComparingTo(n(120_000))
            assertThat(line.grandfatheredValue).isEqualByComparingTo(n(120_000))
            assertThat(line.costOfAcquisition).isEqualByComparingTo(n(120_000))
            assertThat(line.gain).isEqualByComparingTo(n(30_000))
            assertThat(line.ratePercent).isEqualByComparingTo(BigDecimal("12.5"))
        }

        /** Example 1b: sold below the 2018 value — grandfathering cannot manufacture a loss. */
        @Test
        fun `a sale below the 2018 value is capped at the sale value, so no loss is invented`() {
            val line = CapitalGainsRules.line(
                listed, sale(100, 50_000, 90_000, "2016-06-10", "2024-09-15"),
                fmvPerUnit2018 = n(1_200),
            )
            assertThat(line.grandfatheredValue).isEqualByComparingTo(n(90_000))
            assertThat(line.costOfAcquisition).isEqualByComparingTo(n(90_000))
            assertThat(line.gain).isEqualByComparingTo(BigDecimal.ZERO)
        }

        /** Example 1c: bought above the 2018 value — the actual cost stays. */
        @Test
        fun `an actual cost above the 2018 value is kept`() {
            val line = CapitalGainsRules.line(
                listed, sale(100, 140_000, 150_000, "2017-11-01", "2024-09-15"),
                fmvPerUnit2018 = n(1_200),
            )
            assertThat(line.costOfAcquisition).isEqualByComparingTo(n(140_000))
            assertThat(line.gain).isEqualByComparingTo(n(10_000))
        }

        /** Example 2: a 2:1 split since 2018 halves the per-unit value. */
        @Test
        fun `a split since 2018 scales the entered value to today's units`() {
            val line = CapitalGainsRules.line(
                listed, sale(200, 50_000, 150_000, "2016-06-10", "2024-09-15"),
                fmvPerUnit2018 = n(1_200), splitFactorSince2018 = BigDecimal(2),
            )
            assertThat(line.fmvPerUnit2018).isEqualByComparingTo(n(600))
            assertThat(line.fmvTotal2018).isEqualByComparingTo(n(120_000))
            assertThat(line.gain).isEqualByComparingTo(n(30_000))
        }

        @Test
        fun `a missing 2018 value uses what was paid and says so`() {
            val line = CapitalGainsRules.line(listed, sale(100, 50_000, 150_000, "2016-06-10", "2024-09-15"))
            assertThat(line.fmvMissing).isTrue()
            assertThat(line.costOfAcquisition).isEqualByComparingTo(n(50_000))
            assertThat(line.notes.joinToString()).contains("31 January 2018")
        }

        @Test
        fun `bought on 1 February 2018 or later is not grandfathered`() {
            val line = CapitalGainsRules.line(
                equityFund, sale(100, 50_000, 150_000, "2018-02-01", "2024-09-15"),
                fmvPerUnit2018 = n(1_200),
            )
            assertThat(line.fmvMissing).isFalse()
            assertThat(line.grandfatheredValue).isNull()
            assertThat(line.gain).isEqualByComparingTo(n(100_000))
        }
    }

    @Nested
    @DisplayName("23 July 2024")
    inner class RegimeChange {

        /** Example 3. */
        @Test
        fun `listed equity rates change on the day, not the financial year`() {
            val before = CapitalGainsRules.line(listed, sale(10, 10_000, 20_000, "2023-05-01", "2024-07-22"))
            val on = CapitalGainsRules.line(listed, sale(10, 10_000, 20_000, "2023-05-01", "2024-07-23"))
            assertThat(before.section to before.ratePercent).isEqualTo(Section.S112A to BigDecimal("10"))
            assertThat(on.section).isEqualTo(Section.S112A)
            assertThat(on.ratePercent).isEqualByComparingTo(BigDecimal("12.5"))

            val shortBefore = CapitalGainsRules.line(listed, sale(10, 10_000, 12_000, "2024-03-01", "2024-07-01"))
            val shortAfter = CapitalGainsRules.line(listed, sale(10, 10_000, 12_000, "2024-03-01", "2024-08-01"))
            assertThat(shortBefore.section).isEqualTo(Section.S111A)
            assertThat(shortBefore.ratePercent).isEqualByComparingTo(BigDecimal("15"))
            assertThat(shortAfter.ratePercent).isEqualByComparingTo(BigDecimal("20"))
        }

        /** Example 5: gold, 20% with indexation before the change. */
        @Test
        fun `gold sold before the change is indexed at 20 percent`() {
            val line = CapitalGainsRules.line(gold, sale(1, 100_000, 180_000, "2019-05-01", "2024-06-01"))
            assertThat(line.term).isEqualTo("long")
            assertThat(line.section).isEqualTo(Section.S112)
            // 1,00,000 × 363 / 289 = 1,25,605.5 → 1,25,606
            assertThat(line.indexedCost).isEqualByComparingTo(n(125_606))
            assertThat(line.indexedGain).isEqualByComparingTo(n(54_394))
            assertThat(line.indexationApplied).isTrue()
            assertThat(line.taxAtRate).isEqualByComparingTo(n(10_879))
        }

        /** Example 5b: the holding period for gold fell from 36 months to 24. */
        @Test
        fun `gold held 25 months is long-term after the change and short-term before it`() {
            val after = CapitalGainsRules.line(gold, sale(1, 100_000, 130_000, "2022-09-01", "2024-10-01"))
            assertThat(after.term).isEqualTo("long")
            assertThat(after.ratePercent).isEqualByComparingTo(BigDecimal("12.5"))
            assertThat(after.indexedCost).isNull()
            assertThat(after.taxAtRate).isEqualByComparingTo(n(3_750))

            val before = CapitalGainsRules.line(gold, sale(1, 100_000, 130_000, "2022-06-01", "2024-07-01"))
            assertThat(before.term).isEqualTo("short")
            assertThat(before.section).isEqualTo(Section.SLAB)
            assertThat(before.ratePercent).isNull()
        }

        /** REIT units needed 36 months before; as listed units they need 12 after. */
        @Test
        fun `REIT units follow the listed-unit rule only from the change`() {
            val before = CapitalGainsRules.line(reit, sale(10, 3_000, 3_600, "2023-01-01", "2024-06-01"))
            assertThat(before.term).isEqualTo("short")
            assertThat(before.section).isEqualTo(Section.S111A)
            val after = CapitalGainsRules.line(reit, sale(10, 3_000, 3_600, "2023-01-01", "2024-08-01"))
            assertThat(after.section).isEqualTo(Section.S112A)
        }
    }

    @Nested
    @DisplayName("land and buildings bought before 23 July 2024")
    inner class PropertyProviso {

        /** Example 4: indexation wins on an old flat. */
        @Test
        fun `the lower of 12_5 percent unindexed and 20 percent indexed is used`() {
            val line = CapitalGainsRules.line(flat, sale(1, 3_000_000, 9_000_000, "2010-05-15", "2025-06-10"))
            // 30,00,000 × 376 / 167 = 67,54,491
            assertThat(line.indexedCost).isEqualByComparingTo(n(6_754_491))
            assertThat(line.indexedGain).isEqualByComparingTo(n(2_245_509))
            assertThat(line.ratePercent).isEqualByComparingTo(BigDecimal("20"))
            assertThat(line.taxAtRate).isEqualByComparingTo(n(449_102))   // vs ₹7,50,000 at 12.5%
            assertThat(line.notes.joinToString()).contains("resident individual or HUF")
        }

        /** Example 4b: a recent flat — indexation turns the gain into a loss, and the tax is nil. */
        @Test
        fun `indexation can take the tax to nil, and the loss is not usable`() {
            val line = CapitalGainsRules.line(flat, sale(1, 8_000_000, 9_000_000, "2020-06-01", "2025-06-10"))
            assertThat(line.indexedGain!!.signum()).isNegative()
            assertThat(line.taxAtRate).isEqualByComparingTo(BigDecimal.ZERO)
            assertThat(line.notes.joinToString()).contains("cannot be set off")
        }

        @Test
        fun `bought after the change there is no choice`() {
            val line = CapitalGainsRules.line(flat, sale(1, 8_000_000, 9_000_000, "2024-08-01", "2026-09-01"))
            assertThat(line.indexedCost).isNull()
            assertThat(line.ratePercent).isEqualByComparingTo(BigDecimal("12.5"))
        }
    }

    @Nested
    @DisplayName("deemed short-term and special rates")
    inner class Special {

        /** Example 6. */
        @Test
        fun `debt fund units bought from April 2023 are short-term however long they are held`() {
            val line = CapitalGainsRules.line(debtFund, sale(100, 100_000, 130_000, "2023-05-01", "2026-06-01"))
            assertThat(line.deemedShortTerm).isTrue()
            assertThat(line.section).isEqualTo(Section.S50AA)
            assertThat(line.ratePercent).isNull()

            val older = CapitalGainsRules.line(debtFund, sale(100, 100_000, 130_000, "2022-01-01", "2024-09-01"))
            assertThat(older.term).isEqualTo("long")
            assertThat(older.ratePercent).isEqualByComparingTo(BigDecimal("12.5"))
        }

        @Test
        fun `unlisted bonds sold after the change are deemed short-term, listed ones get 10 percent before it`() {
            val unlisted = Holding("bond", "bonds", emptyMap())
            assertThat(CapitalGainsRules.line(unlisted, sale(1, 100_000, 110_000, "2020-01-01", "2024-09-01")).section)
                .isEqualTo(Section.S50AA)

            val listedBond = Holding("bond", "bonds", mapOf("isin" to "INE123A07AB1"))
            val line = CapitalGainsRules.line(listedBond, sale(1, 100_000, 110_000, "2023-01-01", "2024-03-01"))
            assertThat(line.assetClass).isEqualTo(AssetClass.LISTED_BOND)
            assertThat(line.term).isEqualTo("long")
            assertThat(line.ratePercent).isEqualByComparingTo(BigDecimal("10"))
            assertThat(line.indexedCost).isNull()
        }

        @Test
        fun `crypto is 30 percent whatever the holding period`() {
            val line = CapitalGainsRules.line(crypto, sale(1, 10_000, 15_000, "2025-01-01", "2025-02-01"))
            assertThat(line.section).isEqualTo(Section.S115BBH)
            assertThat(line.taxAtRate).isEqualByComparingTo(n(1_500))
        }

        @Test
        fun `a sale in tax year 2026-27 names the section of the 2025 Act`() {
            val line = CapitalGainsRules.line(listed, sale(10, 10_000, 20_000, "2024-01-01", "2026-05-01"))
            assertThat(line.notes.joinToString()).contains("section 198 of the Income-tax Act, 2025")
        }
    }

    @Nested
    @DisplayName("the year")
    inner class Year {

        /** Example 7: FY 2024-25, the exemption used against the 12.5% gains first. */
        @Test
        fun `the 112A exemption is 1_25 lakh from FY 2024-25 and goes to the higher rate first`() {
            val fy = FinancialYear(2024)
            val lines = listOf(
                CapitalGainsRules.line(listed, sale(10, 20_000, 100_000, "2020-01-01", "2024-06-01")),
                CapitalGainsRules.line(listed, sale(10, 20_000, 120_000, "2020-01-01", "2024-09-01")),
            )
            val buckets = CapitalGainsRules.buckets(lines, fy).associateBy { it.period }

            assertThat(buckets[Period.FROM]!!.exemptionApplied).isEqualByComparingTo(n(100_000))
            assertThat(buckets[Period.FROM]!!.taxAtRate).isEqualByComparingTo(BigDecimal.ZERO)
            assertThat(buckets[Period.BEFORE]!!.exemptionApplied).isEqualByComparingTo(n(25_000))
            // (80,000 − 25,000) × 10%
            assertThat(buckets[Period.BEFORE]!!.taxAtRate).isEqualByComparingTo(n(5_500))
        }

        @Test
        fun `the exemption was 1 lakh before FY 2024-25 and did not exist before FY 2018-19`() {
            assertThat(CapitalGainsRules.exemption112A(FinancialYear(2023))).isEqualByComparingTo(n(100_000))
            assertThat(CapitalGainsRules.exemption112A(FinancialYear(2017))).isNull()
        }

        @Test
        fun `losses offset gains inside a bucket`() {
            val fy = FinancialYear(2025)
            val lines = listOf(
                CapitalGainsRules.line(listed, sale(10, 10_000, 15_000, "2025-05-01", "2025-06-01")),
                CapitalGainsRules.line(listed, sale(10, 10_000, 8_000, "2025-05-01", "2025-06-01")),
            )
            val bucket = CapitalGainsRules.buckets(lines, fy).single()
            assertThat(bucket.taxableGain).isEqualByComparingTo(n(3_000))
            assertThat(bucket.taxAtRate).isEqualByComparingTo(n(600))
        }
    }

    @Nested
    @DisplayName("the Cost Inflation Index")
    inner class Index {

        @Test
        fun `the table carries every notified year to 2026-27`() {
            assertThat(CostInflationIndex.forYear(FinancialYear(2001))).isEqualTo(100)
            assertThat(CostInflationIndex.forYear(FinancialYear(2017))).isEqualTo(272)
            assertThat(CostInflationIndex.forYear(FinancialYear(2024))).isEqualTo(363)
            assertThat(CostInflationIndex.forYear(FinancialYear(2025))).isEqualTo(376)
            assertThat(CostInflationIndex.forYear(FinancialYear(2026))).isEqualTo(384)
            assertThat(CostInflationIndex.table()).hasSize(26)
        }

        @Test
        fun `a year not yet notified is unknown rather than guessed`() {
            assertThat(CostInflationIndex.forYear(FinancialYear(2027))).isNull()
            // A flat sold in FY 2027-28 would want the comparison, but the index is not out yet.
            val line = CapitalGainsRules.line(flat, sale(1, 1_000_000, 2_000_000, "2015-01-01", "2027-06-01"))
            assertThat(line.indexedCost).isNull()
            assertThat(line.ratePercent).isEqualByComparingTo(BigDecimal("12.5"))
            assertThat(line.notes.joinToString()).contains("isn't in our table yet")
        }

        @Test
        fun `anything bought before April 2001 is indexed from the base year and flagged`() {
            assertThat(CostInflationIndex.forAcquisition(d("1995-06-01"))).isEqualTo(100)
            val line = CapitalGainsRules.line(gold, sale(1, 10_000, 100_000, "1995-06-01", "2024-06-01"))
            assertThat(line.indexedCost).isEqualByComparingTo(n(36_300))
            assertThat(line.notes.joinToString()).contains("1 April 2001")
        }
    }

    @Test
    fun `holding periods follow the transfer date`() {
        assertThat(CapitalGainsRules.holdingMonths(AssetClass.GOLD, d("2024-07-22"))).isEqualTo(36)
        assertThat(CapitalGainsRules.holdingMonths(AssetClass.GOLD, d("2024-07-23"))).isEqualTo(24)
        assertThat(CapitalGainsRules.holdingMonths(AssetClass.PROPERTY, d("2020-01-01"))).isEqualTo(24)
        assertThat(CapitalGainsRules.holdingMonths(AssetClass.LISTED_EQUITY, d("2026-01-01"))).isEqualTo(12)
        assertThat(CapitalGainsRules.assetClass(Holding("mf_sip", "mutual_funds", emptyMap())))
            .isEqualTo(AssetClass.OTHER_FUND)
    }
}
