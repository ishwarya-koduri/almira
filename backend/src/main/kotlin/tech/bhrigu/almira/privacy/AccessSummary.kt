package tech.bhrigu.almira.privacy

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.provider.ChannelSender
import tech.bhrigu.almira.provider.ProviderMode
import tech.bhrigu.almira.security.RequestUserContext
import java.time.Instant

/** One kind of thing held about the person, and how many. Counts, never contents. */
data class HeldItem(val category: String, val count: Int)

/** Why it is held. The same purposes the consent list shows, plus what needs no consent. */
data class ProcessingPurpose(val purpose: String, val basis: String, val description: String)

data class SharedPerson(val householdName: String, val displayName: String, val role: String)

data class SharedWith(
    /** People with a login in a household you belong to: who can see what you mark "household". */
    val householdMembers: List<SharedPerson>,
    /** Household members you named as an emergency contact. */
    val emergencyContacts: List<String>,
    /** Guest links you made that still open. */
    val liveGuestLinks: Int,
    /** Your nominees under s.14. Nobody is shown anything until they come forward. */
    val nominees: Int,
    /**
     * The outside services a message to you can pass through on this server,
     * by channel and whether it is a sandbox. Named here because s.11 asks who
     * data was shared with, and a message provider is someone.
     */
    val messageChannels: List<String>,
)

data class AccessSummary(
    val generatedAt: Instant,
    val identity: List<String>,
    val held: List<HeldItem>,
    val purposes: List<ProcessingPurpose>,
    val sharedWith: SharedWith,
    val grievance: GrievanceContact,
)

/**
 * The s.11 access summary: what is held about you, how it is used, and who it
 * is shared with (docs/23 "Your data rights", the See card).
 *
 * Answered on the spot rather than queued, because everything in it is already
 * known to the database. Every count runs through the caller's own row-level
 * security and the same ownership helpers the read policies use, so the summary
 * can never mention a record the person cannot open. That also means it counts
 * what is ABOUT them — what they own, hold, owe, uploaded or wrote down — not
 * everything they can see.
 *
 * It names no amounts and no titles. It is a map of where to look, and the
 * export is the copy.
 */
@Service
class AccessSummaryService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val rights: DataRightsService,
    private val channels: List<ChannelSender>,
    private val audit: AuditService,
    private val userContext: RequestUserContext,
) {

    // Not read-only: looking is itself recorded, and a read-only transaction
    // would quietly refuse the audit row.
    @Transactional
    fun summary(): AccessSummary {
        val userId = userContext.require()
        val params = mapOf("uid" to userId)

        val identity = jdbc.query(
            "select phone, email, full_name from users where id = :uid", params,
        ) { rs, _ ->
            listOfNotNull(
                rs.getString("phone")?.let { "phone" },
                rs.getString("email")?.let { "email" },
                rs.getString("full_name")?.let { "name" },
            )
        }.firstOrNull().orEmpty()

        val held = jdbc.queryForMap(
            """
            select
              (select count(*) from investments i
                where i.deleted_at is null and app.owns_investment(i.id))          as holdings,
              (select count(*) from liabilities l
                where l.deleted_at is null and app.owes_liability(l.id))           as loans,
              (select count(*) from accounts a
                where a.deleted_at is null and app.holds_account(a.id))            as accounts,
              (select count(*) from estate_documents e
                where e.deleted_at is null and app.owns_estate_document(e.id))     as estate_documents,
              (select count(*) from documents d
                where d.deleted_at is null and d.uploaded_by = :uid)               as documents,
              (select count(*) from contacts c
                where c.deleted_at is null and c.created_by = :uid)                as contacts,
              (select count(*) from sealed_values s where s.sealed_by = :uid)      as sealed_values,
              (select count(*) from activity_log g where g.actor_user_id = :uid)   as change_log,
              (select count(*) from user_sessions u
                where u.user_id = :uid and u.revoked_at is null
                  and u.expires_at > now())                                        as sessions,
              (select count(*) from consent_events)                                as consent_records,
              (select count(*) from data_rights_requests)                          as rights_requests
            """.trimIndent(),
            params,
        ).map { (category, count) -> HeldItem(category, (count as Number).toInt()) }

        val members = jdbc.query(
            """
            select h.name as household_name, m.display_name, hm.role
              from household_memberships mine
              join households h on h.id = mine.household_id and h.deleted_at is null
              join household_memberships hm
                on hm.household_id = mine.household_id and hm.status = 'active' and hm.user_id <> :uid
              join members m
                on m.household_id = hm.household_id and m.user_id = hm.user_id and m.deleted_at is null
             where mine.user_id = :uid and mine.status = 'active'
             order by h.name, m.display_name
            """.trimIndent(),
            params,
        ) { rs, _ -> SharedPerson(rs.getString("household_name"), rs.getString("display_name"), rs.getString("role")) }

        val emergency = jdbc.queryForList(
            """
            select distinct m.display_name
              from emergency_contacts ec
              join members m on m.id = ec.trusted_member_id and m.deleted_at is null
             where ec.member_id = any(app.current_member_ids(ec.household_id))
             order by m.display_name
            """.trimIndent(),
            params, String::class.java,
        )

        val links = jdbc.queryForObject(
            """
            select count(*) from guest_shares
             where created_by = :uid and revoked_at is null and expires_at > now()
            """.trimIndent(),
            params, Int::class.java,
        ) ?: 0

        val summary = AccessSummary(
            generatedAt = Instant.now(),
            identity = identity,
            held = held,
            purposes = PURPOSES,
            sharedWith = SharedWith(
                householdMembers = members,
                emergencyContacts = emergency,
                liveGuestLinks = links,
                nominees = rights.nominees().size,
                messageChannels = channels
                    .filter { it.mode != ProviderMode.DISABLED && it.mode != ProviderMode.OFF }
                    .map { "${it.channel} (${it.mode.name.lowercase()})" }
                    .sorted(),
            ),
            grievance = rights.grievance(),
        )
        audit.record(
            householdId = null, actorUserId = userId, action = "privacy.access_summary",
            entityType = "user", entityId = userId,
        )
        return summary
    }

    private companion object {
        val PURPOSES = listOf(
            ProcessingPurpose(
                "records", "consent",
                "Keeping what you record and showing it to the people you choose: totals, reports, search and the family handbook.",
            ),
            ProcessingPurpose(
                "messages", "consent",
                "Reminders and \"still true?\" questions by email or text. In-app messages are always kept.",
            ),
            ProcessingPurpose(
                "security", "legitimate_use",
                "Signing you in, confirming it's you before something sensitive, and keeping a log of changes so a household can see who changed what.",
            ),
            ProcessingPurpose(
                "continuity", "consent",
                "Letting the emergency contact you named ask for the records you marked, after the wait you chose.",
            ),
        )
    }
}
