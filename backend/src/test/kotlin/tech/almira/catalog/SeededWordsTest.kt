package tech.almira.catalog

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tech.almira.support.ApiTestBase

/**
 * Rule 1 for the words that are not in the codebase.
 *
 * The type someone picks, the help under a field and the transmission playbooks
 * are reference data, seeded by a migration and read on a screen, in an exported
 * CSV and in the printed family handbook. A sweep over the source files says
 * nothing about them, and V149 rewrote what V6, V15 and V19 had written.
 *
 * Both PDFs are drawn with the Standard-14 fonts, which encode WinAnsi, so an em
 * dash in any of this prints as "?" rather than as a dash. That is the second
 * reason this is a test and not a style note.
 *
 * Only rows the migrations own are read (household_id is null). A household may
 * write whatever it likes into a type of its own.
 */
@DisplayName("Seeded reference data")
class SeededWordsTest : ApiTestBase() {

    /** Each seeded table, with the column that says a row is ours rather than a household's. */
    private val reference = mapOf(
        "investment_categories" to "household_id",
        "investment_types" to "household_id",
        "institutions" to "household_id",
        "transmission_playbooks" to null,
    )

    @Test
    fun `no seeded sentence a family reads carries an em dash`() {
        val offenders = reference.flatMap { (table, ownedBy) ->
            val seeded = ownedBy?.let { " and $it is null" } ?: ""
            columnsOf(table).flatMap { column ->
                db.queryForList(
                    "select $column::text from $table where $column::text like ?$seeded",
                    String::class.java, "%—%",
                ).map { "$table.$column: $it" }
            }
        }

        assertThat(offenders)
            .describedAs("seeded text says its pauses with a full stop, a colon or brackets")
            .isEmpty()
    }

    /** Every column that can hold a sentence, asked for rather than listed by hand. */
    private fun columnsOf(table: String) = db.queryForList(
        """
        select column_name from information_schema.columns
         where table_schema = 'public' and table_name = ?
           and data_type in ('text', 'character varying', 'jsonb')
         order by ordinal_position
        """.trimIndent(),
        String::class.java, table,
    )
}
