package tech.bhrigu.almira.importing

import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.WorkbookFactory
import tech.bhrigu.almira.common.ApiException
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Reads a spreadsheet into rows of strings.
 *
 * Everything comes out as text on purpose. A column of amounts in a real
 * export contains "₹1,00,000", "100000", "1,00,000.00" and a blank, and a reader
 * that decided types per cell would produce a different type per row. Coercion
 * happens once, later, against the column someone actually mapped it to.
 */
object Spreadsheet {

    data class Sheet(val headers: List<String>, val rows: List<List<String>>)

    private const val MAX_ROWS = 5_000

    fun read(bytes: ByteArray, fileName: String): Sheet =
        if (fileName.endsWith(".csv", ignoreCase = true) ||
            fileName.endsWith(".tsv", ignoreCase = true)
        ) {
            readDelimited(bytes, if (fileName.endsWith(".tsv", ignoreCase = true)) '\t' else ',')
        } else {
            readWorkbook(bytes)
        }

    private fun readWorkbook(bytes: ByteArray): Sheet = try {
        WorkbookFactory.create(ByteArrayInputStream(bytes)).use { workbook ->
            val sheet = workbook.getSheetAt(0)
                ?: throw ApiException.badRequest("sheet_empty", "That file has no sheets in it.")

            val all = sheet.rowIterator().asSequence().take(MAX_ROWS + 1).map { row ->
                (0 until (row.lastCellNum.toInt().coerceAtLeast(0))).map { column ->
                    cellText(row.getCell(column))
                }
            }.toList()

            if (all.isEmpty()) {
                throw ApiException.badRequest("sheet_empty", "That file appears to be empty.")
            }
            Sheet(all.first().map { it.trim() }, all.drop(1).filterNot { row -> row.all { it.isBlank() } })
        }
    } catch (e: ApiException) {
        throw e
    } catch (e: Exception) {
        throw ApiException.badRequest(
            "file_unreadable",
            "We couldn't read that file. CSV and Excel (.xlsx) both work.",
        )
    }

    private fun cellText(cell: org.apache.poi.ss.usermodel.Cell?): String = when {
        cell == null -> ""
        cell.cellType == CellType.STRING -> cell.stringCellValue.trim()
        cell.cellType == CellType.BOOLEAN -> cell.booleanCellValue.toString()
        cell.cellType == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell) ->
            // ISO, so the later date parser sees one unambiguous form rather than
            // whatever locale the spreadsheet was saved in.
            cell.localDateTimeCellValue.toLocalDate().toString()
        cell.cellType == CellType.NUMERIC ->
            // Excel numbers are doubles; going via BigDecimal and stripping the
            // trailing zeros keeps 100000 from arriving as "100000.0".
            BigDecimal(cell.numericCellValue).stripTrailingZeros().toPlainString()
        cell.cellType == CellType.FORMULA -> runCatching {
            when (cell.cachedFormulaResultType) {
                CellType.STRING -> cell.stringCellValue.trim()
                CellType.NUMERIC -> BigDecimal(cell.numericCellValue).stripTrailingZeros().toPlainString()
                else -> ""
            }
        }.getOrDefault("")
        else -> ""
    }

    /**
     * A CSV reader that handles quoting, because exported data contains commas
     * inside names — "Flat, Kakinada" is one field, not two, and splitting on
     * commas would shift every column after it.
     */
    private fun readDelimited(bytes: ByteArray, delimiter: Char): Sheet {
        val text = String(bytes, Charsets.UTF_8).removePrefix("﻿")
        val rows = mutableListOf<List<String>>()
        val field = StringBuilder()
        var row = mutableListOf<String>()
        var inQuotes = false
        var index = 0

        while (index < text.length) {
            val char = text[index]
            when {
                inQuotes && char == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
                    field.append('"'); index++
                }
                char == '"' -> inQuotes = !inQuotes
                !inQuotes && char == delimiter -> { row.add(field.toString().trim()); field.clear() }
                !inQuotes && (char == '\n' || char == '\r') -> {
                    if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                    row.add(field.toString().trim()); field.clear()
                    if (row.any { it.isNotBlank() }) rows.add(row)
                    row = mutableListOf()
                }
                else -> field.append(char)
            }
            index++
        }
        row.add(field.toString().trim())
        if (row.any { it.isNotBlank() }) rows.add(row)

        if (rows.isEmpty()) {
            throw ApiException.badRequest("sheet_empty", "That file appears to be empty.")
        }
        return Sheet(rows.first(), rows.drop(1).take(MAX_ROWS))
    }
}
