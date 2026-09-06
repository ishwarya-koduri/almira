package tech.bhrigu.almira.importing

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import tech.bhrigu.almira.common.ApiException
import java.util.UUID

@RestController
@RequestMapping("/api/v1/households/{householdId}/import")
class ImportController(
    private val service: ImportService,
    private val mapper: ObjectMapper,
) {

    /** Reads the file and proposes a mapping. Saves nothing. */
    @PostMapping("/preview", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun preview(
        @PathVariable householdId: UUID,
        @RequestPart("file") file: MultipartFile,
    ): ImportPreview =
        service.preview(householdId, file.originalFilename ?: "spreadsheet", file.bytes)

    /**
     * Runs the import. Defaults to a dry run, so the destructive form has to be
     * asked for: `dryRun=false` after the person has seen what would happen.
     *
     * The file is uploaded again rather than held between calls. It keeps the
     * server stateless and, more usefully, means the import acts on the file in
     * front of the person now — not one they uploaded twenty minutes ago and
     * have since edited.
     */
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun import(
        @PathVariable householdId: UUID,
        @RequestPart("file") file: MultipartFile,
        @RequestParam("request") requestJson: String,
    ): ImportReport {
        val request = runCatching { mapper.readValue(requestJson, ImportRequest::class.java) }
            .getOrElse {
                throw ApiException.badRequest(
                    "request_invalid", "We couldn't read the import settings.",
                )
            }
        return service.import(
            householdId, file.originalFilename ?: "spreadsheet", file.bytes, request,
        )
    }
}
