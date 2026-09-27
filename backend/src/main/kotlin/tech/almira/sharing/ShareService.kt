package tech.almira.sharing

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import tech.almira.audit.AuditService
import tech.almira.common.ApiException
import tech.almira.common.RateLimit
import tech.almira.config.AlmiraProperties
import tech.almira.continuity.FamilyHandbook
import tech.almira.continuity.HandbookService
import tech.almira.household.HouseholdService
import tech.almira.security.JwtService
import tech.almira.security.RequestUserContext
import tech.almira.reports.Export
import tech.almira.reports.ExportService
import tech.almira.tax.FinancialYear
import tech.almira.tax.TaxExportService
import tech.almira.tax.TaxPack
import tech.almira.tax.TaxService
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

data class CreateShare(
    val label: String,
    /** tax_pack, handbook, or an explicit list of records. */
    val scope: String,
    /** The financial year, for a tax pack. */
    val financialYear: String? = null,
    /**
     * Whose tax pack, for a tax pack. A return has one taxpayer, so a link for a
     * CA usually names one; omitted, the pack is the household view.
     */
    val memberId: UUID? = null,
    val investmentIds: List<UUID> = emptyList(),
    val recipientHint: String? = null,
    val note: String? = null,
    val includeDocuments: Boolean = false,
    val expiresInDays: Int = 7,
    val maxViews: Int? = null,
)

data class ShareRow(
    val id: UUID,
    val label: String,
    val scope: String,
    val scopeDetail: String?,
    val recipientHint: String?,
    val note: String?,
    val includeDocuments: Boolean,
    val expiresAt: Instant,
    val revokedAt: Instant?,
    val maxViews: Int?,
    val viewCount: Int,
    val itemCount: Int,
    /**
     * What is actually inside, in a sentence. The family handbook deliberately
     * includes records that are private to their owner — privacy is for life,
     * continuity is for after — and someone sending that link to their CA
     * should be told so before they send it, not after.
     */
    val scopeNote: String? = null,
    val createdAt: Instant,
    /** Returned exactly once, when the link is made. Never stored. */
    val url: String? = null,
    /** For a tax pack: whose. Null is the household view. */
    val memberId: UUID? = null,
    /**
     * For a tax pack: the CA-ready PDF behind the same token, which a browser
     * opens directly. Returned exactly once, like [url].
     */
    val downloadUrl: String? = null,
)

/** A link just made. [url] is the only time the token exists outside the person's hands. */
data class IssuedLink(val shareId: UUID, val url: String)

data class ShareViewRow(val viewedAt: Instant, val userAgent: String?)

/** What a guest actually sees. One of these is populated, never both. */
data class GuestPayload(
    val label: String,
    val scope: String,
    val householdName: String,
    val sharedBy: String,
    val expiresAt: Instant,
    val note: String?,
    val taxPack: TaxPack? = null,
    val handbook: FamilyHandbook? = null,
    val records: List<GuestRecord> = emptyList(),
    val notice: String = NOTICE,
)

data class GuestRecord(
    val title: String,
    val typeLabel: String,
    val institutionName: String?,
    val value: String?,
    val valueBasis: String,
    val reference: String?,
)

private const val NOTICE =
    "A read-only view of one slice of someone's records, shared deliberately and " +
        "for a limited time. It shows nothing else, and it cannot be changed from here."

/**
 * Scoped, time-boxed, revocable guest links (docs/05 §7).
 *
 * Two properties make this safe, and both are structural rather than careful:
 *
 * 1. **The scope is materialised when the link is made**, by reading under the
 *    sharer's own row-level security. A link can therefore never contain
 *    something the sharer could not see — and it cannot quietly widen later as
 *    the household adds records.
 * 2. **Opening a link runs inside a guest session**, where every read policy
 *    additionally requires the row to be in that link's list. The endpoint
 *    builds one specific payload, and the database refuses anything else even
 *    if the endpoint asks. A bug here is a bug that returns less.
 *
 * The token is stored only as a hash: a link reconstructable from a database
 * dump is a password in plaintext.
 */
@Service
class ShareService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
    private val exports: ExportService,
    private val tax: TaxService,
    private val handbook: HandbookService,
    private val audit: AuditService,
    private val jwt: JwtService,
    private val userContext: RequestUserContext,
    private val transactions: TransactionTemplate,
    redis: StringRedisTemplate,
    props: AlmiraProperties,
) {

    private val random = SecureRandom()

    /** The product's one rate limiter, the same one sign-in counts with. */
    private val limits = RateLimit(redis)
    private val caps = props.share

    /** The guest's own transaction: read-only, and enforced as such by Postgres. */
    private val readOnly = TransactionTemplate(transactions.transactionManager!!).apply {
        isReadOnly = true
    }

    /**
     * [maxDays] is ninety for every link a person makes by hand. The envelope
     * edition of the handbook (continuity/HandbookEnvelope.kt) asks for longer,
     * and withdraws its previous link when it does; [wholeHandbook] makes that
     * link carry the debts, paperwork and people the printed pages carry, not
     * only the holdings.
     */
    @Transactional
    fun create(
        householdId: UUID,
        input: CreateShare,
        baseUrl: String,
        maxDays: Int = MAX_DAYS,
        wholeHandbook: Boolean = false,
    ): ShareRow {
        val userId = userContext.require()
        households.get(householdId)
        if (input.label.isBlank()) {
            throw ApiException.badRequest("label_required", "Name the link, so you know what you sent.")
        }
        if (input.scope !in SCOPES) {
            throw ApiException.badRequest(
                "scope_invalid", "A link shares a tax pack, the family handbook, or specific records.",
            )
        }
        if (input.memberId != null) {
            if (input.scope != "tax_pack") {
                throw ApiException.badRequest(
                    "member_not_applicable", "Only a tax pack is shared for one person.",
                )
            }
            if (households.members(householdId).none { it.id == input.memberId }) {
                throw ApiException.notFound("We couldn't find that person.")
            }
        }
        if (input.scope == "tax_pack") FinancialYear.parse(input.financialYear)
        if (input.expiresInDays !in 1..maxDays) {
            throw ApiException.badRequest(
                "expiry_invalid", "A link lasts between a day and ninety days.",
            )
        }
        // Beside the expiry check, and for the same reason. A limit of zero (or
        // below) was accepted and minted a link that every open answered 404 —
        // so the person sent their accountant a link that was dead before it
        // arrived, and neither of them was told why.
        if (input.maxViews != null && input.maxViews < 1) {
            throw ApiException.badRequest(
                "view_limit_invalid", "A link opens at least once.",
            )
        }

        // What the link would share, decided before a token exists or a link
        // row is written. Under the sharer's own RLS either way, and in the
        // same transaction; it needs nothing from the row.
        val items = resolveScope(householdId, input, wholeHandbook)
        if (items.isEmpty()) {
            throw ApiException.badRequest(
                "scope_empty",
                "There's nothing to share in that slice. Check the year, or pick some records.",
            )
        }

        val expiresAt = Instant.now().plus(input.expiresInDays.toLong(), ChronoUnit.DAYS)
        val minted = mint(
            householdId = householdId,
            userId = userId,
            label = input.label.trim(),
            scope = input.scope,
            scopeDetail = input.financialYear,
            items = items,
            expiresAt = expiresAt,
            maxViews = input.maxViews,
            recipientHint = input.recipientHint,
            note = input.note,
            includeDocuments = input.includeDocuments,
            memberId = input.memberId,
        )
        val id = minted.shareId
        val token = minted.token

        return get(householdId, id).copy(
            url = "$baseUrl/share/$token",
            downloadUrl = if (input.scope == "tax_pack") "$baseUrl/api/v1/share/$token/tax-pack.pdf" else null,
            scopeNote = describe(householdId, items),
        )
    }

    /** A row and its token, the one time the token exists outside the person's hands. */
    private data class Minted(val shareId: UUID, val token: String)

    /**
     * The row, its items and its audit line — the only place a link is written.
     *
     * Extracted so an emailed export is the same link as any other rather than
     * a second kind that has to be kept in step. Everything a link is made of
     * is decided by the caller; nothing here knows why it was asked for.
     */
    private fun mint(
        householdId: UUID,
        userId: UUID,
        label: String,
        scope: String,
        scopeDetail: String?,
        items: List<Pair<String, UUID>>,
        expiresAt: Instant,
        maxViews: Int?,
        recipientHint: String? = null,
        note: String? = null,
        includeDocuments: Boolean = false,
        memberId: UUID? = null,
    ): Minted {
        val id = UUID.randomUUID()
        val token = newToken()

        jdbc.update(
            """
            insert into guest_shares (id, household_id, label, scope, scope_detail, token_hash,
                                      recipient_hint, note, include_documents, expires_at,
                                      max_views, created_by, scope_member_id)
            values (:id, :hid, :label, :scope, :detail, :hash, :recipient, :note,
                    :includeDocuments, :expiresAt, :maxViews, :createdBy, :member)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id).addValue("hid", householdId).addValue("label", label)
                .addValue("scope", scope).addValue("detail", scopeDetail)
                .addValue("hash", jwt.hash(token)).addValue("recipient", recipientHint)
                .addValue("note", note).addValue("includeDocuments", includeDocuments)
                .addValue("expiresAt", java.sql.Timestamp.from(expiresAt))
                .addValue("maxViews", maxViews).addValue("createdBy", userId)
                .addValue("member", memberId),
        )

        items.forEach { (type, recordId) ->
            jdbc.update(
                """
                insert into guest_share_items (share_id, record_type, record_id)
                values (:sid, :type, :rid) on conflict do nothing
                """.trimIndent(),
                mapOf("sid" to id, "type" to type, "rid" to recordId),
            )
        }

        audit.record(
            householdId = householdId, actorUserId = userId, action = "share.create",
            entityType = "guest_share", entityId = id,
            diff = mapOf("scope" to scope, "items" to items.size),
        )
        return Minted(id, token)
    }

    /** A link to an export, and how long it lasts. The token is in [url] and nowhere else. */
    data class ExportLink(
        val shareId: UUID,
        val url: String,
        val expiresAt: Instant,
        val opensAllowed: Int,
        val records: Int,
    )

    /**
     * A link to this household's holdings, for emailing.
     *
     * An ordinary guest share with an ordinary token, expiry, view limit, view
     * log and revocation — the same row, the same checks, the same screen in
     * the app. Only two things are particular to it: the scope is `export`, so
     * [openExportFile] knows there is a file behind it, and `scope_detail`
     * carries the format.
     *
     * Its items are the holdings the sharer can see *now*, which is what makes
     * the link a snapshot: the guest clamp (V20) admits only the records named
     * here, so a holding added tomorrow is not in a link sent today. Nothing
     * had to be written to make that true.
     *
     * Not reachable from the shares endpoint — `export` is not in [SCOPES] —
     * because a link with a file behind it is minted by the thing that emails
     * it, which is where the address is proved and the sending is counted.
     */
    @Transactional
    fun createExportLink(
        householdId: UUID,
        format: String,
        ttl: java.time.Duration,
        maxOpens: Int,
        baseUrl: String,
    ): ExportLink {
        val userId = userContext.require()
        households.get(householdId)
        val items = jdbc.query(
            "select id from investments where household_id = :hid and deleted_at is null",
            mapOf("hid" to householdId),
        ) { rs, _ -> "investment" to rs.getObject("id", UUID::class.java) }
        if (items.isEmpty()) {
            throw ApiException.badRequest(
                "nothing_to_export",
                "There's nothing recorded in this household to send yet.",
            )
        }
        val expiresAt = Instant.now().plus(ttl)
        val minted = mint(
            householdId = householdId,
            userId = userId,
            label = "Your export",
            scope = EXPORT_SCOPE,
            scopeDetail = format,
            items = items,
            expiresAt = expiresAt,
            maxViews = maxOpens,
        )
        return ExportLink(
            shareId = minted.shareId,
            url = "$baseUrl/api/v1/share/${minted.token}/export",
            expiresAt = expiresAt,
            opensAllowed = maxOpens,
            records = items.size,
        )
    }

    /**
     * The file behind an emailed export link.
     *
     * Admitted exactly like the link itself — same token check, same expiry,
     * same view count, same audit, same clamped read-only session — and then
     * built by the ordinary [ExportService], which is what makes the file and
     * the download identical rather than merely similar. Any other kind of
     * link has no such file and says so in the words every refusal here uses.
     */
    fun openExportFile(token: String, ipHash: String?, userAgent: String?): Export {
        val share = admit(token, ipHash, userAgent, helperLink = false)
        if (share.share.scope != EXPORT_SCOPE) {
            throw ApiException.notFound("That link doesn't work. It may have expired or been withdrawn.")
        }
        return userContext.runAs(share.createdBy, guestShareId = share.share.id) {
            readOnly.execute { exports.holdings(share.householdId, share.share.scopeDetail ?: "csv") }!!
        }
    }

    /**
     * Counts what is in the link, and says plainly when some of it is private —
     * so the decision to send it is an informed one.
     */
    private fun describe(householdId: UUID, items: List<Pair<String, UUID>>): String {
        val investmentIds = items.filter { it.first == "investment" }.map { it.second }
        if (investmentIds.isEmpty()) return "${items.size} records."

        val private = jdbc.queryForObject(
            """
            select count(*) from investments
            where household_id = :hid and id in (:ids) and visibility <> 'household'
            """.trimIndent(),
            mapOf("hid" to householdId, "ids" to investmentIds),
            Int::class.javaObjectType,
        ) ?: 0

        val documents = items.count { it.first == "document" }
        return buildString {
            append("${investmentIds.size} ${if (investmentIds.size == 1) "record" else "records"}")
            if (documents > 0) append(" and $documents ${if (documents == 1) "document" else "documents"}")
            append(". ")
            if (private > 0) {
                append(
                    "$private of ${if (private == 1) "them is" else "them are"} private to you, " +
                        "whoever opens this link will see ${if (private == 1) "it" else "them"}.",
                )
            } else {
                append("Nothing private to you is included.")
            }
        }
    }

    /**
     * Resolved under the sharer's own RLS — every query here is an ordinary
     * read, so a record they cannot see simply does not come back and cannot
     * enter the link.
     */
    private fun resolveScope(
        householdId: UUID,
        input: CreateShare,
        wholeHandbook: Boolean = false,
    ): List<Pair<String, UUID>> =
        when (input.scope) {
            "tax_pack" -> {
                val pack = tax.pack(householdId, input.memberId, input.financialYear)
                val fromDeductions = pack.deductions.flatMap { meter -> meter.sources.map { it.investmentId } }
                val fromGains = pack.capitalGains.unrealized.map { it.investmentId }
                // A holding sold outright has no unrealised position left, but its
                // sale is the heart of the statement a CA was sent for.
                val fromSales = pack.capitalGainsSchedule.lines.map { it.investmentId }
                val fromInterest = pack.interestIncome.bySource.map { it.investmentId }
                (fromDeductions + fromGains + fromSales + fromInterest).distinct().map { "investment" to it }
            }

            "handbook" -> {
                val book = handbook.build(householdId)
                book.entries.map { "investment" to it.investmentId } +
                    (if (input.includeDocuments) documentsFor(book.entries.map { it.investmentId }) else emptyList()) +
                    (if (wholeHandbook) restOfHandbook(householdId, book) else emptyList())
            }

            else -> {
                val visible = jdbc.query(
                    """
                    select id from investments
                    where household_id = :hid and id in (:ids) and deleted_at is null
                    """.trimIndent(),
                    mapOf("hid" to householdId, "ids" to input.investmentIds.ifEmpty { listOf(UUID.randomUUID()) }),
                ) { rs, _ -> rs.getObject("id", UUID::class.java) }
                visible.map { "investment" to it } +
                    if (input.includeDocuments) documentsFor(visible) else emptyList()
            }
        }

    /** The debts, paperwork and people a printed handbook carries, by id, read under the sharer's RLS. */
    private fun restOfHandbook(householdId: UUID, book: FamilyHandbook): List<Pair<String, UUID>> {
        val params = mapOf("hid" to householdId)
        val debts = jdbc.query(
            "select id from liabilities where household_id = :hid and deleted_at is null and status = 'active'",
            params,
        ) { rs, _ -> "liability" to rs.getObject("id", UUID::class.java) }
        val contacts = jdbc.query(
            "select id from contacts where household_id = :hid and deleted_at is null",
            params,
        ) { rs, _ -> "contact" to rs.getObject("id", UUID::class.java) }
        val instruments = book.instruments.mapNotNull { it.estateDocumentId }.map { "estate_document" to it }
        return debts + contacts + instruments
    }

    private fun documentsFor(investmentIds: List<UUID>): List<Pair<String, UUID>> {
        if (investmentIds.isEmpty()) return emptyList()
        return jdbc.query(
            """
            select d.id from documents d
            join document_links l on l.document_id = d.id
            where l.entity_type = 'investment' and l.entity_id in (:ids) and d.deleted_at is null
            """.trimIndent(),
            mapOf("ids" to investmentIds),
        ) { rs, _ -> "document" to rs.getObject("id", UUID::class.java) }
    }

    @Transactional(readOnly = true)
    fun list(householdId: UUID): List<ShareRow> {
        households.get(householdId)
        return jdbc.query(
            """
            select s.*, (select count(*) from guest_share_items i where i.share_id = s.id) as item_count
            from guest_shares s
            where s.household_id = :hid
            order by s.created_at desc
            """.trimIndent(),
            mapOf("hid" to householdId),
        ) { rs, _ -> mapShare(rs) }
    }

    @Transactional(readOnly = true)
    fun get(householdId: UUID, id: UUID): ShareRow {
        households.get(householdId)
        return jdbc.query(
            """
            select s.*, (select count(*) from guest_share_items i where i.share_id = s.id) as item_count
            from guest_shares s where s.household_id = :hid and s.id = :id
            """.trimIndent(),
            mapOf("hid" to householdId, "id" to id),
        ) { rs, _ -> mapShare(rs) }.firstOrNull() ?: throw ApiException.notFound()
    }

    /**
     * The view log, most recent first, a page at a time.
     *
     * It used to be a bare `limit 100` with no way to ask for the rest, so a
     * link that had been opened two hundred times showed its owner a hundred
     * and said nothing about the other hundred — an audit trail that stops
     * short without saying so is worse than one that admits its edge, and
     * docs/05 §7 promises these links are fully audited.
     *
     * [limit] and [offset], rather than a cursor, because that is what this
     * codebase's other paged list already takes (InvestmentController.list) and
     * because it is additive: the frozen contract gains two optional query
     * parameters, the response keeps its shape, and a v1 client that sends
     * neither still gets exactly the hundred most recent rows it got before.
     *
     * Ordered by `id` after the timestamp: two views in the same millisecond
     * would otherwise be free to swap places between pages, which is how a
     * paged log quietly shows a row twice and drops another.
     */
    @Transactional(readOnly = true)
    fun views(householdId: UUID, id: UUID, limit: Int = MAX_VIEW_PAGE, offset: Int = 0): List<ShareViewRow> {
        get(householdId, id)
        return jdbc.query(
            """
            select viewed_at, user_agent from guest_share_views
            where share_id = :id order by viewed_at desc, id desc
            limit :limit offset :offset
            """.trimIndent(),
            mapOf("id" to id, "limit" to limit.coerceIn(1, MAX_VIEW_PAGE), "offset" to offset.coerceAtLeast(0)),
        ) { rs, _ -> ShareViewRow(rs.getTimestamp("viewed_at").toInstant(), rs.getString("user_agent")) }
    }

    /** Revocation is immediate: the next open fails, mid-session or not. */
    @Transactional
    fun revoke(householdId: UUID, id: UUID) {
        val userId = userContext.require()
        get(householdId, id)
        jdbc.update(
            "update guest_shares set revoked_at = now() where id = :id and revoked_at is null",
            mapOf("id" to id),
        )
        audit.record(
            householdId = householdId, actorUserId = userId, action = "share.revoke",
            entityType = "guest_share", entityId = id,
        )
    }

    // --- opening a link -------------------------------------------------------

    /**
     * Runs outside the caller's identity entirely: there is no signed-in user
     * here, so the identity is assumed from the share and immediately clamped
     * to the share's own scope by [RequestUserContext.runAs].
     */
    fun open(token: String, ipHash: String?, userAgent: String?): GuestPayload {
        val share = admit(token, ipHash, userAgent, helperLink = false)
        return userContext.runAs(share.createdBy, guestShareId = share.share.id) {
            readOnly.execute { payloadFor(share) }!!
        }
    }

    /** What a link that has been let in is: whose, in which household, and until when. */
    data class AdmittedLink(
        val shareId: UUID,
        val householdId: UUID,
        val householdName: String,
        val createdBy: UUID,
        val sharedBy: String,
        val label: String,
        val expiresAt: Instant,
    )

    /**
     * Opens a helper's link (V90, continuity/HeirMode.kt): the same checks, the
     * same view count and audit as any link, and nothing else. The caller runs
     * its read inside the guest session for [AdmittedLink.shareId].
     */
    fun admitHelperLink(token: String, ipHash: String?, userAgent: String?): AdmittedLink =
        admit(token, ipHash, userAgent, helperLink = true).let {
            AdmittedLink(
                shareId = it.share.id, householdId = it.householdId, householdName = it.householdName,
                createdBy = it.createdBy, sharedBy = it.sharedBy, label = it.share.label,
                expiresAt = it.share.expiresAt,
            )
        }

    /**
     * How fast a link may be opened (docs/05 §7, which has always described
     * these links as rate-limited).
     *
     * The token is the whole credential here: there is no password and no
     * account to lock, so a token that leaks — a forwarded email, a browser
     * history on a shared machine — used to be readable as fast as a script
     * could ask. Two caps, both counted by the one rate limiter sign-in uses:
     * one that follows the LINK, which is what bounds a leaked token however
     * many machines are holding it, and one that follows the NETWORK, which
     * bounds a host holding several and an endpoint being hammered with
     * nonsense. Sizes and their reasons: AlmiraProperties.Share.
     *
     * The network's cap is spent first, for the reason sign-in spends it first:
     * an open its network is going to refuse must not also use up one of that
     * link's own hourly opens.
     *
     * [tokenHash], never the token: this counter is keyed by exactly what the
     * database is keyed by, so a Redis dump is no more useful than the token
     * table. And the refusal is the same sentence whatever was presented — a
     * live link, a withdrawn one, a string of nonsense — because a 429 that
     * read differently for a real token would answer the one question the
     * 404s are careful never to answer.
     */
    private fun rateLimit(tokenHash: String, ipHash: String?) {
        ipHash?.let {
            limits.take("share:opens:network:$it", caps.maxOpensPerNetworkPerHour, WINDOW, TOO_FAST)
        }
        limits.take("share:opens:link:$tokenHash", caps.maxOpensPerLinkPerHour, WINDOW, TOO_FAST)
    }

    /**
     * A helper's link and a page link are two different doors. A helper's token
     * opens only its task list, and any other token opens nothing there — so a
     * link sent to an aunt for one task cannot be replayed at the ordinary
     * guest endpoint to read the records behind it.
     */
    private fun admit(token: String, ipHash: String?, userAgent: String?, helperLink: Boolean): ShareLookup {
        val tokenHash = jwt.hash(token)

        // Before the link is resolved, before a view is counted, before an
        // audit row is written and before any payload is built: the cap is on
        // OPENING, so it has to be spent before opening does anything.
        rateLimit(tokenHash, ipHash)

        // Nobody is signed in here, so this one lookup runs with definer rights,
        // keyed by the token hash alone. Everything after it runs as the sharer,
        // clamped to the share's own scope.
        val share = transactions.execute {
            jdbc.query(
                "select * from app.resolve_guest_share(:hash)",
                mapOf("hash" to tokenHash),
            ) { rs, _ ->
                ShareLookup(
                    share = mapShare(rs),
                    householdId = rs.getObject("household_id", UUID::class.java),
                    householdName = rs.getString("household_name"),
                    createdBy = rs.getObject("created_by", UUID::class.java),
                    sharedBy = rs.getString("shared_by"),
                )
            }.firstOrNull()
        } ?: throw ApiException.notFound("That link doesn't work. It may have expired or been withdrawn.")

        // Every failure says the same thing, on purpose: an expired link and a
        // revoked one must not be distinguishable from outside.
        val expired = share.share.expiresAt.isBefore(Instant.now())
        val revoked = share.share.revokedAt != null
        val usedUp = share.share.maxViews?.let { share.share.viewCount >= it } == true
        if (expired || revoked || usedUp) {
            throw ApiException.notFound("That link doesn't work. It may have expired or been withdrawn.")
        }
        if ((share.share.scope == HELPER_SCOPE) != helperLink) {
            throw ApiException.notFound("That link doesn't work. It may have expired or been withdrawn.")
        }

        // Accounting first, in its own transaction and outside the guest scope:
        // a guest transaction is read-only, and counting the view is not part of
        // what the guest is allowed to reach anyway.
        // As the sharer, because a guest has no identity of their own and an
        // audit row attributed to nobody is worse than none — the log has to say
        // whose link was opened. Not clamped to the share, because the write is
        // the app's own bookkeeping rather than anything the guest reaches.
        //
        // The check above is on a copy read a moment ago. The count re-checks
        // expiry, revocation and the limit as it writes (V36) and counts nothing
        // when the link no longer allows the view — a concurrent open that used
        // the last view, or a revocation since — and that is refused here,
        // before the audit row and before any payload is built.
        userContext.runAs(share.createdBy) {
            transactions.execute {
                jdbc.queryForObject(
                    "select app.record_guest_view(:id, :ip, :ua)",
                    mapOf("id" to share.share.id, "ip" to ipHash, "ua" to userAgent?.take(300)),
                    Int::class.javaObjectType,
                ) ?: throw ApiException.notFound("That link doesn't work. It may have expired or been withdrawn.")
                audit.record(
                    householdId = share.householdId, actorUserId = share.createdBy,
                    action = "share.view", entityType = "guest_share", entityId = share.share.id,
                )
            }
        }

        // The name the household knows them by, before the account's — which
        // is often unset, and "Shared by Someone" tells a family nothing.
        val known = userContext.runAs(share.createdBy) {
            transactions.execute {
                jdbc.query(
                    "select display_name from members where household_id = :hid and user_id = :uid",
                    mapOf("hid" to share.householdId, "uid" to share.createdBy),
                ) { rs, _ -> rs.getString("display_name") }.firstOrNull()
            }
        }
        return if (known != null) share.copy(sharedBy = known) else share
    }

    /**
     * A helper's link: no records until tasks are handed over, then exactly the
     * records of those tasks ([replaceItems]). Made by the person holding an
     * open emergency window, and ending when it does (V90).
     */
    @Transactional
    fun issueHelperLink(householdId: UUID, label: String, expiresAt: Instant, baseUrl: String): IssuedLink {
        val userId = userContext.require()
        val id = UUID.randomUUID()
        val token = newToken()
        jdbc.update(
            """
            insert into guest_shares (id, household_id, label, scope, token_hash, expires_at, created_by)
            values (:id, :hid, :label, :scope, :hash, :expiresAt, :createdBy)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id).addValue("hid", householdId).addValue("label", label)
                .addValue("scope", HELPER_SCOPE).addValue("hash", jwt.hash(token))
                .addValue("expiresAt", java.sql.Timestamp.from(expiresAt))
                .addValue("createdBy", userId),
        )
        audit.record(
            householdId = householdId, actorUserId = userId, action = "share.create",
            entityType = "guest_share", entityId = id, diff = mapOf("scope" to HELPER_SCOPE),
        )
        return IssuedLink(id, "$baseUrl/help/$token")
    }

    /** Sets what a link names, exactly. Only ever narrower than what its maker can see. */
    @Transactional
    fun replaceItems(shareId: UUID, items: List<Pair<String, UUID>>) {
        jdbc.update("delete from guest_share_items where share_id = :sid", mapOf("sid" to shareId))
        items.distinct().forEach { (type, recordId) ->
            jdbc.update(
                """
                insert into guest_share_items (share_id, record_type, record_id)
                values (:sid, :type, :rid) on conflict do nothing
                """.trimIndent(),
                mapOf("sid" to shareId, "type" to type, "rid" to recordId),
            )
        }
    }

    /** Withdraws links the caller made, by id, without a household lookup each. */
    @Transactional
    fun withdraw(householdId: UUID, shareIds: Collection<UUID>) {
        if (shareIds.isEmpty()) return
        val userId = userContext.require()
        val withdrawn = jdbc.query(
            """
            update guest_shares set revoked_at = now()
            where household_id = :hid and id in (:ids) and revoked_at is null
            returning id
            """.trimIndent(),
            mapOf("hid" to householdId, "ids" to shareIds),
        ) { rs, _ -> rs.getObject("id", UUID::class.java) }
        withdrawn.forEach {
            audit.record(
                householdId = householdId, actorUserId = userId, action = "share.revoke",
                entityType = "guest_share", entityId = it,
            )
        }
    }

    private fun payloadFor(lookup: ShareLookup): GuestPayload {
        val share = lookup.share
        val base = GuestPayload(
            label = share.label,
            scope = share.scope,
            householdName = lookup.householdName,
            sharedBy = lookup.sharedBy,
            expiresAt = share.expiresAt,
            note = share.note,
        )
        return when (share.scope) {
            "tax_pack" -> base.copy(
                taxPack = tax.pack(lookup.householdId, scopeMember(share.id), share.scopeDetail),
            )
            "handbook" -> base.copy(handbook = handbook.build(lookup.householdId))
            else -> base.copy(records = records(lookup.householdId))
        }
    }

    /** Whose pack a tax-pack link names. Read as the sharer, whose own row it is. */
    private fun scopeMember(shareId: UUID): UUID? = jdbc.query(
        "select scope_member_id from guest_shares where id = :id",
        mapOf("id" to shareId),
    ) { rs, _ -> rs.getObject("scope_member_id", UUID::class.java) }.firstOrNull()

    private fun records(householdId: UUID): List<GuestRecord> = jdbc.query(
        """
        select i.title, t.label as type_label, i.attributes,
               coalesce(inst.name, acct_inst.name) as institution_name,
               v.effective_value, coalesce(v.value_basis, 'unknown') as value_basis
        from investments i
        join investment_types t on t.id = i.type_id
        left join investment_value v on v.investment_id = i.id
        left join institutions inst on inst.id = i.institution_id
        left join accounts acct on acct.id = i.account_id
        left join institutions acct_inst on acct_inst.id = acct.institution_id
        where i.household_id = :hid and i.deleted_at is null
        order by lower(i.title)
        """.trimIndent(),
        mapOf("hid" to householdId),
    ) { rs, _ ->
        GuestRecord(
            title = rs.getString("title"),
            typeLabel = rs.getString("type_label"),
            institutionName = rs.getString("institution_name"),
            value = rs.getBigDecimal("effective_value")
                ?.let { tech.almira.common.IndianNumbers.rupees(it) },
            valueBasis = rs.getString("value_basis"),
            reference = null,
        )
    }

    // --- helpers --------------------------------------------------------------

    private data class ShareLookup(
        val share: ShareRow,
        val householdId: UUID,
        val householdName: String,
        val createdBy: UUID,
        val sharedBy: String,
    )

    /**
     * The CA-ready PDF or the Schedule 112A CSV behind a tax-pack link.
     *
     * Opened exactly like the link itself — same token check, same view count,
     * same audit, same clamped read-only session — and then rendered from the
     * payload that session produced, so the file cannot hold more than the page
     * would. Any other kind of link has no such file, and says "not found".
     */
    fun openTaxPackFile(token: String, kind: String, ipHash: String?, userAgent: String?): Export {
        val payload = open(token, ipHash, userAgent)
        val pack = payload.taxPack
            ?: throw ApiException.notFound("That link doesn't work. It may have expired or been withdrawn.")
        return when (kind) {
            "pdf" -> TaxExportService.pdfOf(pack, payload.householdName)
            else -> TaxExportService.csvOf(pack)
        }
    }

    private fun mapShare(rs: java.sql.ResultSet) = ShareRow(
        id = rs.getObject("id", UUID::class.java),
        label = rs.getString("label"),
        scope = rs.getString("scope"),
        scopeDetail = rs.getString("scope_detail"),
        recipientHint = rs.getString("recipient_hint"),
        note = rs.getString("note"),
        includeDocuments = rs.getBoolean("include_documents"),
        expiresAt = rs.getTimestamp("expires_at").toInstant(),
        revokedAt = rs.getTimestamp("revoked_at")?.toInstant(),
        maxViews = rs.getInt("max_views").takeIf { !rs.wasNull() },
        viewCount = rs.getInt("view_count"),
        itemCount = rs.getInt("item_count"),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        memberId = optionalUuid(rs, "scope_member_id"),
    )

    /** app.resolve_guest_share predates the column, so its rows do not carry it. */
    private fun optionalUuid(rs: java.sql.ResultSet, column: String): UUID? {
        val meta = rs.metaData
        for (i in 1..meta.columnCount) {
            if (meta.getColumnLabel(i).equals(column, ignoreCase = true)) {
                return rs.getObject(i, UUID::class.java)
            }
        }
        return null
    }

    /** 256 bits, URL-safe. Long enough that guessing is not a strategy. */
    private fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        /** What the shares endpoint will mint. Internal so a test can hold the
         *  database's constraint to it: see ShareScopesMatchTheDatabaseTest. */
        internal val SCOPES = setOf("tax_pack", "handbook", "records")
        const val MAX_DAYS = 90
        const val HELPER_SCOPE = "heir_help"

        /** An emailed export's link. Minted by ExportByEmailService, not by the shares endpoint. */
        const val EXPORT_SCOPE = "export"

        /** The same fixed hour sign-in counts in. */
        private val WINDOW: Duration = Duration.ofHours(1)

        /** One sentence for every refusal, so the refusal says nothing about the token. */
        private const val TOO_FAST = "This link is being opened too often. Please try again later."

        /** The most recent views one request may ask for. */
        const val MAX_VIEW_PAGE = 100
    }
}
