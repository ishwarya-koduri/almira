package tech.almira.market

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipInputStream

/** One published price: a NAV, or a closing price. */
data class MarketPrice(
    val source: String,
    /** AMFI scheme code, or the exchange's own instrument id. */
    val code: String,
    /** Every ISIN the line names. A fund's payout and reinvestment options share a NAV and have one ISIN each. */
    val isins: List<String>,
    /** The exchange ticker; null for a fund. */
    val symbol: String?,
    /** The exchange series (EQ, BE…) or BSE group; null for a fund. */
    val series: String?,
    val name: String,
    val price: BigDecimal,
    val asOf: LocalDate,
)

/**
 * AMFI's daily NAV file, NAVAll.txt (docs/13 §6).
 *
 * Semicolon-separated, CRLF, with section headings ("Open Ended Schemes(…)")
 * and fund-house names on lines of their own between blank lines. The columns
 * are found from the header rather than assumed, because the file has changed
 * shape: it was `Scheme Code;ISIN Div Payout/ ISIN Growth;ISIN Div
 * Reinvestment;Scheme Name;Net Asset Value;Date`, and as fetched in September
 * 2026 it has `Plan` and `Option` between the name and the NAV. A parser that
 * counted columns would have read the plan as the price.
 *
 * A NAV that is not a number ("N.A.") and a line that does not have the
 * header's column count are skipped, not guessed at. Old lines are kept — the
 * file still lists schemes that stopped publishing years ago, with their last
 * date — and it is for the caller to decide a price is too old to use.
 */
object AmfiNavFile {

    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH)
    private val ISIN = Regex("^[A-Z]{2}[A-Z0-9]{9}[0-9]$")

    fun parse(bytes: ByteArray): List<MarketPrice> {
        val lines = bytes.toString(Charsets.UTF_8).removePrefix("﻿").lineSequence().map { it.trimEnd('\r') }
        val iterator = lines.iterator()
        val header = generateSequence { if (iterator.hasNext()) iterator.next() else null }
            .firstOrNull { it.startsWith("Scheme Code", ignoreCase = true) }
            ?.split(';')?.map { it.trim().lowercase() }
            ?: throw IllegalArgumentException("not an AMFI NAV file: no header")

        fun column(predicate: (String) -> Boolean) = header.indexOfFirst(predicate).takeIf { it >= 0 }
        val code = column { it == "scheme code" } ?: error("no scheme code column")
        val name = column { it == "scheme name" } ?: error("no scheme name column")
        val nav = column { it.startsWith("net asset value") } ?: error("no NAV column")
        val date = column { it == "date" } ?: error("no date column")
        val isinColumns = header.indices.filter { header[it].contains("isin") }

        val prices = mutableListOf<MarketPrice>()
        while (iterator.hasNext()) {
            val cells = iterator.next().split(';')
            if (cells.size != header.size) continue
            val schemeCode = cells[code].trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: continue
            val value = cells[nav].trim().toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: continue
            val asOf = runCatching { LocalDate.parse(cells[date].trim(), DATE) }.getOrNull() ?: continue
            prices += MarketPrice(
                source = "amfi",
                code = schemeCode,
                isins = isinColumns.map { cells[it].trim().uppercase() }.filter { ISIN.matches(it) },
                symbol = null,
                series = null,
                name = cells[name].trim(),
                price = value,
                asOf = asOf,
            )
        }
        return prices
    }
}

/**
 * The NSE and BSE end-of-day bhavcopies in the common "UDiFF" layout both
 * exchanges moved to in July 2024 — `BhavCopy_NSE_CM_0_0_0_<yyyyMMdd>_F_0000.csv`
 * (zipped by NSE) and `BhavCopy_BSE_CM_0_0_0_<yyyyMMdd>_F_0000.CSV` (plain from
 * BSE). The same header serves both: TradDt, FinInstrmTp, FinInstrmId, ISIN,
 * TckrSymb, SctySrs, FinInstrmNm, ClsPric and so on.
 *
 * Only cash-market stock lines (FinInstrmTp `STK`) are kept, and the price is
 * the closing price. Columns are found by name, for the same reason as the NAV
 * file. The retired pre-2024 layouts are not read.
 */
object BhavcopyFile {

    private const val MAX_UNZIPPED = 50L * 1024 * 1024

    fun parse(bytes: ByteArray, exchange: String): List<MarketPrice> {
        require(exchange == "nse" || exchange == "bse") { "unknown exchange $exchange" }
        val text = csvText(bytes).removePrefix("﻿")
        val lines = text.lineSequence().map { it.trimEnd('\r') }.filter { it.isNotBlank() }.iterator()
        if (!lines.hasNext()) throw IllegalArgumentException("empty bhavcopy")
        val header = splitCsv(lines.next()).map { it.trim() }

        fun column(name: String) = header.indexOf(name).takeIf { it >= 0 }
            ?: throw IllegalArgumentException("not a UDiFF bhavcopy: no $name column")
        val tradeDate = column("TradDt")
        val type = column("FinInstrmTp")
        val id = column("FinInstrmId")
        val isin = column("ISIN")
        val symbol = column("TckrSymb")
        val series = column("SctySrs")
        val name = column("FinInstrmNm")
        val close = column("ClsPric")

        val prices = mutableListOf<MarketPrice>()
        while (lines.hasNext()) {
            val cells = splitCsv(lines.next())
            if (cells.size < header.size) continue
            if (cells[type].trim() != "STK") continue
            val price = cells[close].trim().toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: continue
            val asOf = runCatching { LocalDate.parse(cells[tradeDate].trim()) }.getOrNull() ?: continue
            prices += MarketPrice(
                source = exchange,
                code = cells[id].trim(),
                isins = listOfNotNull(cells[isin].trim().uppercase().takeIf { it.length == 12 }),
                symbol = cells[symbol].trim().uppercase().takeIf { it.isNotEmpty() },
                series = cells[series].trim().takeIf { it.isNotEmpty() },
                name = cells[name].trim(),
                price = price,
                asOf = asOf,
            )
        }
        return prices
    }

    /** NSE serves the file zipped; BSE does not. Told apart by the zip signature, not the address. */
    private fun csvText(bytes: ByteArray): String {
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
            return bytes.toString(Charsets.UTF_8)
        }
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || !entry.name.lowercase().endsWith(".csv")) continue
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    // A zip that unpacks to far more than any bhavcopy is not one.
                    if (out.size() > MAX_UNZIPPED) throw IllegalArgumentException("bhavcopy unpacks larger than expected")
                }
                return out.toString(Charsets.UTF_8)
            }
        }
        throw IllegalArgumentException("no CSV inside the bhavcopy zip")
    }

    /** Commas, with double quotes around a field that contains one. Enough for this file; not a general CSV reader. */
    internal fun splitCsv(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { current.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { cells += current.toString(); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        cells += current.toString()
        return cells
    }
}
