package tech.bhrigu.almira.capture

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.document.DocumentService
import tech.bhrigu.almira.document.DocumentUpload
import java.util.UUID

data class DocumentCapture(
    /** The document is stored regardless, so the proof is never lost. */
    val documentId: UUID,
    val fileName: String,
    val extractedFrom: String,
    val fields: List<QuickAddParser.Field>,
    /** Values worth a second look before saving. */
    val needsReview: List<String>,
    val note: String?,
)

/**
 * "Snap a certificate and fill in the form" (docs/10 Epic 2.4.2).
 *
 * The document is stored first and always, even when nothing can be read from
 * it. The proof is the durable thing; the extracted fields are a convenience,
 * and losing the upload because the parse was disappointing would be exactly
 * backwards.
 *
 * Nothing is saved as a holding. Fields come back as chips to confirm, with the
 * uncertain ones named — an extracted number that goes straight into a record is
 * a number nobody checked.
 */
@Service
class DocumentCaptureService(
    private val documents: DocumentService,
    private val extractor: TextExtractor,
    private val quickAdd: QuickAddService,
) {

    @Transactional
    fun capture(
        householdId: UUID,
        fileName: String,
        mimeType: String?,
        bytes: ByteArray,
        deviceText: String? = null,
    ): DocumentCapture {
        if (deviceText != null && deviceText.length > MAX_DEVICE_TEXT) {
            throw ApiException.badRequest(
                "text_too_long", "That's more text than one document holds. Try a single page.",
            )
        }

        val stored = documents.upload(
            householdId,
            DocumentUpload(
                fileName = fileName,
                mimeType = mimeType,
                bytes = bytes,
                docType = "certificate",
                expiresOn = null,
                visibility = null,
                notes = null,
                linkTo = emptyList(),
            ),
        )

        // The file's own text layer wins when there is one: it is exact, and a
        // device's reading of a photo of the same page can only be worse.
        val extracted = extractor.extract(bytes, mimeType, fileName).let { own ->
            if (own.text.isBlank() && !deviceText.isNullOrBlank()) {
                ExtractedText(deviceText, "device-ocr", null)
            } else {
                own
            }
        }
        if (extracted.text.isBlank()) {
            return DocumentCapture(
                documentId = stored.id,
                fileName = stored.fileName,
                extractedFrom = extracted.source,
                fields = emptyList(),
                needsReview = emptyList(),
                note = extracted.note,
            )
        }

        // The same parser as quick-add, over the document's text. A certificate
        // reads much like shorthand: an amount, a date, an institution, a type.
        // Reference numbers are taken out first: "Policy No: 5567123456" left in
        // reads as an amount of ₹5,56,71,23,456.
        val identifiers = identifiers(extracted.text)
        val withoutIdentifiers = identifiers.fold(extracted.text) { text, field -> text.replace(field.sourceText, " ") }
        val parsed = quickAdd.parse(householdId, condense(withoutIdentifiers))

        val fields = parsed.fields.filterNot { it.key == "title" } + identifiers
        return DocumentCapture(
            documentId = stored.id,
            fileName = stored.fileName,
            extractedFrom = extracted.source,
            fields = fields,
            needsReview = fields.filter { it.confidence != "high" }.map { it.label },
            note = if (fields.isEmpty()) {
                "We read the document but couldn't pick out anything useful. " +
                    "Fill it in and the file stays attached as proof."
            } else {
                "Check these before saving — they're read from the document, not verified."
            },
        )
    }

    /**
     * Reference numbers, which a general parser has no reason to look for but
     * which are the most useful thing on a certificate: they are what makes the
     * record findable again, and what dedupes a later import.
     */
    private fun identifiers(text: String): List<QuickAddParser.Field> = buildList {
        REFERENCE_PATTERNS.forEach { (key, label, pattern) ->
            // The first match that is a number of some kind, trying every place
            // the label appears: "Endowment policy\nPolicy No: 5567…" must not
            // read the second "Policy" as the first one's number.
            val match = generateSequence(pattern.find(text)) { previous -> pattern.find(text, previous.range.first + 1) }
                .firstOrNull { it.groupValues.getOrNull(1).orEmpty().any(Char::isDigit) }
                ?: return@forEach
            val value = match.groupValues[1].trim()
            if (value.length in 4..40) {
                add(
                    QuickAddParser.Field(
                        key = "attributes.$key", label = label, value = value,
                        display = value, sourceText = match.value.trim(),
                        confidence = "medium",
                    ),
                )
            }
        }
    }

    /** Collapses whitespace so multi-line PDF text reads like one line to the parser. */
    private fun condense(text: String): String =
        text.replace(Regex("""\s+"""), " ").trim().take(2000)

    private companion object {
        /** A dense A4 page is about 4,000 characters; five pages is plenty. */
        const val MAX_DEVICE_TEXT = 20_000

        val REFERENCE_PATTERNS: List<Triple<String, String, Regex>> = listOf(
            Triple(
                "policy_no", "Policy number",
                Regex("""(?i)policy\s*(?:no\.?|number|#)?\s*[:\-]?\s*([A-Z0-9\-/]{4,25})"""),
            ),
            Triple(
                "folio_no", "Folio number",
                Regex("""(?i)folio\s*(?:no\.?|number|#)?\s*[:\-]?\s*([A-Z0-9\-/]{4,25})"""),
            ),
            Triple(
                "receipt_no", "Receipt number",
                Regex("""(?i)(?:receipt|fd|deposit)\s*(?:no\.?|number|#)\s*[:\-]?\s*([A-Z0-9\-/]{4,25})"""),
            ),
            Triple(
                "certificate_no", "Certificate number",
                Regex("""(?i)certificate\s*(?:no\.?|number|#)?\s*[:\-]?\s*([A-Z0-9\-/]{4,25})"""),
            ),
        )
    }
}
