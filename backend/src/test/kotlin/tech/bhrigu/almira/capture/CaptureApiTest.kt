package tech.bhrigu.almira.capture

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestTemplate
import tech.bhrigu.almira.support.ApiTestBase
import java.io.ByteArrayOutputStream

@DisplayName("Quick add and reading documents")
class CaptureApiTest : ApiTestBase() {

    private lateinit var owner: String
    private lateinit var householdId: String

    @BeforeEach
    fun setUp() {
        owner = signIn()
        householdId = createHousehold(owner, "Koduri", "private", "Ishwarya").path("id").asText()
    }

    // --- quick add -----------------------------------------------------------

    @Test
    fun `shorthand becomes chips, against the household's own taxonomy`() {
        val parsed = post(
            "/api/v1/households/$householdId/capture/parse-text", owner,
            mapOf("text" to "1L gold 6.3g at ICICI 3 Aug"),
        ).json()

        val fields = parsed.path("fields").associateBy { it.path("key").asText() }
        assertThat(fields["investedAmount"]!!.path("display").asText()).isEqualTo("₹1,00,000")
        assertThat(fields["quantity"]!!.path("display").asText()).isEqualTo("6.3 g")
        assertThat(fields["typeId"]!!.path("display").asText()).isEqualTo("Physical Gold")
        assertThat(fields["institutionId"]!!.path("display").asText()).contains("ICICI")
        assertThat(fields).containsKey("startDate")
    }

    /**
     * The "record anything" promise has to hold for the fast path too, or quick
     * add quietly only works for things we predefined.
     */
    @Test
    fun `a custom type is matched exactly like a built-in one`() {
        post(
            "/api/v1/households/$householdId/types", owner,
            mapOf("label" to "Angel Investment", "categoryCode" to "alternatives"),
        )

        val parsed = post(
            "/api/v1/households/$householdId/capture/parse-text", owner,
            mapOf("text" to "5L angel investment"),
        ).json()

        assertThat(parsed.path("fields").map { it.path("display").asText() })
            .contains("Angel Investment")
    }

    /**
     * The chips and the "didn't understand" words travel with their positions, so
     * the client can underline the words each chip came from.
     */
    @Test
    fun `a real sentence comes back as labelled chips with their positions`() {
        val text = "SBI FD 3 lakh 7.1% matures 5 March 2028 nominee Aarav joint with Sita"
        val parsed = post(
            "/api/v1/households/$householdId/capture/parse-text", owner,
            mapOf("text" to text),
        ).json()

        val fields = parsed.path("fields").associateBy { it.path("key").asText() }
        assertThat(fields["attributes.interest_rate"]!!.path("label").asText()).isEqualTo("Rate")
        assertThat(fields["maturityDate"]!!.path("value").asText()).isEqualTo("2028-03-05")
        assertThat(fields["nomineeName"]!!.path("value").asText()).isEqualTo("Aarav")
        val amount = fields["investedAmount"]!!
        assertThat(text.substring(amount.path("start").asInt(), amount.path("end").asInt())).isEqualTo("3 lakh")
        assertThat(amount.path("hint").asText()).isEqualTo("Three Lakh Rupees")
        assertThat(fields["title"]?.path("value")?.asText() ?: "").doesNotContain("joint")
        assertThat(parsed.path("notUnderstood").map { it.path("text").asText() }).containsExactly("joint with Sita")
    }

    @Test
    fun `parsing saves nothing`() {
        post(
            "/api/v1/households/$householdId/capture/parse-text", owner,
            mapOf("text" to "1L gold at ICICI"),
        )
        assertThat(get("/api/v1/households/$householdId/investments", owner).json())
            .describedAs("a parser that wrote to the database would fill it with guesses")
            .isEmpty()
    }

    // --- documents -----------------------------------------------------------

    private val http = RestTemplate().apply {
        errorHandler = object : org.springframework.web.client.ResponseErrorHandler {
            override fun hasError(r: org.springframework.http.client.ClientHttpResponse) = false
            override fun handleError(r: org.springframework.http.client.ClientHttpResponse) = Unit
        }
    }

    private fun uploadDocument(bytes: ByteArray, name: String, type: String, text: String? = null) =
        http.exchange(
            url("/api/v1/households/$householdId/capture/parse-document"),
            HttpMethod.POST,
            HttpEntity(
                LinkedMultiValueMap<String, Any>().apply {
                    add("file", object : ByteArrayResource(bytes) {
                        override fun getFilename() = name
                    })
                    text?.let { add("text", it) }
                },
                HttpHeaders().apply {
                    contentType = MediaType.MULTIPART_FORM_DATA
                    setBearerAuth(owner)
                },
            ),
            String::class.java,
        )

    private fun pdf(lines: List<String>): ByteArray {
        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)
        PDPageContentStream(document, page).use { content ->
            content.beginText()
            content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
            content.newLineAtOffset(50f, 750f)
            lines.forEach {
                content.showText(it)
                content.newLineAtOffset(0f, -18f)
            }
            content.endText()
        }
        val out = ByteArrayOutputStream()
        document.save(out)
        document.close()
        return out.toByteArray()
    }

    @Test
    fun `a PDF with a text layer is read, and its reference numbers picked out`() {
        val bytes = pdf(
            listOf(
                "LIFE INSURANCE CORPORATION OF INDIA",
                "Policy No: 5567123456",
                "Sum assured 1000000",
                "Premium 18400 due 12 Nov 2025",
            ),
        )

        val result = mapper.readTree(uploadDocument(bytes, "lic.pdf", "application/pdf").body)

        assertThat(result.path("extractedFrom").asText()).isEqualTo("pdf-text-layer")
        val fields = result.path("fields").associateBy { it.path("key").asText() }
        assertThat(fields["attributes.policy_no"]!!.path("value").asText()).isEqualTo("5567123456")
        assertThat(result.path("note").asText()).contains("not verified")
        assertThat(result.path("needsReview").map { it.asText() }).contains("Policy number")
    }

    /**
     * The proof is the durable thing. Losing the upload because the parse was
     * disappointing would be exactly backwards.
     */
    @Test
    fun `the document is stored even when nothing can be read from it`() {
        val result = mapper.readTree(
            uploadDocument("not really a photo".toByteArray(), "cert.jpg", "image/jpeg").body,
        )

        assertThat(result.path("extractedFrom").asText()).isEqualTo("none")
        assertThat(result.path("fields")).isEmpty()
        assertThat(result.path("note").asText())
            .describedAs("says why, rather than showing an empty form")
            .contains("OCR service")

        val documentId = result.path("documentId").asText()
        assertThat(get("/api/v1/households/$householdId/documents/$documentId", owner).json()
            .path("fileName").asText()).isEqualTo("cert.jpg")
    }

    @Test
    fun `a scanned PDF with no text layer says so rather than looking broken`() {
        val result = mapper.readTree(uploadDocument(pdf(emptyList()), "scan.pdf", "application/pdf").body)
        assertThat(result.path("note").asText()).contains("scan rather than text")
        assertThat(result.path("documentId").asText()).isNotBlank()
    }

    // --- read on the device (P-13) ---------------------------------------------

    /**
     * The server cannot read a photo. The person's own browser can, and sends
     * the words it found alongside the picture, which is still stored as proof.
     */
    @Test
    fun `a photo's words read on the device are parsed like a document's`() {
        val result = mapper.readTree(
            uploadDocument(
                "a photo".toByteArray(), "bond.jpg", "image/jpeg",
                text = "LIFE INSURANCE CORPORATION OF INDIA\nPolicy No: 5567123456\nSum assured 1000000",
            ).body,
        )

        assertThat(result.path("extractedFrom").asText()).isEqualTo("device-ocr")
        val fields = result.path("fields").associateBy { it.path("key").asText() }
        assertThat(fields["attributes.policy_no"]!!.path("value").asText()).isEqualTo("5567123456")
        assertThat(result.path("documentId").asText()).isNotBlank()
    }

    /**
     * What a camera reads off a bond: the kind of policy on the line above its
     * number, and a number long enough to look like money.
     */
    @Test
    fun `a reference number is neither mistaken for a word nor read as an amount`() {
        val result = mapper.readTree(
            uploadDocument(
                "a photo".toByteArray(), "bond.jpg", "image/jpeg",
                text = "Endowment policy\nPolicy No: 5567123456\nSum Assured: Rs. 10,00,000",
            ).body,
        )

        val fields = result.path("fields").associateBy { it.path("key").asText() }
        assertThat(fields["attributes.policy_no"]!!.path("value").asText()).isEqualTo("5567123456")
        assertThat(fields["investedAmount"]?.path("display")?.asText() ?: "").doesNotContain("5,56,71,23,456")
    }

    @Test
    fun `a PDF's own text layer wins over what a device read`() {
        val bytes = pdf(listOf("Policy No: 5567123456"))
        val result = mapper.readTree(
            uploadDocument(bytes, "lic.pdf", "application/pdf", text = "Policy No: 1111111111").body,
        )

        assertThat(result.path("extractedFrom").asText()).isEqualTo("pdf-text-layer")
        val fields = result.path("fields").associateBy { it.path("key").asText() }
        assertThat(fields["attributes.policy_no"]!!.path("value").asText()).isEqualTo("5567123456")
    }

    @Test
    fun `more device text than a document holds is refused`() {
        val response = uploadDocument("a photo".toByteArray(), "bond.jpg", "image/jpeg", text = "x".repeat(20_001))
        assertThat(response.statusCode.value()).isEqualTo(400)
        assertThat(mapper.readTree(response.body).path("error").path("code").asText()).isEqualTo("text_too_long")
    }
}
