package tech.bhrigu.almira.provider

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.mock.env.MockEnvironment
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * GO-LIVE.md makes claims about the code, and prose is not executed.
 *
 * The claims worth holding it to are the ones that would mislead somebody if
 * they quietly became false:
 *
 *  - that no live adapter exists. The day one is written, the page for that
 *    provider still says "not watched failing" and lists gaps that may be
 *    closed. This test fails at that moment and says where to look;
 *  - that the interface signatures it quotes are the real ones. A contract
 *    someone implements from must not drift from the interface they compile
 *    against;
 *  - that every provider the configuration knows has a row, and every blocked
 *    one has its page and its "not watched failing" section.
 *
 * It does not check that anything in those pages is *true of the providers* —
 * nothing can, until the accounts exist.
 */
@DisplayName("GO-LIVE.md: the checklist cannot quietly drift from the code")
class GoLiveDocTest {

    private val root: Path = Path.of("..")
    private val goLive: Path = root.resolve("GO-LIVE.md")

    private val blocked = listOf(
        "docs/providers/digilocker.md",
        "docs/providers/account-aggregator.md",
        "docs/providers/push.md",
        "docs/providers/sms.md",
    )

    @Test
    fun `every provider the configuration knows has a row`() {
        val text = goLive.readText()
        ProviderModeCheck.providerNames().forEach { name ->
            assertThat(text)
                .describedAs("GO-LIVE.md has no table row for provider '$name'")
                .contains("| `$name` |")
        }
    }

    @Test
    fun `every provider that cannot be built has a page, linked, that says it is not watched failing`() {
        val text = goLive.readText()
        blocked.forEach { page ->
            assertThat(text)
                .describedAs("GO-LIVE.md does not link $page")
                .contains("($page)")
            val body = root.resolve(page).readText()
            assertThat(body.lowercase())
                .describedAs("$page must state its status as not watched failing")
                .contains("not watched failing")
            assertThat(body)
                .describedAs("$page must have a section saying what is not verified")
                .contains("## (d) Not verified")
        }
    }

    /**
     * Every `fun`, `val`, `interface` and `data class` line inside a ```kotlin
     * block of a provider page must exist, character for character once
     * trimmed, somewhere in the main source. Proposals are written as prose or
     * tables, never as a kotlin block, precisely so this can be strict.
     */
    @Test
    fun `the signatures the pages quote are the ones in the code`() {
        val source = Files.walk(root.resolve("backend/src/main/kotlin")).use { paths ->
            paths.filter { it.extension == "kt" }.toList()
                .flatMap { it.readText().lines() }
                .map(String::trim)
                .toSet()
        }
        val quoted = blocked.flatMap { page ->
            KOTLIN_BLOCK.findAll(root.resolve(page).readText())
                .flatMap { it.groupValues[1].lines() }
                .map(String::trim)
                .filter { line -> SIGNATURE_STARTS.any { line.startsWith(it) } }
                .map { page to it }
                .toList()
        }
        assertThat(quoted).describedAs("the pages quote no signatures at all — the regex is wrong").isNotEmpty()
        val missing = quoted.filter { (_, line) -> line !in source }
        assertThat(missing)
            .describedAs(
                "these lines are quoted as the interface contract but no longer exist in the code. " +
                    "Update the page to the real signature — it is what a live adapter is written against",
            )
            .isEmpty()
    }

    /**
     * The whole of GO-LIVE.md rests on this. When a live adapter is added to
     * ProviderModeCheck.implemented, this fails for that provider: update its
     * row in GO-LIVE.md and its page's "not watched failing" section in the
     * same change, then change this test's expectation for that provider.
     */
    @Test
    fun `no provider has a live adapter, as GO-LIVE says`() {
        assertThat(goLive.readText()).contains("**Every provider is a fake today.**")
        ProviderModeCheck.providerNames().forEach { name ->
            val environment = MockEnvironment()
                .withProperty("almira.providers.$name.mode", "live")
                .withProperty("almira.providers.$name.api-key", "present")
            // catchThrowable, not assertThatThrownBy: the latter fails with its own
            // "Expecting code to raise a throwable" before any description applies.
            val refusal = catchThrowable { ProviderModeCheck().postProcessEnvironment(environment, SpringApplication()) }
            assertThat(refusal)
                .describedAs(
                    "'$name' no longer refuses live. GO-LIVE.md and docs/providers still say no live " +
                        "adapter exists — update them in the same change.",
                )
                .isNotNull()
                .hasMessageContaining("no live adapter for $name")
        }
    }

    private companion object {
        val KOTLIN_BLOCK = Regex("```kotlin\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)
        val SIGNATURE_STARTS = listOf("fun ", "val ", "interface ", "data class ")
    }
}
