package tech.almira.privacy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * No analytics, no tracking — so none that could touch a child's records
 * (DPDP Act s.9(3); docs/05 §6).
 *
 * The Act forbids tracking, behavioural monitoring and targeted advertising
 * directed at children. The simplest way to be sure no analytics event ever
 * carries a minor's record is for there to be no analytics at all, and this is
 * what keeps it that way: the web client, the server and the shared native code
 * are scanned for the usual SDKs and the browser APIs they are built on, and the
 * page loads no script from anywhere but itself.
 *
 * A future product-analytics wish is not forbidden by this test; it is made
 * deliberate. Whoever adds one has to change this file, and docs/05 §6 says
 * what it must never see.
 */
@DisplayName("No analytics or tracking anywhere")
class NoTrackingTest {

    private val roots = listOf(
        Path.of("src", "main", "resources", "static"),
        Path.of("src", "main", "kotlin"),
        Path.of("..", "app", "shared", "src"),
    )

    private val markers = listOf(
        "google-analytics", "googletagmanager", "gtag(", "firebase-analytics", "firebaseanalytics",
        "mixpanel", "amplitude", "segment.io", "segment.com", "posthog", "plausible.io", "matomo",
        "hotjar", "fullstory", "clarity.ms", "appsflyer", "branch.io", "facebook.net", "fbq(",
        "navigator.sendbeacon", "clevertap", "moengage", "webengage",
    )

    private fun sources(): List<Path> = roots.filter { Files.exists(it) }.flatMap { root ->
        Files.walk(root).use { stream ->
            stream.filter { it.isRegularFile() && it.extension in setOf("js", "html", "kt", "kts", "swift", "json") }
                .toList()
        }
    }

    @Test
    fun `no analytics SDK or beacon is in any client or the server`() {
        val files = sources()
        assertThat(files).describedAs("the scan found the sources it is meant to read").isNotEmpty()

        val hits = files.flatMap { file ->
            val text = file.readText().lowercase()
            markers.filter { it in text }.map { "${file.invariantSeparatorsPathString}: $it" }
        }.filterNot { it.contains("NoTrackingTest") }

        assertThat(hits).describedAs("tracking would reach children's records too").isEmpty()
    }

    @Test
    fun `the web page loads scripts only from itself`() {
        val index = Path.of("src", "main", "resources", "static", "index.html").readText()
        val external = Regex("""<script[^>]+src\s*=\s*["'](?:https?:)?//""", RegexOption.IGNORE_CASE)
        assertThat(external.containsMatchIn(index)).isFalse()
    }
}
