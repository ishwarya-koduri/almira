package tech.bhrigu.almira.capture

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

data class ExtractedText(
    val text: String,
    /** "pdf-text-layer", "ocr", or "none". */
    val source: String,
    val note: String?,
)

/**
 * Gets the words out of a document.
 *
 * Two quite different jobs behind one interface. A PDF from a bank or an insurer
 * almost always carries a text layer, and reading it is exact, free, offline and
 * available today — that covers most of what people will upload. A photograph of
 * a paper certificate needs actual OCR, which needs a provider account, and is
 * the same class of deferred dependency as SMS and push (docs/09 §2).
 *
 * The distinction is reported rather than hidden: a person who uploads a photo
 * should be told we cannot read it yet, not shown an empty form and left to
 * wonder.
 */
interface TextExtractor {
    fun extract(bytes: ByteArray, mimeType: String?, fileName: String): ExtractedText
}

@Component
class DocumentTextExtractor : TextExtractor {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun extract(bytes: ByteArray, mimeType: String?, fileName: String): ExtractedText {
        val looksLikePdf = mimeType?.contains("pdf", ignoreCase = true) == true ||
            fileName.endsWith(".pdf", ignoreCase = true) ||
            bytes.size > 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "%PDF"

        if (looksLikePdf) return fromPdf(bytes)

        val isText = mimeType?.startsWith("text/") == true ||
            fileName.endsWith(".txt", ignoreCase = true)
        if (isText) {
            return ExtractedText(String(bytes, Charsets.UTF_8), "pdf-text-layer", null)
        }

        return ExtractedText(
            text = "",
            source = "none",
            note = "We can read PDFs today. Reading a photo needs an OCR service, " +
                "which isn't connected yet — fill in the details and attach the " +
                "picture as proof.",
        )
    }

    private fun fromPdf(bytes: ByteArray): ExtractedText = try {
        Loader.loadPDF(bytes).use { document ->
            if (document.isEncrypted) {
                return ExtractedText(
                    "", "none",
                    "That PDF is password-protected, so we can't read it. " +
                        "You can still attach it as proof.",
                )
            }
            val text = PDFTextStripper().getText(document).trim()
            if (text.isBlank()) {
                // A scan saved as a PDF: pages of images, no text layer. Common
                // enough that it deserves its own explanation rather than
                // looking like a failure.
                ExtractedText(
                    "", "none",
                    "That PDF is a scan rather than text, so there's nothing to read " +
                        "without OCR. Fill in the details and attach it as proof.",
                )
            } else {
                ExtractedText(text, "pdf-text-layer", null)
            }
        }
    } catch (e: Exception) {
        log.debug("could not read pdf: {}", e.message)
        ExtractedText("", "none", "We couldn't read that file, but you can still attach it.")
    }
}
