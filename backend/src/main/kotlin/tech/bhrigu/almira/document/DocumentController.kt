package tech.bhrigu.almira.document

import org.springframework.core.io.ByteArrayResource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class DocumentLinkResponse(val entityType: String, val entityId: UUID, val entityTitle: String?)

data class DocumentResponse(
    val id: UUID,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val docType: String,
    val expiresOn: LocalDate?,
    /** True once the expiry has passed — a lapsed policy is worth surfacing. */
    val expired: Boolean,
    val visibility: String,
    val notes: String?,
    val links: List<DocumentLinkResponse>,
    val createdAt: Instant,
)

@RestController
@RequestMapping("/api/v1/households/{householdId}/documents")
class DocumentController(private val service: DocumentService) {

    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun upload(
        @PathVariable householdId: UUID,
        @RequestPart("file") file: MultipartFile,
        @RequestParam(defaultValue = "other") docType: String,
        @RequestParam(required = false) expiresOn: LocalDate?,
        @RequestParam(required = false) visibility: String?,
        @RequestParam(required = false) notes: String?,
        @RequestParam(required = false) entityType: String?,
        @RequestParam(required = false) entityId: UUID?,
    ): DocumentResponse = service.upload(
        householdId,
        DocumentUpload(
            fileName = file.originalFilename ?: "document",
            mimeType = file.contentType,
            bytes = file.bytes,
            docType = docType,
            expiresOn = expiresOn,
            visibility = visibility,
            notes = notes,
            linkTo = if (entityType != null && entityId != null) listOf(entityType to entityId) else emptyList(),
        ),
    ).toResponse()

    @GetMapping
    fun list(
        @PathVariable householdId: UUID,
        @RequestParam(required = false) entityType: String?,
        @RequestParam(required = false) entityId: UUID?,
    ): List<DocumentResponse> = service.list(householdId, entityType, entityId).map { it.toResponse() }

    /**
     * Holdings with no proof attached. Runs through the caller's own
     * visibility, so it never reveals that someone else's record is missing
     * something (docs/10 Epic 1.7).
     */
    @GetMapping("/missing-proof")
    fun missingProof(@PathVariable householdId: UUID): List<MissingProof> =
        service.missingProof(householdId)

    @GetMapping("/{id}")
    fun get(@PathVariable householdId: UUID, @PathVariable id: UUID): DocumentResponse =
        service.get(householdId, id).toResponse()

    /**
     * Step one of viewing a document: confirm who you are, get a two-minute,
     * single-use ticket. POST because it is an event worth recording, not a
     * read (docs/05 §4).
     */
    @PostMapping("/{id}/access")
    fun requestAccess(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
    ): DownloadTicket = service.issueTicket(householdId, id)

    @PostMapping("/{id}/links")
    fun link(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @RequestParam entityType: String,
        @RequestParam entityId: UUID,
    ): DocumentResponse = service.link(householdId, id, entityType, entityId).toResponse()

    @DeleteMapping("/{id}/links")
    fun unlink(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @RequestParam entityType: String,
        @RequestParam entityId: UUID,
    ): DocumentResponse = service.unlink(householdId, id, entityType, entityId).toResponse()

    @DeleteMapping("/{id}")
    fun archive(@PathVariable householdId: UUID, @PathVariable id: UUID): ResponseEntity<Void> {
        service.archive(householdId, id)
        return ResponseEntity.noContent().build()
    }
}

/**
 * Step two: redeeming the ticket.
 *
 * Deliberately outside the household path and unauthenticated, so an <img> or
 * an <iframe> can load it directly. The ticket IS the authority — minted only
 * after a visibility check and a step-up, valid for two minutes, and consumed on
 * first use, so a token that leaks through a log or a screenshot is already
 * spent.
 */
@RestController
@RequestMapping("/api/v1/documents")
class DocumentDownloadController(private val service: DocumentService) {

    @GetMapping("/download")
    fun download(@RequestParam token: String): ResponseEntity<ByteArrayResource> {
        val content = service.redeem(token)
        return ResponseEntity.ok()
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.inline().filename(content.fileName).build().toString(),
            )
            // Never cached: the ticket is single-use, and a cached copy would
            // outlive the permission that produced it.
            .header(HttpHeaders.CACHE_CONTROL, "no-store, private")
            .contentType(runCatching { MediaType.parseMediaType(content.mimeType) }
                .getOrDefault(MediaType.APPLICATION_OCTET_STREAM))
            .body(ByteArrayResource(content.bytes))
    }
}

private fun DocumentRow.toResponse() = DocumentResponse(
    id = id,
    fileName = fileName,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    docType = docType,
    expiresOn = expiresOn,
    expired = expiresOn?.isBefore(LocalDate.now()) ?: false,
    visibility = visibility,
    notes = notes,
    links = links.map { DocumentLinkResponse(it.entityType, it.entityId, it.entityTitle) },
    createdAt = createdAt,
)
