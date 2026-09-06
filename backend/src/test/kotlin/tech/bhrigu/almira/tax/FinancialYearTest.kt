package tech.bhrigu.almira.tax

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The boundary is 1 April, and every case here is a date a naive calendar-year
 * assumption would file in the wrong return.
 */
@DisplayName("The Indian financial year")
class FinancialYearTest {

    @Test
    fun `runs from the first of April to the thirty-first of March`() {
        val fy = FinancialYear(2025)
        assertThat(fy.start).isEqualTo(LocalDate.parse("2025-04-01"))
        assertThat(fy.end).isEqualTo(LocalDate.parse("2026-03-31"))
        assertThat(fy.label).isEqualTo("2025-26")
    }

    @Test
    fun `January to March belong to the year before`() {
        assertThat(FinancialYear.containing(LocalDate.parse("2026-03-31")).label).isEqualTo("2025-26")
        assertThat(FinancialYear.containing(LocalDate.parse("2026-04-01")).label).isEqualTo("2026-27")
    }

    @Test
    fun `the boundary is exact on both sides`() {
        val fy = FinancialYear(2025)
        assertThat(LocalDate.parse("2025-03-31") in fy).isFalse()
        assertThat(LocalDate.parse("2025-04-01") in fy).isTrue()
        assertThat(LocalDate.parse("2026-03-31") in fy).isTrue()
        assertThat(LocalDate.parse("2026-04-01") in fy).isFalse()
    }

    @Test
    fun `a label rolling into a new century still reads correctly`() {
        assertThat(FinancialYear(2099).label).isEqualTo("2099-00")
        assertThat(FinancialYear(2009).label).isEqualTo("2009-10")
    }

    @Test
    fun `every way people write it is accepted`() {
        listOf("2025-26", "2025-2026", "2025", " 2025-26 ").forEach {
            assertThat(FinancialYear.parse(it).startYear).describedAs(it).isEqualTo(2025)
        }
    }

    @Test
    fun `nothing given means the year we are in`() {
        assertThat(FinancialYear.parse(null)).isEqualTo(FinancialYear.current())
        assertThat(FinancialYear.parse("")).isEqualTo(FinancialYear.current())
    }

    @Test
    fun `nonsense is refused with a message that shows the format`() {
        assertThatThrownBy { FinancialYear.parse("last year") }.hasMessageContaining("2025-26")
        assertThatThrownBy { FinancialYear.parse("1200-01") }.hasMessageContaining("financial year")
    }
}
