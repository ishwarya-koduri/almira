package tech.bhrigu.almira.tax

import tech.bhrigu.almira.common.ApiException
import java.time.LocalDate

/**
 * The Indian financial year: 1 April to 31 March.
 *
 * Small enough to inline anywhere, which is exactly why it is not. A deduction
 * claimed in "2025" is ambiguous — March 2025 and April 2025 fall in different
 * years — and getting the boundary wrong moves someone's ₹1.5 lakh into the
 * wrong return without anything looking broken.
 */
data class FinancialYear(val startYear: Int) {

    val start: LocalDate get() = LocalDate.of(startYear, 4, 1)
    val end: LocalDate get() = LocalDate.of(startYear + 1, 3, 31)

    /** "2025-26", the form people actually write. */
    val label: String get() = "$startYear-${(startYear + 1) % 100}".let {
        "$startYear-" + "%02d".format((startYear + 1) % 100)
    }

    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)

    fun previous() = FinancialYear(startYear - 1)

    companion object {
        /** The year a date falls in. January to March belong to the year before. */
        fun containing(date: LocalDate): FinancialYear =
            if (date.monthValue >= 4) FinancialYear(date.year) else FinancialYear(date.year - 1)

        fun current(today: LocalDate = LocalDate.now()) = containing(today)

        /** Accepts "2025-26", "2025-2026" or "2025". */
        fun parse(label: String?): FinancialYear {
            if (label.isNullOrBlank()) return current()
            val year = label.trim().substringBefore('-').toIntOrNull()
                ?: throw ApiException.badRequest(
                    "fy_invalid", "Write the financial year like 2025-26.",
                )
            if (year < 1990 || year > 2100) {
                throw ApiException.badRequest("fy_invalid", "That doesn't look like a financial year.")
            }
            return FinancialYear(year)
        }
    }
}
