package tech.bhrigu.almira.provider

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * DigiLocker, in sandbox.
 *
 * It returns the documents an Indian household most often has there, as real
 * PDFs with real text, so the whole path — consent, list, fetch, store
 * encrypted, read the text layer, propose fields — is exercised end to end. The
 * only thing not proven is the transport.
 *
 * Live needs: a registered client id and secret from the DigiLocker partner
 * portal, a redirect URI on a public host, and the organisation's KYC. See
 * docs/13.
 */
@Component
@ConditionalOnProperty(
    name = ["almira.providers.digilocker.mode"], havingValue = "sandbox", matchIfMissing = true,
)
class SandboxDocumentVault : DocumentVaultProvider {

    override val mode = ProviderMode.SANDBOX

    override fun authorizationUrl(householdId: UUID, state: String) =
        "/app/#/connect/digilocker/sandbox?state=$state"

    override fun exchange(householdId: UUID, code: String) = ProviderSession(
        token = "sandbox-${UUID.randomUUID()}",
        expiresAt = Instant.now().plus(1, ChronoUnit.HOURS),
        scope = "files.issueddocs",
    )

    override fun list(session: ProviderSession) = FIXTURES

    override fun fetch(session: ProviderSession, uri: String): ByteArray {
        val document = FIXTURES.firstOrNull { it.uri == uri }
            ?: throw IllegalArgumentException("no such document in the sandbox")
        return pdf(
            listOf(
                document.issuer.uppercase(),
                document.name,
                "Issued: ${document.issuedOn}",
                sampleLineFor(document.docType),
                "",
                "This is a sandbox document. It is not anybody's record.",
            ),
        )
    }

    private fun sampleLineFor(docType: String) = when (docType) {
        "insurance" -> "Policy No: 5567123456    Sum assured 1000000    Premium 18400"
        "pan" -> "Permanent Account Number: ABCDE1234F"
        "driving_licence" -> "DL No: AP0420110012345"
        else -> "Reference: SANDBOX-0001"
    }

    private fun pdf(lines: List<String>): ByteArray {
        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)
        PDPageContentStream(document, page).use { content ->
            content.beginText()
            content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
            content.newLineAtOffset(50f, 750f)
            lines.forEach { line ->
                content.showText(line)
                content.newLineAtOffset(0f, -18f)
            }
            content.endText()
        }
        val out = ByteArrayOutputStream()
        document.save(out)
        document.close()
        return out.toByteArray()
    }

    private companion object {
        val FIXTURES = listOf(
            VaultDocument(
                "in.gov.pan-PANCR-ABCDE1234F", "PAN card", "Income Tax Department",
                "pan", LocalDate.of(2011, 6, 14), "application/pdf", 42_000,
            ),
            VaultDocument(
                "in.lic-POLICY-5567123456", "LIC term policy", "Life Insurance Corporation",
                "insurance", LocalDate.of(2019, 11, 12), "application/pdf", 88_000,
            ),
            VaultDocument(
                "in.gov.dl-DL-AP0420110012345", "Driving licence", "Transport Department",
                "driving_licence", LocalDate.of(2018, 3, 2), "application/pdf", 36_000,
            ),
        )
    }
}

/**
 * The Account Aggregator network, in sandbox.
 *
 * Consent is granted, not assumed: the sandbox still makes you approve, because
 * a flow that skips consent in testing is a flow nobody has tested. The payload
 * is shaped like the real FI data — masked account numbers, a value and an
 * as-of date, never a credential — because scraping and credential harvesting
 * are exactly what the AA framework exists to replace (docs/05 §8).
 *
 * Live needs: an FIU registration with an AA (Sahamati onboarding), a signed
 * client certificate, and a published purpose code.
 */
@Component
@ConditionalOnProperty(
    name = ["almira.providers.aa.mode"], havingValue = "sandbox", matchIfMissing = true,
)
class SandboxAccountAggregator : AccountAggregatorClient {

    override val mode = ProviderMode.SANDBOX

    private val consents = mutableMapOf<String, ConsentHandle>()

    override fun requestConsent(householdId: UUID, request: ConsentRequest): ConsentHandle {
        val handle = "sandbox-consent-${UUID.randomUUID()}"
        val consent = ConsentHandle(
            handle = handle,
            status = "PENDING",
            approvalUrl = "/app/#/connect/aa/sandbox?handle=$handle",
            expiresAt = Instant.now().plus(30, ChronoUnit.DAYS),
        )
        consents[handle] = consent
        return consent
    }

    override fun consentStatus(handle: String): ConsentHandle {
        // The sandbox approves on the second look, so the "waiting for consent"
        // state is a state the app has actually been through.
        val current = consents[handle] ?: throw IllegalArgumentException("unknown consent")
        val next = if (current.status == "PENDING") current.copy(status = "ACTIVE") else current
        consents[handle] = next
        return next
    }

    override fun fetch(handle: String): List<DiscoveredHolding> {
        val consent = consents[handle] ?: throw IllegalArgumentException("unknown consent")
        if (consent.status != "ACTIVE") throw IllegalStateException("consent is not active")
        return FIXTURES
    }

    private companion object {
        val FIXTURES = listOf(
            DiscoveredHolding(
                "DEPOSIT", "State Bank of India", "XXXX4417", "SBI savings",
                BigDecimal("184320.00"), "INR", LocalDate.now(),
                mapOf("ifsc" to "SBIN0001234", "type" to "SAVINGS"),
            ),
            DiscoveredHolding(
                "TERM_DEPOSIT", "ICICI Bank", "XXXX8821", "ICICI fixed deposit",
                BigDecimal("500000.00"), "INR", LocalDate.now(),
                mapOf("maturityDate" to LocalDate.now().plusMonths(11).toString(), "rate" to "7.1"),
            ),
            DiscoveredHolding(
                "MUTUAL_FUNDS", "CAMS", "FOLIO-19284412", "Parag Parikh Flexi Cap",
                BigDecimal("326540.00"), "INR", LocalDate.now(),
                mapOf("units" to "4213.221", "nav" to "77.50"),
            ),
        )
    }
}

/**
 * WhatsApp capture, in sandbox.
 *
 * The one behaviour worth having before the account exists is the refusal: a
 * webhook that trusts its caller is an open door, so a live gateway checks the
 * signature and the sandbox says plainly that it does not.
 *
 * Live needs: a Meta business account, a verified number, a permanent access
 * token and an app secret for signature checks.
 */
@Component
@ConditionalOnProperty(
    name = ["almira.providers.whatsapp.mode"], havingValue = "sandbox", matchIfMissing = true,
)
class SandboxWhatsAppGateway(private val mapper: ObjectMapper) : WhatsAppGateway {

    private val log = LoggerFactory.getLogger(javaClass)

    override val mode = ProviderMode.SANDBOX

    override fun verify(signature: String?, body: ByteArray): Boolean {
        log.debug("sandbox WhatsApp gateway: signature not checked")
        return true
    }

    override fun parse(body: ByteArray): InboundMessage? {
        val node = runCatching { mapper.readTree(body) }.getOrNull() ?: return null
        // Shaped like Meta's webhook, so the live parser is this one.
        val message = node.at("/entry/0/changes/0/value/messages/0")
        if (message.isMissingNode) return null
        return InboundMessage(
            from = message.path("from").asText(),
            text = message.at("/text/body").asText(""),
            receivedAt = Instant.now(),
            mediaId = message.at("/image/id").asText(null),
        )
    }

    override fun reply(to: String, text: String) {
        log.info("sandbox WhatsApp reply to {}: {} characters", to.takeLast(4), text.length)
    }
}
