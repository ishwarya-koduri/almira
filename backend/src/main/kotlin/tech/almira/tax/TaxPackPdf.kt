package tech.almira.tax

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDDocumentInformation
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import tech.almira.common.IndianNumbers
import java.awt.Color
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar

/**
 * The tax pack as a PDF a CA can file away: a one-page cover that says what the
 * year came to, then the working behind it.
 *
 * *Informational, not tax advice* — said on the cover and at the foot of every
 * page, because a PDF is exactly the thing that gets forwarded without the
 * screen it came from.
 *
 * Typography follows docs/02: Fraunces for the title and figures, Inter for
 * everything else, both embedded as subsets (OFL). The net figure is brass
 * (#8A6D10), the single rule under the title is gold (#C9A227), captions are
 * muted (#6B6558), and the notice is muted text beside an info mark rather than
 * a coloured box. Nothing is set below 10pt.
 *
 * Neither face carries Telugu or Devanagari, so a holding named in either
 * script prints with the unsupported characters replaced — the same trade the
 * holdings export makes, and better than a pack that fails to save (known
 * issues, "The CA pack cannot print Telugu or Devanagari"). If the fonts cannot
 * be loaded at all the pack falls back to Helvetica and writes "Rs" for the
 * rupee sign.
 */
object TaxPackPdf {

    private val INK = Color(0x1F, 0x1D, 0x1A)
    private val MUTED = Color(0x6B, 0x65, 0x58)
    private val BRASS = Color(0x8A, 0x6D, 0x10)
    private val GOLD = Color(0xC9, 0xA2, 0x27)
    private val RULE = Color(0xE4, 0xDF, 0xD3)

    private const val MARGIN = 48f
    private const val BODY = 10f

    fun render(pack: TaxPack, householdName: String, today: LocalDate = LocalDate.now()): ByteArray {
        PDDocument().use { document ->
            val fonts = Fonts.load(document)
            val writer = Writer(document, fonts, pack)
            writer.cover(householdName, today)
            writer.lines()
            writer.schedule112A()
            writer.deductionsAndInterest()
            writer.assumptions()
            writer.finish()

            document.documentInformation = PDDocumentInformation().apply {
                title = "Tax pack FY ${pack.financialYear}"
                subject = "Informational summary of recorded holdings. Not tax advice."
                producer = "Almira"
                creationDate = Calendar.getInstance()
            }
            val out = ByteArrayOutputStream()
            document.save(out)
            return out.toByteArray()
        }
    }

    // --- fonts ----------------------------------------------------------------

    private class Fonts(
        val display: PDFont,
        val body: PDFont,
        val bold: PDFont,
        val embedded: Boolean,
    ) {
        private val cache = HashMap<PDFont, HashMap<Int, Boolean>>()

        /** Replaces what a face cannot draw, rather than failing the whole document. */
        fun safe(font: PDFont, text: String): String = buildString {
            text.codePoints().forEach { cp ->
                val ok = cache.getOrPut(font) { HashMap() }.getOrPut(cp) {
                    runCatching { font.encode(String(Character.toChars(cp))); true }.getOrDefault(false)
                }
                if (ok) appendCodePoint(cp) else append(if (cp == 0x20B9) "Rs " else "?")
            }
        }

        fun money(amount: BigDecimal): String {
            val text = IndianNumbers.rupees(amount)
            return if (embedded) text else text.replace("₹", "Rs ").replace("−", "-")
        }

        companion object {
            fun load(document: PDDocument): Fonts = runCatching {
                fun ttf(name: String) = TaxPackPdf::class.java.getResourceAsStream("/fonts/$name")
                    ?.use { PDType0Font.load(document, it) }
                    ?: error("missing font $name")
                Fonts(ttf("fraunces_semibold.ttf"), ttf("inter_regular.ttf"), ttf("inter_semibold.ttf"), true)
            }.getOrElse {
                Fonts(
                    PDType1Font(Standard14Fonts.FontName.TIMES_BOLD),
                    PDType1Font(Standard14Fonts.FontName.HELVETICA),
                    PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD),
                    false,
                )
            }
        }
    }

    // --- layout ---------------------------------------------------------------

    private class Writer(val document: PDDocument, val fonts: Fonts, val pack: TaxPack) {
        private var page: PDPage? = null
        private var content: PDPageContentStream? = null
        private var y = 0f
        private var width = 0f
        private var height = 0f
        private val schedule = pack.capitalGainsSchedule

        private var landscape = false

        private fun newPage(landscape: Boolean = this.landscape) {
            content?.close()
            this.landscape = landscape
            val size = if (landscape) PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width) else PDRectangle.A4
            page = PDPage(size).also { document.addPage(it) }
            content = PDPageContentStream(document, page)
            width = size.width
            height = size.height
            y = height - MARGIN
        }

        private fun ensure(space: Float, onBreak: () -> Unit = {}) {
            if (y - space < MARGIN + 24f) {
                newPage()
                onBreak()
            }
        }

        private fun text(
            value: String, x: Float, at: Float, font: PDFont = fonts.body,
            size: Float = BODY, color: Color = INK,
        ) {
            val stream = content!!
            stream.beginText()
            stream.setFont(font, size)
            stream.setNonStrokingColor(color)
            stream.newLineAtOffset(x, at)
            stream.showText(fonts.safe(font, value))
            stream.endText()
        }

        private fun textRight(value: String, right: Float, at: Float, font: PDFont = fonts.body, size: Float = BODY, color: Color = INK) {
            val safe = fonts.safe(font, value)
            text(safe, right - font.getStringWidth(safe) / 1000f * size, at, font, size, color)
        }

        private fun widthOf(value: String, font: PDFont, size: Float) =
            font.getStringWidth(fonts.safe(font, value)) / 1000f * size

        /** Word-wrapped paragraph; returns nothing, moves the cursor. */
        private fun paragraph(value: String, size: Float = BODY, color: Color = MUTED, x: Float = MARGIN, maxWidth: Float = width - 2 * MARGIN, font: PDFont = fonts.body) {
            val words = value.split(" ")
            var line = ""
            val lineHeight = size * 1.45f
            for (word in words) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (widthOf(candidate, font, size) > maxWidth && line.isNotEmpty()) {
                    ensure(lineHeight)
                    text(line, x, y, font, size, color)
                    y -= lineHeight
                    line = word
                } else {
                    line = candidate
                }
            }
            if (line.isNotEmpty()) {
                ensure(lineHeight)
                text(line, x, y, font, size, color)
                y -= lineHeight
            }
        }

        private fun fit(value: String, font: PDFont, size: Float, maxWidth: Float): String {
            if (widthOf(value, font, size) <= maxWidth) return value
            var cut = value
            while (cut.isNotEmpty() && widthOf("$cut…", font, size) > maxWidth) cut = cut.dropLast(1)
            return "$cut…"
        }

        private fun rule(color: Color = RULE, thickness: Float = 0.6f, length: Float = width - 2 * MARGIN) {
            val stream = content!!
            stream.setStrokingColor(color)
            stream.setLineWidth(thickness)
            stream.moveTo(MARGIN, y)
            stream.lineTo(MARGIN + length, y)
            stream.stroke()
        }

        private fun heading(value: String) {
            ensure(40f)
            y -= 8f
            text(value, MARGIN, y, fonts.display, 15f, INK)
            y -= 8f
            rule()
            y -= 16f
        }

        /** The info mark: a small ring with an "i", drawn rather than relying on a glyph. */
        private fun notice(value: String) {
            val stream = content!!
            val cx = MARGIN + 6f
            val cy = y + 3.5f
            stream.setStrokingColor(MUTED)
            stream.setLineWidth(0.8f)
            val r = 5.5f
            val k = 0.5523f * r
            stream.moveTo(cx + r, cy)
            stream.curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r)
            stream.curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy)
            stream.curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r)
            stream.curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy)
            stream.stroke()
            text("i", cx - 1.3f, cy - 3.2f, fonts.bold, 8.5f, MUTED)
            paragraph(value, BODY, MUTED, x = MARGIN + 18f, maxWidth = width - 2 * MARGIN - 18f)
        }

        private fun footer(pageNumber: Int, total: Int, target: PDPage) {
            PDPageContentStream(document, target, PDPageContentStream.AppendMode.APPEND, true).use { stream ->
                val w = target.mediaBox.width
                stream.beginText()
                stream.setFont(fonts.body, BODY)
                stream.setNonStrokingColor(MUTED)
                stream.newLineAtOffset(MARGIN, 26f)
                stream.showText(fonts.safe(fonts.body, "Tax pack FY ${pack.financialYear} · Informational only, not tax advice"))
                stream.endText()
                val label = "Page $pageNumber of $total"
                val lw = fonts.body.getStringWidth(label) / 1000f * BODY
                stream.beginText()
                stream.setFont(fonts.body, BODY)
                stream.setNonStrokingColor(MUTED)
                stream.newLineAtOffset(w - MARGIN - lw, 26f)
                stream.showText(label)
                stream.endText()
            }
        }

        fun finish() {
            content?.close()
            content = null
            val total = document.numberOfPages
            document.pages.forEachIndexed { index, p -> footer(index + 1, total, p) }
        }

        // --- page 1: the cover -------------------------------------------------

        fun cover(householdName: String, today: LocalDate) {
            newPage(landscape = false)
            text("ALMIRA  ·  TAX PACK", MARGIN, y, fonts.bold, BODY, MUTED)
            y -= 34f
            text("FY ${pack.financialYear}", MARGIN, y, fonts.display, 28f, INK)
            y -= 20f
            val whose = pack.memberName?.let { "For $it · $householdName" } ?: "$householdName · everyone you can see"
            text(whose, MARGIN, y, fonts.body, 12f, INK)
            y -= 16f
            text("Prepared ${today.format(DATE)} from what was recorded.", MARGIN, y, fonts.body, BODY, MUTED)
            y -= 14f
            rule(GOLD, 1.2f, 64f)
            y -= 22f

            notice(
                "Informational only, not tax advice. A summary of recorded holdings, arranged the way a " +
                    "return asks for it. Check every figure against broker and bank statements before filing.",
            )
            y -= 12f

            // Capital gains: the one figure in brass.
            text("Capital gains realised in the year", MARGIN, y, fonts.bold, 11f, INK)
            y -= 30f
            text(fonts.money(schedule.netGain), MARGIN, y, fonts.display, 24f, BRASS)
            y -= 15f
            paragraph(schedule.netGainInWords, BODY, MUTED)
            y -= 6f

            if (schedule.buckets.isEmpty()) {
                paragraph("Nothing recorded as sold in this year.", BODY, MUTED)
            } else {
                val right = width - MARGIN
                text("Taxable gain", right - 190f, y, fonts.bold, BODY, MUTED)
                textRight("At the rate", right, y, fonts.bold, BODY, MUTED)
                y -= 6f
                rule()
                y -= 14f
                schedule.buckets.forEach { bucket ->
                    ensure(30f)
                    text(fit("${bucket.label} · ${bucket.periodLabel.replaceFirstChar { it.lowercase() }}", fonts.body, BODY, right - MARGIN - 210f), MARGIN, y)
                    textRight(fonts.money(bucket.taxableGain), right - 100f, y, fonts.display, BODY + 1)
                    textRight(bucket.taxAtRate?.let(fonts::money) ?: "slab", right, y, fonts.body, BODY, MUTED)
                    y -= 14f
                    if (bucket.exemptionApplied.signum() > 0) {
                        text("Less ${fonts.money(bucket.exemptionApplied)} of the 112A exemption", MARGIN + 10f, y, fonts.body, BODY, MUTED)
                        y -= 14f
                    }
                }
                paragraph(
                    "\"At the rate\" is the gain times the rate, before set-off of losses, surcharge, cess " +
                        "and rebate. Your CA works out the tax.",
                    BODY, MUTED,
                )
            }
            y -= 10f

            text("Deductions recorded", MARGIN, y, fonts.bold, 11f, INK)
            y -= 16f
            pack.deductions.forEach { meter ->
                ensure(16f)
                text(fit(meter.label, fonts.body, BODY, 300f), MARGIN, y)
                textRight("${fonts.money(meter.used)} of ${fonts.money(meter.limit)}", width - MARGIN, y)
                y -= 14f
            }
            y -= 8f
            text("Interest and dividends", MARGIN, y, fonts.bold, 11f, INK)
            textRight(fonts.money(pack.interestIncome.total), width - MARGIN, y, fonts.display, BODY + 1)
            y -= 22f

            if (schedule.warnings.isNotEmpty()) {
                text("Worth a look before filing", MARGIN, y, fonts.bold, 11f, INK)
                y -= 16f
                schedule.warnings.forEach { paragraph("·  $it", BODY, MUTED) }
            }
        }

        // --- the working -------------------------------------------------------

        fun lines() {
            if (schedule.lines.isEmpty()) return
            newPage(landscape = true)
            heading("Capital gains, lot by lot")
            paragraph(schedule.disclaimer, BODY, MUTED)
            y -= 6f

            val cols = listOf(
                Col("Holding", 146f), Col("Bought", 74f), Col("Sold", 74f), Col("Units", 52f, true),
                Col("Sale value", 78f, true), Col("Cost used", 78f, true), Col("Indexed cost", 78f, true),
                Col("Gain", 78f, true), Col("Section", 88f),
            )
            val header = { tableHeader(cols) }
            header()
            schedule.lines.forEach { line ->
                ensure(30f, onBreak = header)
                tableRow(
                    cols,
                    listOf(
                        line.title, line.acquiredOn.format(SHORT), line.transferredOn.format(SHORT),
                        units(line.quantity), fonts.money(line.proceeds), fonts.money(line.costOfAcquisition),
                        line.indexedCost?.let(fonts::money) ?: "-", fonts.money(line.gain),
                        line.section + (line.ratePercent?.let { " · ${CapitalGainsRules.pct(it)}" } ?: " · slab"),
                    ),
                )
                val detail = buildList {
                    if (line.fmvTotal2018 != null) add("31 Jan 2018 value ${fonts.money(line.fmvTotal2018)}; paid ${fonts.money(line.actualCost)}.")
                    addAll(line.notes)
                }
                detail.forEach { paragraph(it, BODY, MUTED, x = MARGIN + 12f, maxWidth = width - 2 * MARGIN - 12f) }
                y -= 2f
            }
        }

        fun schedule112A() {
            val rows = Schedule112ACsv.rows(schedule.lines)
            if (rows.isEmpty()) return
            newPage(landscape = true)
            heading("Schedule 112A, as laid out in the return")
            paragraph(
                "Long-term gains on listed shares and equity fund units. BE rows are scrip-wise for units " +
                    "acquired on or before 31 January 2018; later acquisitions are consolidated, as the schedule " +
                    "asks. Column 12 (transfer expenses) is not recorded and shows zero.",
                BODY, MUTED,
            )
            y -= 6f
            val cols = listOf(
                Col("1a", 24f), Col("1b", 24f), Col("ISIN", 92f), Col("Name", 150f), Col("4 Units", 56f, true),
                Col("6 Sale value", 76f, true), Col("8 Cost", 76f, true), Col("11 FMV total", 76f, true),
                Col("7 Cost used", 76f, true), Col("14 Balance", 76f, true),
            )
            val header = { tableHeader(cols) }
            header()
            rows.forEach { row ->
                ensure(18f, onBreak = header)
                tableRow(
                    cols,
                    listOf(row[0], row[1], row[2], row[3], row[4], grouped(row[6]), grouped(row[8]), grouped(row[11]), grouped(row[7]), grouped(row[14])),
                )
            }
        }

        fun deductionsAndInterest() {
            newPage(landscape = false)
            heading("Deductions")
            pack.deductions.forEach { meter ->
                ensure(40f)
                text(meter.label, MARGIN, y, fonts.bold)
                textRight("${fonts.money(meter.used)} of ${fonts.money(meter.limit)}", width - MARGIN, y)
                y -= 14f
                meter.sources.forEach { source ->
                    ensure(14f)
                    text(fit("${source.title} (${source.basis})", fonts.body, BODY, 360f), MARGIN + 12f, y, color = MUTED)
                    textRight(fonts.money(source.amount), width - MARGIN, y, color = MUTED)
                    y -= 14f
                }
                meter.note?.let { paragraph(it, BODY, MUTED, x = MARGIN + 12f, maxWidth = width - 2 * MARGIN - 12f) }
                y -= 6f
            }

            heading("Interest and dividends")
            if (pack.interestIncome.bySource.isEmpty()) {
                paragraph("No interest or dividends recorded for this year.")
            }
            pack.interestIncome.bySource.forEach { source ->
                ensure(14f)
                text(fit(source.title, fonts.body, BODY, 360f), MARGIN, y)
                textRight(fonts.money(source.amount), width - MARGIN, y)
                y -= 14f
            }
        }

        fun assumptions() {
            newPage(landscape = false)
            if (schedule.grandfathering.isNotEmpty()) {
                heading("Values on 31 January 2018")
                paragraph("Entered by the owner, per unit as the share stood that day. Later splits are applied from the recorded split transactions.")
                y -= 4f
                schedule.grandfathering.forEach { entry ->
                    ensure(16f)
                    text(fit(entry.title + (entry.isin?.let { " · $it" } ?: ""), fonts.body, BODY, 300f), MARGIN, y)
                    textRight(
                        entry.fmvPerUnit?.let { fonts.money(it) + fraction(it) + " per unit" } ?: "not entered",
                        width - MARGIN, y, color = if (entry.fmvPerUnit == null) MUTED else INK,
                    )
                    y -= 14f
                    entry.sourceNote?.let { paragraph(it, BODY, MUTED, x = MARGIN + 12f) }
                }
                y -= 6f
            }

            heading("What this assumes")
            schedule.assumptions.forEach { paragraph("·  $it", BODY, INK) }
            y -= 6f

            heading("Cost Inflation Index used")
            paragraph(
                "Base year 2001-02 = 100. Notification No. 44/2017 (S.O. 1790(E)) and its annual amendments, " +
                    "including No. 44/2024 (363), No. 70/2025 (376), and No. 85/2026 under section 72(8)(a) of " +
                    "the Income-tax Act, 2025 (384).",
            )
            y -= 4f
            val table = CostInflationIndex.table()
            val perColumn = (table.size + 2) / 3
            val top = y
            table.chunked(perColumn).forEachIndexed { column, chunk ->
                var rowY = top
                val x = MARGIN + column * 160f
                chunk.forEach { (year, index) ->
                    text(year, x, rowY, color = MUTED)
                    textRight(index.toString(), x + 110f, rowY, fonts.display)
                    rowY -= 14f
                }
                y = minOf(y, rowY)
            }
            y -= 10f
            notice(pack.disclaimer)
        }

        // --- tables ------------------------------------------------------------

        private data class Col(val title: String, val width: Float, val right: Boolean = false)

        private fun tableHeader(cols: List<Col>) {
            var x = MARGIN
            cols.forEach { col ->
                if (col.right) textRight(col.title, x + col.width - 6f, y, fonts.bold, BODY, MUTED)
                else text(col.title, x, y, fonts.bold, BODY, MUTED)
                x += col.width
            }
            y -= 6f
            rule()
            y -= 14f
        }

        private fun tableRow(cols: List<Col>, values: List<String>) {
            var x = MARGIN
            cols.forEachIndexed { index, col ->
                val value = fit(values[index], fonts.body, BODY, col.width - 8f)
                if (col.right) textRight(value, x + col.width - 6f, y) else text(value, x, y)
                x += col.width
            }
            y -= 14f
        }

        private fun units(quantity: BigDecimal) =
            quantity.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().let {
                if (it.scale() < 0) it.setScale(0) else it
            }.toPlainString()

        private fun grouped(raw: String) =
            raw.toBigDecimalOrNull()?.let(IndianNumbers::group) ?: raw

        private fun fraction(value: BigDecimal): String {
            val frac = value.setScale(2, RoundingMode.HALF_UP).toPlainString().substringAfter('.', "")
            return if (frac.isEmpty() || frac == "00") "" else ".$frac"
        }
    }

    private val DATE = DateTimeFormatter.ofPattern("d MMMM yyyy")
    private val SHORT = DateTimeFormatter.ofPattern("dd-MM-yyyy")
}
