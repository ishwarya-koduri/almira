package tech.bhrigu.almira.sharing

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.continuity.FamilyHandbook
import tech.bhrigu.almira.continuity.HandbookService
import tech.bhrigu.almira.household.HouseholdService
import tech.bhrigu.almira.security.JwtService
import tech.bhrigu.almira.security.RequestUserContext
import tech.bhrigu.almira.tax.TaxPack
import tech.bhrigu.almira.tax.TaxService
import java.security.SecureRandom
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
    val createdAt: Instant,
    /** Returned exactly once, when the link is made. Never stored. */
    val url: String? = null,
)

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
    private val tax: TaxService,
    private val handbook: HandbookService,
    private val audit: AuditService,
    private val jwt: JwtService,
    private val userContext: RequestUserContext,
    private val transactions: TransactionTemplate,
) {

    private val random = SecureRandom()

    /** The guest's own transaction: read-only, and enforced as such by Postgres. */
    private val readOnly = TransactionTemplate(transactions.transactionManager!!).apply {
        isReadOnly = true
    }

    @Transactional
    fun create(householdId: UUID, input: CreateShare, baseUrl: String): ShareRow {
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
        if (input.expiresInDays !in 1..90) {
            throw ApiException.badRequest(
                "expiry_invalid", "A link lasts between a day and ninety days.",
            )
        }

        val id = UUID.randomUUID()
        val token = newToken()
        val expiresAt = Instant.now().plus(input.expiresInDays.toLong(), ChronoUnit.DAYS)

        jdbc.update(
            """
            insert into guest_shares (id, household_id, label, scope, scope_detail, token_hash,
                                      recipient_hint, note, include_documents, expires_at,
                                      max_views, created_by)
            values (:id, :hid, :label, :scope, :detail, :hash, :recipient, :note,
                    :includeDocuments, :expiresAt, :maxViews, :createdBy)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id).addValue("hid", householdId).addValue("label", input.label.trim())
                .addValue("scope", input.scope).addValue("detail", input.financialYear)
                .addValue("hash", jwt.hash(token)).addValue("recipient", input.recipientHint)
                .addValue("note", input.note).addValue("includeDocuments", input.includeDocuments)
                .addValue("expiresAt", java.sql.Timestamp.from(expiresAt))
                .addValue("maxViews", input.maxViews).addValue("createdBy", userId),
        )

        val items = resolveScope(householdId, input)
        if (items.isEmpty()) {
            throw ApiException.badRequest(
                "scope_empty",
                "There's nothing to share in that slice — check the year, or pick some records.",
            )
        }
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
            diff = mapOf("scope" to input.scope, "items" to items.size, "days" to input.expiresInDays),
        )

        return get(householdId, id).copy(url = "$baseUrl/share/$token")
    }

    /**
     * Resolved under the sharer's own RLS — every query here is an ordinary
     * read, so a record they cannot see simply does not come back and cannot
     * enter the link.
     */
    private fun resolveScope(householdId: UUID, input: CreateShare): List<Pair<String, UUID>> =
        when (input.scope) {
            "tax_pack" -> {
                val pack = tax.pack(householdId, null, input.financialYear)
                val fromDeductions = pack.deductions.flatMap { meter -> meter.sources.map { it.investmentId } }
                val fromGains = pack.capitalGains.unrealized.map { it.investmentId }
                val fromInterest = pack.interestIncome.bySource.map { it.investmentId }
                (fromDeductions + fromGains + fromInterest).distinct().map { "investment" to it }
            }

            "handbook" -> {
                val book = handbook.build(householdId)
                book.entries.map { "investment" to it.investmentId } +
                    if (input.includeDocuments) documentsFor(book.entries.map { it.investmentId }) else emptyList()
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

    @Transactional(readOnly = true)
    fun views(householdId: UUID, id: UUID): List<ShareViewRow> {
        get(householdId, id)
        return jdbc.query(
            """
            select viewed_at, user_agent from guest_share_views
            where share_id = :id order by viewed_at desc limit 100
            """.trimIndent(),
            mapOf("id" to id),
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
        // Nobody is signed in here, so this one lookup runs with definer rights,
        // keyed by the token hash alone. Everything after it runs as the sharer,
        // clamped to the share's own scope.
        val share = transactions.execute {
            jdbc.query(
                "select * from app.resolve_guest_share(:hash)",
                mapOf("hash" to jwt.hash(token)),
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

        // Accounting first, in its own transaction and outside the guest scope:
        // a guest transaction is read-only, and counting the view is not part of
        // what the guest is allowed to reach anyway.
        // As the sharer, because a guest has no identity of their own and an
        // audit row attributed to nobody is worse than none — the log has to say
        // whose link was opened. Not clamped to the share, because the write is
        // the app's own bookkeeping rather than anything the guest reaches.
        userContext.runAs(share.createdBy) {
            transactions.execute {
                jdbc.queryForObject(
                    "select app.record_guest_view(:id, :ip, :ua)",
                    mapOf("id" to share.share.id, "ip" to ipHash, "ua" to userAgent?.take(300)),
                    Int::class.javaObjectType,
                )
                audit.record(
                    householdId = share.householdId, actorUserId = share.createdBy,
                    action = "share.view", entityType = "guest_share", entityId = share.share.id,
                )
            }
        }

        return userContext.runAs(share.createdBy, guestShareId = share.share.id) {
            readOnly.execute { payloadFor(share) }!!
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
            "tax_pack" -> base.copy(taxPack = tax.pack(lookup.householdId, null, share.scopeDetail))
            "handbook" -> base.copy(handbook = handbook.build(lookup.householdId))
            else -> base.copy(records = records(lookup.householdId))
        }
    }

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
                ?.let { tech.bhrigu.almira.common.IndianNumbers.rupees(it) },
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
    )

    /** 256 bits, URL-safe. Long enough that guessing is not a strategy. */
    private fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        val SCOPES = setOf("tax_pack", "handbook", "records")
    }
}
