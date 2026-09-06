package tech.bhrigu.almira.e2e

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class SealBody(val ciphertext: String, val keyVersion: Int = 1)

data class E2eStatus(
    val enabled: Boolean,
    val key: E2eKeyEnvelope?,
    val sealedFieldCount: Int,
    val caveats: List<String> = CAVEATS,
)

private val CAVEATS = listOf(
    "Sealed fields can't be searched, sorted or read by anything on the server — including the app itself.",
    "There is no recovery. If you forget the passphrase, what you sealed is gone, and that is what makes it worth doing.",
    "Everything else about the record — its name, its value, who owns it — is unchanged and still visible as usual.",
)

/**
 * Zero-knowledge mode. The server never receives the passphrase and stores only
 * what the browser hands it: a wrapped key it cannot unwrap, and ciphertext it
 * cannot open (docs/12).
 */
@RestController
@RequestMapping("/api/v1/households/{householdId}/e2e")
class E2eController(private val service: SealedFieldService) {

    @GetMapping
    fun status(@PathVariable householdId: UUID): E2eStatus {
        val key = service.key(householdId)
        return E2eStatus(
            enabled = key != null,
            key = key,
            sealedFieldCount = service.list(householdId, null, null).size,
        )
    }

    /** Also the rotation endpoint: a new passphrase rewraps the same content key. */
    @PutMapping("/key")
    fun putKey(
        @PathVariable householdId: UUID,
        @RequestBody @Valid body: E2eKeyEnvelope,
    ): E2eKeyEnvelope = service.storeKey(householdId, body)

    @GetMapping("/values")
    fun list(
        @PathVariable householdId: UUID,
        @RequestParam(required = false) recordType: String?,
        @RequestParam(required = false) recordId: UUID?,
    ): List<SealedField> = service.list(householdId, recordType, recordId)

    @PutMapping("/values/{recordType}/{recordId}/{fieldKey}")
    fun seal(
        @PathVariable householdId: UUID,
        @PathVariable recordType: String,
        @PathVariable recordId: UUID,
        @PathVariable fieldKey: String,
        @RequestBody @Valid body: SealBody,
    ): SealedField =
        service.seal(householdId, recordType, recordId, fieldKey, body.ciphertext, body.keyVersion)

    @DeleteMapping("/values/{recordType}/{recordId}/{fieldKey}")
    fun unseal(
        @PathVariable householdId: UUID,
        @PathVariable recordType: String,
        @PathVariable recordId: UUID,
        @PathVariable fieldKey: String,
    ): ResponseEntity<Void> {
        service.unseal(householdId, recordType, recordId, fieldKey)
        return ResponseEntity.noContent().build()
    }
}
