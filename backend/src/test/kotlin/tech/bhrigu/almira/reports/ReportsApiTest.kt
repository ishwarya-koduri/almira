package tech.bhrigu.almira.reports

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tech.bhrigu.almira.support.ApiTestBase
import java.math.BigDecimal

@DisplayName("Reports: completeness, insights and export")
class ReportsApiTest : ApiTestBase() {

    private lateinit var owner: String
    private lateinit var spouse: String
    private lateinit var householdId: String
    private lateinit var spouseMemberId: String

    @BeforeEach
    fun setUp() {
        owner = signIn()
        spouse = signIn()
        householdId = createHousehold(owner, "Koduri", "private", "Ishwarya").path("id").asText()
        spouseMemberId = addMember(owner, householdId, "Partner").path("id").asText()
        joinHousehold(owner, householdId, spouseMemberId, spouse)
    }

    private fun completeness(token: String) =
        get("/api/v1/households/$householdId/reports/completeness", token).json()

    private fun insights(token: String) =
        get("/api/v1/households/$householdId/reports/insights", token).json()

    private fun check(report: com.fasterxml.jackson.databind.JsonNode, code: String) =
        report.path("checks").first { it.path("code").asText() == code }

    // --- completeness --------------------------------------------------------

    /**
     * The cold-start rule, again: a new user has not failed at anything yet.
     * A score of 0% and a list of five problems is how an attention surface
     * teaches people to ignore it.
     */
    @Test
    fun `an empty household is not scored as a failure`() {
        val report = completeness(owner)

        assertThat(report.path("score").asInt()).isEqualTo(100)
        assertThat(report.path("recordCount").asInt()).isZero()
        assertThat(report.path("checks")).isEmpty()
        assertThat(report.path("nextStep").asText()).contains("Add your first holding")
    }

    @Test
    fun `the score counts what a family member would need, and names the next thing`() {
        capture(owner, householdId, "gold_physical", "Wedding gold", BigDecimal("500000"))

        val report = completeness(owner)
        assertThat(report.path("recordCount").asInt()).isEqualTo(1)
        assertThat(check(report, "nominee").path("outstanding").asInt()).isEqualTo(1)
        assertThat(check(report, "proof").path("outstanding").asInt()).isEqualTo(1)
        assertThat(report.path("score").asInt()).isLessThan(100)
        assertThat(report.path("nextStep").asText())
            .describedAs("the heaviest gap, not the longest list")
            .contains("nominee")

        // Physical gold has no account to be linked to, so it is not asked for.
        assertThat(report.path("checks").map { it.path("code").asText() })
            .doesNotContain("linkage")
    }

    @Test
    fun `adding a nominee moves the score, and the check counts it as done`() {
        val id = capture(owner, householdId, "gold_physical", "Wedding gold", BigDecimal("500000"))
            .path("id").asText()
        val before = completeness(owner).path("score").asInt()

        call(
            org.springframework.http.HttpMethod.PUT,
            "/api/v1/households/$householdId/investments/$id/nominees", owner,
            mapOf(
                "nominees" to listOf(
                    mapOf("memberId" to spouseMemberId, "relationship" to "spouse", "sharePct" to 100),
                ),
            ),
        )

        val after = completeness(owner)
        assertThat(after.path("score").asInt()).isGreaterThan(before)
        assertThat(check(after, "nominee").path("done").asInt()).isEqualTo(1)
        assertThat(check(after, "nominee").path("outstanding").asInt()).isZero()
    }

    /**
     * The score is derived from records, and derived data leaks as readily as
     * the records themselves: a spouse's score must not move because of a
     * holding they cannot see.
     */
    @Test
    fun `a private holding does not appear in anyone else's score`() {
        capture(
            owner, householdId, "gold_physical", "Her private gold", BigDecimal("500000"),
            visibility = "private",
        )

        val theirs = completeness(spouse)
        assertThat(theirs.path("recordCount").asInt()).isZero()
        assertThat(theirs.path("score").asInt()).isEqualTo(100)
    }

    // --- insights ------------------------------------------------------------

    @Test
    fun `insights report proportions, and never a recommendation`() {
        capture(
            owner, householdId, "fd", "ICICI FD", BigDecimal("800000"),
            visibility = "household", attributes = mapOf("interest_rate" to 7.1),
        )
        capture(
            owner, householdId, "gold_physical", "Gold", BigDecimal("200000"),
            visibility = "household",
        )

        val report = insights(owner)
        assertThat(report.path("totalAssets").decimalValue()).isEqualByComparingTo(BigDecimal("1000000"))

        val holding = report.path("concentration").first { it.path("kind").asText() == "holding" }
        assertThat(holding.path("label").asText()).isEqualTo("ICICI FD")
        assertThat(holding.path("percentage").decimalValue()).isEqualByComparingTo(BigDecimal("80.0"))

        assertThat(report.path("observations").map { it.asText() })
            .describedAs("a proportion, stated; never what to do about it")
            .anyMatch { it.contains("80.0%") }
        assertThat(report.path("disclaimer").asText()).contains("not financial advice")
    }

    @Test
    fun `liquidity separates what is locked from what is not`() {
        post(
            "/api/v1/households/$householdId/investments", owner,
            mapOf(
                "typeId" to typeId(owner, householdId, "fd"),
                "title" to "Locked FD",
                "investedAmount" to 300000,
                "maturityDate" to "2030-04-01",
                "attributes" to mapOf("interest_rate" to 7.1),
                "visibility" to "household",
            ),
        )
        capture(
            owner, householdId, "gold_physical", "Gold", BigDecimal("100000"),
            visibility = "household",
        )

        val buckets = insights(owner).path("liquidity").associateBy { it.path("code").asText() }
        assertThat(buckets["locked"]!!.path("value").decimalValue())
            .isEqualByComparingTo(BigDecimal("300000"))
        assertThat(buckets["reachable"]!!.path("value").decimalValue())
            .isEqualByComparingTo(BigDecimal("100000"))
        assertThat(buckets["locked"]!!.path("description").asText())
            .describedAs("says what put it in this bucket, because it is a rough guide")
            .isNotBlank()
    }

    @Test
    fun `insights say so plainly when nothing has a value yet`() {
        val report = insights(owner)
        assertThat(report.path("observations").first().asText()).isEqualTo("Nothing recorded yet.")
        assertThat(report.path("concentration")).isEmpty()
    }

    // --- export --------------------------------------------------------------

    @Test
    fun `a CSV export carries raw numbers, so a spreadsheet can add them up`() {
        capture(
            owner, householdId, "gold_physical", "Gold, 22k", BigDecimal("500000"),
            visibility = "household",
        )

        val response = call(
            org.springframework.http.HttpMethod.GET,
            "/api/v1/households/$householdId/reports/export?format=csv", owner,
        )
        assertThat(response.status()).isEqualTo(HttpStatus.OK)
        assertThat(response.headers.getFirst("Content-Disposition"))
            .contains("attachment").contains(".csv")

        val body = response.body!!
        assertThat(body.lineSequence().first()).contains("Title,Type,Category")
        assertThat(body)
            .describedAs("a comma inside a title must not become a new column")
            .contains("\"Gold, 22k\"")
        assertThat(body)
            .describedAs("a number a spreadsheet can sum, not a pretty string")
            .contains(",500000,")
            .doesNotContain("₹5,00,000")
    }

    @Test
    fun `an XLSX export is a real workbook with numeric value columns`() {
        capture(
            owner, householdId, "gold_physical", "Gold", BigDecimal("500000"),
            visibility = "household",
        )

        val bytes = binary("format=xlsx")
        XSSFWorkbook(bytes.inputStream()).use { workbook ->
            val sheet = workbook.getSheetAt(0)
            assertThat(sheet.getRow(0).getCell(0).stringCellValue).isEqualTo("Title")
            assertThat(sheet.getRow(1).getCell(0).stringCellValue).isEqualTo("Gold")
            assertThat(sheet.getRow(1).getCell(6).numericCellValue).isEqualTo(500000.0)
        }
    }

    @Test
    fun `a PDF export is readable, and survives a name it cannot typeset`() {
        capture(
            owner, householdId, "gold_physical", "गोल्ड — wedding set", BigDecimal("500000"),
            visibility = "household",
        )

        val bytes = binary("format=pdf")
        Loader.loadPDF(bytes).use { document ->
            val text = PDFTextStripper().getText(document)
            assertThat(text).contains("Koduri")
            assertThat(text).contains("wedding set")
            assertThat(text)
                .describedAs("the rupee sign is not in this encoding, so the column says so")
                .contains("Value (INR)")
        }
    }

    @Test
    fun `an export contains only what the person asking can see`() {
        capture(
            owner, householdId, "gold_physical", "Her private gold", BigDecimal("500000"),
            visibility = "private",
        )
        capture(
            owner, householdId, "gold_physical", "Household gold", BigDecimal("100000"),
            visibility = "household",
        )

        val theirs = call(
            org.springframework.http.HttpMethod.GET,
            "/api/v1/households/$householdId/reports/export?format=csv", spouse,
        ).body!!
        assertThat(theirs).contains("Household gold")
        assertThat(theirs)
            .describedAs("an export is a report; it obeys the same rules as the screen")
            .doesNotContain("Her private gold")
    }

    @Test
    fun `an unknown format is refused, by name`() {
        val refused = get("/api/v1/households/$householdId/reports/export?format=doc", owner)
        assertThat(refused.status()).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(refused.errorCode()).isEqualTo("format_unsupported")
    }

    private fun binary(query: String): ByteArray {
        val http = org.springframework.web.client.RestTemplate()
        return http.exchange(
            url("/api/v1/households/$householdId/reports/export?$query"),
            org.springframework.http.HttpMethod.GET,
            org.springframework.http.HttpEntity<Void>(
                org.springframework.http.HttpHeaders().apply { setBearerAuth(owner) },
            ),
            ByteArray::class.java,
        ).body!!
    }
}
