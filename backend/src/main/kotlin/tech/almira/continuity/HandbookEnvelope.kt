package tech.almira.continuity

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDDocumentInformation
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.almira.audit.AuditService
import tech.almira.auth.StepUpService
import tech.almira.common.ApiException
import tech.almira.household.HouseholdService
import tech.almira.measurement.ProductEvent
import tech.almira.measurement.ProductMeasurement
import tech.almira.security.RequestUserContext
import tech.almira.sharing.CreateShare
import tech.almira.sharing.ShareService
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/** One printed edition, and the PDF that is its only copy of the link. */
data class EnvelopeEdition(
    val edition: Int,
    val preparedOn: LocalDate,
    val linkExpiresAt: Instant,
    val fileName: String,
    val bytes: ByteArray,
)

/**
 * The family handbook, printed for an envelope (P-28).
 *
 * Kubera's handover is a spreadsheet behind a link that expires; a family that
 * finds it in a drawer a year later finds nothing. So this edition is built the
 * other way round: **the pages are the handbook**, printed in full, and they
 * work with no app, no link and no account. The QR code on the cover is a
 * convenience on top — a guest link to the online copy (docs/05 §7), scoped
 * when printed, read-only, logged, and withdrawable — for as long as Almira and
 * the link last.
 *
 * Numbered and dated, so two envelopes can be told apart. Printing a new
 * edition withdraws the previous edition's link, so an old envelope stops
 * opening anything once a newer one exists. The link lasts a year, longer than
 * a hand-made link's ninety days, so the step-up asked for here is the same one
 * revealing an account number asks for.
 */
@Service
class HandbookEnvelopeService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
    private val handbook: HandbookService,
    private val shares: ShareService,
    private val stepUp: StepUpService,
    private val audit: AuditService,
    private val measurement: ProductMeasurement,
    private val userContext: RequestUserContext,
) {

    @Transactional
    fun print(householdId: UUID, baseUrl: String): EnvelopeEdition {
        val userId = userContext.require()
        households.get(householdId)
        stepUp.requireElevated(
            userId, userContext.currentSessionId(),
            "Confirm it's you. The envelope's code opens your family handbook for a year.",
        )

        val book = handbook.build(householdId)
        if (book.entries.isEmpty() && book.instruments.isEmpty() && book.debts.isEmpty()) {
            throw ApiException.badRequest(
                "handbook_empty", "There's nothing in the family handbook to print yet.",
            )
        }

        // The last edition's link stops working the moment this one exists.
        val previous = jdbc.query(
            """
            select share_id from handbook_editions
            where household_id = :hid and created_by = :me and share_id is not null
            """.trimIndent(),
            mapOf("hid" to householdId, "me" to userId),
        ) { rs, _ -> rs.getObject("share_id", UUID::class.java) }
        shares.withdraw(householdId, previous)

        val edition = (
            jdbc.queryForObject(
                "select max(edition) from handbook_editions where household_id = :hid and created_by = :me",
                mapOf("hid" to householdId, "me" to userId),
                Int::class.javaObjectType,
            ) ?: 0
            ) + 1
        val today = LocalDate.now(INDIA)

        val link = shares.create(
            householdId,
            CreateShare(
                label = "Family handbook, envelope edition $edition",
                scope = "handbook",
                note = "Printed on ${DATE.format(today)} for an envelope.",
                expiresInDays = LINK_DAYS,
            ),
            baseUrl,
            maxDays = LINK_DAYS,
            wholeHandbook = true,
        )

        jdbc.update(
            """
            insert into handbook_editions (household_id, created_by, edition, share_id, link_expires_at)
            values (:hid, :me, :edition, :share, :expires)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("hid", householdId).addValue("me", userId).addValue("edition", edition)
                .addValue("share", link.id).addValue("expires", java.sql.Timestamp.from(link.expiresAt)),
        )
        audit.record(
            householdId = householdId, actorUserId = userId, action = "continuity.handbook.envelope",
            entityType = "guest_share", entityId = link.id,
            diff = mapOf("edition" to edition, "withdrew" to previous.size),
        )
        measurement.record(ProductEvent.HANDBOOK_PRINTED)

        val cover = EnvelopeCover(
            householdName = book.householdName,
            preparedFor = book.preparedFor,
            edition = edition,
            preparedOn = today,
            linkUrl = link.url ?: error("a new link carries its url"),
            linkExpiresOn = link.expiresAt.atZone(INDIA).toLocalDate(),
        )
        val bytes = PDDocument().use { document ->
            EnvelopeCoverPdf.draw(document, cover)
            handbook.appendPages(document, book)
            document.documentInformation = PDDocumentInformation().apply {
                title = "Family handbook, edition $edition"
                subject = "Printed in full. Works without the app or the link."
                producer = "Almira"
                creationDate = Calendar.getInstance()
            }
            ByteArrayOutputStream().also { document.save(it) }.toByteArray()
        }
        return EnvelopeEdition(
            edition = edition,
            preparedOn = today,
            linkExpiresAt = link.expiresAt,
            fileName = "almira-family-handbook-edition-$edition-$today.pdf",
            bytes = bytes,
        )
    }

    companion object {
        const val LINK_DAYS = 365
        private val INDIA: ZoneId = ZoneId.of("Asia/Kolkata")
        private val DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)
    }
}

data class EnvelopeCover(
    val householdName: String,
    val preparedFor: String,
    val edition: Int,
    val preparedOn: LocalDate,
    val linkUrl: String,
    val linkExpiresOn: LocalDate,
)

/** The cover page: Fraunces, one gold rule, the code, and the sentence that matters. */
object EnvelopeCoverPdf {

    const val WORKS_WITHOUT = "This works even if Almira is gone."

    private const val MARGIN = 64f
    private val DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)

    fun draw(document: PDDocument, cover: EnvelopeCover) {
        val fonts = PrintFonts.load(document)
        val page = PDPage(PDRectangle.A4)
        document.addPage(page)
        val width = page.mediaBox.width
        val height = page.mediaBox.height
        val column = width - MARGIN * 2

        PDPageContentStream(document, page).use { content ->
            var y = height - MARGIN - 40f
            fonts.text(content, fonts.bold, 10f, Ink.MUTED, MARGIN, y, "EDITION ${cover.edition} · ${DATE.format(cover.preparedOn).uppercase()}")
            y -= 70f
            fonts.text(content, fonts.display, 44f, Ink.INK, MARGIN, y, "For my family")
            y -= 22f
            content.setStrokingColor(Ink.GOLD)
            content.setLineWidth(1.5f)
            content.moveTo(MARGIN, y)
            content.lineTo(MARGIN + 64f, y)
            content.stroke()
            y -= 34f
            fonts.wrap(fonts.display, 19f, "${cover.householdName}, prepared by ${cover.preparedFor}", column).forEach {
                fonts.text(content, fonts.display, 19f, Ink.INK, MARGIN, y, it)
                y -= 26f
            }

            y -= 40f
            fonts.text(content, fonts.display, 23f, Ink.INK, MARGIN, y, WORKS_WITHOUT)
            y -= 24f
            listOf(
                "Everything that follows is printed in full: what there is, where it is held, what is owed, " +
                    "the paperwork, and who to call. Nothing on these pages needs the app, the link or an account.",
                "Where the original of something is kept was sealed on a phone, so it is not printed here. " +
                    "Ask the person who prepared this, or the people they told.",
            ).forEach { paragraph ->
                fonts.wrap(fonts.body, 12f, paragraph, column).forEach {
                    fonts.text(content, fonts.body, 12f, Ink.INK, MARGIN, y, it)
                    y -= 17f
                }
                y -= 8f
            }

            val qrSize = 150f
            val qrY = MARGIN + 90f
            QrCode.draw(content, cover.linkUrl, MARGIN - 8f, qrY, qrSize)
            val textX = MARGIN + qrSize + 16f
            var ty = qrY + qrSize - 22f
            fonts.text(content, fonts.bold, 12f, Ink.INK, textX, ty, "The online copy")
            ty -= 18f
            listOf(
                "Scan to open this handbook online, read-only, until ${DATE.format(cover.linkExpiresOn)}.",
                "Every opening is logged. A newer edition turns this code off.",
            ).forEach { paragraph ->
                fonts.wrap(fonts.body, 10.5f, paragraph, width - MARGIN - textX).forEach {
                    fonts.text(content, fonts.body, 10.5f, Ink.MUTED, textX, ty, it)
                    ty -= 15f
                }
                ty -= 4f
            }

            content.setStrokingColor(Ink.RULE)
            content.setLineWidth(0.8f)
            content.moveTo(MARGIN, MARGIN + 50f)
            content.lineTo(width - MARGIN, MARGIN + 50f)
            content.stroke()
            fonts.wrap(
                fonts.body, 10f,
                "Keep this envelope with the property papers. Anyone holding the code can read the handbook " +
                    "until it is turned off, so keep it where you would keep the papers themselves.",
                column,
            ).forEachIndexed { index, line ->
                fonts.text(content, fonts.body, 10f, Ink.MUTED, MARGIN, MARGIN + 30f - index * 14f, line)
            }
        }
    }
}
