package tech.bhrigu.almira.sharing

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.util.UUID

@RestController
@RequestMapping("/api/v1/households/{householdId}/shares")
class ShareController(private val service: ShareService) {

    @PostMapping
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable householdId: UUID,
        @RequestBody @Valid body: CreateShare,
        request: HttpServletRequest,
    ): ShareRow = service.create(householdId, body, baseUrl(request))

    @GetMapping
    fun list(@PathVariable householdId: UUID): List<ShareRow> = service.list(householdId)

    @GetMapping("/{id}")
    fun get(@PathVariable householdId: UUID, @PathVariable id: UUID): ShareRow =
        service.get(householdId, id)

    /**
     * Who opened it, and when. Sharing without this is sharing into the dark.
     *
     * Most recent first, a hundred at a time, and `offset` walks back through
     * the rest: a busy link's trail used to stop at a hundred with nothing to
     * say so. Both parameters are optional and their defaults are what this
     * endpoint always did, so a client that sends neither sees no change.
     */
    @GetMapping("/{id}/views")
    fun views(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "100") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): List<ShareViewRow> = service.views(householdId, id, limit, offset)

    @DeleteMapping("/{id}")
    fun revoke(@PathVariable householdId: UUID, @PathVariable id: UUID): ResponseEntity<Void> {
        service.revoke(householdId, id)
        return ResponseEntity.noContent().build()
    }

    private fun baseUrl(request: HttpServletRequest): String {
        val scheme = request.getHeader("X-Forwarded-Proto") ?: request.scheme
        val host = request.getHeader("X-Forwarded-Host") ?: request.getHeader("Host")
            ?: "${request.serverName}:${request.serverPort}"
        return "$scheme://$host"
    }
}

/**
 * The guest's own door. Unauthenticated by design — the token *is* the
 * credential — and deliberately the only unauthenticated read in the product.
 */
@RestController
@RequestMapping("/api/v1/share")
class GuestShareController(private val service: ShareService) {

    @GetMapping("/{token}")
    fun open(@PathVariable token: String, request: HttpServletRequest): GuestPayload =
        service.open(token, ipHash(request), request.getHeader("User-Agent"))

    /**
     * The CA-ready PDF behind a tax-pack link, so a CA can open it straight
     * from the link in a browser. Opening it counts as a view, like the page.
     */
    @GetMapping("/{token}/tax-pack.pdf")
    fun taxPackPdf(@PathVariable token: String, request: HttpServletRequest): ResponseEntity<ByteArray> =
        file(service.openTaxPackFile(token, "pdf", ipHash(request), request.getHeader("User-Agent")))

    /** The Schedule 112A-shaped CSV behind a tax-pack link. */
    @GetMapping("/{token}/schedule-112a.csv")
    fun schedule112A(@PathVariable token: String, request: HttpServletRequest): ResponseEntity<ByteArray> =
        file(service.openTaxPackFile(token, "csv", ipHash(request), request.getHeader("User-Agent")))

    private fun file(export: tech.bhrigu.almira.reports.Export): ResponseEntity<ByteArray> =
        ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + export.fileName + "\"")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .contentType(MediaType.parseMediaType(export.contentType))
            .body(export.bytes)

    /**
     * Hashed, and never stored raw: enough to notice one link being opened from
     * twenty places, not enough to follow anybody around (docs/05 §5).
     *
     * The address is the container's `remoteAddr`, which honours
     * X-Forwarded-For only from a trusted proxy (application.yml,
     * `server.forward-headers-strategy`). It used to read the header's first
     * entry from anyone, so whoever opened a link chose what the view log said
     * about where they were — and a log that can be written by the person it
     * records reads as evidence while being none. ShareAuditAddressTest.
     */
    private fun ipHash(request: HttpServletRequest): String? {
        val ip = request.remoteAddr ?: return null
        return MessageDigest.getInstance("SHA-256").digest(ip.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
    }
}
