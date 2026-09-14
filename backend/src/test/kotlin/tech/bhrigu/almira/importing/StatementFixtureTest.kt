package tech.bhrigu.almira.importing

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The password-protected statement the browser checks open (P-11,
 * scripts/browser-checks).
 *
 * It is **synthetic**: made up here, with invented people, funds and folio
 * numbers, and laid out like no real registrar's statement. Nobody has
 * confirmed the layout of a real CAMS, KFintech, NSDL or CDSL statement for
 * this project (known-issues 46), so the fixture proves what the client can
 * honestly claim — that a protected PDF opens on the device with its password,
 * refuses without it, and yields its lines — and nothing about any real format.
 *
 * The committed file is what is checked. To make it again:
 *
 *     ALMIRA_WRITE_FIXTURES=1 ./gradlew test --tests '*StatementFixtureTest*'
 */
@DisplayName("The synthetic protected statement fixture")
class StatementFixtureTest {

    private val fixture = File("../scripts/browser-checks/fixtures/statement-synthetic-aes256.pdf")

    @Test
    fun `opens with its password, and only with it`() {
        if (System.getenv("ALMIRA_WRITE_FIXTURES") == "1") write()

        assertThat(fixture).exists()
        assertThatThrownBy { Loader.loadPDF(fixture).close() }
            .isInstanceOf(InvalidPasswordException::class.java)
        assertThatThrownBy { Loader.loadPDF(fixture, "wrong").close() }
            .isInstanceOf(InvalidPasswordException::class.java)

        Loader.loadPDF(fixture, PASSWORD).use { document ->
            assertThat(document.isEncrypted).isTrue()
            assertThat(document.encryption.length).describedAs("AES-256").isEqualTo(256)
            val text = PDFTextStripper().getText(document)
            LINES.flatten().filter { it.isNotBlank() }.forEach { assertThat(text).contains(it) }
        }
    }

    private fun write() {
        fixture.parentFile.mkdirs()
        PDDocument().use { document ->
            LINES.forEach { lines ->
                val page = PDPage()
                document.addPage(page)
                PDPageContentStream(document, page).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 10f)
                    content.newLineAtOffset(48f, 740f)
                    lines.forEach {
                        content.showText(it)
                        content.newLineAtOffset(0f, -16f)
                    }
                    content.endText()
                }
            }
            val policy = StandardProtectionPolicy("owner-$PASSWORD", PASSWORD, AccessPermission())
            policy.encryptionKeyLength = 256
            document.protect(policy)
            document.save(fixture)
        }
    }

    companion object {
        const val PASSWORD = "almira-test"

        /** Two pages, two invented people; the browser check expects exactly these lines. */
        val LINES = listOf(
            listOf(
                "Statement of holdings - SYNTHETIC TEST FIXTURE, not a real registrar format",
                "Period 01-Apr-2026 to 31-Aug-2026",
                "",
                "Investor: Ishwarya Example",
                "Example Flexi Cap Fund - Direct Growth",
                "Folio No: 1234567/89   Units 1,234.567   Value 1,25,000.50",
                "Example Liquid Fund - Direct Growth",
                "Folio No: 99887766   Units 40.000   Value 40,000.00",
            ),
            listOf(
                "Investor: Aarav Example",
                "Example Index Fund - Regular Growth",
                "Folio No: 55501234   Units 312.100   Value 18,250.75",
            ),
        )
    }
}
