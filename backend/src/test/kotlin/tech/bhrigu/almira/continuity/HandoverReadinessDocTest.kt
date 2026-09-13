package tech.bhrigu.almira.continuity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tech.bhrigu.almira.support.ApiTestBase
import java.nio.file.Files
import java.nio.file.Path

/**
 * docs/22 §3's applicability tables, read back out of [HandoverChecks].
 *
 * Which check applies to which holding is an argued policy, and the argument
 * lives in the document. Same idea as StillTrueDocTest: prose that is executed.
 * The seeded taxonomy is read from the database, so a type added by a later
 * migration without a row here fails rather than quietly taking a fallback.
 */
@DisplayName("Doc 22's applicability tables are the code's")
class HandoverReadinessDocTest : ApiTestBase() {

    private val doc: Path = Path.of("..", "docs", "22-handover-readiness.md")

    private fun table(marker: String): Map<String, Applies> {
        val text = Files.readString(doc)
        val body = text.substringAfter("<!-- $marker:start -->").substringBefore("<!-- $marker:end -->")
        return body.lines().filter { it.startsWith("| `") }.associate { line ->
            val cells = line.trim('|').split("|").map { it.trim() }
            fun yes(cell: String) = when (cell) {
                "yes" -> true
                "no" -> false
                else -> error("docs/22 $marker: '$cell' is neither yes nor no, in: $line")
            }
            cells[0].trim('`') to Applies(yes(cells[1]), yes(cells[2]), yes(cells[3]))
        }
    }

    @Test
    fun `the type table in the document is the code's, row for row`() {
        val documented = table("handover-applicability")
        assertThat(documented).describedAs("the type table in docs/22 could not be read").isNotEmpty()
        assertThat(documented).isEqualTo(HandoverChecks.TYPES)
    }

    @Test
    fun `the category table in the document is the code's, row for row`() {
        val documented = table("handover-category-defaults")
        assertThat(documented).describedAs("the category table in docs/22 could not be read").isNotEmpty()
        assertThat(documented).isEqualTo(HandoverChecks.CATEGORIES)
    }

    @Test
    fun `every seeded type and category has a row`() {
        val types = db.queryForList(
            "select code from investment_types where household_id is null", String::class.java,
        )
        val categories = db.queryForList("select code from asset_categories", String::class.java)
        assertThat(HandoverChecks.TYPES.keys).containsAll(types)
        assertThat(HandoverChecks.CATEGORIES.keys).containsAll(categories)
    }
}
