package tech.bhrigu.almira.importing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestTemplate
import tech.bhrigu.almira.support.ApiTestBase

/**
 * Migration is the moment this product either earns its place or does not
 * (docs/08 §2), so the tests here are mostly about the awkward cases: a sheet
 * with three bad rows, a person who runs the import twice, an amount written
 * with a rupee sign.
 */
@DisplayName("Spreadsheet import")
class ImportApiTest : ApiTestBase() {

    private lateinit var owner: String
    private lateinit var householdId: String

    @BeforeEach
    fun setUp() {
        owner = signIn()
        householdId = createHousehold(owner, "Koduri", "private", "Ishwarya").path("id").asText()
    }

    private val http = RestTemplate().apply {
        errorHandler = object : org.springframework.web.client.ResponseErrorHandler {
            override fun hasError(r: org.springframework.http.client.ClientHttpResponse) = false
            override fun handleError(r: org.springframework.http.client.ClientHttpResponse) = Unit
        }
    }

    private fun upload(
        path: String,
        csv: String,
        request: String? = null,
        name: String = "holdings.csv",
    ): org.springframework.http.ResponseEntity<String> {
        val body = LinkedMultiValueMap<String, Any>().apply {
            add("file", object : ByteArrayResource(csv.toByteArray()) {
                override fun getFilename() = name
            })
            request?.let { add("request", it) }
        }
        val headers = HttpHeaders().apply {
            contentType = MediaType.MULTIPART_FORM_DATA
            setBearerAuth(owner)
        }
        return http.exchange(
            url("/api/v1/households/$householdId/import$path"),
            HttpMethod.POST, HttpEntity(body, headers), String::class.java,
        )
    }

    private fun goldTypeId() = typeId(owner, householdId, "gold_physical")

    private val csv = """
        Name,Amount,Weight,Purchase Date,Bank
        Wedding coins,"₹1,00,000",6.3,03/08/2024,ICICI Bank
        Anniversary bangles,150000,9.1,2024-11-20,
        Broken row,not a number,,31/02/2024,
    """.trimIndent()

    // --- preview -------------------------------------------------------------

    @Test
    fun `preview reads the file and guesses the mapping from the headers`() {
        val preview = mapper.readTree(upload("/preview", csv).body)

        assertThat(preview.path("rowCount").asInt()).isEqualTo(3)
        assertThat(preview.path("headers").map { it.asText() })
            .containsExactly("Name", "Amount", "Weight", "Purchase Date", "Bank")

        val suggested = preview.path("suggestedMapping")
        assertThat(suggested.path("title").asText()).isEqualTo("Name")
        assertThat(suggested.path("investedAmount").asText()).isEqualTo("Amount")
        assertThat(suggested.path("quantity").asText()).isEqualTo("Weight")
        assertThat(suggested.path("startDate").asText()).isEqualTo("Purchase Date")
        assertThat(suggested.path("institution").asText()).isEqualTo("Bank")
        assertThat(preview.path("note").asText()).contains("Nothing is saved")
    }

    @Test
    fun `a comma inside a quoted field does not shift the columns`() {
        val preview = mapper.readTree(upload("/preview", csv).body)
        assertThat(preview.path("sample")[0].path("Amount").asText())
            .describedAs("₹1,00,000 is one field, not two")
            .isEqualTo("₹1,00,000")
    }

    // --- importing -----------------------------------------------------------

    private fun request(dryRun: Boolean) = """
        {"typeId":"${goldTypeId()}","visibility":"household","dryRun":$dryRun,
         "mapping":{"title":"Name","investedAmount":"Amount","quantity":"Weight",
                    "startDate":"Purchase Date","institution":"Bank"}}
    """.trimIndent()

    @Test
    fun `a dry run changes nothing and says what would happen`() {
        val report = mapper.readTree(upload("", csv, request(dryRun = true)).body)

        assertThat(report.path("dryRun").asBoolean()).isTrue()
        assertThat(report.path("imported").asInt())
            .describedAs("a preview has imported nothing, and must not claim to have")
            .isZero()
        assertThat(report.path("wouldImport").asInt()).isEqualTo(3)
        assertThat(report.path("note").asText()).contains("Nothing saved yet")
        assertThat(get("/api/v1/households/$householdId/investments", owner).json()).isEmpty()
    }

    /**
     * A cell that is present but unreadable is not a failed row — the holding
     * still belongs in the registry — but the amount must not vanish quietly.
     * Left unsaid, "not a number" becomes a holding worth nothing, discovered
     * months later.
     */
    @Test
    fun `the preview names the cells it could not read, before anything is saved`() {
        val report = mapper.readTree(upload("", csv, request(dryRun = true)).body)

        val broken = report.path("rows").first { it.path("title").asText() == "Broken row" }
        assertThat(broken.path("outcome").asText()).isEqualTo("would-import")
        assertThat(broken.path("message").asText())
            .contains("Amount “not a number”")
            .contains("Start date “31/02/2024”")
        assertThat(report.path("note").asText()).contains("we couldn't read")
    }

    @Test
    fun `and the same warning travels with the row that was actually imported`() {
        val report = mapper.readTree(upload("", csv, request(dryRun = false)).body)

        val broken = report.path("rows").first { it.path("title").asText() == "Broken row" }
        assertThat(broken.path("outcome").asText()).isEqualTo("imported")
        assertThat(broken.path("message").asText()).contains("left empty")
        assertThat(report.path("wouldImport").asInt())
            .describedAs("nothing is hypothetical on a real run")
            .isZero()
    }

    /**
     * The rule that matters most: one unreadable row must not cost the other
     * ninety-nine. Refusing the lot over a typo is how a migration stalls.
     */
    @Test
    fun `bad rows are reported and the good ones still import`() {
        val report = mapper.readTree(upload("", csv, request(dryRun = false)).body)

        assertThat(report.path("imported").asInt()).isEqualTo(3)
        assertThat(get("/api/v1/households/$householdId/investments", owner).json()).hasSize(3)

        // The third row imports, but its unreadable amount and impossible date
        // are simply absent rather than invented.
        val broken = get("/api/v1/households/$householdId/investments?q=Broken", owner).json().single()
        assertThat(broken.path("investedAmount").isMissingNode || broken.path("investedAmount").isNull)
            .describedAs("'not a number' must not become zero")
            .isTrue()
        assertThat(broken.path("startDate").isMissingNode || broken.path("startDate").isNull)
            .describedAs("31 February must not become the 29th")
            .isTrue()
    }

    @Test
    fun `amounts and dates survive the way they were written`() {
        upload("", csv, request(dryRun = false))
        val coins = get("/api/v1/households/$householdId/investments?q=Wedding", owner).json().single()

        assertThat(coins.path("investedAmount").decimalValue())
            .isEqualByComparingTo(java.math.BigDecimal(100_000))
        assertThat(coins.path("startDate").asText()).isEqualTo("2024-08-03")
        assertThat(coins.path("quantity").decimalValue())
            .isEqualByComparingTo(java.math.BigDecimal("6.3"))
        assertThat(coins.path("institutionName").asText()).isEqualTo("ICICI Bank")
    }

    /**
     * People re-run imports — the first looked wrong, or they added rows. The
     * second run must be a no-op, not a doubled net worth.
     */
    /**
     * A sheet of FDs almost never carries the interest rate, and a type that
     * insists on one would make the whole file unimportable. The preview said
     * "3 rows would be added" and then every row failed at the door — a preview
     * that lies is worse than no preview.
     */
    @Test
    fun `a type's required field does not block a migration, and the preview agrees`() {
        val fdCsv = """
            Name,Amount,Opened
            Axis FD,100000,01/04/2025
            SBI FD,250000,01/04/2025
        """.trimIndent()
        val fdRequest = """
            {"typeId":"${typeId(owner, householdId, "fd")}",
             "mapping":{"title":"Name","investedAmount":"Amount","startDate":"Opened"},
             "dryRun":%s}
        """.trimIndent()

        val preview = mapper.readTree(upload("", fdCsv, fdRequest.format("true")).body)
        assertThat(preview.path("wouldImport").asInt()).isEqualTo(2)

        val report = mapper.readTree(upload("", fdCsv, fdRequest.format("false")).body)
        assertThat(report.path("imported").asInt())
            .describedAs("what the preview promised is what happens")
            .isEqualTo(2)
        assertThat(report.path("failed").asInt()).isZero()

        // And the gap is surfaced where gaps belong, rather than at the door.
        val completeness = get("/api/v1/households/$householdId/reports/completeness", owner).json()
        assertThat(completeness.path("score").asInt()).isLessThan(100)
    }

    @Test
    fun `running the same import twice does not duplicate anything`() {
        upload("", csv, request(dryRun = false))
        val second = mapper.readTree(upload("", csv, request(dryRun = false)).body)

        assertThat(second.path("imported").asInt()).isZero()
        assertThat(second.path("duplicates").asInt()).isEqualTo(3)
        assertThat(second.path("note").asText()).contains("already here")
        assertThat(get("/api/v1/households/$householdId/investments", owner).json())
            .describedAs("still three, not six")
            .hasSize(3)
    }

    @Test
    fun `every row is reported with its spreadsheet row number`() {
        val report = mapper.readTree(upload("", csv, request(dryRun = false)).body)
        assertThat(report.path("rows").map { it.path("row").asInt() })
            .describedAs("row 1 is the header, as the spreadsheet shows it")
            .containsExactly(2, 3, 4)
    }

    // --- refusals ------------------------------------------------------------

    @Test
    fun `an import without a name column is refused before anything is written`() {
        val body = """{"typeId":"${goldTypeId()}","dryRun":false,"mapping":{"investedAmount":"Amount"}}"""
        val response = upload("", csv, body)
        assertThat(mapper.readTree(response.body).path("error").path("code").asText())
            .isEqualTo("mapping_incomplete")
    }

    @Test
    fun `an import with no type at all is refused`() {
        val body = """{"dryRun":false,"mapping":{"title":"Name"}}"""
        assertThat(mapper.readTree(upload("", csv, body).body).path("error").path("code").asText())
            .isEqualTo("type_required")
    }

    @Test
    fun `a file that is not a spreadsheet is refused kindly`() {
        val response = upload("/preview", "this is not a spreadsheet", name = "notes.xlsx")
        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(mapper.readTree(response.body).path("error").path("message").asText())
            .contains("CSV and Excel")
    }
}
