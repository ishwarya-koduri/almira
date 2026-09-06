package tech.bhrigu.almira.importing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Real exported spreadsheets contain currency symbols, Indian grouping,
 * accounting negatives and four date formats in one column. The rule is to
 * tolerate all of it and guess at none of it — a null gets the person told about
 * the row; a wrong value is one they never find.
 */
@DisplayName("Reading spreadsheet cells")
class CoerceTest {

    @Test
    fun `money survives the ways it is actually written`() {
        mapOf(
            "100000" to "100000",
            "1,00,000" to "100000",
            "₹1,00,000" to "100000",
            "₹ 1,00,000.50" to "100000.50",
            "1.5L" to "150000.0",
            "2 Cr" to "20000000",
            "50k" to "50000",
        ).forEach { (input, expected) ->
            assertThat(Coerce.money(input)).describedAs(input)
                .isEqualByComparingTo(BigDecimal(expected))
        }
    }

    @Test
    fun `accounting negatives in brackets are read as negative`() {
        assertThat(Coerce.money("(5,000)")).isEqualByComparingTo(BigDecimal(-5000))
    }

    @Test
    fun `unreadable money is null, not zero`() {
        // Zero would import silently and quietly understate a net worth.
        assertThat(Coerce.money("see attached")).isNull()
        assertThat(Coerce.money("")).isNull()
        assertThat(Coerce.money("-")).isNull()
    }

    @Test
    fun `dates are read in the formats people export`() {
        listOf(
            "2024-08-03", "03/08/2024", "3/8/2024", "03-08-2024",
            "3 Aug 2024", "03 Aug 2024", "03-Aug-2024", "03.08.2024",
        ).forEach {
            assertThat(Coerce.date(it)).describedAs(it).isEqualTo(LocalDate.parse("2024-08-03"))
        }
    }

    @Test
    fun `an unreadable date is null rather than today`() {
        assertThat(Coerce.date("last August")).isNull()
        assertThat(Coerce.date("31/02/2024"))
            .describedAs("a date that does not exist must not become one that does")
            .isNull()
    }

    @Test
    fun `quantities keep their decimals and lose their units`() {
        assertThat(Coerce.number("6.3 g")).isEqualByComparingTo(BigDecimal("6.3"))
        assertThat(Coerce.number("100 shares")).isEqualByComparingTo(BigDecimal(100))
        assertThat(Coerce.number("n/a")).isNull()
    }
}
