package tech.almira.tax

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The long-term listed-equity lines, laid out like Schedule 112A of ITR-2/ITR-3.
 *
 * *Informational, not tax advice.* Shaped by the e-filing portal's own
 * "Instructions for filling Schedule 112A/115AD(1)(b)(iii)(P)" (the CSV help
 * published at static.incometax.gov.in), which fix what each column holds:
 *
 *  - 1a "BE" for a share or unit acquired on or before 31 January 2018, "AE"
 *    after. AE rows are CONSOLIDATED: ISIN "INNOTREQUIRD", name
 *    "CONSOLIDATED", units, prices and FMV left blank.
 *  - 1b "BE" when transferred before 23 July 2024, "AE" on or after (added to
 *    the schedule for AY 2025-26).
 *  - 2 ISIN, or "INNOTAVAILAB" when a BE share has none recorded.
 *  - 3 name, alphanumeric only.
 *  - 6 = 4 × 5, 11 = 4 × 10, 13 = 7 + 12 and 14 = 6 − 13, rounded to the rupee;
 *    7 is the higher of 8 and 9; 9 is the lower of 11 and 6.
 *  - No `, / - _ ( ) & @ \ ' " ; :` anywhere in a value.
 *
 * Column 12, transfer expenses, is zero: brokerage is not tied to a sale in what
 * is recorded, and a guessed figure would be worse than a visible zero.
 *
 * The headers are the schedule's column descriptions, plain. The portal's own
 * template has its own header row, which must not be altered; this file is for
 * a CA to read and to paste rows from, and it says so rather than pretending to
 * be the upload.
 */
object Schedule112ACsv {

    val HEADERS = listOf(
        "1a Share or Unit acquired",
        "1b Share or Unit transferred",
        "2 ISIN Code",
        "3 Name of the Share or Unit",
        "4 No of Shares or Units",
        "5 Sale price per Share or Unit",
        "6 Full Value of Consideration",
        "7 Cost of acquisition without indexation",
        "8 Cost of acquisition",
        "9 If acquired before 01 02 2018 lower of 11 and 6",
        "10 Fair Market Value per share or unit as on 31st January 2018",
        "11 Total Fair Market Value as per section 55 2 ac",
        "12 Expenditure wholly and exclusively in connection with transfer",
        "13 Total deductions",
        "14 Balance",
    )

    fun rows(lines: List<GainLine>): List<List<String>> {
        val eligible = lines.filter { it.section == "112A" }
        val grandfathered = eligible.filter { !it.acquiredOn.isAfter(CapitalGainsRules.GRANDFATHERING_DATE) }
        val later = eligible - grandfathered.toSet()

        val beRows = grandfathered
            .sortedWith(compareBy({ it.period != "before" }, { it.title.lowercase() }, { it.acquiredOn }))
            .map { line ->
                val consideration = rupee(line.proceeds)
                val cost = line.actualCost
                val fmvTotal = line.fmvTotal2018
                val col9 = fmvTotal?.min(consideration)
                val col7 = if (col9 != null) cost.max(col9) else cost
                val col13 = rupee(col7)
                listOf(
                    "BE",
                    transferred(line),
                    line.isin ?: "INNOTAVAILAB",
                    name(line.title),
                    decimal(line.quantity),
                    decimal(line.proceeds.divide(line.quantity, 4, RoundingMode.HALF_UP)),
                    plain(consideration),
                    plain(rupee(col7)),
                    decimal(cost),
                    col9?.let(::plain) ?: "",
                    line.fmvPerUnit2018?.let(::decimal) ?: "",
                    fmvTotal?.let(::plain) ?: "",
                    "0",
                    plain(col13),
                    plain(consideration - col13),
                )
            }

        val aeRows = later.groupBy { it.period }.toSortedMap(compareBy { it != "before" }).map { (_, group) ->
            val consideration = rupee(group.fold(BigDecimal.ZERO) { acc, l -> acc + l.proceeds })
            val cost = group.fold(BigDecimal.ZERO) { acc, l -> acc + l.actualCost }
            val col13 = rupee(cost)
            listOf(
                "AE",
                transferred(group.first()),
                "INNOTREQUIRD",
                "CONSOLIDATED",
                "",
                "",
                plain(consideration),
                plain(rupee(cost)),
                decimal(cost),
                "",
                "",
                "",
                "0",
                plain(col13),
                plain(consideration - col13),
            )
        }

        return beRows + aeRows
    }

    fun csv(lines: List<GainLine>): ByteArray {
        val out = StringBuilder()
        out.append(HEADERS.joinToString(",")).append("\r\n")
        rows(lines).forEach { out.append(it.joinToString(",")).append("\r\n") }
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    private fun transferred(line: GainLine) = if (line.period == "before") "BE" else "AE"

    /** Letters, digits and single spaces; anything else would fail the portal's check. */
    fun name(title: String): String =
        title.map { if (it.isLetterOrDigit() && it.code < 128) it else ' ' }
            .joinToString("").trim().replace(Regex(" +"), " ")
            .ifEmpty { "UNNAMED" }

    private fun rupee(amount: BigDecimal) = amount.setScale(0, RoundingMode.HALF_UP)

    private fun plain(amount: BigDecimal) = amount.setScale(0, RoundingMode.HALF_UP).toPlainString()

    /** Up to four decimals, no trailing zeros, never exponent notation. */
    private fun decimal(amount: BigDecimal) =
        amount.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().let {
            if (it.scale() < 0) it.setScale(0) else it
        }.toPlainString()
}
