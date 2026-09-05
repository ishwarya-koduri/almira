package tech.bhrigu.almira.reminder

import java.time.LocalDate

/**
 * Advancing a recurring due date.
 *
 * The whole difficulty is the end of the month. A loan due on the 31st still
 * falls due in February, and a naive `plusMonths` on a date built from day 31
 * silently slides everything to the 28th and never recovers — every subsequent
 * month is then wrong. Anchoring on the intended day and clamping per month is
 * what banks do, and it is the difference between a reminder that fires and one
 * that quietly does not (docs/07 §1).
 */
object DueDates {

    fun advance(from: LocalDate, recurrence: String, anchorDay: Int = from.dayOfMonth): LocalDate =
        when (recurrence) {
            "monthly" -> onDay(from.plusMonths(1), anchorDay)
            "quarterly" -> onDay(from.plusMonths(3), anchorDay)
            "half_yearly" -> onDay(from.plusMonths(6), anchorDay)
            "yearly" -> onDay(from.plusYears(1), anchorDay)
            else -> from
        }

    /** The next time [dayOfMonth] comes round, today included. */
    fun nextMonthly(dayOfMonth: Int, from: LocalDate): LocalDate {
        val thisMonth = onDay(from, dayOfMonth)
        return if (!thisMonth.isBefore(from)) thisMonth else onDay(from.plusMonths(1), dayOfMonth)
    }

    /** Every occurrence of [dayOfMonth] within a window, inclusive. */
    fun monthlyOccurrences(dayOfMonth: Int, from: LocalDate, until: LocalDate): List<LocalDate> {
        val dates = mutableListOf<LocalDate>()
        var cursor = nextMonthly(dayOfMonth, from)
        while (!cursor.isAfter(until)) {
            dates += cursor
            cursor = onDay(cursor.plusMonths(1), dayOfMonth)
        }
        return dates
    }

    /** Clamps to the last day of the month rather than sliding into the next. */
    private fun onDay(date: LocalDate, dayOfMonth: Int): LocalDate =
        date.withDayOfMonth(minOf(dayOfMonth, date.lengthOfMonth()))
}
