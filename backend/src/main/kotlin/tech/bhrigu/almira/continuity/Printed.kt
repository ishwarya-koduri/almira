package tech.bhrigu.almira.continuity

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.awt.Color

/**
 * A QR code made here, from bytes to modules, with no request to anyone.
 *
 * Printed pages are kept for years and scanned by whatever phone is to hand, so
 * the code is the plainest one that holds the text: byte mode, error
 * correction level M (a crease or a coffee ring, not a torn corner), and the
 * four-module quiet zone the standard asks for, drawn in the output rather than
 * left to the margin. The encoding is ZXing's (build.gradle.kts).
 */
object QrCode {

    /** Modules, true for dark, without the quiet zone. */
    fun modules(text: String): Array<BooleanArray> {
        require(text.isNotEmpty()) { "a QR code needs something to say" }
        val code = Encoder.encode(
            text, ErrorCorrectionLevel.M, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"),
        )
        val matrix = code.matrix
        return Array(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix.get(x, y).toInt() == 1 } }
    }

    const val QUIET_ZONE = 4

    /**
     * Draws the code as filled squares at ([x], [y]) — the bottom-left corner,
     * PDF-style — [size] points across including the quiet zone. Vector, so it
     * prints sharp at any size.
     */
    fun draw(content: PDPageContentStream, text: String, x: Float, y: Float, size: Float) {
        val grid = modules(text)
        val count = grid.size + QUIET_ZONE * 2
        val module = size / count
        content.setNonStrokingColor(Color.WHITE)
        content.addRect(x, y, size, size)
        content.fill()
        content.setNonStrokingColor(Color.BLACK)
        grid.forEachIndexed { row, cells ->
            cells.forEachIndexed { column, dark ->
                if (dark) {
                    content.addRect(
                        x + (column + QUIET_ZONE) * module,
                        y + size - (row + QUIET_ZONE + 1) * module,
                        module, module,
                    )
                }
            }
        }
        content.fill()
    }
}

/**
 * The faces a printed page is set in (docs/02, docs/25 §3): Fraunces for the
 * title and anything meant to be read first, Inter for the rest, both embedded
 * as subsets. Neither carries Telugu or Devanagari, so such a name prints with
 * those characters replaced (known issue 44) — better than a page that fails
 * to print. If the files cannot be read the page falls back to the standard
 * PDF faces and says "Rs" for the rupee sign.
 */
internal class PrintFonts private constructor(
    val display: PDFont,
    val body: PDFont,
    val bold: PDFont,
    val embedded: Boolean,
) {
    private val cache = HashMap<PDFont, HashMap<Int, Boolean>>()

    /** Replaces what a face cannot draw, rather than failing the page. */
    fun safe(font: PDFont, text: String): String = buildString {
        text.codePoints().forEach { cp ->
            val ok = cache.getOrPut(font) { HashMap() }.getOrPut(cp) {
                runCatching { font.encode(String(Character.toChars(cp))); true }.getOrDefault(false)
            }
            if (ok) appendCodePoint(cp) else append(if (cp == 0x20B9) "Rs " else "?")
        }
    }

    fun width(font: PDFont, size: Float, text: String): Float = font.getStringWidth(safe(font, text)) / 1000f * size

    /** Words onto lines no wider than [maxWidth]. A single over-long word is left whole. */
    fun wrap(font: PDFont, size: Float, text: String, maxWidth: Float): List<String> {
        val lines = mutableListOf<String>()
        var current = ""
        text.split(' ').forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (current.isNotEmpty() && width(font, size, candidate) > maxWidth) {
                lines += current
                current = word
            } else {
                current = candidate
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    fun text(content: PDPageContentStream, font: PDFont, size: Float, color: Color, x: Float, y: Float, value: String) {
        content.beginText()
        content.setFont(font, size)
        content.setNonStrokingColor(color)
        content.newLineAtOffset(x, y)
        content.showText(safe(font, value))
        content.endText()
    }

    companion object {
        fun load(document: PDDocument): PrintFonts = runCatching {
            fun ttf(name: String) = PrintFonts::class.java.getResourceAsStream("/fonts/$name")
                ?.use { PDType0Font.load(document, it) }
                ?: error("missing font $name")
            PrintFonts(ttf("fraunces_semibold.ttf"), ttf("inter_regular.ttf"), ttf("inter_semibold.ttf"), true)
        }.getOrElse {
            PrintFonts(
                PDType1Font(Standard14Fonts.FontName.TIMES_BOLD),
                PDType1Font(Standard14Fonts.FontName.HELVETICA),
                PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD),
                false,
            )
        }
    }
}

/** The printed palette: the light-theme roles of docs/25 §2, nothing more. */
internal object Ink {
    val INK = Color(0x1C, 0x1A, 0x17)
    val MUTED = Color(0x6B, 0x65, 0x58)
    val BRASS = Color(0x8A, 0x6D, 0x10)
    val GOLD = Color(0xC9, 0xA2, 0x27)
    val RULE = Color(0xE7, 0xE2, 0xD8)
}
