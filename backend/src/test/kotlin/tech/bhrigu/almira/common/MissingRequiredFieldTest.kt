package tech.bhrigu.almira.common

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The name [ApiErrorHandler] puts in details.fields for a missing required
 * field. It is built from the Kotlin declarations only: a map key is text the
 * caller wrote, so it is never carried, and no other kind of failure names a
 * field at all.
 */
@DisplayName("A missing required field is named from our declarations, never from the input")
class MissingRequiredFieldTest {

    data class Leaf(val name: String, val note: String? = null)
    data class Body(
        val title: String,
        val items: List<Leaf> = emptyList(),
        val byKey: Map<String, Leaf> = emptyMap(),
    )

    private val mapper = jacksonObjectMapper()

    private fun nameFor(json: String): String? =
        runCatching { mapper.readValue<Body>(json) }.exceptionOrNull().let(::missingRequiredField)

    @Test
    fun `a top-level field is its name`() {
        assertThat(nameFor("""{"items":[]}""")).isEqualTo("title")
        assertThat(nameFor("""{"title":null}""")).isEqualTo("title")
    }

    @Test
    fun `a field inside a list is its path with the index`() {
        assertThat(nameFor("""{"title":"t","items":[{"name":"a"},{"note":"zq-secret"}]}""")).isEqualTo("items[1].name")
    }

    @Test
    fun `a map key the caller wrote is never carried`() {
        val name = nameFor("""{"title":"t","byKey":{"zq-secret-key":{"note":"x"}}}""")
        assertThat(name).isEqualTo("byKey[*].name")
        assertThat(name).doesNotContain("zq-secret-key")
    }

    @Test
    fun `any other failure names no field`() {
        assertThat(nameFor("""{"title":["zq-secret"]}""")).isNull()
        assertThat(nameFor("""{"title":"zq-secret""")).isNull()
        assertThat(nameFor("""{"title":zq}""")).isNull()
        assertThat(missingRequiredField(null)).isNull()
    }
}
