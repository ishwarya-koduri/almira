package tech.bhrigu.almira.reminder

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Month ends, leap years, and the 31st. These are where a due date silently
 * drifts, and a drifted reminder is worse than none: it looks like it is working
 * right up until the year someone needed it.
 */
@DisplayName("Recurring due dates")
class DueDatesTest {

    @Test
    fun `the 31st falls due on the last day of shorter months`() {
        assertThat(DueDates.nextMonthly(31, LocalDate.of(2026, 2, 1)))
            .isEqualTo(LocalDate.of(2026, 2, 28))
        assertThat(DueDates.nextMonthly(31, LocalDate.of(2026, 4, 1)))
            .isEqualTo(LocalDate.of(2026, 4, 30))
    }

    @Test
    fun `a leap February keeps the 29th`() {
        assertThat(DueDates.nextMonthly(31, LocalDate.of(2028, 2, 1)))
            .isEqualTo(LocalDate.of(2028, 2, 29))
    }

    /**
     * The drift bug, pinned. Advancing month by month from a date that was
     * clamped must return to the 31st, not stay at 28 for ever.
     */
    @Test
    fun `a clamped date recovers instead of drifting`() {
        var date = LocalDate.of(2026, 1, 31)
        val sequence = (1..5).map { date = DueDates.advance(date, "monthly", 31); date }

        assertThat(sequence).containsExactly(
            LocalDate.of(2026, 2, 28),
            LocalDate.of(2026, 3, 31),
            LocalDate.of(2026, 4, 30),
            LocalDate.of(2026, 5, 31),
            LocalDate.of(2026, 6, 30),
        )
    }

    @Test
    fun `today counts as the next occurrence`() {
        assertThat(DueDates.nextMonthly(5, LocalDate.of(2026, 9, 5)))
            .isEqualTo(LocalDate.of(2026, 9, 5))
    }

    @Test
    fun `a passed day rolls to next month`() {
        assertThat(DueDates.nextMonthly(5, LocalDate.of(2026, 9, 6)))
            .isEqualTo(LocalDate.of(2026, 10, 5))
    }

    @Test
    fun `occurrences across a window include every month, clamped`() {
        assertThat(
            DueDates.monthlyOccurrences(31, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 5, 15)),
        ).containsExactly(
            LocalDate.of(2026, 1, 31),
            LocalDate.of(2026, 2, 28),
            LocalDate.of(2026, 3, 31),
            LocalDate.of(2026, 4, 30),
        )
    }

    @Test
    fun `yearly recurrence handles 29 February`() {
        assertThat(DueDates.advance(LocalDate.of(2028, 2, 29), "yearly", 29))
            .isEqualTo(LocalDate.of(2029, 2, 28))
    }
}
