package tech.bhrigu.almira.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.http.HttpStatus
import tech.bhrigu.almira.support.ApiTestBase
import java.security.MessageDigest

/**
 * The libraries that read a statement and a photo on the device come from this
 * server, exactly as published, and nowhere else (P-11, P-13, app/vendor/SOURCE).
 *
 * The promise is "the password and the photo never leave the device", and a
 * library fetched from a CDN at runtime would quietly tell that CDN who opened
 * which feature, and when — or, altered, read the password itself. So: every
 * vendored file matches its recorded checksum, is served to a visitor with a
 * type a browser will run, and the modules that load them name every path
 * explicitly, because tesseract.js falls back to a CDN for anything left out.
 */
@DisplayName("On-device reading libraries are served from here, as published")
class ClientVendorAssetsTest : ApiTestBase() {

    private val resolver = PathMatchingResourcePatternResolver()

    private fun bytes(location: String) = resolver.getResource(location).inputStream.use { it.readBytes() }
    private fun text(location: String) = String(bytes(location))

    private val sums: Map<String, String> by lazy {
        text("classpath:static/app/vendor/SHA256SUMS").lines().filter { it.isNotBlank() }.associate { line ->
            val (sum, path) = line.split(Regex("\\s+"), limit = 2)
            path.trim() to sum
        }
    }

    @Test
    fun `every vendored file is the one recorded, and nothing unrecorded is shipped`() {
        val shipped = resolver.getResources("classpath:static/app/vendor/**/*")
            .filter { it.isReadable && it.filename != null && it.filename !in setOf("SHA256SUMS", "SOURCE") }
            .map { it.url.toString().substringAfter("static/app/vendor/") }
            .filterNot { it.endsWith("/") }
            .toSet()
        assertThat(shipped).isEqualTo(sums.keys)

        sums.forEach { (path, sum) ->
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes("classpath:static/app/vendor/$path"))
                .joinToString("") { "%02x".format(it) }
            assertThat(actual).`as`(path).isEqualTo(sum)
        }
    }

    @Test
    fun `each library ships with its licence`() {
        listOf("pdfjs-6.3.289/LICENSE.txt", "tesseract-7.0.0/LICENSE.txt", "tesseract-7.0.0/LICENSE-core.txt").forEach {
            assertThat(text("classpath:static/app/vendor/$it")).`as`(it).contains("Apache License")
        }
    }

    @Test
    fun `they are served without signing in, as types a browser will run`() {
        mapOf(
            "pdfjs-6.3.289/pdf.min.mjs" to "javascript",
            "pdfjs-6.3.289/pdf.worker.min.mjs" to "javascript",
            "tesseract-7.0.0/tesseract.esm.min.js" to "javascript",
            "tesseract-7.0.0/worker.min.js" to "javascript",
            "tesseract-7.0.0/tesseract-core-simd-lstm.js" to "javascript",
            // Streaming compilation needs exactly this type; anything else is a slower fallback.
            "tesseract-7.0.0/tesseract-core-simd-lstm.wasm" to "application/wasm",
            "tesseract-7.0.0/lang/eng.traineddata.gz" to "",
        ).forEach { (path, type) ->
            val response = get("/app/vendor/$path")
            assertThat(response.statusCode).`as`(path).isEqualTo(HttpStatus.OK)
            assertThat(response.headers.contentType?.toString() ?: "").`as`(path).contains(type)
        }
    }

    @Test
    fun `the modules that load them give every path, so no default reaches for a CDN`() {
        val ocr = text("classpath:static/app/ocr.js")
        listOf("workerPath:", "corePath:", "langPath:", "workerBlobURL: false").forEach {
            assertThat(ocr).`as`("ocr.js sets $it").contains(it)
        }
        val paths = Regex("""`\$\{BASE}/([^`]+)`""").findAll(ocr).map { "tesseract-7.0.0/" + it.groupValues[1] }.toList()
        assertThat(paths).isNotEmpty
        paths.filterNot { it.endsWith("/lang") }.forEach { assertThat(sums).`as`(it).containsKey(it) }
        assertThat(sums).containsKey("tesseract-7.0.0/lang/eng.traineddata.gz")

        val pdf = text("classpath:static/app/pdf-text.js")
        Regex("""/app/vendor/([^"]+)""").findAll(pdf).map { it.groupValues[1] }.toList().also {
            assertThat(it).hasSize(2)
        }.forEach { assertThat(sums).`as`(it).containsKey(it) }
    }

    @Test
    fun `nothing outside the vendor directory names a script CDN`() {
        val offenders = resolver.getResources("classpath:static/**/*")
            .filter { resource ->
                val url = resource.url.toString()
                resource.isReadable && !url.contains("/static/app/vendor/") &&
                    listOf(".html", ".js", ".mjs", ".css").any { url.endsWith(it) }
            }
            .filter { resource ->
                val body = resource.inputStream.bufferedReader().use { it.readText() }
                listOf("cdn.jsdelivr.net", "unpkg.com", "cdnjs.cloudflare.com").any(body::contains)
            }
            .map { it.description }
        assertThat(offenders).isEmpty()
    }
}
