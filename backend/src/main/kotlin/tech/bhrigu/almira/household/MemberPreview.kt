package tech.bhrigu.almira.household

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.common.IndianNumbers
import tech.bhrigu.almira.security.RequestUserContext
import java.math.BigDecimal
import java.util.UUID

/** One record in a preview. Only ever a record the viewer can already read. */
data class PreviewRecord(
    /** `investment` or `liability`. */
    val recordType: String,
    val recordId: UUID,
    val title: String,
    /** A holding's category, or a debt's kind. */
    val categoryCode: String,
    val categoryLabel: String,
    val color: String?,
    /** private | household | scoped */
    val visibility: String,
    /** Present only in [MemberPreview.sees], where the other member sees the same figure. */
    val valueFormatted: String? = null,
)

/**
 * "What Ravi sees" (catch-up plan X-56): Home through another member's eyes,
 * built only from what the viewer can see.
 *
 * [sees] is every record the viewer can read that the member would read too.
 * [notInTheirView] is every record the viewer can read that the member would
 * not — the viewer's own private things, visibly absent. What the member sees
 * that the viewer cannot is in neither list, and not counted anywhere: it was
 * never read. So the preview is a lower bound of their view, and says so.
 */
data class MemberPreview(
    val memberId: UUID,
    val displayName: String,
    val canSignIn: Boolean,
    val role: String?,
    val sees: List<PreviewRecord>,
    val notInTheirView: List<PreviewRecord>,
    /** What the records in [sees] add up to, in the household's currency. */
    val sharedAssetsFormatted: String,
    val sharedOwedFormatted: String?,
    val explanation: String,
    val caveats: List<String>,
)

@Service
class MemberPreviewService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
    private val userContext: RequestUserContext,
    private val audit: AuditService,
) {

    /** Not read-only: the audit line is written in the same transaction. */
    @Transactional
    fun preview(householdId: UUID, memberId: UUID): MemberPreview {
        val userId = userContext.require()
        if (userContext.currentGuestShareId() != null) throw ApiException.forbidden()
        val household = households.get(householdId)
        val member = households.members(householdId).firstOrNull { it.id == memberId }
            ?: throw ApiException.notFound("We couldn't find that person.")
        if (member.isMe) {
            throw ApiException.badRequest("preview_is_you", "That's you. Home already shows what you see.")
        }

        // Ordinary sight only, like readiness: an open emergency window is sight
        // handed to the viewer for a while, not something to preview.
        val params = mapOf("hid" to householdId, "mid" to memberId, "base" to household.baseCurrency)
        val holdings = jdbc.query(
            """
            select i.id, i.title, i.visibility, c.code as category_code, c.label as category_label,
                   coalesce(t.color, c.color) as color,
                   case when i.currency = :base then iv.effective_value end as value,
                   app.member_would_see(:mid, 'investment', i.id) as they_see
            from investments i
            join investment_types t on t.id = i.type_id
            join asset_categories c on c.id = t.category_id
            left join investment_value iv on iv.investment_id = i.id
            where i.household_id = :hid
              and i.deleted_at is null
              and i.status in ('active','matured')
              and app.can_read_record(i.household_id, i.visibility, 'investment', i.id, app.owns_investment(i.id))
              and not exists (select 1 from investments s where s.rolled_from_id = i.id and s.deleted_at is null)
            order by lower(i.title), i.id
            """.trimIndent(),
            params,
        ) { rs, _ ->
            Row(
                PreviewRecord(
                    recordType = "investment",
                    recordId = rs.getObject("id", UUID::class.java),
                    title = rs.getString("title"),
                    categoryCode = rs.getString("category_code"),
                    categoryLabel = rs.getString("category_label"),
                    color = rs.getString("color"),
                    visibility = rs.getString("visibility"),
                ),
                rs.getBigDecimal("value"),
                rs.getBoolean("they_see"),
            )
        }
        val debts = jdbc.query(
            """
            select l.id, l.title, l.visibility, l.kind, lc.outstanding,
                   app.member_would_see(:mid, 'liability', l.id) as they_see
            from liabilities l
            left join liability_current lc on lc.liability_id = l.id
            where l.household_id = :hid
              and l.deleted_at is null
              and l.status = 'active'
              and app.can_read_record(l.household_id, l.visibility, 'liability', l.id, app.owes_liability(l.id))
            order by lower(l.title), l.id
            """.trimIndent(),
            params,
        ) { rs, _ ->
            val kind = rs.getString("kind")
            Row(
                PreviewRecord(
                    recordType = "liability",
                    recordId = rs.getObject("id", UUID::class.java),
                    title = rs.getString("title"),
                    categoryCode = kind,
                    categoryLabel = kind.replace('_', ' ').replaceFirstChar { it.uppercase() },
                    color = null,
                    visibility = rs.getString("visibility"),
                ),
                rs.getBigDecimal("outstanding"),
                rs.getBoolean("they_see"),
            )
        }

        val canSignIn = !member.isManaged && member.role != null
        val seenHoldings = holdings.filter { it.theySee }
        val seenDebts = debts.filter { it.theySee }
        val assets = seenHoldings.mapNotNull { it.value }.fold(BigDecimal.ZERO, BigDecimal::add)
        val owed = seenDebts.mapNotNull { it.value }.fold(BigDecimal.ZERO, BigDecimal::add)

        // A preview is a look at someone's sight; it is recorded like a document
        // being opened, with the member it was about and nothing it contained.
        audit.record(householdId, userId, "member.preview", "member", memberId)

        val name = member.displayName
        return MemberPreview(
            memberId = member.id,
            displayName = name,
            canSignIn = canSignIn,
            role = member.role,
            sees = (seenHoldings + seenDebts).map { it.withValue() },
            notInTheirView = (holdings + debts).filterNot { it.theySee }.map { it.record },
            sharedAssetsFormatted = IndianNumbers.rupees(assets),
            sharedOwedFormatted = if (seenDebts.isEmpty()) null else IndianNumbers.rupees(owed),
            explanation = when {
                !canSignIn -> "$name has no sign-in of their own, so $name sees nothing in Almira. " +
                    "Their records are kept by the people who manage them."
                member.role == "advisor" -> "$name is an advisor, and sees only what someone has shared with them by name."
                else -> "Of everything you can see, this is what $name sees too. " +
                    "Their own private records are theirs, and are not shown to you here."
            },
            caveats = listOf(
                "Built from what you can see. $name may see more (their own private records) and you will not see those here.",
                "A role never decides sight: an admin sees what is shared with them, like everyone else.",
                "Amounts in another currency are not added into the figure.",
            ),
        )
    }

    private data class Row(val record: PreviewRecord, val value: BigDecimal?, val theySee: Boolean) {
        fun withValue() = record.copy(valueFormatted = value?.let(IndianNumbers::rupees))
    }
}

@RestController
@RequestMapping("/api/v1/households/{householdId}/members")
class MemberPreviewController(private val service: MemberPreviewService) {

    /**
     * "What Ravi sees": of the records you can see, which this member sees too,
     * and which they do not (X-56). Never anything you cannot already see.
     */
    @GetMapping("/{memberId}/preview")
    fun preview(@PathVariable householdId: UUID, @PathVariable memberId: UUID): MemberPreview =
        service.preview(householdId, memberId)
}
