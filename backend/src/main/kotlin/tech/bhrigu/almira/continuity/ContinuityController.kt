package tech.bhrigu.almira.continuity

import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/v1/households/{householdId}/continuity")
class ContinuityController(
    private val transmission: TransmissionService,
    private val handbook: HandbookService,
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
}
