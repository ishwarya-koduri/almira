package tech.bhrigu.almira.reports

import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.common.IndianNumbers
import tech.bhrigu.almira.household.HouseholdService
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class Export(
    val fileName: String,
    val contentType: String,
    val bytes: ByteArray,
) {
    // Data classes with an array field need these to behave; equality on the
    // bytes is what a caller would mean.
    override fun equals(other: Any?) = this === other ||
        (other is Export && fileName == other.fileName && bytes.contentEquals(other.bytes))

    override fun hashCode() = 31 * fileName.hashCode() + bytes.contentHashCode()
}

/**
 * Your data, out, in a form something else can read.
 *
 * Two rules govern this. First, an export contains exactly what the person
 * asking could see on screen — it runs under their own row-level security, so
 * there is no separate "and remember to filter the export" step to forget
 * (docs/05 §3.3, docs/07 §1). Second, the machine-readable formats carry raw
 * numbers rather than pretty ones: an export people cannot sum in a spreadsheet
 * is a screenshot with extra steps.
 */
@Service
class ExportService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
) {

    @Transactional(readOnly = true)
    fun holdings(householdId: UUID, format: String): Export {
        val household = households.get(householdId)
        val rows = load(householdId)
        val stamp = LocalDate.now()
        val base = "almira-holdings-$stamp"

        return when (format.lowercase()) {
            "csv" -> Export("$base.csv", "text/csv; charset=utf-8", csv(rows))
            "xlsx" -> Export(
                "$base.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                xlsx(rows),
            )
            "pdf" -> Export("$base.pdf", "application/pdf", pdf(rows, household.name, stamp))
            else -> throw ApiException.badRequest(
                "format_unsupported", "Choose csv, xlsx or pdf.",
            )
        }
    }

    // --- csv ------------------------------------------------------------------

    private fun csv(rows: List<Row>): ByteArray {
        val out = StringBuilder()
        out.append(HEADERS.joinToString(",")).append("\r\n")
        rows.forEach { row ->
            out.append(cells(row).joinToString(",") { quote(it) }).append("\r\n")
        }
        // A BOM, so a double-clicked file opens as UTF-8 in Excel on Windows
        // rather than turning every name with an accent into mojibake.
        return byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            out.toString().toByteArray(Charsets.UTF_8)
    }

    private fun quote(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    // --- xlsx -----------------------------------------------------------------

    private fun xlsx(rows: List<Row>): ByteArray {
        val workbook = SXSSFWorkbook(100)
        try {
            val sheet = workbook.createSheet("Holdings")
            val bold = workbook.createCellStyle().apply {
                setFont(workbook.createFont().apply { bold = true })
            }
            sheet.createRow(0).let { header ->
                HEADERS.forEachIndexed { index, title ->
                    header.createCell(index).apply {
                        setCellValue(title)
                        cellStyle = bold
                    }
                }
            }
            rows.forEachIndexed { index, row ->
                val sheetRow = sheet.createRow(index + 1)
                cells(row).forEachIndexed { column, value ->
                    val cell = sheetRow.createCell(column)
                    // Numbers as numbers, so the column can be summed.
                    val number = if (column in NUMERIC_COLUMNS) value.toDoubleOrNull() else null
                    if (number != null) cell.setCellValue(number) else cell.setCellValue(value)
                }
            }
            val out = ByteArrayOutputStream()
            workbook.write(out)
            return out.toByteArray()
        } finally {
            workbook.dispose()
            workbook.close()
        }
    }

    // --- pdf ------------------------------------------------------------------

    private fun pdf(rows: List<Row>, householdName: String, stamp: LocalDate): ByteArray {
        val document = PDDocument()
        // Courier, because the columns line up without measuring every string,
        // and WinAnsi, which is why amounts read "1,00,000" with the currency in
        // the column heading: the rupee sign is not in this encoding, and a
        // statement that throws while saving is worse than one that says INR.
        val body = PDType1Font(Standard14Fonts.FontName.COURIER)
        val heading = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)

        val perPage = 40
        val pages = rows.chunked(perPage).ifEmpty { listOf(emptyList()) }
        pages.forEachIndexed { pageIndex, chunk ->
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            PDPageContentStream(document, page).use { content ->
                var y = 800f
                content.beginText()
                content.setFont(heading, 14f)
                content.newLineAtOffset(40f, y)
                content.showText(ascii("$householdName — holdings"))
                content.endText()

                y -= 16f
                content.beginText()
                content.setFont(body, 8f)
                content.newLineAtOffset(40f, y)
                content.showText(
                    "As at $stamp. Values in INR. Shows only what you can see. " +
                        "Page ${pageIndex + 1} of ${pages.size}.",
                )
                content.endText()

                y -= 22f
                content.beginText()
                content.setFont(body, 8f)
                content.newLineAtOffset(40f, y)
                content.showText(pdfLine(PDF_HEADERS))
                content.newLineAtOffset(0f, -4f)
                content.endText()

                y -= 14f
                chunk.forEach { row ->
                    content.beginText()
                    content.setFont(body, 8f)
                    content.newLineAtOffset(40f, y)
                    content.showText(
                        pdfLine(
                            listOf(
                                row.title, row.typeLabel, row.institution ?: "—",
                                row.value?.let { IndianNumbers.group(it) } ?: "not recorded",
                                row.maturityDate?.toString() ?: "",
                            ),
                        ),
                    )
                    content.endText()
                    y -= 12f
                }

                if (chunk.isEmpty()) {
                    content.beginText()
                    content.setFont(body, 9f)
                    content.newLineAtOffset(40f, y)
                    content.showText("Nothing recorded yet.")
                    content.endText()
                }
            }
        }

        val out = ByteArrayOutputStream()
        document.save(out)
        document.close()
        return out.toByteArray()
    }

    private fun pdfLine(values: List<String>) = ascii(
        values.mapIndexed { index, value -> pad(value, PDF_WIDTHS[index]) }.joinToString("  "),
    )

    private fun pad(value: String, width: Int) =
        if (value.length > width) value.take(width - 1) + "…" else value.padEnd(width)

    /**
     * The Standard-14 fonts encode WinAnsi only, and PDFBox throws on anything
     * outside it. A statement that fails to save because someone's holding is
     * called "गोल्ड" would be a poor trade for a typeface, so unsupported
     * characters are replaced rather than fatal.
     */
    private fun ascii(value: String) = buildString {
        // WinAnsi also carries the ellipsis, which is the one character `pad`
        // adds of its own accord.
        value.forEach { append(if (it.code in 32..255 || it == '…') it else '?') }
    }

    // --- rows -----------------------------------------------------------------

    private fun cells(row: Row) = listOf(
        row.title,
        row.typeLabel,
        row.categoryLabel,
        row.institution ?: "",
        row.accountLabel ?: "",
        row.owners,
        // stripTrailingZeros, because numeric(18,4) renders 500000.0000 and a
        // column of those is harder to read than it is to sum.
        row.investedAmount?.stripTrailingZeros()?.toPlainString() ?: "",
        row.value?.stripTrailingZeros()?.toPlainString() ?: "",
        row.valueBasis,
        row.valuedOn?.toString() ?: "",
        row.startDate?.toString() ?: "",
        row.maturityDate?.toString() ?: "",
        row.status,
        row.visibility,
    )

    private data class Row(
        val title: String,
        val typeLabel: String,
        val categoryLabel: String,
        val institution: String?,
        val accountLabel: String?,
        val owners: String,
        val investedAmount: BigDecimal?,
        val value: BigDecimal?,
        val valueBasis: String,
        val valuedOn: LocalDate?,
        val startDate: LocalDate?,
        val maturityDate: LocalDate?,
        val status: String,
        val visibility: String,
    )

    private fun load(householdId: UUID): List<Row> = jdbc.query(
        """
        select i.title, i.invested_amount, i.start_date, i.maturity_date, i.status, i.visibility,
               t.label as type_label, c.label as category_label,
               coalesce(inst.name, acct_inst.name) as institution_name,
               acct.label as account_label,
               v.effective_value, coalesce(v.value_basis, 'unknown') as value_basis, v.valued_on,
               coalesce(
                 (select string_agg(m.display_name, ' + ' order by m.display_name)
                  from investment_ownerships o
                  join members m on m.id = o.member_id
                  where o.investment_id = i.id), '') as owners
        from investments i
        join investment_types t on t.id = i.type_id
        join asset_categories c on c.id = t.category_id
        left join investment_value v on v.investment_id = i.id
        left join institutions inst on inst.id = i.institution_id
        left join accounts acct on acct.id = i.account_id
        left join institutions acct_inst on acct_inst.id = acct.institution_id
        where i.household_id = :hid and i.deleted_at is null
        order by lower(i.title)
        """.trimIndent(),
        mapOf("hid" to householdId),
    ) { rs, _ ->
        Row(
            title = rs.getString("title"),
            typeLabel = rs.getString("type_label"),
            categoryLabel = rs.getString("category_label"),
            institution = rs.getString("institution_name"),
            accountLabel = rs.getString("account_label"),
            owners = rs.getString("owners"),
            investedAmount = rs.getBigDecimal("invested_amount"),
            value = rs.getBigDecimal("effective_value"),
            valueBasis = rs.getString("value_basis"),
            valuedOn = rs.getDate("valued_on")?.toLocalDate(),
            startDate = rs.getDate("start_date")?.toLocalDate(),
            maturityDate = rs.getDate("maturity_date")?.toLocalDate(),
            status = rs.getString("status"),
            visibility = rs.getString("visibility"),
        )
    }

    private companion object {
        val HEADERS = listOf(
            "Title", "Type", "Category", "Institution", "Account", "Owners",
            "Invested amount", "Current value", "Value basis", "Valued on",
            "Start date", "Maturity date", "Status", "Visibility",
        )
        val NUMERIC_COLUMNS = setOf(6, 7)
        val PDF_HEADERS = listOf("Holding", "Type", "Institution", "Value (INR)", "Matures")
        val PDF_WIDTHS = listOf(30, 18, 18, 14, 10)
    }
}
