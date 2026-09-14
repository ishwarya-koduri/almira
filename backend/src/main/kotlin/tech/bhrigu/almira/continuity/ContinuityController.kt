package tech.bhrigu.almira.continuity

import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/v1/households/{householdId}/continuity")
class ContinuityController(
    private val transmission: TransmissionService,
    private val handbook: HandbookService,
    private val envelope: HandbookEnvelopeService,
    private val kit: EmergencyKitService,
) {

    /** "How your family claims this", for one holding. */
    @GetMapping("/transmission/{investmentId}")
    fun transmission(
        @PathVariable householdId: UUID,
        @PathVariable investmentId: UUID,
    ): TransmissionGuide = transmission.forInvestment(householdId, investmentId)

    /** Everything marked for continuity that you can see, in one place. */
    @GetMapping("/handbook")
    fun familyHandbook(@PathVariable householdId: UUID): FamilyHandbook = handbook.build(householdId)

    /**
     * The printed version — put in a drawer, and read by someone who has never
     * opened the app, under the worst circumstances they will ever read
     * anything.
     */
    @GetMapping("/handbook.pdf")
    fun printableHandbook(@PathVariable householdId: UUID): ResponseEntity<ByteArray> =
        ResponseEntity.ok()
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"almira-family-handbook-" + LocalDate.now() + ".pdf\"",
            )
            .contentType(MediaType.APPLICATION_PDF)
            .body(handbook.printable(householdId))

    /**
     * The envelope edition (P-28): numbered, dated, printed in full, with a QR
     * code for a year-long guest link to the online copy. A POST, because it
     * makes that link and withdraws the last edition's. Needs a step-up.
     */
    @PostMapping("/handbook/envelope.pdf")
    fun envelopeHandbook(@PathVariable householdId: UUID): ResponseEntity<ByteArray> {
        val edition = envelope.print(householdId, baseUrl())
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + edition.fileName + "\"")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header("X-Almira-Edition", edition.edition.toString())
            .contentType(MediaType.APPLICATION_PDF)
            .body(edition.bytes)
    }

    /** Who to call and how to reach the family plan, for the almirah (X-61). */
    @GetMapping("/emergency-kit")
    fun emergencyKit(@PathVariable householdId: UUID): EmergencyKit = kit.build(householdId, baseUrl())

    /** The same, as one printed page with a QR code made on this server. */
    @GetMapping("/emergency-kit.pdf")
    fun printableEmergencyKit(@PathVariable householdId: UUID): ResponseEntity<ByteArray> =
        ResponseEntity.ok()
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"almira-emergency-kit-" + LocalDate.now() + ".pdf\"",
            )
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .contentType(MediaType.APPLICATION_PDF)
            .body(kit.printable(householdId, baseUrl()))

    /**
     * This server's own address as the request reached it. Forwarded headers
     * count only from a trusted proxy (application.yml,
     * `server.forward-headers-strategy`), so a caller cannot choose what the
     * printed code points at.
     */
    private fun baseUrl(): String = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString()
}
