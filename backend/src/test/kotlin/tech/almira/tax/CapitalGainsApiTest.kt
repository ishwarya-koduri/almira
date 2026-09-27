package tech.almira.tax

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestTemplate
import tech.almira.support.ApiTestBase
import java.math.BigDecimal

/**
 * The CA-grade statement end to end: recorded sales in, the lot-by-lot
 * schedule, the Schedule 112A CSV, the PDF, and the same files behind a link
 * for a CA — all through the caller's own row-level security.
 */
@DisplayName("Capital gains for a CA")
class CapitalGainsApiTest : ApiTestBase() {

    private lateinit var owner: String
    private lateinit var spouse: String
    private lateinit var householdId: String
    private lateinit var ownerMemberId: String

    private val fy = "2024-25"

    @BeforeEach
    fun setUp() {
        owner = signIn()
        spouse = signIn()
        val household = createHousehold(owner, "Koduri", "private", "Ishwarya")
        householdId = household.path("id").asText()
        ownerMemberId = household.path("myMemberId").asText()
        val spouseMemberId = addMember(owner, householdId, "Ravi").path("id").asText()
        joinHousehold(owner, householdId, spouseMemberId, spouse, role = "admin")
    }

    private val bytes = RestTemplate(org.springframework.http.client.JdkClientHttpRequestFactory()).apply {
        errorHandler = object : org.springframework.web.client.ResponseErrorHandler {
            override fun hasError(response: org.springframework.http.client.ClientHttpResponse) = false
            override fun handleError(response: org.springframework.http.client.ClientHttpResponse) = Unit
        }
    }

    private fun download(path: String, token: String? = null): ResponseEntity<ByteArray> {
        val headers = HttpHeaders().apply { token?.let { setBearerAuth(it) } }
        return bytes.exchange(url(path), HttpMethod.GET, HttpEntity<Void>(headers), ByteArray::class.java)
    }

    private fun put(path: String, token: String, body: Any) = call(HttpMethod.PUT, path, token, body)

    /** Example 1 of docs/tax/capital-gains.md, recorded the way a person would. */
    private fun infosysSoldIn2024(visibility: String = "household"): String {
        val id = capture(
            owner, householdId, "stock_listed", "Infosys", BigDecimal(0), visibility,
            attributes = mapOf("symbol" to "INFY", "exchange" to "nse", "isin" to "INE009A01021"),
        ).path("id").asText()
        fun txn(type: String, date: String, qty: Int, price: Int) = check(
            post(
                "/api/v1/households/$householdId/investments/$id/transactions", owner,
                mapOf("txnType" to type, "txnDate" to date, "quantity" to qty, "price" to price, "amount" to qty * price),
            ).statusCode.is2xxSuccessful,
        )
        txn("buy", "2016-06-10", 100, 500)
        txn("sell", "2024-09-15", 100, 1500)
        return id
    }

    private fun schedule(token: String = owner, year: String = fy) =
        get("/api/v1/households/$householdId/tax/capital-gains/schedule?fy=$year", token).json()

    @Test
    fun `a split recorded after a sale does not shrink that sale's 31 January 2018 value`() {
        val id = capture(
            owner, householdId, "stock_listed", "Infosys", BigDecimal(0), "household",
            attributes = mapOf("symbol" to "INFY", "exchange" to "nse", "isin" to "INE009A01021"),
        ).path("id").asText()
        fun txn(type: String, date: String, fields: Map<String, Any>) {
            val response = post(
                "/api/v1/households/$householdId/investments/$id/transactions", owner,
                mapOf("txnType" to type, "txnDate" to date) + fields,
            )
            assertThat(response.statusCode.is2xxSuccessful).describedAs(response.body).isTrue()
        }
        txn("buy", "2016-06-10", mapOf("quantity" to 100, "price" to 500, "amount" to 50_000))
        txn("sell", "2023-08-10", mapOf("quantity" to 50, "price" to 3000, "amount" to 150_000))
        txn("split", "2025-03-01", mapOf("ratio" to "5:1"))
        put("/api/v1/households/$householdId/tax/grandfathering/$id", owner, mapOf("fmvPerUnit" to 1000))

        val line = schedule(year = "2023-24").path("lines").single()
        assertThat(line.path("quantity").decimalValue()).isEqualByComparingTo(BigDecimal(50))
        // 50 units of the sale day at ₹1,000 each, not at ₹1,000 ÷ 5.
        assertThat(line.path("costOfAcquisition").decimalValue()).isEqualByComparingTo(BigDecimal(50_000))
        assertThat(line.path("gain").decimalValue()).isEqualByComparingTo(BigDecimal(100_000))
    }

    @Test
    fun `an old lot asks for its 31 January 2018 value, and the gain corrects once it is given`() {
        val infosys = infosysSoldIn2024()

        val before = schedule()
        val line = before.path("lines").single()
        assertThat(line.path("section").asText()).isEqualTo("112A")
        assertThat(line.path("fmvMissing").asBoolean()).isTrue()
        assertThat(line.path("gain").decimalValue()).isEqualByComparingTo(BigDecimal(100_000))
        assertThat(before.path("grandfathering").single().path("investmentId").asText()).isEqualTo(infosys)
        assertThat(before.path("warnings").toString()).contains("31 January 2018")

        val set = put(
            "/api/v1/households/$householdId/tax/grandfathering/$infosys", owner,
            mapOf("fmvPerUnit" to 1200, "sourceNote" to "BSE high, 31 Jan 2018"),
        )
        assertThat(set.statusCode.value()).describedAs(set.body).isEqualTo(200)

        val after = schedule().path("lines").single()
        assertThat(after.path("fmvMissing").asBoolean()).isFalse()
        assertThat(after.path("costOfAcquisition").decimalValue()).isEqualByComparingTo(BigDecimal(120_000))
        assertThat(after.path("gain").decimalValue()).isEqualByComparingTo(BigDecimal(30_000))

        // The older summary agrees with the statement rather than with the stored disposal.
        val summary = get("/api/v1/households/$householdId/tax/capital-gains?fy=$fy", owner).json()
        assertThat(summary.path("netRealized").decimalValue()).isEqualByComparingTo(BigDecimal(30_000))

        val audited = db.queryForObject(
            "select count(*) from activity_log where action = 'tax.fmv_2018.set' and entity_id = ?::uuid",
            Int::class.java, infosys,
        )
        assertThat(audited).isEqualTo(1)
    }

    @Test
    fun `the 2018 value is refused for things it does not apply to, and for nonsense`() {
        val gold = capture(owner, householdId, "universal", "Gold coins", BigDecimal(50_000), "household")
            .path("id").asText()
        assertThat(
            put("/api/v1/households/$householdId/tax/grandfathering/$gold", owner, mapOf("fmvPerUnit" to 100))
                .errorCode(),
        ).isEqualTo("fmv_not_applicable")

        val infosys = infosysSoldIn2024()
        assertThat(
            put("/api/v1/households/$householdId/tax/grandfathering/$infosys", owner, mapOf("fmvPerUnit" to -1))
                .errorCode(),
        ).isEqualTo("fmv_invalid")
        assertThat(
            put("/api/v1/households/$householdId/tax/grandfathering/$infosys", owner, emptyMap<String, Any>())
                .errorCode(),
        ).isEqualTo("fmv_required")
    }

    @Test
    fun `a private holding is not found by anyone else, not even an admin`() {
        val infosys = infosysSoldIn2024(visibility = "private")

        val attempt = put(
            "/api/v1/households/$householdId/tax/grandfathering/$infosys", spouse, mapOf("fmvPerUnit" to 1200),
        )
        assertThat(attempt.statusCode.value()).isEqualTo(404)
        assertThat(delete("/api/v1/households/$householdId/tax/grandfathering/$infosys", spouse).statusCode.value())
            .isEqualTo(404)
        assertThat(schedule(spouse).path("lines")).isEmpty()
        assertThat(schedule(spouse).path("grandfathering")).isEmpty()
        assertThat(schedule(owner).path("lines")).hasSize(1)
    }

    @Test
    fun `the Schedule 112A CSV and the PDF come from the same figures`() {
        val infosys = infosysSoldIn2024()
        put("/api/v1/households/$householdId/tax/grandfathering/$infosys", owner, mapOf("fmvPerUnit" to 1200))

        val csv = download("/api/v1/households/$householdId/tax/schedule-112a?fy=$fy&member=$ownerMemberId", owner)
        assertThat(csv.statusCode.value()).isEqualTo(200)
        assertThat(csv.headers.contentDisposition.filename).isEqualTo("almira-schedule-112a-2024-25.csv")
        assertThat(String(csv.body!!)).contains(
            "BE,AE,INE009A01021,Infosys,100,1500,150000,120000,50000,120000,1200,120000,0,120000,30000",
        )

        val pdf = download("/api/v1/households/$householdId/tax/pack/pdf?fy=$fy&member=$ownerMemberId", owner)
        assertThat(pdf.statusCode.value()).isEqualTo(200)
        assertThat(pdf.headers.contentType.toString()).isEqualTo("application/pdf")
        val cover = Loader.loadPDF(pdf.body!!).use { PDFTextStripper().apply { endPage = 1 }.getText(it) }
        assertThat(cover).contains("FY 2024-25").contains("For Ishwarya").contains("not tax advice").contains("₹30,000")

        assertThat(
            db.queryForObject("select count(*) from activity_log where action = 'tax.pack.pdf'", Int::class.java),
        ).isGreaterThanOrEqualTo(1)
        assertThat(download("/api/v1/households/$householdId/tax/pack/pdf?fy=$fy").statusCode.value())
            .describedAs("no token, no pack").isEqualTo(401)
    }

    @Test
    fun `share with my CA names the taxpayer, and the link opens the PDF and the CSV until it is withdrawn`() {
        infosysSoldIn2024()

        val created = post(
            "/api/v1/households/$householdId/shares", owner,
            mapOf(
                "label" to "For Ramesh, CA", "scope" to "tax_pack", "financialYear" to fy,
                "memberId" to ownerMemberId, "expiresInDays" to 7,
            ),
        )
        assertThat(created.statusCode.value()).describedAs(created.body).isEqualTo(201)
        val share = created.json()
        assertThat(share.path("memberId").asText()).isEqualTo(ownerMemberId)
        val downloadUrl = share.path("downloadUrl").asText()
        assertThat(downloadUrl).endsWith("/tax-pack.pdf")
        val token = share.path("url").asText().substringAfterLast('/')

        val page = get("/api/v1/share/$token").json()
        assertThat(page.path("taxPack").path("memberName").asText()).isEqualTo("Ishwarya")
        assertThat(page.path("taxPack").path("capitalGainsSchedule").path("lines")).hasSize(1)

        val pdf = download("/api/v1/share/$token/tax-pack.pdf")
        assertThat(pdf.statusCode.value()).isEqualTo(200)
        assertThat(String(pdf.body!!.copyOfRange(0, 5))).isEqualTo("%PDF-")
        val csv = download("/api/v1/share/$token/schedule-112a.csv")
        assertThat(String(csv.body!!)).contains("INE009A01021")

        val views = get("/api/v1/households/$householdId/shares/${share.path("id").asText()}", owner).json()
        assertThat(views.path("viewCount").asInt()).describedAs("each file counts as an opening").isEqualTo(3)

        delete("/api/v1/households/$householdId/shares/${share.path("id").asText()}", owner)
        assertThat(download("/api/v1/share/$token/tax-pack.pdf").statusCode.value()).isEqualTo(404)
    }

    @Test
    fun `a CA link is refused for a person outside the household, or for a slice that is not a tax pack`() {
        infosysSoldIn2024()
        val stranger = signIn()
        val otherMember = createHousehold(stranger, "Others", "private", "Someone").path("myMemberId").asText()

        val outsider = post(
            "/api/v1/households/$householdId/shares", owner,
            mapOf("label" to "x", "scope" to "tax_pack", "financialYear" to fy, "memberId" to otherMember),
        )
        assertThat(outsider.statusCode.value()).isEqualTo(404)

        val handbook = post(
            "/api/v1/households/$householdId/shares", owner,
            mapOf("label" to "x", "scope" to "handbook", "memberId" to ownerMemberId),
        )
        assertThat(handbook.errorCode()).isEqualTo("member_not_applicable")
    }

    @Test
    fun `a link that is not a tax pack has no PDF`() {
        val id = infosysSoldIn2024()
        val share = post(
            "/api/v1/households/$householdId/shares", owner,
            mapOf("label" to "One record", "scope" to "records", "investmentIds" to listOf(id)),
        ).json()
        assertThat(share.path("downloadUrl").isNull || share.path("downloadUrl").isMissingNode).isTrue()
        val token = share.path("url").asText().substringAfterLast('/')
        assertThat(download("/api/v1/share/$token/tax-pack.pdf").statusCode.value()).isEqualTo(404)
    }
}
