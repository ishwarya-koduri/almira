package tech.bhrigu.almira.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.http.HttpStatus
import tech.bhrigu.almira.support.ApiTestBase

/**
 * The web client's fonts come from this server and nowhere else (P-36, docs/25 §3).
 *
 * A privacy-first app that loads its type from a font CDN tells that CDN who
 * opened it, and when. So: every file tokens.css declares is served, to a
 * visitor who is not signed in (the sign-in screen needs them too), as a font;
 * and nothing the client ships names a font host.
 */
class WebClientFontsTest : ApiTestBase() {

    private val resolver = PathMatchingResourcePatternResolver()

    private fun text(location: String) =
        resolver.getResource(location).inputStream.bufferedReader().use { it.readText() }

    @Test
    fun `every font tokens css declares is served without signing in, as woff2`() {
        val declared = Regex("""url\("(fonts/[^"]+\.woff2)"\)""")
            .findAll(text("classpath:static/app/tokens.css"))
            .map { it.groupValues[1] }
            .toSet()

        // Fraunces and Inter in two Latin subsets, four Noto faces in their scripts.
        assertThat(declared).hasSize(8)
        declared.forEach { file ->
            val response = get("/app/$file")
            assertThat(response.statusCode).`as`(file).isEqualTo(HttpStatus.OK)
            assertThat(response.headers.contentType?.toString()).`as`(file).isEqualTo("font/woff2")
        }
    }

    @Test
    fun `each font family ships with its licence`() {
        val names = resolver.getResources("classpath:static/app/fonts/*").mapNotNull { it.filename }
        assertThat(names).contains(
            "OFL-Fraunces.txt", "OFL-Inter.txt", "OFL-Noto-Telugu.txt", "OFL-Noto-Devanagari.txt",
        )
        names.filter { it.endsWith(".txt") }.forEach { licence ->
            assertThat(text("classpath:static/app/fonts/$licence")).contains("SIL Open Font License")
        }
    }

    @Test
    fun `nothing the web client ships asks a font host for anything`() {
        val files = resolver.getResources("classpath:static/**/*").filter { resource ->
            val name = resource.filename ?: return@filter false
            listOf(".html", ".css", ".js", ".webmanifest").any(name::endsWith)
        }
        assertThat(files).isNotEmpty

        val offenders = files.filter { resource ->
            val body = resource.inputStream.bufferedReader().use { it.readText() }
            body.contains("fonts.googleapis.com") || body.contains("fonts.gstatic.com")
        }.map { it.description }
        assertThat(offenders).isEmpty()
    }
}
