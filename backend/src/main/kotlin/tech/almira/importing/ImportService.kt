package tech.almira.importing

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import tech.almira.audit.AuditService
import tech.almira.catalog.CatalogService
import tech.almira.common.ApiException
import tech.almira.common.IndianNumbers
import tech.almira.household.HouseholdService
import tech.almira.investment.CreateInvestment
import tech.almira.investment.InvestmentFilter
import tech.almira.investment.InvestmentService
import tech.almira.investment.OwnerInput
import tech.almira.investment.ValuationInput
import tech.almira.security.RequestUserContext
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
    /**
     * Whose it is: a member's name as the household knows them, or their id.
     * Unmapped or blank means the person importing, as capture does. A name
     * that matches nobody fails that row rather than guessing — a consolidated
     * statement for three people must not land as one person's.
     */
    val owner: String? = null,
    /**
     * What it is worth now, as a statement says — recorded as a valuation dated
     * today, never as the amount invested, so returns are not computed from a
     * market value mistaken for cost.
     */
    val currentValue: String? = null,
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
    /**
     * On a dry run, how many rows would land. Zero on a real run, where
     * [imported] is the count that matters — reporting the would-be number as
     * "imported" would have a preview claim it had saved things.
     */
    val wouldImport: Int,
    val duplicates: Int,
    val failed: Int,
    val rows: List<RowOutcome>,
    val note: String,
)

/**
 * Labels this product used to seed, and what they are called now (V149, which
 * took the em dashes out of the words a family reads). A sheet exported before
 * that still says the old name, and a year-old spreadsheet is exactly the thing
 * somebody imports.
 *
 * Tried only after the code column and the current label, so the file's own
 * `code` always wins and a household that has named a type of its own is never
 * overruled by this list. Keyed in lower case; the match ignores case either way.
 */
private val LEGACY_TYPE_LABELS = mapOf(
    "mutual fund — sip" to "Mutual Fund (SIP)",
    "mutual fund — lumpsum" to "Mutual Fund (Lumpsum)",
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
    private val transactions: TransactionTemplate,
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
        val members = if (request.mapping.owner != null) households.members(householdId) else emptyList()
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
                types.firstOrNull { it.code.equals(text, true) }
                    ?: types.firstOrNull { it.label.equals(text, true) }
                    ?: types.firstOrNull { it.label.equals(LEGACY_TYPE_LABELS[text.trim().lowercase()], true) }
            } ?: defaultType

            if (type == null) {
                outcomes += RowOutcome(
                    rowNumber, "failed", title, null,
                    "We don't recognise the type “${cell(request.mapping.type)}”.",
                )
                return@forEachIndexed
            }

            // A cell that is present but unreadable is not a failure — the row
            // still belongs in the registry — but it must not vanish silently
            // either. "not a number" leaves the amount empty and says so, here
            // and in the preview, rather than being discovered months later as a
            // holding worth nothing.
            val unreadable = mutableListOf<String>()
            fun <T> read(column: String?, label: String, parse: (String) -> T?): T? {
                val raw = cell(column) ?: return null
                return parse(raw) ?: null.also { unreadable += "$label “$raw”" }
            }

            val amount = read(request.mapping.investedAmount, "Amount", Coerce::money)
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

            val ownerCell = cell(request.mapping.owner)
            val ownerId = ownerCell?.let { text ->
                members.firstOrNull { it.id.toString().equals(text, true) }?.id
                    ?: members.filter { it.displayName.trim().equals(text, true) }
                        .singleOrNull()?.id
            }
            if (ownerCell != null && ownerId == null) {
                outcomes += RowOutcome(
                    rowNumber, "failed", title, null,
                    "We don't know who “$ownerCell” is. Use a name from your household.",
                )
                return@forEachIndexed
            }

            val quantity = read(request.mapping.quantity, "Quantity", Coerce::number)
            val currentValue = read(request.mapping.currentValue, "Value", Coerce::money)
            val startDate = read(request.mapping.startDate, "Start date", Coerce::date)
            val maturityDate = read(request.mapping.maturityDate, "Maturity date", Coerce::date)

            val warning = unreadable.takeIf { it.isNotEmpty() }
                ?.joinToString(", ", prefix = "Couldn't read ", postfix = ", left empty.")

            if (request.dryRun) {
                outcomes += RowOutcome(
                    rowNumber, "would-import", title, null,
                    listOfNotNull(
                        amount?.let { "as ${IndianNumbers.rupees(it)}" },
                        currentValue?.let { "worth ${IndianNumbers.rupees(it)}" },
                        warning,
                    )
                        .joinToString(". ").ifEmpty { null },
                )
                return@forEachIndexed
            }

            val save = { value: BigDecimal? ->
                importRow(
                    householdId, type.id, title, amount,
                    quantity = quantity,
                    unit = cell(request.mapping.unit),
                    startDate = startDate,
                    maturityDate = maturityDate,
                    institutionId = cell(request.mapping.institution)?.let { name ->
                        institutions.firstOrNull { it.name.equals(name, true) }?.id
                            ?: institutions.firstOrNull { it.name.startsWith(name, true) }?.id
                    },
                    notes = cell(request.mapping.notes),
                    visibility = request.visibility,
                    ownerId = ownerId,
                    currentValue = value,
                    reference = reference?.let { text ->
                        val keys = type.schema.fields.map { it.key }
                        REFERENCE_KEYS.firstOrNull { it in keys }?.let { it to text }
                    },
                )
            }

            try {
                var valueDropped = false
                val created = try {
                    save(currentValue)
                } catch (e: Exception) {
                    // A holding saved as private for someone else cannot be read
                    // back by the person importing it, and today that also stops
                    // them giving it a first value (known-issues 61). The holding
                    // still belongs in the registry: save it without the value,
                    // and say so, rather than lose the row. A refusal of the row
                    // itself (a bad type, a bad owner) is not retried.
                    val refusedForAccess = e !is ApiException || e.status == HttpStatus.FORBIDDEN
                    if (currentValue == null || !refusedForAccess) throw e
                    valueDropped = true
                    save(null)
                }
                val rowWarning = listOfNotNull(
                    warning,
                    "Saved without its value: a private holding for someone else can't be given one by you yet."
                        .takeIf { valueDropped },
                ).joinToString(" ").ifEmpty { null }
                outcomes += RowOutcome(rowNumber, "imported", title, created, rowWarning)
            } catch (e: ApiException) {
                outcomes += RowOutcome(rowNumber, "failed", title, null, e.message)
            } catch (e: Exception) {
                outcomes += RowOutcome(rowNumber, "failed", title, null, "Couldn't save this row.")
            }
        }

        if (!request.dryRun) {
            // In a transaction of its own: the import runs outside one (each row
            // has its own), and the database only knows who is writing inside
            // one, so without this the entry was refused and never written.
            transactions.executeWithoutResult {
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
        }

        val imported = outcomes.count { it.outcome == "imported" }
        val duplicates = outcomes.count { it.outcome == "duplicate" }
        val failed = outcomes.count { it.outcome == "failed" }

        return ImportReport(
            dryRun = request.dryRun,
            total = sheet.rows.size,
            imported = imported,
            wouldImport = outcomes.count { it.outcome == "would-import" },
            duplicates = duplicates,
            failed = failed,
            rows = outcomes,
            note = when {
                request.dryRun -> {
                    val warned = outcomes.count { it.outcome == "would-import" && it.message?.contains("Couldn't read") == true }
                    "Nothing saved yet. ${outcomes.count { it.outcome == "would-import" }} rows " +
                        "would be added" +
                        (if (duplicates > 0) ", $duplicates are already here" else "") +
                        (if (failed > 0) ", $failed need a look" else "") + "." +
                        (if (warned > 0) " $warned ${if (warned == 1) "row has a cell" else "rows have cells"} we couldn't read." else "")
                }
                failed == 0 && duplicates == 0 -> "Imported all $imported."
                else ->
                    "Imported $imported." +
                        (if (duplicates > 0) " $duplicates were already here." else "") +
                        (if (failed > 0) " $failed couldn't be read. The rows are listed below." else "")
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
        ownerId: UUID? = null,
        reference: Pair<String, String>? = null,
        currentValue: BigDecimal? = null,
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
            owners = listOfNotNull(ownerId?.let { OwnerInput(it) }),
            // Kept where the type keeps it, so the next run of the same file
            // finds this row by its number instead of adding it again.
            attributes = reference?.let { mapOf(it.first to it.second) } ?: emptyMap(),
            initialValuation = currentValue?.let { ValuationInput(value = it, quantity = quantity) },
            // A migration is capture. Twenty FDs without their interest rate
            // should become twenty records that say so, not nothing at all —
            // the completeness report is where a gap belongs, not the door.
            allowMissingRequired = true,
        ),
    ).id

    /**
     * Guesses the mapping from header names, so the common case is one glance
     * and a click rather than eight dropdowns.
     */
    private fun suggestMapping(headers: List<String>): ColumnMapping {
        fun find(vararg needles: String, except: String? = null): String? = headers.firstOrNull { header ->
            if (header == except) return@firstOrNull false
            val normalised = header.lowercase().replace(Regex("[^a-z0-9]"), "")
            needles.any { normalised == it || normalised.contains(it) }
        }
        val currentValue = find("currentvalue", "marketvalue", "worthnow", "valuation")
        return ColumnMapping(
            title = find("name", "title", "description", "particulars", "scheme", "instrument"),
            // "Market value" is what it is worth, not what went in.
            investedAmount = find("amount", "invested", "value", "principal", "cost", except = currentValue),
            quantity = find("quantity", "qty", "units", "weight", "grams"),
            unit = find("unit"),
            startDate = find("date", "purchasedate", "startdate", "investedon", "opened"),
            maturityDate = find("maturity", "maturitydate", "duedate"),
            institution = find("bank", "institution", "amc", "broker", "insurer", "company"),
            notes = find("notes", "remarks", "comment"),
            reference = find("folio", "policy", "receipt", "reference", "accountno", "certificate"),
            type = find("type", "category", "assettype"),
            owner = find("owner", "person", "holder", "investor", "member"),
            currentValue = currentValue,
        )
    }

    private companion object {
        /** Where a type keeps the number a row is recognised by, in order of preference. */
        val REFERENCE_KEYS = listOf("folio_no", "policy_no", "receipt_no", "certificate_no")
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
