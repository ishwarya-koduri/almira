package tech.bhrigu.almira.continuity

import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.household.HouseholdService
import tech.bhrigu.almira.security.RequestUserContext
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Whether a trusted contact has said, lately, that they can still be reached. */
data class Reachability(
    val contactId: UUID,
    /** The last dated tick, from the app or a one-tap link. */
    val confirmedAt: Instant?,
    /** When they were last asked, by the yearly sweep or by "Ask now". */
    val askedAt: Instant?,
    /** Confirmed within the last year. */
    val current: Boolean,
)

/**
 * Once a year, one tap from each trusted contact: "Yes, I can still be reached"
 * (docs/27 §3). The owner sees a dated tick beside the name.
 *
 * Only the person named can confirm, and only for themselves — the policy on
 * `trusted_contact_confirmations` says so, and a confirmation is never
 * rewritten. The owner can ask before the year is up, at most once a week.
 */
@Service
class ReachabilityService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
    private val audit: AuditService,
    private val links: ContinuityLinks,
    private val notices: ContinuityNotices,
    private val userContext: RequestUserContext,
) {

    /** Per contact the caller can see, under V20's policy on the contact itself. */
    @Transactional(readOnly = true)
    fun forHousehold(householdId: UUID): Map<UUID, Reachability> = jdbc.query(
        """
        select c.id,
               (select max(x.confirmed_at) from trusted_contact_confirmations x where x.emergency_contact_id = c.id) as confirmed_at,
               (select a.last_asked_at from trusted_contact_asks a where a.emergency_contact_id = c.id) as asked_at
        from emergency_contacts c
        where c.household_id = :hid
        """.trimIndent(),
        mapOf("hid" to householdId),
    ) { rs, _ ->
        val confirmed = rs.getTimestamp("confirmed_at")?.toInstant()
        Reachability(
            contactId = rs.getObject("id", UUID::class.java),
            confirmedAt = confirmed,
            askedAt = rs.getTimestamp("asked_at")?.toInstant(),
            current = confirmed != null && confirmed.isAfter(Instant.now().minusSeconds(YEAR_DAYS * 86_400L)),
        )
    }.associateBy { it.contactId }

    /** "Yes, I can still be reached", from inside the app. */
    @Transactional
    fun confirm(householdId: UUID, contactId: UUID): Reachability {
        val userId = userContext.require()
        households.get(householdId)
        val mine = jdbc.queryForObject(
            """
            select exists (select 1 from emergency_contacts c
                           where c.id = :id and c.household_id = :hid
                             and c.trusted_member_id = any(app.current_member_ids(:hid)))
            """.trimIndent(),
            mapOf("id" to contactId, "hid" to householdId),
            Boolean::class.java,
        ) == true
        // The owner, or anyone else, gets the same answer as for a contact that
        // does not exist.
        if (!mine) throw ApiException.notFound()
        jdbc.update(
            """
            insert into trusted_contact_confirmations (household_id, emergency_contact_id, confirmed_by, via)
            values (:hid, :id, :uid, 'app')
            """.trimIndent(),
            mapOf("hid" to householdId, "id" to contactId, "uid" to userId),
        )
        audit.record(
            householdId = householdId, actorUserId = userId, action = "continuity.reachable",
            entityType = "emergency_contact", entityId = contactId, diff = mapOf("via" to "app"),
        )
        return forHousehold(householdId).getValue(contactId)
    }

    /** The owner's "Ask now". */
    @Transactional
    fun ask(householdId: UUID, contactId: UUID): Reachability {
        val userId = userContext.require()
        households.get(householdId)
        val contact = jdbc.query(
            """
            select c.id, t.user_id, o.display_name as owner_name, a.last_asked_at
            from emergency_contacts c
            join members t on t.id = c.trusted_member_id and t.deleted_at is null
            join members o on o.id = c.member_id
            left join trusted_contact_asks a on a.emergency_contact_id = c.id
            where c.id = :id and c.household_id = :hid and c.member_id = any(app.current_member_ids(:hid))
            """.trimIndent(),
            mapOf("id" to contactId, "hid" to householdId),
        ) { rs, _ ->
            Triple(
                rs.getObject("user_id", UUID::class.java),
                rs.getString("owner_name"),
                rs.getTimestamp("last_asked_at")?.toInstant(),
            )
        }.firstOrNull() ?: throw ApiException.notFound()
        val (trustedUser, ownerName, lastAsked) = contact
        if (trustedUser == null) {
            throw ApiException.badRequest(
                "cannot_sign_in", "They can't sign in yet, so there is nowhere to ask. Invite them first.",
            )
        }
        if (lastAsked != null && lastAsked.isAfter(Instant.now().minusSeconds(ASK_AGAIN_DAYS * 86_400L))) {
            throw ApiException.conflict(
                "asked_recently", "You asked them this week. Give them a few days to answer.",
            )
        }

        val token = links.newToken()
        val url = links.url(token)
        if (url != null) {
            jdbc.queryForObject(
                "select app.issue_reachability_link(:id, :hash, :expires)",
                mapOf(
                    "id" to contactId, "hash" to links.hash(token),
                    "expires" to Timestamp.from(Instant.now().plus(ContinuityLinks.LIFETIME)),
                ),
                UUID::class.java,
            ) ?: throw ApiException.notFound()
        }
        try {
            jdbc.update(
                """
                insert into trusted_contact_asks (emergency_contact_id, household_id, last_asked_at)
                values (:id, :hid, now())
                on conflict (emergency_contact_id) do update set last_asked_at = now()
                """.trimIndent(),
                mapOf("id" to contactId, "hid" to householdId),
            )
        } catch (e: DataAccessException) {
            throw ApiException.notFound()
        }
        audit.record(
            householdId = householdId, actorUserId = userId, action = "continuity.reachable.asked",
            entityType = "emergency_contact", entityId = contactId,
        )
        notices.send(
            trustedUser, householdId, ContinuityNotices.REACHABLE,
            ContinuityNotices.reachableTitle(ownerName),
            ContinuityNotices.reachableBody(ownerName, url),
            "continuity.reachable:$contactId:ask:${Instant.now().epochSecond}",
        )
        return forHousehold(householdId).getValue(contactId)
    }

    companion object {
        const val YEAR_DAYS = 365
        const val ASK_AGAIN_DAYS = 7
    }
}

@RestController
@RequestMapping("/api/v1/households/{householdId}/emergency/contacts/{contactId}/reachable")
class ReachabilityController(private val service: ReachabilityService) {

    /** The person named: "Yes, I can still be reached." */
    @PostMapping
    fun confirmReachable(@PathVariable householdId: UUID, @PathVariable contactId: UUID): Reachability =
        service.confirm(householdId, contactId)

    /** The owner: ask them now rather than waiting for the year. */
    @PostMapping("/ask")
    fun askReachable(@PathVariable householdId: UUID, @PathVariable contactId: UUID): Reachability =
        service.ask(householdId, contactId)
}
