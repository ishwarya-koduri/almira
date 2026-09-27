package tech.almira.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest

/**
 * The service worker's cache name follows the shell, without anyone bumping it
 * (known-issues 3).
 *
 * The build stamps a fingerprint of every static file but the worker itself onto
 * `VERSION`. This recomputes that fingerprint from the sources and reads the
 * worker that will actually be served — the one on the classpath — so a build
 * change that stopped stamping, or stamped something stale, fails here rather
 * than in a browser that keeps showing yesterday's screen.
 */
@DisplayName("The service worker's version is derived from the shell it caches")
class ServiceWorkerVersionTest {

    private val sources = File("src/main/resources/static")

    private fun fingerprint(root: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        root.walkTopDown()
            .filter { it.isFile && it.relativeTo(root).invariantSeparatorsPath != "sw.js" }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .forEach { f ->
                digest.update(f.relativeTo(root).invariantSeparatorsPath.toByteArray())
                digest.update(0)
                digest.update(f.readBytes())
                digest.update(0)
            }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(12)
    }

    private fun servedVersion(): String {
        val worker = javaClass.classLoader.getResource("static/sw.js")!!.readText()
        return Regex("""^const VERSION = "([^"]+)";$""", RegexOption.MULTILINE)
            .findAll(worker).single().groupValues[1]
    }

    @Test
    fun `the served worker carries the fingerprint of the current shell`() {
        assertThat(servedVersion()).endsWith("+" + fingerprint(sources))
    }

    @Test
    fun `the hand-written part is kept in front of it`() {
        val source = File(sources, "sw.js").readText()
        val written = Regex("""^const VERSION = "([^"]+)";$""", RegexOption.MULTILINE)
            .findAll(source).single().groupValues[1]
        assertThat(servedVersion()).startsWith("$written+")
    }

    @Test
    fun `changing any one asset changes the fingerprint`() {
        val copy = kotlin.io.path.createTempDirectory("shell").toFile()
        try {
            sources.copyRecursively(copy)
            val before = fingerprint(copy)
            File(copy, "app/base.css").appendText("\n/* one more line */\n")
            assertThat(fingerprint(copy)).isNotEqualTo(before)
            File(copy, "sw.js").appendText("\n// the worker's own bytes do not count\n")
            val afterWorker = fingerprint(copy)
            File(copy, "sw.js").writeText(File(sources, "sw.js").readText())
            assertThat(fingerprint(copy)).isEqualTo(afterWorker)
        } finally {
            copy.deleteRecursively()
        }
    }
}
