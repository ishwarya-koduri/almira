package tech.bhrigu.almira.lifecycle

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import jakarta.servlet.http.HttpServletResponse
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.auth.StepUpService
import tech.bhrigu.almira.common.IndianNumbers
import tech.bhrigu.almira.crypto.EnvelopeCipher
import tech.bhrigu.almira.document.DocumentStorage
import tech.bhrigu.almira.security.RequestUserContext
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.LocalDate
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * "Download everything" (docs/05 §8, §12): one zip with a readable PDF, the same
 * data as CSV and as JSON, and the documents themselves.
 *
 * **What "everything" means.** Everything the person could open in the app, in
 * every household they belong to — which is what they own and what has been
 * shared with them. Every query runs on the runtime connection with their
 * identity, so row-level security decides the contents exactly as it decides a
 * screen; there is no export-only filter to get wrong (docs/05 §3.6).
 *
 * **What is deliberately not in it.**
 *  - Full account numbers. The server keeps them encrypted and shows one at a
 *    time, audited, after a step-up; a zip in a downloads folder holding every
 *    one of them is a worse place than where they are. The last four digits are
 *    in it, and the README says how to see a full number.
 *  - The plaintext of sealed fields. The server has never had it (docs/12). The
 *    ciphertext is in it, with the wrapped key and the parameters needed to open
 *    it on a device with the passphrase — and the README says that, too.
 *
 * **Why a step-up.** It is the largest single disclosure the app makes, and
 * mass export is on the list of things docs/05 §9 watches for. It is audited
 * with counts, never contents.
 */
@Service
class ExportEverythingService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val stepUp: StepUpService,
    private val audit: AuditService,
    private val cipher: EnvelopeCipher,
    private val storage: DocumentStorage,
    private val userContext: RequestUserContext,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val readOnly = TransactionTemplate(transactionManager).apply { isReadOnly = true }
    private val writing = TransactionTemplate(transactionManager)
    private val json = ObjectMapper().findAndRegisterModules()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    /** Checked before a byte of the response is written, so a refusal is a proper error. */
    fun requireAllowed() {
        stepUp.requireElevated(userContext.require(), userContext.currentSessionId())
    }

    fun write(response: HttpServletResponse) {
        val userId = userContext.require()
        requireAllowed()
        val stamp = LocalDate.now()
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"almira-everything-$stamp.zip\"")
        response.contentType = "application/zip"

        // Written straight to the response, inside one read-only transaction so
        // every query sees the same moment. Documents are read one at a time and
        // never all held in memory.
        val counts = readOnly.execute {
            val zip = ZipOutputStream(response.outputStream)
            writeZip(zip, userId, stamp).also { zip.finish(); zip.flush() }
        }
        writing.executeWithoutResult {
            audit.record(
                householdId = null, actorUserId = userId, action = "account.export_everything",
                entityType = "user", entityId = userId, diff = counts,
            )
        }
    }

    private fun writeZip(zip: ZipOutputStream, userId: UUID, stamp: LocalDate): Map<String, Any?> {
        val profile = rows(
            """
            select id, phone, email, full_name, locale, currency_pref, default_visibility, created_at, last_login_at
              from users where id = :uid
            """.trimIndent(),
            mapOf("uid" to userId),
        )
        val sessions = rows(
            """
            select device_name, created_at, last_used_at, expires_at, revoked_at
              from user_sessions where user_id = :uid order by created_at
            """.trimIndent(),
            mapOf("uid" to userId),
        )
        val activity = rows(
            """
            select created_at, household_id, action, entity_type, entity_id, diff
              from activity_log where actor_user_id = :uid order by created_at
            """.trimIndent(),
            mapOf("uid" to userId),
        )

        val households = rows(
            """
            select h.id, h.name, h.base_currency, h.default_visibility, h.created_at, hm.role as my_role
              from households h
              join household_memberships hm on hm.household_id = h.id and hm.user_id = :uid and hm.status = 'active'
             where h.deleted_at is null
             order by h.created_at
            """.trimIndent(),
            mapOf("uid" to userId),
        )

        val exported = mutableListOf<Map<String, Any?>>()
        val pdfSections = mutableListOf<HouseholdSummary>()
        var documentFiles = 0
        households.forEachIndexed { index, household ->
            val hid = household["id"] as UUID
            val folder = "${index + 1}-${slug(household["name"] as String)}"
            val tables = linkedMapOf<String, List<Map<String, Any?>>>()
            HOUSEHOLD_TABLES.forEach { (name, sql) -> tables[name] = rows(sql, mapOf("hid" to hid, "uid" to userId)) }
            tables.forEach { (name, data) -> entry(zip, "csv/$folder/$name.csv", csv(data)) }

            tables.getValue("documents").forEach { document ->
                val id = document["id"] as UUID
                val key = document["storage_key"] as String?
                if (key == null) return@forEach
                runCatching { cipher.decryptBytes(hid, "documents.content", storage.get(key)) }
                    .onSuccess {
                        entry(zip, "documents/$folder/$id-${safeName(document["file_name"] as String)}", it)
                        documentFiles++
                    }
                    .onFailure { log.warn("export: a document could not be read: {}", it.javaClass.simpleName) }
            }
            exported += linkedMapOf(
                "household" to household,
                "tables" to tables.mapValues { (_, data) -> data.map { it - "storage_key" } },
            )
            pdfSections += HouseholdSummary(household, tables)
        }

        entry(zip, "csv/profile.csv", csv(profile))
        entry(zip, "csv/signed-in-devices.csv", csv(sessions))
        entry(zip, "csv/your-activity.csv", csv(activity))
        entry(
            zip, "almira-everything.json",
            json.writeValueAsBytes(
                linkedMapOf(
                    "format" to "almira-everything",
                    "version" to 1,
                    "exportedOn" to stamp.toString(),
                    "notes" to NOTES,
                    "profile" to profile.firstOrNull(),
                    "signedInDevices" to sessions,
                    "yourActivity" to activity,
                    "households" to exported,
                ),
            ),
        )
        entry(zip, "almira-everything.pdf", pdf(profile.firstOrNull(), pdfSections, stamp))
        entry(zip, "README.txt", readme(stamp).toByteArray(Charsets.UTF_8))

        return mapOf(
            "households" to households.size,
            "documents" to documentFiles,
            "records" to pdfSections.sumOf { section -> RECORD_TABLES.sumOf { section.tables[it]?.size ?: 0 } },
        )
    }

    // --- data -----------------------------------------------------------------

    private fun rows(sql: String, params: Map<String, Any?>): List<Map<String, Any?>> =
        jdbc.queryForList(sql, params).map { row ->
            row.mapValuesTo(LinkedHashMap()) { (_, value) ->
                when (value) {
                    is Timestamp -> value.toInstant().toString()
                    is java.sql.Date -> value.toLocalDate().toString()
                    is java.sql.Array -> (value.array as Array<*>).toList()
                    is ByteArray -> null
                    // jsonb arrives as the driver's PGobject, which is only on the
                    // runtime classpath; its string form is the JSON text.
                    else -> if (value?.javaClass?.name == "org.postgresql.util.PGobject") {
                        value.toString().let { text -> runCatching { json.readTree(text) }.getOrDefault(text) }
                    } else {
                        value
                    }
                }
            }
        }

    private fun csv(data: List<Map<String, Any?>>): ByteArray {
        val columns = data.firstOrNull()?.keys?.filter { it != "storage_key" } ?: emptyList()
        val out = StringBuilder()
        // A byte-order mark, so a spreadsheet opens Telugu and Hindi names as
        // themselves rather than as mojibake.
        out.append('﻿')
        out.append(columns.joinToString(",") { quote(it) }).append("\r\n")
        data.forEach { row ->
            out.append(columns.joinToString(",") { quote(cell(row[it])) }).append("\r\n")
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    private fun cell(value: Any?): String = when (value) {
        null -> ""
        is BigDecimal -> value.stripTrailingZeros().toPlainString()
        is String -> value
        is Number, is Boolean, is UUID -> value.toString()
        else -> json.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(value)
    }

    /**
     * Quoted always. A leading =, +, - or @ is prefixed with an apostrophe so a
     * spreadsheet shows a title rather than running it as a formula.
     */
    private fun quote(value: String): String {
        val safe = if (value.isNotEmpty() && value[0] in "=+-@") "'$value" else value
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }

    private fun entry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun slug(value: String) =
        value.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "household" }

    private fun safeName(value: String) =
        value.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(120).ifEmpty { "document" }

    // --- the readable part ----------------------------------------------------

    private data class HouseholdSummary(val household: Map<String, Any?>, val tables: Map<String, List<Map<String, Any?>>>)

    private fun pdf(profile: Map<String, Any?>?, sections: List<HouseholdSummary>, stamp: LocalDate): ByteArray {
        val lines = mutableListOf<Pair<String, Boolean>>()
        fun line(text: String = "", strong: Boolean = false) = wrap(text).forEach { lines += it to strong }

        line("Everything in Almira", true)
        line("Prepared on $stamp for ${profile?.get("full_name") ?: profile?.get("phone") ?: profile?.get("email") ?: "you"}.")
        line("It shows what you could see in the app on that day, in every household you belong to.")
        line("Amounts are in INR unless a row says otherwise.")
        line()

        sections.forEach { section ->
            val t = section.tables
            line("${section.household["name"]}  (you are ${section.household["my_role"]})", true)
            line("People: " + t.getValue("members").joinToString(", ") { it["display_name"].toString() })
            line()

            val holdings = t.getValue("investments").filter { it["deleted_at"] == null }
            val total = holdings.mapNotNull { it["effective_value"] as? BigDecimal }.fold(BigDecimal.ZERO, BigDecimal::add)
            line("What there is: ${holdings.size} holdings, INR ${IndianNumbers.group(total)}", true)
            line("(${IndianNumbers.words(total)} rupees)")
            holdings.forEach { row ->
                val value = (row["effective_value"] as? BigDecimal)?.let { "INR ${IndianNumbers.group(it)}" } ?: "value not recorded"
                line("  ${row["title"]} - ${row["type_label"]} - $value - ${row["visibility"]}")
            }
            line()

            val debts = t.getValue("liabilities").filter { it["deleted_at"] == null }
            val owed = debts.mapNotNull { it["outstanding"] as? BigDecimal }.fold(BigDecimal.ZERO, BigDecimal::add)
            line("What is owed: ${debts.size}, INR ${IndianNumbers.group(owed)}", true)
            debts.forEach { row ->
                line("  ${row["title"]} - INR ${IndianNumbers.group(row["outstanding"] as BigDecimal)} outstanding")
            }
            line()

            listOf(
                "accounts" to "Accounts (last four digits only)",
                "goals" to "Goals",
                "estate_documents" to "Wills and other instruments",
                "contacts" to "Contacts",
                "documents" to "Documents (the files are in the documents folder)",
            ).forEach { (table, heading) ->
                val data = t.getValue(table)
                if (data.isEmpty()) return@forEach
                line("$heading: ${data.size}", true)
                data.forEach { row ->
                    line("  " + listOf("label", "name", "title", "file_name", "number_masked")
                        .mapNotNull { row[it]?.toString() }.distinct().joinToString(" - "))
                }
                line()
            }
            val sealed = t.getValue("sealed_values").size
            if (sealed > 0) {
                line("Sealed fields: $sealed", true)
                line("Their contents are in almira-everything.json as ciphertext only. See README.txt.")
                line()
            }
        }
        NOTES.forEach { line(it) }

        val document = PDDocument()
        val regular = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        val bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
        var index = 0
        while (index < lines.size || document.numberOfPages == 0) {
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            PDPageContentStream(document, page).use { content ->
                var y = 800f
                while (index < lines.size && y > 50f) {
                    val (text, strong) = lines[index]
                    content.beginText()
                    content.setFont(if (strong) bold else regular, if (strong) 11f else 9.5f)
                    content.newLineAtOffset(40f, y)
                    content.showText(winAnsi(text))
                    content.endText()
                    y -= if (strong) 15f else 12.5f
                    index++
                }
            }
        }
        val out = ByteArrayOutputStream()
        document.save(out)
        document.close()
        return out.toByteArray()
    }

    private fun wrap(text: String, width: Int = 105): List<String> {
        if (text.length <= width) return listOf(text)
        val out = mutableListOf<String>()
        var current = StringBuilder()
        text.split(" ").forEach { word ->
            if (current.length + word.length + 1 > width && current.isNotEmpty()) {
                out += current.toString()
                current = StringBuilder("    ")
            }
            if (current.isNotEmpty() && !current.endsWith(" ")) current.append(' ')
            current.append(word)
        }
        if (current.isNotBlank()) out += current.toString()
        return out
    }

    /** The standard PDF fonts are WinAnsi; the CSV and JSON carry every name exactly. */
    private fun winAnsi(value: String) = buildString {
        value.forEach { append(if (it.code in 32..126 || it.code in 160..255) it else '?') }
    }

    private fun readme(stamp: LocalDate) = buildString {
        appendLine("Almira - everything, as of $stamp")
        appendLine()
        appendLine("almira-everything.pdf   A readable summary, household by household.")
        appendLine("almira-everything.json  All of it, machine-readable, exactly as stored.")
        appendLine("csv/                    The same data, one file per kind of record, per household.")
        appendLine("documents/              The files you could open, decrypted, as you uploaded them.")
        appendLine()
        NOTES.forEach { appendLine("- $it") }
        appendLine()
        appendLine(
            "Names in Telugu, Hindi and other scripts appear as question marks in the PDF, because its " +
                "fonts cannot draw them. They are exact in the CSV and JSON.",
        )
    }

    private companion object {
        val NOTES = listOf(
            "This contains what you could see in Almira: what you hold, and what others shared with you. " +
                "Other people's private records are not in it, as they are not on your screen.",
            "Account numbers are the last four digits only. To see a full number, open the account in " +
                "Almira and confirm it is you.",
            "Sealed fields (zero-knowledge mode) are included as ciphertext, never as plaintext: Almira " +
                "has never been able to read them. The e2e_keys table holds your wrapped key and the " +
                "parameters to derive it from your passphrase; each value's AAD is " +
                "\"householdId|recordType|recordId|fieldKey\" (docs/12).",
        )

        /** Tables whose rows count as records in the audit line. */
        val RECORD_TABLES = listOf("investments", "liabilities", "accounts", "goals", "estate_documents", "contacts", "documents")

        /**
         * One query per kind of record, all with `household_id = :hid`, all under
         * the caller's row-level security. Encrypted columns are left out by
         * name, never filtered afterwards.
         */
        val HOUSEHOLD_TABLES = linkedMapOf(
            "members" to """
                select id, display_name, relationship, date_of_birth, (user_id = :uid) as is_me,
                       (user_id is null) as no_login, created_at
                  from members where household_id = :hid and deleted_at is null order by created_at
            """,
            "investments" to """
                select i.id, i.title, t.code as type_code, t.label as type_label, i.status, i.invested_amount,
                       i.currency, i.quantity, i.unit, i.start_date, i.maturity_date, i.attributes, i.notes,
                       i.visibility, i.is_in_continuity, i.account_id, i.institution_id, inst.name as institution,
                       v.effective_value, v.value_basis, v.valued_on, i.created_at, i.updated_at, i.deleted_at
                  from investments i
                  join investment_types t on t.id = i.type_id
                  left join institutions inst on inst.id = i.institution_id
                  left join investment_value v on v.investment_id = i.id
                 where i.household_id = :hid order by i.created_at
            """,
            "investment_owners" to """
                select o.investment_id, o.member_id, m.display_name, o.holder_type, o.share_pct
                  from investment_ownerships o join investments i on i.id = o.investment_id
                  join members m on m.id = o.member_id
                 where i.household_id = :hid
            """,
            "investment_nominees" to """
                select n.investment_id, n.member_id, coalesce(n.nominee_name, m.display_name) as nominee,
                       n.relationship, n.share_pct
                  from investment_nominees n join investments i on i.id = n.investment_id
                  left join members m on m.id = n.member_id
                 where i.household_id = :hid
            """,
            "valuations" to """
                select v.investment_id, v.as_of_date, v.value, v.quantity, v.source, v.note
                  from valuations v join investments i on i.id = v.investment_id
                 where i.household_id = :hid order by v.investment_id, v.as_of_date
            """,
            "transactions" to """
                select x.id, x.investment_id, x.txn_type, x.amount, x.quantity, x.price, x.txn_date,
                       x.from_account_id, x.notes
                  from transactions x join investments i on i.id = x.investment_id
                 where i.household_id = :hid order by x.investment_id, x.txn_date
            """,
            "liabilities" to """
                select l.id, l.title, l.kind, l.principal, l.outstanding, l.interest_rate, l.emi_amount, l.emi_day,
                       l.start_date, l.end_date, l.status, l.attributes, l.notes, l.visibility, l.is_in_continuity,
                       inst.name as lender, l.created_at, l.deleted_at
                  from liabilities l left join institutions inst on inst.id = l.institution_id
                 where l.household_id = :hid order by l.created_at
            """,
            "liability_holders" to """
                select h.liability_id, h.member_id, m.display_name, h.holder_type, h.responsibility_pct
                  from liability_holders h join liabilities l on l.id = h.liability_id
                  join members m on m.id = h.member_id
                 where l.household_id = :hid
            """,
            "liability_balances" to """
                select b.liability_id, b.as_of_date, b.outstanding, b.source, b.note
                  from liability_balances b join liabilities l on l.id = b.liability_id
                 where l.household_id = :hid order by b.liability_id, b.as_of_date
            """,
            "accounts" to """
                select a.id, a.label, a.account_kind, a.number_masked, a.ifsc, inst.name as institution, a.notes,
                       a.visibility, a.created_at, a.deleted_at
                  from accounts a left join institutions inst on inst.id = a.institution_id
                 where a.household_id = :hid order by a.created_at
            """,
            "account_holders" to """
                select h.account_id, h.member_id, m.display_name, h.holder_type
                  from account_holders h join accounts a on a.id = h.account_id
                  join members m on m.id = h.member_id
                 where a.household_id = :hid
            """,
            "goals" to """
                select id, name, target_amount, target_date, priority, member_id, notes, status, visibility, created_at
                  from goals where household_id = :hid order by created_at
            """,
            "documents" to """
                select id, file_name, mime_type, size_bytes, content_sha256, doc_type, version, expires_on,
                       visibility, notes, created_at, storage_key
                  from documents where household_id = :hid and deleted_at is null order by created_at
            """,
            "document_links" to """
                select dl.document_id, dl.entity_type, dl.entity_id
                  from document_links dl join documents d on d.id = dl.document_id
                 where d.household_id = :hid
            """,
            "estate_documents" to """
                select id, member_id, kind, title, executed_on, registered, status, notes, visibility, created_at
                  from estate_documents where household_id = :hid and deleted_at is null order by created_at
            """,
            "contacts" to """
                select id, kind, name, organisation, phone, email, address, notes, visibility, created_at
                  from contacts where household_id = :hid and deleted_at is null order by created_at
            """,
            "reminders" to """
                select id, investment_id, liability_id, kind, title, due_date, recurrence, amount, status, note
                  from reminders where household_id = :hid and deleted_at is null order by due_date
            """,
            "emergency_contacts" to """
                select member_id, trusted_member_id, wait_days, note, created_at
                  from emergency_contacts where household_id = :hid
            """,
            "sealed_values" to """
                select record_type, record_id, field_key, ciphertext, algorithm, key_version, created_at
                  from sealed_values where household_id = :hid order by record_type, record_id, field_key
            """,
            "e2e_keys" to """
                select kdf, kdf_salt, iterations, wrap_algorithm, wrapped_key, key_version, verifier
                  from e2e_keys where household_id = :hid and user_id = :uid
            """,
        ).mapValues { (_, sql) -> sql.trimIndent() }
    }
}

@RestController
@RequestMapping("/api/v1/me/export")
class ExportEverythingController(private val service: ExportEverythingService) {

    /**
     * One zip: a readable PDF, CSV and JSON of everything you can see, and the
     * documents. Needs a recent step-up on this session (403 step_up_required).
     */
    @GetMapping(produces = ["application/zip"])
    fun downloadEverything(response: HttpServletResponse) {
        service.requireAllowed()
        service.write(response)
    }
}
