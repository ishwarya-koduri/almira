package tech.bhrigu.almira.sharing

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
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

    /** Who opened it, and when. Sharing without this is sharing into the dark. */
    @GetMapping("/{id}/views")
    fun views(@PathVariable householdId: UUID, @PathVariable id: UUID): List<ShareViewRow> =
        service.views(householdId, id)

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
     * Hashed, and never stored raw: enough to notice one link being opened from
     * twenty places, not enough to follow anybody around (docs/05 §5).
     */
    private fun ipHash(request: HttpServletRequest): String? {
        val ip = request.getHeader("X-Forwarded-For")?.substringBefore(',')?.trim()
            ?: request.remoteAddr ?: return null
        return MessageDigest.getInstance("SHA-256").digest(ip.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
    }
}
