package tech.bhrigu.almira.stilltrue

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tech.bhrigu.almira.support.ApiTestBase
import java.nio.file.Files
import java.nio.file.Path

/**
 * docs/21 §2's period table, read back out of the database.
 *
 * The periods are an argued policy, and the argument lives in the document. A
 * period changed in a migration without the document, or the other way round,
 * would leave the argument describing a product that no longer exists. Same
 * idea as GoLiveDocTest and scripts/check-spec.py: prose that is executed.
 */
@DisplayName("Doc 21's periods are the database's periods")
class StillTrueDocTest : ApiTestBase() {

    private val doc: Path = Path.of("..", "docs", "21-still-true.md")

    private data class Row(val recordType: String, val subtype: String?, val months: Int)

    private fun rows(): List<Row> {
        val text = Files.readString(doc)
        val table = text.substringAfter("<!-- still-true-periods:start -->")
            .substringBefore("<!-- still-true-periods:end -->")
        return table.lines()
            .filter { it.startsWith("| `") }
            .map { line ->
                val cells = line.trim('|').split("|").map { it.trim() }
                Row(
                    recordType = cells[0].trim('`'),
                    // "*(any other kind)*" is the fallthrough, not a subtype.
                    subtype = cells[1].takeIf { it.startsWith("`") }?.trim('`'),
                    months = cells[2].toInt(),
                )
            }
    }

    private fun period(recordType: String, subtype: String): Int =
        db.queryForObject("select app.still_true_period_months(?, ?)", Int::class.javaObjectType, recordType, subtype)!!

    @Test
    fun `every row of the table is what the function returns`() {
        val rows = rows()
        assertThat(rows).describedAs("the period table in docs/21 could not be read").isNotEmpty()
        rows.forEach { row ->
            // A fallthrough row is checked with a subtype nothing will ever be called.
            val subtype = row.subtype ?: "no-such-subtype"
            assertThat(period(row.recordType, subtype))
                .describedAs("docs/21 says ${row.recordType}/${row.subtype ?: "*"} is ${row.months} months")
                .isEqualTo(row.months)
        }
    }

    @Test
    fun `every record type that is asked about has a row, and nothing else does`() {
        assertThat(rows().map { it.recordType }.toSet())
            .containsExactlyInAnyOrderElementsOf(StillTrue.RECORD_TYPES)
    }

    @Test
    fun `a subtype the table does not name gets its type's fallthrough period`() {
        // Every named subtype must really change something, or it is noise in
        // the document that a reader will try to reason about.
        rows().filter { it.subtype != null }.forEach { row ->
            assertThat(period(row.recordType, row.subtype!!))
                .describedAs("${row.recordType}/${row.subtype} is named in docs/21, so it must differ from the fallthrough")
                .isNotEqualTo(period(row.recordType, "no-such-subtype"))
        }
    }
}
