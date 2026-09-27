package tech.almira.common

import java.time.LocalDate

/**
 * The calendar a family record can actually live on.
 *
 * A date column will take year 1 and year 9999 without complaint, and the API
 * used to pass them straight through: a maturity date of `0001-01-01`, of
 * `9999-12-31`, of `+10000-01-01`, and a date of birth in 1700 were all saved.
 * None of them is a typo anyone can spot later, and they are not inert — a
 * maturity date is what the reminder worker schedules from and what a rollover
 * starts the next term at, so one of these quietly produces a reminder that is
 * either eight thousand years late or eight thousand years early.
 *
 * The server already bounds dates where it noticed: a date of birth may not be
 * in the future, a goal's target date may not fall before 2000, and an end date
 * may not precede its start. This is the same rule, stated once, for the dates
 * that had no bound at all.
 *
 * The window is deliberately generous — an inherited holding really can start
 * in the 1950s, and a child's plan really can mature eighty years out. It is
 * meant to catch what is impossible, not to argue with what is unusual.
 */
object SensibleDates {

    val EARLIEST: LocalDate = LocalDate.of(1900, 1, 1)
    val LATEST: LocalDate = LocalDate.of(2200, 12, 31)

    private val RANGE = "${EARLIEST.year} and ${LATEST.year}"

    /**
     * @param field the request field, so a client can put the message on the
     *   right input rather than at the top of the form.
     * @param label how to name that field to a person, capitalised for the
     *   start of a sentence.
     */
    fun require(value: LocalDate?, field: String, label: String) {
        if (value == null) return
        if (value < EARLIEST || value > LATEST) {
            throw ApiException.badRequest(
                "date_out_of_range",
                "$label needs to be between $RANGE.",
                mapOf("fields" to mapOf(field to "Between $RANGE.")),
            )
        }
    }
}
