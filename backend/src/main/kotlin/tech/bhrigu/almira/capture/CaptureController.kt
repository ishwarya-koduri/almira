package tech.bhrigu.almira.capture

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class ParseTextBody(
    @field:NotBlank(message = "Type what you'd like to add")
    val text: String,
)

@RestController
@RequestMapping("/api/v1/households/{householdId}/capture")
class CaptureController(
    private val quickAdd: QuickAddService,
    private val documentCapture: DocumentCaptureService,
) {

    /**
     * Reads shorthand into editable chips. **Saves nothing** — the client shows
     * the chips, the person edits or drops them, and the ordinary create endpoint
     * does the saving (docs/10 Epic 2.4.1). A parser that wrote straight to the
     * database would fill a registry with confident guesses.
     */
    @PostMapping("/parse-text")
    fun parseText(
        @PathVariable householdId: UUID,
        @RequestBody @Valid body: ParseTextBody,
    ): QuickAddParser.Parsed = quickAdd.parse(householdId, body.text)

    /**
     * Reads what it can from a document and proposes fields.
     *
     * The file is stored as an encrypted proof either way — including when
     * nothing can be read from it. The proof is the durable thing; losing the
     * upload because the parse was disappointing would be backwards.
     */
    @PostMapping("/parse-document", consumes = [org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE])
    fun parseDocument(
        @PathVariable householdId: UUID,
        @org.springframework.web.bind.annotation.RequestPart("file")
        file: org.springframework.web.multipart.MultipartFile,
    ): DocumentCapture = documentCapture.capture(
        householdId, file.originalFilename ?: "document", file.contentType, file.bytes,
    )
}
