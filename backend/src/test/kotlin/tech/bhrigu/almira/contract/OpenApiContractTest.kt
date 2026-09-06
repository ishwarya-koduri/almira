package tech.bhrigu.almira.contract

import com.fasterxml.jackson.databind.node.ObjectNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tech.bhrigu.almira.config.OpenApiConfig
import tech.bhrigu.almira.support.ApiTestBase
import java.nio.file.Files
import java.nio.file.Path

/**
 * v1 is frozen, and this is what makes that a fact rather than an intention.
 *
 * The mobile app is built against docs/api/openapi-v1.json. Nothing stops a
 * Phase 2 refactor from renaming a field, tightening a validation, or dropping
 * something that looked unused — and none of it would fail a backend test,
 * because the backend would be perfectly consistent with itself. The first sign
 * would be a crash on someone's phone, weeks later, in a build that had already
 * shipped.
 *
 * So the contract is diffed against the frozen copy on every run. Additive
 * changes pass. Anything a v1 client would notice fails, with a list of exactly
 * what and where.
 */
@DisplayName("The v1 API contract")
class OpenApiContractTest : ApiTestBase() {

    private val frozenSpec: Path = Path.of("..", "docs", "api", "openapi-v1.json")

    @Test
    fun `the live API is still compatible with the frozen v1 contract`() {
        val baseline = mapper.readTree(Files.readString(frozenSpec))
        val current = mapper.readTree(get("/v3/api-docs").body)

        val breakages = OpenApiCompatibility.check(baseline, current)

        // Written out whether or not this passes, so a failure can be diffed
        // against the frozen file rather than reasoned about from a message.
        val actual = Path.of("build", "openapi-current.json")
        Files.createDirectories(actual.parent)
        Files.writeString(actual, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(current))

        assertThat(breakages)
            .describedAs(
                """
                The API is no longer compatible with frozen v1.

                Every change listed below would be felt by an app built against
                docs/api/openapi-v1.json. Two ways forward, and only two:

                  · If the change was NOT meant to be breaking, fix it. Add a new
                    optional field instead of renaming one; keep the old field and
                    deprecate it; widen a type rather than changing it.

                  · If it genuinely has to break, it belongs in /api/v2. v1 stays
                    as it is for as long as a released app depends on it.

                For a purely ADDITIVE change — a new endpoint, a new optional
                field — re-freeze the contract deliberately:

                    ./scripts/freeze-api-spec.sh

                The spec as it stands now is in backend/build/openapi-current.json.
                """.trimIndent(),
            )
            .isEmpty()
    }

    @Test
    fun `the frozen contract is committed and describes v1`() {
        assertThat(Files.exists(frozenSpec))
            .describedAs("docs/api/openapi-v1.json is the handoff artefact — it must be in the repo")
            .isTrue()

        val baseline = mapper.readTree(Files.readString(frozenSpec))
        assertThat(baseline.path("info").path("version").asText())
            .isEqualTo(OpenApiConfig.API_VERSION)
        assertThat(baseline.path("paths").size())
            .describedAs("a contract with no paths is not a contract")
            .isGreaterThan(40)
        baseline.path("paths").fieldNames().forEach {
            assertThat(it).startsWith("/api/v1/")
        }
    }

    /**
     * Guards the guard. If the comparison always returned "compatible" — an
     * empty baseline, a silent parse failure, a rule that never fires — the test
     * above would pass for ever while the contract rotted. These are the four
     * changes it must never miss.
     */
    @Test
    fun `the comparison actually catches the changes it claims to`() {
        val baseline = mapper.readTree(Files.readString(frozenSpec)) as ObjectNode

        val removedPath = baseline.deepCopy().apply {
            (get("paths") as ObjectNode).remove("/api/v1/households/{householdId}/investments")
        }
        assertThat(OpenApiCompatibility.check(baseline, removedPath))
            .describedAs("a deleted endpoint").isNotEmpty()

        val removedField = baseline.deepCopy().apply {
            val schemas = get("components").get("schemas") as ObjectNode
            (schemas.get("InvestmentResponse").get("properties") as ObjectNode).remove("value")
        }
        assertThat(OpenApiCompatibility.check(baseline, removedField))
            .describedAs("a deleted response field").isNotEmpty()

        val changedType = baseline.deepCopy().apply {
            val schemas = get("components").get("schemas") as ObjectNode
            val props = schemas.get("InvestmentResponse").get("properties") as ObjectNode
            (props.get("title") as ObjectNode).put("type", "integer")
        }
        assertThat(OpenApiCompatibility.check(baseline, changedType))
            .describedAs("a field whose type changed").isNotEmpty()

        val newlyRequired = baseline.deepCopy().apply {
            val schemas = get("components").get("schemas") as ObjectNode
            val body = schemas.get("CreateInvestmentBody") as ObjectNode
            val required = body.get("required") as com.fasterxml.jackson.databind.node.ArrayNode
            required.add("notes")
        }
        assertThat(OpenApiCompatibility.check(baseline, newlyRequired))
            .describedAs("a request field that became required").isNotEmpty()
    }

    /** Additive changes are the whole point of the rule — they must pass. */
    @Test
    fun `adding an endpoint or an optional field is not a breaking change`() {
        val baseline = mapper.readTree(Files.readString(frozenSpec)) as ObjectNode

        // A path that does not exist yet, asserted rather than assumed: this
        // fixture used "/goals", Phase 2 built it, and putObject then *replaced*
        // a real endpoint — so the test that proves additive changes pass was
        // quietly testing a removal instead.
        val futurePath = "/api/v1/households/{householdId}/not-built-yet"
        assertThat(baseline.get("paths").has(futurePath))
            .describedAs("the fixture path must be one the API does not have")
            .isFalse()

        val widened = baseline.deepCopy().apply {
            (get("paths") as ObjectNode).putObject(futurePath)
                .putObject("get").put("summary", "Something a later phase adds")

            val schemas = get("components").get("schemas") as ObjectNode
            (schemas.get("InvestmentResponse").get("properties") as ObjectNode)
                .putObject("xirr").put("type", "number")
        }

        assertThat(OpenApiCompatibility.check(baseline, widened))
            .describedAs("v1 is additive-only, not frozen-solid")
            .isEmpty()
    }
}
