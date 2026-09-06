package tech.bhrigu.almira.importing

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.catalog.CatalogService
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.common.IndianNumbers
import tech.bhrigu.almira.household.HouseholdService
import tech.bhrigu.almira.investment.CreateInvestment
import tech.bhrigu.almira.investment.InvestmentFilter
import tech.bhrigu.almira.investment.InvestmentService
import tech.bhrigu.almira.security.RequestUserContext
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

data class ColumnMapping(
    val title: String? = null,
    val investedAmount: String? = null,
    val quantity: String? = null,
    val unit: String? = null,
    val startDate: String? = null,
    val maturityDate: String? = null,
    val institution: String? = null,
    val notes: String? = null,
    /** A folio, policy or receipt number — what makes a row recognisable again. */
    val reference: String? = null,
    /** A per-row type, by label or code. Falls back to [ImportRequest.typeId]. */
    val type: String? = null,
)

data class ImportRequest(
    val typeId: UUID? = null,
    val mapping: ColumnMapping,
    val visibility: String? = null,
    /** Preview only: work out what would happen, change nothing. */
    val dryRun: Boolean = true,
)

data class ImportPreview(
    val fileName: String,
    val headers: List<String>,
    val rowCount: Int,
    val sample: List<Map<String, String>>,
    /** A guess at the mapping, from the header names. Always editable. */
    val suggestedMapping: ColumnMapping,
    val note: String,
)

data class RowOutcome(
    /** 1-based, counting the header as row 1 — what the spreadsheet shows. */
    val row: Int,
    val outcome: String,
    val title: String?,
    val investmentId: UUID?,
    val message: String?,
)

data class ImportReport(
    val dryRun: Boolean,
    val total: Int,
    val imported: Int,
    val duplicates: Int,
    val failed: Int,
    val rows: List<RowOutcome>,
    val note: String,
)

/**
 * Spreadsheet import (docs/10 Epic 2.4.3).
 *
 * Two rules shape the whole thing:
 *
 * **Never all-or-nothing.** A hundred-row sheet with three bad dates should
 * import ninety-seven rows and tell you about the three. Refusing the lot
 * because of one typo is how migration stalls, and migration is the moment this
 * product either earns its place or does not (docs/08 §2).
 *
 * **Never silently duplicate.** People run an import twice — the first attempt
 * looked wrong, or they added a few rows and re-uploaded. Matching on a folio or
 * policy number where there is one, and on title plus amount where there is not,
 * turns the second run into a no-op instead of a doubled net worth.
 */
@Service
class ImportService(
    private val investments: InvestmentService,
    private val catalog: CatalogService,
    private val households: HouseholdService,
    private val audit: AuditService,
    private val userContext: RequestUserContext,
) {

    @Transactional(readOnly = true)
    fun preview(householdId: UUID, fileName: String, bytes: ByteArray): ImportPreview {
        households.get(householdId)
        val sheet = Spreadsheet.read(bytes, fileName)

        return ImportPreview(
            fileName = fileName,
            headers = sheet.headers,
            rowCount = sheet.rows.size,
            sample = sheet.rows.take(5).map { row ->
                sheet.headers.mapIndexed { index, header -> header to (row.getOrNull(index) ?: "") }
                    .toMap()
            },
            suggestedMapping = suggestMapping(sheet.headers),
            note = "Check the mapping below, then import. Nothing is saved until you do.",
        )
    }

    /**
     * Imports row by row, each in its own transaction.
     *
     * REQUIRES_NEW per row is the point: one bad row must not roll back the
     * ninety-nine good ones. It costs a transaction per row, which for a
     * one-off migration of a few hundred rows is a price worth paying for not
     * losing someone's afternoon of typing.
     */
    fun import(
        householdId: UUID,
        fileName: String,
        bytes: ByteArray,
        request: ImportRequest,
    ): ImportReport {
        val userId = userContext.require()
        households.get(householdId)
        val sheet = Spreadsheet.read(bytes, fileName)

        if (request.mapping.title == null) {
            throw ApiException.badRequest(
                "mapping_incomplete", "Tell us which column holds the name of each thing.",
            )
        }

        val types = catalog.taxonomy(householdId).flatMap { it.types }
        val defaultType = request.typeId?.let { id ->
            types.firstOrNull { it.id == id }
                ?: throw ApiException.badRequest("type_unknown", "We don't recognise that type.")
        }
        if (defaultType == null && request.mapping.type == null) {
            throw ApiException.badRequest(
                "type_required", "Choose a type for these rows, or map a column that holds it.",
            )
        }

        val institutions = catalog.institutions(householdId, null, null)
        val existing = investments.list(householdId, InvestmentFilter(limit = 500))
        val outcomes = mutableListOf<RowOutcome>()

        sheet.rows.forEachIndexed { index, row ->
            val rowNumber = index + 2 // header is row 1, as the spreadsheet shows it
            val cell = { column: String? ->
                column?.let { name ->
                    sheet.headers.indexOf(name).takeIf { it >= 0 }?.let { row.getOrNull(it) }
                }?.trim()?.takeIf { it.isNotEmpty() }
            }

            val title = cell(request.mapping.title)
            if (title == null) {
                outcomes += RowOutcome(rowNumber, "skipped", null, null, "No name in this row.")
                return@forEachIndexed
            }

            val type = cell(request.mapping.type)?.let { text ->
                types.firstOrNull {
                    it.label.equals(text, true) || it.code.equals(text, true)
                }
            } ?: defaultType

            if (type == null) {
                outcomes += RowOutcome(
                    rowNumber, "failed", title, null,
                    "We don't recognise the type “${cell(request.mapping.type)}”.",
                )
                return@forEachIndexed
            }

            val amount = cell(request.mapping.investedAmount)?.let { Coerce.money(it) }
            val reference = cell(request.mapping.reference)

            val duplicate = existing.firstOrNull { candidate ->
                if (reference != null) {
                    candidate.attributes.values.any { it?.toString()?.equals(reference, true) == true }
                } else {
                    candidate.title.equals(title, true) &&
                        candidate.typeId == type.id &&
                        (amount == null || candidate.investedAmount?.compareTo(amount) == 0)
                }
            }
            if (duplicate != null) {
                outcomes += RowOutcome(
                    rowNumber, "duplicate", title, duplicate.id,
                    "Already here as “${duplicate.title}”.",
                )
                return@forEachIndexed
            }

            if (request.dryRun) {
                outcomes += RowOutcome(
                    rowNumber, "would-import", title, null,
                    amount?.let { "as ${IndianNumbers.rupees(it)}" },
                )
                return@forEachIndexed
            }

            try {
                val created = importRow(
                    householdId, type.id, title, amount,
                    quantity = cell(request.mapping.quantity)?.let { Coerce.number(it) },
                    unit = cell(request.mapping.unit),
                    startDate = cell(request.mapping.startDate)?.let { Coerce.date(it) },
                    maturityDate = cell(request.mapping.maturityDate)?.let { Coerce.date(it) },
                    institutionId = cell(request.mapping.institution)?.let { name ->
                        institutions.firstOrNull { it.name.equals(name, true) }?.id
                            ?: institutions.firstOrNull { it.name.startsWith(name, true) }?.id
                    },
                    notes = cell(request.mapping.notes),
                    visibility = request.visibility,
                )
                outcomes += RowOutcome(rowNumber, "imported", title, created, null)
            } catch (e: ApiException) {
                outcomes += RowOutcome(rowNumber, "failed", title, null, e.message)
            } catch (e: Exception) {
                outcomes += RowOutcome(rowNumber, "failed", title, null, "Couldn't save this row.")
            }
        }

        if (!request.dryRun) {
            audit.record(
                householdId = householdId, actorUserId = userId, action = "import.run",
                entityType = "household", entityId = householdId,
                diff = mapOf(
                    "fileName" to fileName,
                    "imported" to outcomes.count { it.outcome == "imported" },
                    "failed" to outcomes.count { it.outcome == "failed" },
                ),
            )
        }

        val imported = outcomes.count { it.outcome == "imported" }
        val duplicates = outcomes.count { it.outcome == "duplicate" }
        val failed = outcomes.count { it.outcome == "failed" }

        return ImportReport(
            dryRun = request.dryRun,
            total = sheet.rows.size,
            imported = imported,
            duplicates = duplicates,
            failed = failed,
            rows = outcomes,
            note = when {
                request.dryRun ->
                    "Nothing saved yet. ${outcomes.count { it.outcome == "would-import" }} rows " +
                        "would be added" +
                        (if (duplicates > 0) ", $duplicates are already here" else "") +
                        (if (failed > 0) ", $failed need a look" else "") + "."
                failed == 0 && duplicates == 0 -> "Imported all $imported."
                else ->
                    "Imported $imported." +
                        (if (duplicates > 0) " $duplicates were already here." else "") +
                        (if (failed > 0) " $failed couldn't be read — the rows are listed below." else "")
            },
        )
    }

    /** One row, one transaction, so a failure takes only itself down. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun importRow(
        householdId: UUID,
        typeId: UUID,
        title: String,
        amount: BigDecimal?,
        quantity: BigDecimal?,
        unit: String?,
        startDate: LocalDate?,
        maturityDate: LocalDate?,
        institutionId: UUID?,
        notes: String?,
        visibility: String?,
    ): UUID = investments.create(
        householdId,
        CreateInvestment(
            typeId = typeId,
            title = title,
            investedAmount = amount,
            quantity = quantity,
            unit = unit,
            startDate = startDate,
            maturityDate = maturityDate,
            institutionId = institutionId,
            notes = notes,
            visibility = visibility,
        ),
    ).id

    /**
     * Guesses the mapping from header names, so the common case is one glance
     * and a click rather than eight dropdowns.
     */
    private fun suggestMapping(headers: List<String>): ColumnMapping {
        fun find(vararg needles: String): String? = headers.firstOrNull { header ->
            val normalised = header.lowercase().replace(Regex("[^a-z0-9]"), "")
            needles.any { normalised == it || normalised.contains(it) }
        }
        return ColumnMapping(
            title = find("name", "title", "description", "particulars", "scheme", "instrument"),
            investedAmount = find("amount", "invested", "value", "principal", "cost"),
            quantity = find("quantity", "qty", "units", "weight", "grams"),
            unit = find("unit"),
            startDate = find("date", "purchasedate", "startdate", "investedon", "opened"),
            maturityDate = find("maturity", "maturitydate", "duedate"),
            institution = find("bank", "institution", "amc", "broker", "insurer", "company"),
            notes = find("notes", "remarks", "comment"),
            reference = find("folio", "policy", "receipt", "reference", "accountno", "certificate"),
            type = find("type", "category", "assettype"),
        )
    }
}

/**
 * Turns spreadsheet text into values.
 *
 * Everything here is about tolerating what real exports contain — currency
 * symbols, Indian grouping, four different date formats — without ever guessing
 * at something unreadable. A null is a row the person gets told about; a wrong
 * value is one they never find.
 */
object Coerce {

    fun money(text: String): BigDecimal? {
        val cleaned = text.replace(Regex("""[₹$,\s]"""), "")
            .replace("(", "-").replace(")", "")   // accounting negatives
        if (cleaned.isEmpty()) return null

        val suffix = Regex("""(?i)(cr|crore|l|lac|lakh|k)$""").find(cleaned)?.value?.lowercase()
        val number = (if (suffix != null) cleaned.dropLast(suffix.length) else cleaned)
            .toBigDecimalOrNull() ?: return null

        return when {
            suffix == null -> number
            suffix.startsWith("cr") -> number.multiply(BigDecimal(10_000_000))
            suffix.startsWith("l") -> number.multiply(BigDecimal(100_000))
            else -> number.multiply(BigDecimal(1_000))
        }
    }

    fun number(text: String): BigDecimal? =
        text.replace(Regex("""[^\d.\-]"""), "").takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()

    /**
     * `uuuu` rather than `yyyy`, and STRICT resolution.
     *
     * The default SMART resolver quietly repairs impossible dates: "31/02/2024"
     * parses as 29 February. That is the worst possible outcome here — a date
     * that never existed, imported without a murmur, and nobody ever finds it.
     * STRICT refuses, the row is reported, and a person decides. (STRICT needs
     * `uuuu`; `yyyy` is year-of-era and requires an era it will not get.)
     */
    private val DATE_FORMATS: List<DateTimeFormatter> = listOf(
        "uuuu-MM-dd", "dd/MM/uuuu", "dd-MM-uuuu", "d/M/uuuu", "d-M-uuuu",
        "dd/MM/uu", "d MMM uuuu", "dd MMM uuuu", "MMM d uuuu", "MMM d, uuuu",
        "dd-MMM-uuuu", "dd.MM.uuuu",
    ).map {
        DateTimeFormatter.ofPattern(it, java.util.Locale.ENGLISH)
            .withResolverStyle(java.time.format.ResolverStyle.STRICT)
    }

    fun date(text: String): LocalDate? {
        val cleaned = text.trim()
        DATE_FORMATS.forEach { format ->
            runCatching { return LocalDate.parse(cleaned, format) }
        }
        return null
    }
}
