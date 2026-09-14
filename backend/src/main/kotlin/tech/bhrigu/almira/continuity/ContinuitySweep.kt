package tech.bhrigu.almira.continuity

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** What one run did. Counts only. */
data class ContinuitySweepResult(
    val firstReminders: Int = 0,
    val secondReminders: Int = 0,
    /** Owners whose timeline ran out, and for whom requests were raised. */
    val raised: Int = 0,
    val requestsRaised: Int = 0,
    val reachabilityAsks: Int = 0,
)

/**
 * The sweep behind "if I go quiet" and the yearly "still reachable?" (docs/27).
 *
 * **Which connection.** A scheduled job has no user, so on the runtime pool
 * row-level security shows it nothing, and a sweep that finds nothing looks
 * exactly like a sweep with nothing to do. It takes the OWNER data source by
 * explicit qualifier, as StillTrueSweep does, and so enforces privacy in whom
 * it acts for rather than in what it can read: only owners who turned the
 * trigger on, only the contacts those owners named.
 *
 * **What it may do.** Send a reminder to the owner; raise an ordinary emergency
 * request in a named contact's name, exactly the row that contact could have
 * inserted by hand, with the contact's own waiting period; and ask a named
 * contact once a year whether they can still be reached. It never opens
 * anything: opening is still a matter of time passing in silence (V25).
 *
 * **When.** Hourly, acting only inside each household's daytime, so a question
 * about going quiet never arrives at three in the morning. Each step stamps its
 * row in the same transaction that decides it, before the message is handed
 * over, so a second server or a re-run finds nothing left to do.
 */
@Component
class ContinuitySweep(
    @Qualifier("ownerDataSource") ownerDataSource: DataSource,
    private val links: ContinuityLinks,
    private val notices: ContinuityNotices,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val jdbc = NamedParameterJdbcTemplate(ownerDataSource)
    private val transactions = TransactionTemplate(DataSourceTransactionManager(ownerDataSource))

    @Scheduled(cron = "0 40 * * * *")
    fun scheduled() {
        runCatching { run() }.onFailure {
            log.warn("continuity sweep failed: {}", it.javaClass.simpleName)
        }
    }

    fun run(now: Instant = Instant.now()): ContinuitySweepResult {
        var result = ContinuitySweepResult()
        candidates().forEach { candidate ->
            result = runCatching { step(candidate, now, result) }.getOrElse {
                log.warn("continuity sweep skipped one owner: {}", it.javaClass.simpleName)
                result
            }
        }
        dueReachability().forEach { contact ->
            runCatching { askReachable(contact) }.onSuccess { if (it) result = result.copy(reachabilityAsks = result.reachabilityAsks + 1) }
                .onFailure { log.warn("continuity sweep skipped one contact: {}", it.javaClass.simpleName) }
        }
        if (result != ContinuitySweepResult()) {
            log.info(
                "continuity sweep: {} first reminder(s), {} second, {} owner(s) raised ({} request(s)), {} reachability ask(s)",
                result.firstReminders, result.secondReminders, result.raised, result.requestsRaised,
                result.reachabilityAsks,
            )
        }
        return result
    }

    // --- going quiet ----------------------------------------------------------

    private data class Candidate(val checkId: UUID, val householdId: UUID)

    /** Owners with the trigger on, in daytime where they live, who are still with us. */
    private fun candidates(): List<Candidate> = jdbc.query(
        """
        select c.id, c.household_id
        from inactivity_checks c
        join members m on m.id = c.member_id and m.deleted_at is null and m.user_id = c.user_id
        join household_memberships hm
          on hm.household_id = c.household_id and hm.user_id = c.user_id and hm.status = 'active'
        join households h on h.id = c.household_id
        where c.enabled
          and not app.notifications_stopped(c.user_id, c.household_id)
          and extract(hour from now() at time zone h.time_zone) between :fromHour and :untilHour
        """.trimIndent(),
        mapOf("fromHour" to DAY_STARTS_AT, "untilHour" to DAY_ENDS_BEFORE - 1),
    ) { rs, _ -> Candidate(rs.getObject("id", UUID::class.java), rs.getObject("household_id", UUID::class.java)) }

    private data class Owner(
        val checkId: UUID,
        val householdId: UUID,
        val memberId: UUID,
        val userId: UUID,
        val name: String,
        val periodDays: Int,
        val presence: Instant,
        val firstReminderAt: Instant?,
        val secondReminderAt: Instant?,
        val raisedAt: Instant?,
    )

    private data class Contact(val memberId: UUID, val userId: UUID, val name: String, val waitDays: Int)

    private sealed interface Outcome
    private data class Remind(val owner: Owner, val second: Boolean, val url: String?, val contacts: List<Contact>) : Outcome
    private data class Raised(val owner: Owner, val requests: List<Pair<UUID, Contact>>) : Outcome

    private fun step(candidate: Candidate, now: Instant, result: ContinuitySweepResult): ContinuitySweepResult {
        val outcome = transactions.execute { decide(candidate, now) } ?: return result
        return when (outcome) {
            is Remind -> {
                val owner = outcome.owner
                notices.send(
                    owner.userId, owner.householdId, ContinuityNotices.CHECK_IN,
                    ContinuityNotices.checkInTitle(outcome.second),
                    ContinuityNotices.checkInBody(
                        outcome.second, outcome.url, outcome.contacts.map { it.name },
                        outcome.contacts.minOfOrNull { it.waitDays },
                    ),
                    "continuity.check_in:${owner.checkId}:${if (outcome.second) 2 else 1}:${owner.presence.epochSecond}",
                )
                if (outcome.second) result.copy(secondReminders = result.secondReminders + 1)
                else result.copy(firstReminders = result.firstReminders + 1)
            }
            is Raised -> {
                val owner = outcome.owner
                outcome.requests.forEach { (requestId, contact) ->
                    notices.send(
                        owner.userId, owner.householdId, ContinuityNotices.REQUESTED,
                        "Emergency access has begun, because we haven't heard from you",
                        "You asked us to start this if you went quiet. ${contact.name} can see what's marked for " +
                            "the family after ${contact.waitDays} days, unless you stop it. Signing in, or tapping " +
                            "\"I'm here\" in Almira, stops it.",
                        "emergency.requested:$requestId",
                    )
                    notices.send(
                        contact.userId, owner.householdId, ContinuityNotices.RAISED,
                        "${owner.name} hasn't been in touch for a while",
                        "${owner.name} asked Almira to start emergency access for you if they went quiet, and they " +
                            "haven't answered two check-ins. Nothing opens for ${contact.waitDays} days, and it stays " +
                            "shut if they sign in. Please try to reach them.",
                        "emergency.raised:$requestId",
                    )
                }
                result.copy(raised = result.raised + 1, requestsRaised = result.requestsRaised + outcome.requests.size)
            }
        }
    }

    /** Locks the owner's row, decides the next step from fresh state, and stamps it. */
    private fun decide(candidate: Candidate, now: Instant): Outcome? {
        val owner = jdbc.query(
            """
            select c.id, c.household_id, c.member_id, c.user_id, c.period_days, m.display_name,
                   c.first_reminder_at, c.second_reminder_at, c.raised_at,
                   greatest(c.enabled_at, c.last_check_in_at,
                            (select max(s.last_used_at) from user_sessions s where s.user_id = c.user_id)) as presence
            from inactivity_checks c
            join members m on m.id = c.member_id
            where c.id = :id and c.enabled
            for update of c
            """.trimIndent(),
            mapOf("id" to candidate.checkId),
        ) { rs, _ ->
            Owner(
                checkId = rs.getObject("id", UUID::class.java),
                householdId = rs.getObject("household_id", UUID::class.java),
                memberId = rs.getObject("member_id", UUID::class.java),
                userId = rs.getObject("user_id", UUID::class.java),
                name = rs.getString("display_name"),
                periodDays = rs.getInt("period_days"),
                presence = rs.getTimestamp("presence").toInstant(),
                firstReminderAt = rs.getTimestamp("first_reminder_at")?.toInstant(),
                secondReminderAt = rs.getTimestamp("second_reminder_at")?.toInstant(),
                raisedAt = rs.getTimestamp("raised_at")?.toInstant(),
            )
        }.firstOrNull() ?: return null

        val next = InactivityTimeline.next(
            owner.presence, owner.periodDays, owner.firstReminderAt, owner.secondReminderAt, owner.raisedAt, now,
        ) ?: return null
        val contacts = contactsOf(owner)

        return when (next) {
            InactivityTimeline.Step.FIRST_REMINDER, InactivityTimeline.Step.SECOND_REMINDER -> {
                val second = next == InactivityTimeline.Step.SECOND_REMINDER
                val column = if (second) "second_reminder_at" else "first_reminder_at"
                jdbc.update(
                    "update inactivity_checks set $column = :now where id = :id",
                    MapSqlParameterSource().addValue("now", Timestamp.from(now)).addValue("id", owner.checkId),
                )
                val url = checkInLink(owner, now)
                audit(owner.householdId, null, "continuity.inactivity.reminder", "inactivity_check", owner.checkId,
                    """{"reminder":${if (second) 2 else 1}}""")
                Remind(owner, second, url, contacts)
            }
            InactivityTimeline.Step.RAISE -> {
                jdbc.update(
                    "update inactivity_checks set raised_at = :now where id = :id",
                    MapSqlParameterSource().addValue("now", Timestamp.from(now)).addValue("id", owner.checkId),
                )
                val raised = contacts.mapNotNull { contact -> raise(owner, contact, now)?.let { it to contact } }
                audit(owner.householdId, null, "continuity.inactivity.raised", "inactivity_check", owner.checkId,
                    """{"requests":${raised.size}}""")
                Raised(owner, raised)
            }
        }
    }

    /** Only contacts who could have asked by hand: a login, and still in the household. */
    private fun contactsOf(owner: Owner): List<Contact> = jdbc.query(
        """
        select t.id, t.user_id, t.display_name, c.wait_days
        from emergency_contacts c
        join members t on t.id = c.trusted_member_id and t.deleted_at is null and t.user_id is not null
        join household_memberships hm
          on hm.household_id = c.household_id and hm.user_id = t.user_id and hm.status = 'active'
        where c.household_id = :hid and c.member_id = :member
        order by lower(t.display_name)
        """.trimIndent(),
        mapOf("hid" to owner.householdId, "member" to owner.memberId),
    ) { rs, _ ->
        Contact(
            rs.getObject("id", UUID::class.java), rs.getObject("user_id", UUID::class.java),
            rs.getString("display_name"), rs.getInt("wait_days"),
        )
    }

    /** The row the contact could have inserted themselves (V20's insert policy), unless one is already live. */
    private fun raise(owner: Owner, contact: Contact, now: Instant): UUID? {
        val live = jdbc.queryForObject(
            """
            select exists (select 1 from emergency_requests
                           where household_id = :hid and subject_member_id = :subject and requested_by = :by
                             and vetoed_at is null and revoked_at is null and now() < access_expires_at)
            """.trimIndent(),
            mapOf("hid" to owner.householdId, "subject" to owner.memberId, "by" to contact.userId),
            Boolean::class.java,
        ) == true
        if (live) return null
        val id = UUID.randomUUID()
        val unlockAt = now.plus(Duration.ofDays(contact.waitDays.toLong()))
        jdbc.update(
            """
            insert into emergency_requests (id, household_id, subject_member_id, requested_by, reason,
                                            requested_at, unlock_at, access_expires_at, raised_by)
            values (:id, :hid, :subject, :by, :reason, :now, :unlockAt, :expiresAt, 'inactivity')
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id).addValue("hid", owner.householdId).addValue("subject", owner.memberId)
                .addValue("by", contact.userId)
                .addValue("reason", "No answer to two check-ins after ${owner.periodDays} quiet days.")
                .addValue("now", Timestamp.from(now))
                .addValue("unlockAt", Timestamp.from(unlockAt))
                .addValue("expiresAt", Timestamp.from(unlockAt.plus(Duration.ofDays(30)))),
        )
        audit(owner.householdId, null, "emergency.request", "member", owner.memberId,
            """{"raisedBy":"inactivity","unlockAt":"$unlockAt","requestId":"$id"}""")
        return id
    }

    /** A link only when there is somewhere for it to point. */
    private fun checkInLink(owner: Owner, now: Instant): String? {
        val token = links.newToken()
        val url = links.url(token) ?: return null
        jdbc.update(
            """
            insert into continuity_links (purpose, token_hash, household_id, user_id, inactivity_check_id,
                                          created_at, expires_at)
            values ('check_in', :hash, :hid, :uid, :check, :now, :expires)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("hash", links.hash(token)).addValue("hid", owner.householdId)
                .addValue("uid", owner.userId).addValue("check", owner.checkId)
                .addValue("now", Timestamp.from(now))
                .addValue("expires", Timestamp.from(now.plus(ContinuityLinks.LIFETIME))),
        )
        return url
    }

    // --- still reachable, once a year ------------------------------------------

    private data class DueContact(
        val contactId: UUID,
        val householdId: UUID,
        val trustedUserId: UUID,
        val ownerName: String,
    )

    private fun dueReachability(): List<DueContact> = jdbc.query(
        """
        select c.id, c.household_id, t.user_id, o.display_name as owner_name
        from emergency_contacts c
        join members t on t.id = c.trusted_member_id and t.deleted_at is null and t.user_id is not null
        join household_memberships hm
          on hm.household_id = c.household_id and hm.user_id = t.user_id and hm.status = 'active'
        join members o on o.id = c.member_id and o.deleted_at is null
        join households h on h.id = c.household_id
        left join trusted_contact_asks a on a.emergency_contact_id = c.id
        where greatest(c.created_at, a.last_asked_at,
                       (select max(x.confirmed_at) from trusted_contact_confirmations x
                        where x.emergency_contact_id = c.id)) < now() - make_interval(days => :days)
          and not app.notifications_stopped(t.user_id, c.household_id)
          and extract(hour from now() at time zone h.time_zone) between :fromHour and :untilHour
        """.trimIndent(),
        mapOf(
            "days" to ReachabilityService.YEAR_DAYS,
            "fromHour" to DAY_STARTS_AT, "untilHour" to DAY_ENDS_BEFORE - 1,
        ),
    ) { rs, _ ->
        DueContact(
            rs.getObject("id", UUID::class.java), rs.getObject("household_id", UUID::class.java),
            rs.getObject("user_id", UUID::class.java), rs.getString("owner_name"),
        )
    }

    private fun askReachable(contact: DueContact): Boolean {
        // Stamped in the same transaction that decides, so a second server finds
        // it asked; the link is made only when there is somewhere for it to point.
        val asked = transactions.execute {
            val stamped = jdbc.update(
                """
                insert into trusted_contact_asks (emergency_contact_id, household_id, last_asked_at)
                values (:id, :hid, now())
                on conflict (emergency_contact_id) do update set last_asked_at = now()
                  where trusted_contact_asks.last_asked_at < now() - make_interval(days => :days)
                """.trimIndent(),
                mapOf("id" to contact.contactId, "hid" to contact.householdId, "days" to ReachabilityService.YEAR_DAYS),
            )
            if (stamped == 0) return@execute null
            val token = links.newToken()
            val url = links.url(token)
            if (url != null) {
                jdbc.update(
                    """
                    insert into continuity_links (purpose, token_hash, household_id, user_id, emergency_contact_id, expires_at)
                    values ('reachable', :hash, :hid, :uid, :contact, now() + make_interval(days => :life))
                    """.trimIndent(),
                    mapOf(
                        "hash" to links.hash(token), "hid" to contact.householdId, "uid" to contact.trustedUserId,
                        "contact" to contact.contactId, "life" to ContinuityLinks.LIFETIME.toDays().toInt(),
                    ),
                )
            }
            audit(contact.householdId, null, "continuity.reachable.asked", "emergency_contact", contact.contactId, null)
            Asked(url)
        } ?: return false
        notices.send(
            contact.trustedUserId, contact.householdId, ContinuityNotices.REACHABLE,
            ContinuityNotices.reachableTitle(contact.ownerName),
            ContinuityNotices.reachableBody(contact.ownerName, asked.url),
            "continuity.reachable:${contact.contactId}:${Instant.now().epochSecond / 86_400}",
        )
        return true
    }

    private data class Asked(val url: String?)

    private fun audit(householdId: UUID, actor: UUID?, action: String, entityType: String, entityId: UUID, diff: String?) {
        jdbc.update(
            """
            insert into activity_log (household_id, actor_user_id, action, entity_type, entity_id, diff)
            values (:hid, :actor, :action, :type, :id, cast(:diff as jsonb))
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("hid", householdId).addValue("actor", actor).addValue("action", action)
                .addValue("type", entityType).addValue("id", entityId).addValue("diff", diff),
        )
    }

    private companion object {
        const val DAY_STARTS_AT = 9
        const val DAY_ENDS_BEFORE = 20
    }
}
