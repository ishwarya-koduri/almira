package tech.bhrigu.almira.provider

import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class CompleteConnectionBody(val code: String)
data class ImportDocumentsBody(val uris: List<String>)

/**
 * Connecting to the outside (docs/13).
 *
 * Every one of these runs against a sandbox until a credential says otherwise,
 * and the status endpoint says which — so nobody mistakes a fixture for their
 * own data, and anyone deploying this can see exactly what is still missing.
 */
@RestController
@RequestMapping("/api/v1/households/{householdId}/connect")
class ConnectController(private val service: ConnectService) {

    @GetMapping("/providers")
    fun providers(@PathVariable householdId: UUID): List<ProviderStatus> = service.status(householdId)

    // --- DigiLocker ------------------------------------------------------------

    @PostMapping("/digilocker/start")
    fun startDigiLocker(@PathVariable householdId: UUID): Map<String, String> =
        service.startDocumentVault(householdId)

    @PostMapping("/digilocker/complete")
    fun completeDigiLocker(
        @PathVariable householdId: UUID,
        @RequestBody body: CompleteConnectionBody,
    ): List<VaultDocument> = service.completeDocumentVault(householdId, body.code)

    @PostMapping("/digilocker/import")
    fun importDocuments(
        @PathVariable householdId: UUID,
        @RequestBody body: ImportDocumentsBody,
    ): ImportedFromProvider = service.importDocuments(householdId, body.uris)

    // --- Account Aggregator ----------------------------------------------------

    @PostMapping("/aa/consent")
    fun requestConsent(@PathVariable householdId: UUID): ConsentHandle =
        service.requestConsent(householdId)

    @GetMapping("/aa/consent")
    fun consentStatus(@PathVariable householdId: UUID): ConsentHandle =
        service.consentStatus(householdId)

    @PostMapping("/aa/import")
    fun importHoldings(@PathVariable householdId: UUID): ImportedFromProvider =
        service.importHoldings(householdId)

    // --- WhatsApp --------------------------------------------------------------

    /**
     * The inbound webhook. Authenticated by the provider's signature rather than
     * by a bearer token — which is why the sandbox gateway says out loud that it
     * checks nothing, and why the status endpoint warns against exposing it.
     */
    @PostMapping("/whatsapp/inbound")
    fun inbound(
        @PathVariable householdId: UUID,
        @RequestHeader(name = "X-Hub-Signature-256", required = false) signature: String?,
        request: HttpServletRequest,
    ): WhatsAppCapture =
        service.captureFromWhatsApp(householdId, signature, request.inputStream.readAllBytes())
}
