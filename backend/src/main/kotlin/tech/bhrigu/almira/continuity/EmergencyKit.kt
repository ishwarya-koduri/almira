package tech.bhrigu.almira.continuity

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDDocumentInformation
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.household.HouseholdService
import tech.bhrigu.almira.security.RequestUserContext
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import java.util.UUID

data class KitPerson(val name: String, val role: String, val phone: String?)

data class EmergencyKit(
    val householdName: String,
    val preparedFor: String,
    val preparedOn: LocalDate,
    /** The people who can ask for emergency access to the preparer's records. */
    val trusted: List<KitPerson>,
    /** Executors named on the preparer's executed instruments. */
    val executors: List<String>,
    /** Everyone else worth ringing: the CA, the lawyer, the agent. */
    val whoToCall: List<KitPerson>,
    val steps: List<String>,
    /** Where the QR code points: the family plan, behind the reader's own sign-in. */
    val appUrl: String,
    val keepItNote: String = KEEP_IT,
    val holdsNoSecrets: String = NO_SECRETS,
)

private const val KEEP_IT = "Keep this in the almirah."
private const val NO_SECRETS =
    "This card holds no passwords and opens nothing by itself. Anyone who finds it still has to sign in " +
        "as themselves and wait."

/**
 * The emergency kit: one printed page that works as a key (X-61).
 *
 * The handbook says what exists. This says what to do first — who to ring, who
 * can ask for access, and how — on a single sheet that fits in the almirah with
 * the property papers. Its QR code is the address of the family plan and
 * nothing else: no token, no link that opens records, so a card left on a
 * table is an inconvenience rather than a breach. The access itself still runs
 * through the delay and the veto (docs/05 §6).
 *
 * Built under the caller's own row-level security like the handbook.
 */
@Service
class EmergencyKitService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
    private val emergency: EmergencyService,
    private val audit: AuditService,
    private val userContext: RequestUserContext,
) {

    @Transactional(readOnly = true)
    fun build(householdId: UUID, baseUrl: String): EmergencyKit {
        val household = households.get(householdId)
        val me = households.members(householdId).firstOrNull { it.isMe }
        val myName = me?.displayName ?: "me"

        val trusted = emergency.listTrustedContacts(householdId)
            .filter { !it.theyTrustMe }
            .map {
                KitPerson(
                    name = it.trustedMemberName ?: "Someone in the household",
                    role = "Can ask for emergency access · ${it.waitDays} ${if (it.waitDays == 1) "day" else "days"} to say no",
                    phone = null,
                )
            }

        val executors = if (me == null) emptyList() else jdbc.query(
            """
            select distinct coalesce(rm.display_name, rc.name, r.person_name) as name
            from estate_documents e
            join estate_roles r on r.estate_document_id = e.id
                 and r.role in ('executor','alternate_executor')
            left join members rm on rm.id = r.member_id
            left join contacts rc on rc.id = r.contact_id
            where e.household_id = :hid and e.member_id = :me
              and e.deleted_at is null and e.status = 'executed'
            order by 1
            """.trimIndent(),
            mapOf("hid" to householdId, "me" to me.id),
        ) { rs, _ -> rs.getString("name") }.filterNotNull()

        val whoToCall = jdbc.query(
            """
            select name, kind, organisation, phone from contacts
            where household_id = :hid and deleted_at is null
            order by case kind when 'lawyer' then 0 when 'executor' then 1 when 'ca' then 2
                               when 'doctor' then 3 when 'agent' then 4 when 'banker' then 5 else 6 end,
                     lower(name)
            limit 8
            """.trimIndent(),
            mapOf("hid" to householdId),
        ) { rs, _ ->
            KitPerson(
                name = rs.getString("name"),
                role = listOfNotNull(kindWord(rs.getString("kind")), rs.getString("organisation")).joinToString(" · "),
                phone = rs.getString("phone"),
            )
        }

        val appUrl = baseUrl.trimEnd('/') + "/#/continuity"
        val firstTrusted = trusted.firstOrNull()?.name
        return EmergencyKit(
            householdName = household.name,
            preparedFor = myName,
            preparedOn = LocalDate.now(),
            trusted = trusted,
            executors = executors,
            whoToCall = whoToCall,
            steps = listOf(
                "Scan the code, or open ${baseUrl.trimEnd('/')} and sign in as yourself.",
                "Go to Family plan, then Emergency access, and ask.",
                "$myName is told straight away and can say no. " +
                    (if (firstTrusted != null) "Nothing opens for the waiting period." else "Nobody has been named to ask yet."),
                "After that you see what $myName marked for the family, and how to claim each thing.",
            ),
            appUrl = appUrl,
        )
    }

    /** One A4 page, always. Lists that would not fit are cut, and say so. */
    @Transactional
    fun printable(householdId: UUID, baseUrl: String): ByteArray {
        val kit = build(householdId, baseUrl)
        audit.record(
            householdId = householdId, actorUserId = userContext.currentUserId(),
            action = "continuity.emergency_kit.print",
        )
        return EmergencyKitPdf.render(kit)
    }

    private fun kindWord(kind: String?) = when (kind) {
        "ca" -> "Chartered accountant"
        "advisor" -> "Advisor"
        "agent" -> "Insurance agent"
        "lawyer" -> "Lawyer"
        "banker" -> "Banker"
        "broker" -> "Broker"
        "doctor" -> "Doctor"
        "executor" -> "Executor"
        "witness" -> "Witness"
        else -> null
    }
}

/** The page itself. Separate from the service so it can be tested without a database. */
object EmergencyKitPdf {

    private const val MARGIN = 56f
    private val DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)

    fun render(kit: EmergencyKit): ByteArray = PDDocument().use { document ->
        val fonts = PrintFonts.load(document)
        val page = PDPage(PDRectangle.A4)
        document.addPage(page)
        val width = page.mediaBox.width
        val height = page.mediaBox.height
        val column = width - MARGIN * 2

        PDPageContentStream(document, page).use { content ->
            var y = height - MARGIN - 8f

            fonts.text(content, fonts.bold, 10f, Ink.MUTED, MARGIN, y, "EMERGENCY KIT · ${kit.householdName.uppercase()}")
            y -= 34f
            val title = fonts.wrap(fonts.display, 28f, "If something happens to ${kit.preparedFor}", column)
            title.take(2).forEachIndexed { index, line ->
                if (index > 0) y -= 32f
                fonts.text(content, fonts.display, 28f, Ink.INK, MARGIN, y, line)
            }
            y -= 14f
            content.setStrokingColor(Ink.GOLD)
            content.setLineWidth(1.2f)
            content.moveTo(MARGIN, y)
            content.lineTo(MARGIN + 48f, y)
            content.stroke()
            y -= 20f
            fonts.text(content, fonts.body, 10.5f, Ink.MUTED, MARGIN, y, "Prepared ${DATE.format(kit.preparedOn)}")

            // The code, top right of the steps, large enough for an old phone.
            val qrSize = 132f
            val qrTop = y - 18f
            QrCode.draw(content, kit.appUrl, width - MARGIN - qrSize, qrTop - qrSize, qrSize)
            val label = "Opens the family plan"
            val labelX = width - MARGIN - qrSize + (qrSize - fonts.width(fonts.body, 10f, label)) / 2
            fonts.text(content, fonts.body, 10f, Ink.MUTED, labelX, qrTop - qrSize - 14f, label)

            y -= 36f
            y = heading(content, fonts, "What to do first", y)
            val stepsWidth = column - qrSize - 24f
            kit.steps.forEachIndexed { index, step ->
                val lines = fonts.wrap(fonts.body, 12f, step, stepsWidth - 20f)
                fonts.text(content, fonts.display, 12f, Ink.INK, MARGIN, y, "${index + 1}")
                lines.forEach { line ->
                    fonts.text(content, fonts.body, 12f, Ink.INK, MARGIN + 20f, y, line)
                    y -= 17f
                }
                y -= 5f
            }
            y = minOf(y, qrTop - qrSize - 34f)

            y = heading(content, fonts, "Who can ask for access", y)
            y = people(content, fonts, kit.trusted.ifEmpty {
                listOf(KitPerson("Nobody named yet", "Name someone in Almira under Family plan, Emergency access", null))
            }, y, limit = 3)
            if (kit.executors.isNotEmpty()) {
                y -= 4f
                y = heading(content, fonts, "Executor", y)
                fonts.text(content, fonts.body, 12f, Ink.INK, MARGIN, y, kit.executors.joinToString(", "))
                y -= 24f
            }

            y = heading(content, fonts, "Who to call", y)
            val roomFor = ((y - 150f) / 34f).toInt().coerceIn(0, 8)
            y = people(content, fonts, kit.whoToCall.ifEmpty {
                listOf(KitPerson("No one recorded yet", "Add your lawyer or CA in Almira, then print this again", null))
            }, y, limit = roomFor.coerceAtLeast(1))

            // The foot: the two sentences that make this card safe to leave in a drawer.
            val foot = 118f
            content.setStrokingColor(Ink.RULE)
            content.setLineWidth(0.8f)
            content.moveTo(MARGIN, foot)
            content.lineTo(width - MARGIN, foot)
            content.stroke()
            fonts.text(content, fonts.display, 20f, Ink.INK, MARGIN, foot - 32f, kit.keepItNote)
            var noteY = foot - 52f
            fonts.wrap(fonts.body, 10.5f, kit.holdsNoSecrets, column).forEach {
                fonts.text(content, fonts.body, 10.5f, Ink.MUTED, MARGIN, noteY, it)
                noteY -= 14f
            }
            fonts.text(content, fonts.body, 10f, Ink.MUTED, MARGIN, noteY - 4f, kit.appUrl)
        }

        document.documentInformation = PDDocumentInformation().apply {
            title = "Emergency kit"
            subject = "Who to call and how to reach the family plan. Holds no passwords."
            producer = "Almira"
            creationDate = Calendar.getInstance()
        }
        val out = ByteArrayOutputStream()
        document.save(out)
        out.toByteArray()
    }

    private fun heading(content: PDPageContentStream, fonts: PrintFonts, text: String, y: Float): Float {
        fonts.text(content, fonts.bold, 10f, Ink.MUTED, MARGIN, y, text.uppercase())
        return y - 20f
    }

    private fun people(content: PDPageContentStream, fonts: PrintFonts, people: List<KitPerson>, top: Float, limit: Int): Float {
        var y = top
        people.take(limit).forEach { person ->
            val name = person.name + (person.phone?.let { "   $it" } ?: "")
            fonts.text(content, fonts.display, 13f, Ink.INK, MARGIN, y, name)
            y -= 15f
            fonts.text(content, fonts.body, 10.5f, Ink.MUTED, MARGIN, y, person.role)
            y -= 19f
        }
        if (people.size > limit) {
            fonts.text(content, fonts.body, 10.5f, Ink.MUTED, MARGIN, y, "and ${people.size - limit} more in the family handbook")
            y -= 19f
        }
        return y - 6f
    }
}
