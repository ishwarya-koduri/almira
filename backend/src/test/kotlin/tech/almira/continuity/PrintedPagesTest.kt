package tech.almira.continuity

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.LocalDate

/** A QR code nobody can scan is decoration. These read every code back the way a phone would. */
@DisplayName("Printed pages: the QR code, the emergency kit and the envelope cover")
class PrintedPagesTest {

    @Test
    fun `a code made here reads back exactly, including Indian scripts`() {
        listOf(
            "https://almira.example.in/#/continuity",
            "https://almira.example.in/share/" + "A".repeat(43),
            "కుటుంబ ప్రణాళిక · परिवार",
        ).forEach { text ->
            assertThat(decode(image(QrCode.modules(text)))).isEqualTo(text)
        }
    }

    @Test
    fun `a code is square and grows with what it has to hold`() {
        val short = QrCode.modules("https://a.in/")
        val long = QrCode.modules("https://almira.example.in/share/" + "x".repeat(120))
        assertThat(short.size).isEqualTo(short[0].size)
        assertThat(long.size).isGreaterThan(short.size)
    }

    @Test
    fun `the emergency kit is one page, says keep it in the almirah, and its code opens the family plan`() {
        val kit = EmergencyKit(
            householdName = "Koduri",
            preparedFor = "Ishwarya",
            preparedOn = LocalDate.of(2026, 9, 14),
            trusted = listOf(KitPerson("Ravi", "Can ask for emergency access · 14 days to say no", null)),
            executors = listOf("Ramesh Rao"),
            whoToCall = List(12) { KitPerson("Contact $it", "Lawyer", "98765 4321$it") },
            steps = listOf("Scan the code.", "Ask.", "Wait.", "Read."),
            appUrl = "https://almira.example.in/#/continuity",
        )
        Loader.loadPDF(EmergencyKitPdf.render(kit)).use { document ->
            assertThat(document.numberOfPages).describedAs("a card, not a booklet").isEqualTo(1)
            val text = PDFTextStripper().getText(document)
            assertThat(text).contains("Keep this in the almirah.")
            assertThat(text).contains("If something happens to Ishwarya")
            assertThat(text).contains("Ravi").contains("Ramesh Rao")
            assertThat(text).describedAs("a list too long for the page says so").contains("more in the family handbook")
            assertThat(text).describedAs("it holds no secrets, and says so").contains("holds no passwords")
            assertThat(decode(keep(render(document), "emergency-kit"))).isEqualTo("https://almira.example.in/#/continuity")
        }
    }

    @Test
    fun `the envelope cover is dated and numbered, works without Almira, and its code is the link`() {
        val url = "https://almira.example.in/share/abcDEF123_-" + "z".repeat(32)
        val bytes = PDDocument().use { document ->
            EnvelopeCoverPdf.draw(
                document,
                EnvelopeCover(
                    householdName = "Koduri", preparedFor = "Ishwarya", edition = 3,
                    preparedOn = LocalDate.of(2026, 9, 14), linkUrl = url,
                    linkExpiresOn = LocalDate.of(2027, 9, 14),
                ),
            )
            ByteArrayOutputStream().also { document.save(it) }.toByteArray()
        }
        Loader.loadPDF(bytes).use { document ->
            val text = PDFTextStripper().getText(document)
            assertThat(text).contains("EDITION 3").contains("14 SEPTEMBER 2026")
            assertThat(text).contains("For my family")
            assertThat(text).contains(EnvelopeCoverPdf.WORKS_WITHOUT)
            assertThat(text.replace(Regex("\\s+"), " ")).contains("until 14 September 2027")
            assertThat(decode(keep(render(document), "envelope-cover"))).isEqualTo(url)
        }
    }

    companion object {
        fun render(document: PDDocument, page: Int = 0): BufferedImage =
            PDFRenderer(document).renderImageWithDPI(page, 150f)

        /** Left in build/ so a person can look at the page the test read. */
        fun keep(image: BufferedImage, name: String): BufferedImage {
            val out = java.io.File("build/printed/$name.png")
            out.parentFile.mkdirs()
            javax.imageio.ImageIO.write(image, "png", out)
            return image
        }

        fun decode(image: BufferedImage): String {
            val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels)))
            return QRCodeReader().decode(bitmap, mapOf(DecodeHintType.TRY_HARDER to true)).text
        }

        /** Modules to pixels, with the quiet zone, the way a printer would lay them down. */
        fun image(modules: Array<BooleanArray>, scale: Int = 6): BufferedImage {
            val count = modules.size + QrCode.QUIET_ZONE * 2
            val image = BufferedImage(count * scale, count * scale, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until count * scale) {
                for (x in 0 until count * scale) {
                    val row = y / scale - QrCode.QUIET_ZONE
                    val column = x / scale - QrCode.QUIET_ZONE
                    val dark = row in modules.indices && column in modules.indices && modules[row][column]
                    image.setRGB(x, y, if (dark) 0x000000 else 0xFFFFFF)
                }
            }
            return image
        }
    }
}
