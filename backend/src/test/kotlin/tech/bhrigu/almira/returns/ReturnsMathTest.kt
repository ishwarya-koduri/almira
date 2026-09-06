package tech.bhrigu.almira.returns

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import tech.bhrigu.almira.returns.ReturnsMath.CashFlow
import java.math.BigDecimal
import java.time.LocalDate

/**
 * A wrong rate of return looks exactly like a right one. There is no shape to it
 * that a person could recognise as suspicious, which is why these are checked
 * against values worked out independently rather than against whatever the
 * implementation happened to produce.
 */
@DisplayName("Return calculations")
class ReturnsMathTest {

    private fun flow(date: String, amount: Double) = CashFlow(LocalDate.parse(date), amount)

    @Nested
    @DisplayName("XIRR")
    inner class Xirr {

        /** One year, ₹1,00,000 in, ₹1,10,000 out. The answer is 10%. */
        @Test
        fun `a single year of simple growth is exactly what you would work out by hand`() {
            val rate = ReturnsMath.xirr(
                listOf(flow("2023-01-01", -100_000.0), flow("2024-01-01", 110_000.0)),
            )
            assertThat(rate!!.toDouble()).isCloseTo(10.0, within(0.05))
        }

        @Test
        fun `doubling in a year is a hundred percent`() {
            val rate = ReturnsMath.xirr(
                listOf(flow("2023-01-01", -50_000.0), flow("2024-01-01", 100_000.0)),
            )
            assertThat(rate!!.toDouble()).isCloseTo(100.0, within(0.1))
        }

        /** Two years at 20% compounds to 1.44x, so 1,00,000 becomes 1,44,000. */
        @Test
        fun `two years of compounding is annualised, not totalled`() {
            val rate = ReturnsMath.xirr(
                listOf(flow("2022-01-01", -100_000.0), flow("2024-01-01", 144_000.0)),
            )
            assertThat(rate!!.toDouble())
                .describedAs("20% a year, not 44%")
                .isCloseTo(20.0, within(0.2))
        }

        @Test
        fun `a loss gives a negative rate`() {
            val rate = ReturnsMath.xirr(
                listOf(flow("2023-01-01", -100_000.0), flow("2024-01-01", 80_000.0)),
            )
            assertThat(rate!!.toDouble()).isCloseTo(-20.0, within(0.1))
        }

        /**
         * The case the product exists for. Twelve monthly instalments of
         * ₹10,000 — ₹1,20,000 in — worth ₹1,32,000 at the end.
         *
         * The absolute return is 10%, but the money was not invested for a year:
         * the last instalment was in for a month. The annualised rate is
         * therefore much higher, and getting this wrong is exactly how a SIP
         * gets reported as underperforming when it is not.
         */
        @Test
        fun `a monthly SIP annualises correctly, and is not the absolute return`() {
            val flows = (0..11).map {
                flow(LocalDate.parse("2023-01-01").plusMonths(it.toLong()).toString(), -10_000.0)
            } + flow("2024-01-01", 132_000.0)

            val rate = ReturnsMath.xirr(flows)!!.toDouble()
            val absolute = 10.0

            assertThat(rate)
                .describedAs("money in for an average of ~6 months earns more than 10% annualised")
                .isGreaterThan(absolute * 1.5)
            assertThat(rate).isBetween(18.0, 24.0)
        }

        /** Newton diverges on long irregular series; the bisection fallback must not. */
        @Test
        fun `ten years of monthly contributions still converges`() {
            val flows = (0 until 120).map {
                flow(LocalDate.parse("2014-01-01").plusMonths(it.toLong()).toString(), -5_000.0)
            } + flow("2024-01-01", 1_100_000.0)

            val rate = ReturnsMath.xirr(flows)
            assertThat(rate).describedAs("must produce an answer, not give up").isNotNull()
            assertThat(rate!!.toDouble()).isBetween(5.0, 20.0)
        }

        @Test
        fun `a near-total loss is reported rather than refused`() {
            val rate = ReturnsMath.xirr(
                listOf(flow("2023-01-01", -100_000.0), flow("2024-01-01", 1_000.0)),
            )
            assertThat(rate!!.toDouble()).isLessThan(-95.0)
        }

        @Test
        fun `the order flows arrive in makes no difference`() {
            val flows = listOf(
                flow("2023-01-01", -100_000.0),
                flow("2023-07-01", -50_000.0),
                flow("2024-01-01", 170_000.0),
            )
            assertThat(ReturnsMath.xirr(flows)).isEqualByComparingTo(ReturnsMath.xirr(flows.reversed()))
        }

        @Test
        fun `a better outcome always gives a better rate`() {
            val rates = listOf(105_000.0, 110_000.0, 120_000.0).map {
                ReturnsMath.xirr(listOf(flow("2023-01-01", -100_000.0), flow("2024-01-01", it)))!!
                    .toDouble()
            }
            assertThat(rates).isSorted()
        }

        // --- where it must refuse to answer ---------------------------------

        @Test
        fun `money that has only gone out has no rate of return yet`() {
            assertThat(
                ReturnsMath.xirr(
                    listOf(flow("2023-01-01", -10_000.0), flow("2023-02-01", -10_000.0)),
                ),
            ).describedAs("until you say what it is worth, there is nothing to compute").isNull()
        }

        @Test
        fun `a single flow is not a return`() {
            assertThat(ReturnsMath.xirr(listOf(flow("2023-01-01", -10_000.0)))).isNull()
            assertThat(ReturnsMath.xirr(emptyList())).isNull()
        }

        @Test
        fun `an absurd rate is withheld rather than shown`() {
            // A day's holding annualises to a number in the millions of percent.
            // It is arithmetically true and completely useless.
            val rate = ReturnsMath.xirr(
                listOf(flow("2024-01-01", -1_000.0), flow("2024-01-02", 2_000.0)),
            )
            assertThat(rate).isNull()
        }
    }

    @Nested
    @DisplayName("CAGR")
    inner class Cagr {

        @Test
        fun `three years from one lakh to two lakh is about twenty six percent`() {
            val rate = ReturnsMath.cagr(
                BigDecimal(100_000), BigDecimal(200_000),
                LocalDate.parse("2021-01-01"), LocalDate.parse("2024-01-01"),
            )
            assertThat(rate!!.toDouble()).isCloseTo(26.0, within(0.5))
        }

        /**
         * Annualising a few months produces enormous percentages that read as
         * performance. Refusing is the honest answer.
         */
        @Test
        fun `under a year it is refused, not annualised`() {
            assertThat(
                ReturnsMath.cagr(
                    BigDecimal(100_000), BigDecimal(120_000),
                    LocalDate.parse("2024-01-01"), LocalDate.parse("2024-04-01"),
                ),
            ).isNull()
        }

        @Test
        fun `without a start date there is nothing to annualise over`() {
            assertThat(
                ReturnsMath.cagr(BigDecimal(100_000), BigDecimal(200_000), null, LocalDate.now()),
            ).isNull()
        }

        @Test
        fun `a decline gives a negative rate`() {
            val rate = ReturnsMath.cagr(
                BigDecimal(100_000), BigDecimal(80_000),
                LocalDate.parse("2022-01-01"), LocalDate.parse("2024-01-01"),
            )
            assertThat(rate!!.toDouble()).isCloseTo(-10.6, within(0.5))
        }
    }

    @Nested
    @DisplayName("Absolute return")
    inner class Absolute {

        @Test
        fun `is the plain percentage change`() {
            assertThat(
                ReturnsMath.absoluteReturn(BigDecimal(100_000), BigDecimal(143_000))!!.toDouble(),
            ).isCloseTo(43.0, within(0.01))
        }

        @Test
        fun `is withheld when there is nothing to compare against`() {
            assertThat(ReturnsMath.absoluteReturn(null, BigDecimal(100))).isNull()
            assertThat(ReturnsMath.absoluteReturn(BigDecimal(100), null)).isNull()
            assertThat(ReturnsMath.absoluteReturn(BigDecimal.ZERO, BigDecimal(100)))
                .describedAs("a return on nothing is not infinite, it is undefined")
                .isNull()
        }
    }
}
