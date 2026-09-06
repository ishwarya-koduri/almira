package tech.bhrigu.almira.continuity

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.bhrigu.almira.common.IndianNumbers
import tech.bhrigu.almira.household.HouseholdService
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class HandbookEntry(
    val investmentId: UUID,
    val title: String,
    val typeLabel: String,
    val typeCode: String,
    val categoryCode: String,
    val institutionName: String?,
    val accountLabel: String?,
    val value: BigDecimal?,
    val valueFormatted: String?,
    val valueBasis: String,
    val whereItIsKept: String?,
    val reference: String?,
    val nominees: List<String>,
    val contacts: List<TransmissionContact>,
    val hasProof: Boolean,
    /** One line, so the printed page says what to do rather than only what exists. */
    val howToClaim: String,
)

data class HandbookDebt(
    val title: String,
    val lender: String?,
    val outstanding: BigDecimal?,
    val outstandingFormatted: String?,
    val securedAgainst: List<String>,
    val note: String,
)

data class HandbookInstrument(
    val kind: String,
    val title: String,
    val forMember: String?,
    val location: String?,
    val executedOn: LocalDate?,
    val executors: List<String>,
)

data class FamilyHandbook(
    val householdName: String,
    val preparedOn: LocalDate,
    val preparedFor: String,
    val entries: List<HandbookEntry>,
    val debts: List<HandbookDebt>,
    val instruments: List<HandbookInstrument>,
    val contacts: List<TransmissionContact>,
    val totalIncluded: BigDecimal,
    val totalIncludedFormatted: String,
    val excludedCount: Int,
    val note: String,
    val disclaimer: String = DISCLAIMER,
)

/**
 * "For my family — if I'm unreachable, here's everything." (docs/03 §8)
 *
 * The handbook is built under the caller's own row-level security, which gives
 * it the property that matters: **it can only ever contain what the person
 * holding it may see.** A member printing it gets their own things and whatever
 * the household shares; an emergency unlock widens that to continuity-marked
 * records and no further (V20).
 *
 * `is_in_continuity` is the switch. A record can be private now and still
 * marked for continuity — privacy is for life, continuity is for after
 * (docs/05 §3.4) — and one deliberately excluded is counted but never named,
 * so the family knows the list is not the whole story without learning what is
 * missing.
 */
@Service
class HandbookService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
) {

    @Transactional(readOnly = true)
    fun build(householdId: UUID): FamilyHandbook {
        val household = households.get(householdId)
        val me = households.members(householdId).firstOrNull { it.isMe }

        val entries = jdbc.query(
            """
            select i.id, i.title, i.storage_location, i.attributes,
                   t.code as type_code, t.label as type_label, c.code as category_code,
                   coalesce(inst.name, acct_inst.name) as institution_name,
                   acct.label as account_label,
                   v.effective_value, coalesce(v.value_basis, 'unknown') as value_basis,
                   p.title as playbook_title, p.summary as playbook_summary,
                   p.contact_hint,
                   exists (select 1 from document_links dl
                           where dl.entity_type = 'investment' and dl.entity_id = i.id) as has_proof
            from investments i
            join investment_types t on t.id = i.type_id
            join asset_categories c on c.id = t.category_id
            left join investment_value v on v.investment_id = i.id
            left join institutions inst on inst.id = i.institution_id
            left join accounts acct on acct.id = i.account_id
            left join institutions acct_inst on acct_inst.id = acct.institution_id
            left join lateral (
              select * from transmission_playbooks pb
              where pb.type_code = t.code or pb.category_code = c.code
              order by case when pb.type_code = t.code then 0 else 1 end
              limit 1
            ) p on true
            where i.household_id = :hid
              and i.deleted_at is null
              and i.status in ('active','matured')
              and i.is_in_continuity
              and not exists (select 1 from investments s
                              where s.rolled_from_id = i.id and s.deleted_at is null)
            order by c.sort, lower(i.title)
            """.trimIndent(),
            mapOf("hid" to householdId),
        ) { rs, _ ->
            val id = rs.getObject("id", UUID::class.java)
            HandbookEntry(
                investmentId = id,
                title = rs.getString("title"),
                typeLabel = rs.getString("type_label"),
                typeCode = rs.getString("type_code"),
                categoryCode = rs.getString("category_code"),
                institutionName = rs.getString("institution_name"),
                accountLabel = rs.getString("account_label"),
                value = rs.getBigDecimal("effective_value"),
                valueFormatted = rs.getBigDecimal("effective_value")?.let(IndianNumbers::rupees),
                valueBasis = rs.getString("value_basis"),
                whereItIsKept = rs.getString("storage_location"),
                reference = referenceFrom(rs.getString("attributes")),
                nominees = emptyList(),
                contacts = emptyList(),
                hasProof = rs.getBoolean("has_proof"),
                howToClaim = rs.getString("playbook_summary")
                    ?: "No standard route — start with the institution named here.",
            )
        }

        val withPeople = attachPeople(entries)

        val debts = jdbc.query(
            """
            select l.title, inst.name as lender, lc.outstanding,
                   coalesce(array_agg(i.title) filter (where i.title is not null), '{}') as secured
            from liabilities l
            left join institutions inst on inst.id = l.institution_id
            left join liability_current lc on lc.liability_id = l.id
            left join asset_liability_links al on al.liability_id = l.id
            left join investments i on i.id = al.investment_id and i.deleted_at is null
            where l.household_id = :hid and l.deleted_at is null and l.status = 'active'
            group by l.title, inst.name, lc.outstanding
            order by lower(l.title)
            """.trimIndent(),
            mapOf("hid" to householdId),
        ) { rs, _ ->
            HandbookDebt(
                title = rs.getString("title"),
                lender = rs.getString("lender"),
                outstanding = rs.getBigDecimal("outstanding"),
                outstandingFormatted = rs.getBigDecimal("outstanding")?.let(IndianNumbers::rupees),
                securedAgainst = (rs.getArray("secured").array as Array<*>).map { it.toString() },
                note = "Tell the lender. An EMI does not pause, and many loans carry " +
                    "insurance that may settle the balance.",
            )
        }

        val instruments = jdbc.query(
            """
            select e.kind, e.title, e.location, e.executed_on, m.display_name as member_name,
                   coalesce(array_agg(coalesce(rm.display_name, rc.name, r.person_name))
                            filter (where r.id is not null), '{}') as executors
            from estate_documents e
            left join members m on m.id = e.member_id
            left join estate_roles r on r.estate_document_id = e.id
                 and r.role in ('executor','alternate_executor','attorney')
            left join members rm on rm.id = r.member_id
            left join contacts rc on rc.id = r.contact_id
            where e.household_id = :hid and e.deleted_at is null and e.status = 'executed'
            group by e.kind, e.title, e.location, e.executed_on, m.display_name
            order by e.executed_on desc nulls last
            """.trimIndent(),
            mapOf("hid" to householdId),
        ) { rs, _ ->
            HandbookInstrument(
                kind = rs.getString("kind"),
                title = rs.getString("title"),
                forMember = rs.getString("member_name"),
                location = rs.getString("location"),
                executedOn = rs.getDate("executed_on")?.toLocalDate(),
                executors = (rs.getArray("executors").array as Array<*>).map { it.toString() },
            )
        }

        val contacts = jdbc.query(
            """
            select name, kind, phone, organisation from contacts
            where household_id = :hid and deleted_at is null
            order by lower(name)
            """.trimIndent(),
            mapOf("hid" to householdId),
        ) { rs, _ ->
            TransmissionContact(
                rs.getString("name"), rs.getString("kind"), rs.getString("phone"),
                rs.getString("organisation"),
            )
        }

        // Counted, never named: the family learns the list is not everything,
        // without learning what was left out (docs/05 §3.4).
        val excluded = jdbc.queryForObject(
            """
            select count(*) from investments
            where household_id = :hid and deleted_at is null
              and status in ('active','matured') and not is_in_continuity
            """.trimIndent(),
            mapOf("hid" to householdId),
            Int::class.javaObjectType,
        ) ?: 0

        val total = withPeople.mapNotNull { it.value }.fold(BigDecimal.ZERO, BigDecimal::add)

        return FamilyHandbook(
            householdName = household.name,
            preparedOn = LocalDate.now(),
            preparedFor = me?.displayName ?: "the family",
            entries = withPeople,
            debts = debts,
            instruments = instruments,
            contacts = contacts,
            totalIncluded = total,
            totalIncludedFormatted = IndianNumbers.rupees(total),
            excludedCount = excluded,
            note = if (excluded > 0) {
                "$excluded ${if (excluded == 1) "record is" else "records are"} deliberately " +
                    "left out of this summary."
            } else {
                "Everything recorded is included."
            },
        )
    }

    private fun attachPeople(entries: List<HandbookEntry>): List<HandbookEntry> {
        if (entries.isEmpty()) return entries
        val ids = entries.map { it.investmentId }

        val nominees = jdbc.query(
            """
            select n.investment_id, coalesce(m.display_name, n.nominee_name) as name, n.share_pct
            from investment_nominees n
            left join members m on m.id = n.member_id
            where n.investment_id in (:ids)
            """.trimIndent(),
            mapOf("ids" to ids),
        ) { rs, _ ->
            rs.getObject("investment_id", UUID::class.java) to
                "${rs.getString("name")} (${rs.getBigDecimal("share_pct").stripTrailingZeros().toPlainString()}%)"
        }.groupBy({ it.first }, { it.second })

        val contacts = jdbc.query(
            """
            select l.entity_id, c.name, c.kind, c.phone, l.role
            from contact_links l join contacts c on c.id = l.contact_id
            where l.entity_type = 'investment' and l.entity_id in (:ids) and c.deleted_at is null
            """.trimIndent(),
            mapOf("ids" to ids),
        ) { rs, _ ->
            rs.getObject("entity_id", UUID::class.java) to TransmissionContact(
                rs.getString("name"), rs.getString("kind"), rs.getString("phone"), rs.getString("role"),
            )
        }.groupBy({ it.first }, { it.second })

        return entries.map {
            it.copy(
                nominees = nominees[it.investmentId].orEmpty(),
                contacts = contacts[it.investmentId].orEmpty(),
            )
        }
    }

    /** A folio, policy or receipt number — what makes a holding findable again. */
    private fun referenceFrom(attributesJson: String?): String? {
        if (attributesJson == null) return null
        val keys = listOf("policy_no", "folio_no", "account_no", "receipt_no", "certificate_no", "pran")
        return keys.firstNotNullOfOrNull { key ->
            Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"([^\"]+)\"").find(attributesJson)?.groupValues?.get(1)
        }
    }

    // --- the printable version -------------------------------------------------

    /**
     * Deliberately plain. This is printed, put in a drawer, and read by someone
     * who has never opened the app — under the worst circumstances they will
     * ever read anything. No colour, no branding, one thing per line.
     */
    @Transactional(readOnly = true)
    fun printable(householdId: UUID): ByteArray {
        val handbook = build(householdId)
        val document = PDDocument()
        val body = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        val bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)

        val lines = mutableListOf<Pair<String, Boolean>>()
        fun line(text: String, strong: Boolean = false) { lines += ascii(text) to strong }

        line("For my family", true)
        line("${handbook.householdName} · prepared ${handbook.preparedOn} by ${handbook.preparedFor}")
        line("")
        line(
            "This is what exists, where it is, and who to call. It shows only what the " +
                "person who printed it could see.",
        )
        line(handbook.note)
        line("")

        line("WHAT THERE IS  (${handbook.totalIncludedFormatted} of recorded value)", true)
        handbook.entries.forEach { entry ->
            line("")
            line("${entry.title} — ${entry.typeLabel}", true)
            entry.institutionName?.let { line("  Held at: $it") }
            entry.reference?.let { line("  Reference: $it") }
            entry.valueFormatted?.let { line("  Value: $it (${basisInWords(entry.valueBasis)})") }
            entry.whereItIsKept?.let { line("  Kept at: $it") }
            if (entry.nominees.isNotEmpty()) line("  Nominee: ${entry.nominees.joinToString(", ")}")
            else line("  Nominee: none recorded")
            entry.contacts.forEach { line("  Call: ${it.name}${it.phone?.let { p -> " — $p" } ?: ""}") }
            line("  Proof: ${if (entry.hasProof) "a scan is in the vault" else "no document attached"}")
            line("  To claim: ${entry.howToClaim}")
        }

        if (handbook.debts.isNotEmpty()) {
            line("")
            line("WHAT IS OWED", true)
            handbook.debts.forEach { debt ->
                line("")
                line("${debt.title}${debt.lender?.let { " — $it" } ?: ""}", true)
                debt.outstandingFormatted?.let { line("  Outstanding: $it") }
                if (debt.securedAgainst.isNotEmpty()) {
                    line("  Secured against: ${debt.securedAgainst.joinToString(", ")}")
                }
                line("  ${debt.note}")
            }
        }

        if (handbook.instruments.isNotEmpty()) {
            line("")
            line("THE PAPERWORK", true)
            handbook.instruments.forEach { instrument ->
                line("")
                line("${instrument.title} (${instrument.kind})", true)
                instrument.forMember?.let { line("  For: $it") }
                instrument.location?.let { line("  The original is: $it") }
                if (instrument.executors.isNotEmpty()) {
                    line("  Executor: ${instrument.executors.joinToString(", ")}")
                }
            }
        }

        if (handbook.contacts.isNotEmpty()) {
            line("")
            line("WHO TO CALL", true)
            handbook.contacts.forEach {
                line("  ${it.name} (${it.kind})${it.phone?.let { p -> " — $p" } ?: ""}")
            }
        }

        line("")
        line(handbook.disclaimer)

        var index = 0
        while (index < lines.size) {
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            PDPageContentStream(document, page).use { content ->
                var y = 800f
                while (index < lines.size && y > 50f) {
                    val (text, strong) = lines[index]
                    content.beginText()
                    content.setFont(if (strong) bold else body, if (strong) 11f else 9.5f)
                    content.newLineAtOffset(50f, y)
                    content.showText(text.take(105))
                    content.endText()
                    y -= if (strong) 16f else 13f
                    index++
                }
            }
        }

        val out = ByteArrayOutputStream()
        document.save(out)
        document.close()
        return out.toByteArray()
    }

    private fun basisInWords(basis: String) = when (basis) {
        "valued" -> "last recorded value"
        "at_cost" -> "what was paid"
        "custom_field" -> "from a recorded field"
        else -> "value not recorded"
    }

    /** The Standard-14 fonts are WinAnsi; anything outside it is replaced, never fatal. */
    private fun ascii(value: String) = buildString {
        value.forEach { append(if (it.code in 32..255 || it == '…') it else '?') }
    }
}
