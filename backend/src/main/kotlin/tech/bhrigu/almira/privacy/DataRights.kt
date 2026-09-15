package tech.bhrigu.almira.privacy

import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.auth.StepUpService
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.common.EmailAddress
import tech.bhrigu.almira.common.PhoneNumber
import tech.bhrigu.almira.provider.ChannelSender
import tech.bhrigu.almira.provider.ProviderMode
import tech.bhrigu.almira.security.RequestUserContext
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Where a complaint goes and when it is answered. Part of every rights reply,
 * so nobody has to hunt for it at the moment they are unhappy.
 */
data class GrievanceContact(
    val name: String?,
    val email: String?,
    val respondWithinDays: Int,
    /** False until a deployment names someone. The page says so plainly. */
    val configured: Boolean,
)

/**
 * One purpose and where the person stands on it.
 *
 * [given] is null when they have never answered. For `messages` that is a no:
 * nothing but an essential notice goes outside the app until they say yes
 * (V125). It is null too for a yes from before channels were chosen, which no
 * longer counts (V142, [askingAgain]). `records` is [required]: it is the
 * service itself, and withdrawing it is closing the account.
 */
data class ConsentState(
    val purpose: String,
    val required: Boolean,
    val given: Boolean?,
    val changedAt: Instant?,
    /**
     * For `messages` when [given]: the channels the yes covers — `email`, `sms`,
     * `push`, `whatsapp`. Null otherwise.
     */
    val channels: List<String>? = null,
    /**
     * For `messages`: the latest answer is a yes from before channels were chosen
     * (V142). It sends nothing, [given] is null, and the person is asked again,
     * with words that say the choices changed. [changedAt] is when they said it.
     */
    val askingAgain: Boolean = false,
)

/**
 * Whether to ask, now, "Want a reminder by email or SMS when this is due?"
 * (docs/23 "Asked when it helps").
 *
 * [ask] is true only for someone who has not answered about messages (or whose
 * yes is from before channels were chosen, [askingAgain]), on a server that
 * offers a channel to answer for, and who has not said "Not now" to **the same
 * thing** in the last [DataRightsService.NOT_NOW_FOR] (V141). The thing is the
 * context the client names — a holding or loan whose date made a reminder, or
 * the Still true? digest — and for a context the moment must also have come: the
 * record has a reminder, or something is waiting to be confirmed. With no
 * context, any "Not now" in the last 90 days keeps the question away, as V125
 * did. Someone who withdrew is not asked again: that was an answer.
 */
data class MessagesAsk(
    val ask: Boolean,
    /** The channels this server can send on, in the order to show them. Never pre-ticked. */
    val channels: List<String>,
    /** The notice a yes is recorded against. */
    val noticeVersion: String,
    /** Set after "Not now" to this context (or, with none, to anything): no ask about it before this. */
    val notNowUntil: Instant?,
    /** The person said yes before channels were chosen; ask again, and say why (V142). */
    val askingAgain: Boolean = false,
)

/** What "Not now" was said beside (V141). Both omitted: said to nothing in particular. */
data class MessagesAskContextBody(
    /** `investment`, `liability` or `still_true_digest`. */
    val contextType: String? = null,
    /** The holding or loan; omitted for the digest. */
    val contextId: UUID? = null,
)

data class Nominee(
    val id: UUID,
    val fullName: String,
    val relationship: String?,
    val contact: String,
    val createdAt: Instant,
)

data class RightsRequest(
    val id: UUID,
    val kind: String,
    val details: String,
    val status: String,
    val respondBy: LocalDate,
    val response: String?,
    val answeredAt: Instant?,
    val createdAt: Instant,
    val grievance: GrievanceContact,
)

data class PrivacyOverview(
    val noticeVersion: String,
    val noticeLegallyReviewed: Boolean,
    val acceptedNoticeVersion: String?,
    val acceptedAt: Instant?,
    val consents: List<ConsentState>,
    val nominees: List<Nominee>,
    val requests: List<RightsRequest>,
    val grievance: GrievanceContact,
    /** When the DPDP Act's core obligations commence. Shown, never enforced from. */
    val obligationsCommenceOn: LocalDate,
)

/** A dated line on the consent timeline. [kind]: consent, notice or parental_consent. */
data class ConsentHistoryEntry(
    val at: Instant,
    val kind: String,
    val purpose: String?,
    val action: String,
    val noticeVersion: String?,
    /** For a parental consent: the child's name. Nothing else is ever put here. */
    val subject: String?,
    /**
     * For consent to messages given: the channels it covers (see [ConsentState.channels]).
     * Null for a yes from before channels were chosen, which covers none (V142).
     */
    val channels: List<String>? = null,
    /** An answer given when the person was asked again because the choices changed (V142). */
    val askedAgain: Boolean = false,
)

data class AcceptNoticeBody(val version: String)

data class ChangeConsentBody(
    val purpose: String,
    val given: Boolean,
    /**
     * For `messages` given: the channels ticked, at least one, each one this server
     * offers. A yes with none — empty or left out — is refused as `channels_required`.
     */
    val channels: List<String>? = null,
    /** `settings` or `in_context` (asked at the moment a reminder would first help). */
    val askedIn: String? = null,
)

data class CreateRightsRequestBody(
    val kind: String,
    @field:Size(max = 2000) val details: String,
)

data class NominateBody(
    @field:Size(max = 120) val fullName: String,
    @field:Size(max = 40) val relationship: String? = null,
    @field:Size(max = 254) val contact: String,
)

/**
 * The data-principal rights as things a person can do (docs/23 "Your data
 * rights"): see consent purpose by purpose and change it, accept the notice in
 * force, ask for a correction or complain with a date we reply by, and name
 * nominees under s.14.
 *
 * Everything is read and written through the caller's own row-level security,
 * where every one of these tables is the caller's alone. Nothing here takes an
 * id from the path and trusts it: a request or a nominee that is not yours is
 * the same 404 as one that does not exist.
 */
@Service
class DataRightsService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val props: PrivacyProperties,
    private val stepUp: StepUpService,
    private val audit: AuditService,
    private val userContext: RequestUserContext,
    private val senders: List<ChannelSender>,
) {

    fun grievance(): GrievanceContact = props.grievance.let {
        GrievanceContact(
            name = it.name.trim().ifEmpty { null },
            email = it.email.trim().ifEmpty { null },
            respondWithinDays = it.responseDays,
            configured = it.configured,
        )
    }

    @Transactional(readOnly = true)
    fun overview(): PrivacyOverview {
        userContext.require()
        val notice = currentNotice()
        val accepted = jdbc.query(
            """
            select notice_version, accepted_at from privacy_notice_acceptances
             order by accepted_at desc limit 1
            """.trimIndent(),
            emptyMap<String, Any>(),
        ) { rs, _ -> rs.getString("notice_version") to rs.getTimestamp("accepted_at").toInstant() }
            .firstOrNull()
        return PrivacyOverview(
            noticeVersion = notice.first,
            noticeLegallyReviewed = notice.second,
            acceptedNoticeVersion = accepted?.first,
            acceptedAt = accepted?.second,
            consents = consents(),
            nominees = nominees(),
            requests = requests(),
            grievance = grievance(),
            obligationsCommenceOn = OBLIGATIONS_COMMENCE,
        )
    }

    /**
     * Accepting a notice that is no longer the one in force is refused: the
     * person read something else, and a record saying otherwise would be false.
     */
    @Transactional
    fun acceptNotice(version: String): PrivacyOverview {
        val userId = userContext.require()
        val current = currentNotice().first
        if (version != current) {
            throw ApiException.conflict(
                "notice_changed",
                "The privacy notice changed while you were reading it. Have a look at the new one.",
                mapOf("currentVersion" to current),
            )
        }
        jdbc.update(
            """
            insert into privacy_notice_acceptances (user_id, notice_version)
            values (:uid, :version) on conflict do nothing
            """.trimIndent(),
            mapOf("uid" to userId, "version" to version),
        )
        audit.record(
            householdId = null, actorUserId = userId, action = "privacy.notice_accept",
            diff = mapOf("version" to version),
        )
        return overview()
    }

    /** The channels a yes to messages can name here: the ones this server sends on. */
    fun offeredChannels(): List<String> {
        val live = senders.filter { it.mode == ProviderMode.LIVE || it.mode == ProviderMode.SANDBOX }
            .map { it.channel }.toSet()
        return MESSAGE_CHANNELS.filter { it in live }
    }

    /**
     * One tap either way. Asking for the state you are already in writes
     * nothing, so the history shows decisions rather than button presses. A yes
     * to messages names its channels; a yes for different channels is a new
     * decision and is recorded.
     */
    @Transactional
    fun changeConsent(purpose: String, given: Boolean, channels: List<String>? = null, askedIn: String? = null): PrivacyOverview {
        val userId = userContext.require()
        if (purpose !in PURPOSES) {
            throw ApiException.badRequest("purpose_invalid", "Choose one of: ${PURPOSES.joinToString()}.")
        }
        if (purpose in REQUIRED_PURPOSES && !given) {
            throw ApiException.conflict(
                "withdraw_by_closing",
                "Keeping your records is the service itself. To withdraw this, close your account.",
            )
        }
        if (askedIn != null && askedIn !in ASKED_IN) {
            throw ApiException.badRequest("asked_in_invalid", "Choose one of: ${ASKED_IN.joinToString()}.")
        }
        val chosen = if (purpose == MESSAGES && given) {
            // A yes that names no channel looks like permission and delivers nothing — worse
            // than a no, because everyone thinks it is handled (owner's decision, 2026-09-15).
            // Refused whether the list is empty or left out, before anything is written.
            val offered = offeredChannels()
            val distinct = channels.orEmpty().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
            if (distinct.isEmpty()) {
                throw ApiException.badRequest("channels_required", "Choose at least one way to be reminded.")
            }
            distinct.firstOrNull { it !in offered }?.let {
                throw ApiException.badRequest(
                    "channel_not_offered", "We can't send reminders by $it. Choose from: ${offered.joinToString()}.",
                )
            }
            MESSAGE_CHANNELS.filter { it in distinct }
        } else {
            null
        }
        val current = consents().first { it.purpose == purpose }
        val unchanged = current.given == given && (!given || purpose != MESSAGES || current.channels == chosen)
        if (!unchanged) {
            // An answer to being asked again (V142) is a new event, marked as one; the
            // old yes stays in the history as what it was.
            val askedAgain = current.askingAgain
            jdbc.update(
                """
                insert into consent_events (user_id, purpose, action, notice_version, channels, asked_in, asked_again)
                values (:uid, :purpose, :action, app.current_privacy_notice_version(),
                        cast(:channels as text[]), :askedIn, :askedAgain)
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("uid", userId).addValue("purpose", purpose)
                    .addValue("action", if (given) "given" else "withdrawn")
                    .addValue("channels", chosen?.joinToString(",", "{", "}"))
                    .addValue("askedIn", askedIn)
                    .addValue("askedAgain", askedAgain),
            )
            audit.record(
                householdId = null, actorUserId = userId,
                action = if (given) "privacy.consent_give" else "privacy.consent_withdraw",
                diff = buildMap {
                    put("purpose", purpose)
                    chosen?.let { put("channels", it) }
                    askedIn?.let { put("askedIn", it) }
                    if (askedAgain) put("askedAgain", true)
                },
            )
        }
        return overview()
    }

    /** What an ask is about (V141): a holding or loan by id, or the Still true? digest. */
    data class AskContext(val type: String, val id: UUID?)

    /**
     * Reads the context a client named, or null for none. A record the caller cannot
     * see is the same 404 as one that does not exist: the question is never a way to
     * learn that a holding is there.
     */
    private fun askContext(type: String?, id: UUID?): AskContext? {
        if (type == null && id == null) return null
        if (type !in ASK_CONTEXTS) {
            throw ApiException.badRequest("context_invalid", "Choose one of: ${ASK_CONTEXTS.joinToString()}.")
        }
        if (type == STILL_TRUE_DIGEST) {
            if (id != null) throw ApiException.badRequest("context_invalid", "The Still true? digest takes no id.")
            return AskContext(type, null)
        }
        if (id == null) throw ApiException.badRequest("context_invalid", "Say which $type this is about.")
        val table = if (type == "investment") "investments" else "liabilities"
        val visible = jdbc.queryForObject(
            "select exists (select 1 from $table where id = :id and deleted_at is null)",
            mapOf("id" to id), Boolean::class.java,
        ) == true
        if (!visible) throw ApiException.notFound("We couldn't find that record.")
        return AskContext(type!!, id)
    }

    /**
     * Whether the moment the question is for has come: the holding or loan has a
     * reminder, or the person has something the digest would ask them to confirm
     * (the same `still_true_items` the sweep reads, through their own RLS).
     */
    private fun momentHasCome(context: AskContext): Boolean = when (context.type) {
        STILL_TRUE_DIGEST -> jdbc.queryForObject(
            "select exists (select 1 from still_true_items where is_due)",
            emptyMap<String, Any>(), Boolean::class.java,
        ) == true
        else -> jdbc.queryForObject(
            """
            select exists (
              select 1 from reminders
               where ${if (context.type == "investment") "investment_id" else "liability_id"} = :id
                 and deleted_at is null and status not in ('done', 'cancelled'))
            """.trimIndent(),
            mapOf("id" to context.id), Boolean::class.java,
        ) == true
    }

    private fun lastNotNow(context: AskContext?): Instant? = if (context == null) {
        jdbc.query(
            "select not_now_at from messages_consent_asks", emptyMap<String, Any>(),
        ) { rs, _ -> rs.getTimestamp("not_now_at").toInstant() }.firstOrNull()
    } else {
        jdbc.query(
            """
            select not_now_at from messages_consent_ask_contexts
             where context_type = :type and context_id is not distinct from cast(:id as uuid)
            """.trimIndent(),
            MapSqlParameterSource().addValue("type", context.type).addValue("id", context.id),
        ) { rs, _ -> rs.getTimestamp("not_now_at").toInstant() }.firstOrNull()
    }

    /** Whether to ask about messages now, about [contextType] and [contextId] if named. See [MessagesAsk]. */
    @Transactional(readOnly = true)
    fun messagesAsk(contextType: String? = null, contextId: UUID? = null): MessagesAsk {
        userContext.require()
        return messagesAsk(askContext(contextType, contextId))
    }

    private fun messagesAsk(context: AskContext?): MessagesAsk {
        val offered = offeredChannels()
        val messages = consents().first { it.purpose == MESSAGES }
        val answered = messages.given != null
        val notNowUntil = lastNotNow(context)?.plus(NOT_NOW_FOR)?.takeIf { it.isAfter(Instant.now()) }
        return MessagesAsk(
            ask = offered.isNotEmpty() && !answered && notNowUntil == null &&
                (context == null || momentHasCome(context)),
            channels = offered,
            noticeVersion = currentNotice().first,
            notNowUntil = notNowUntil,
            askingAgain = messages.askingAgain,
        )
    }

    /**
     * "Not now": not a consent record, and nothing is sent because of it. It keeps the
     * question about the same context from coming back for [NOT_NOW_FOR] (V141); a
     * different holding may still ask. It also moves on the person's last "Not now"
     * anywhere, which a client naming no context is answered from.
     */
    @Transactional
    fun messagesNotNow(contextType: String? = null, contextId: UUID? = null): MessagesAsk {
        val userId = userContext.require()
        val context = askContext(contextType, contextId)
        jdbc.update(
            """
            insert into messages_consent_asks (user_id) values (:uid)
            on conflict (user_id) do update set not_now_at = now()
            """.trimIndent(),
            mapOf("uid" to userId),
        )
        if (context != null) {
            jdbc.update(
                """
                insert into messages_consent_ask_contexts (user_id, context_type, context_id)
                values (:uid, :type, cast(:id as uuid))
                on conflict (user_id, context_type, context_id) do update set not_now_at = now()
                """.trimIndent(),
                MapSqlParameterSource().addValue("uid", userId).addValue("type", context.type).addValue("id", context.id),
            )
        }
        val askingAgain = consents().first { it.purpose == MESSAGES }.askingAgain
        audit.record(
            householdId = null, actorUserId = userId, action = "privacy.messages_not_now",
            entityType = context?.id?.let { context.type }, entityId = context?.id,
            diff = buildMap {
                context?.let { put("context", it.type) }
                if (askingAgain) put("askingAgain", true)
            }.ifEmpty { null },
        )
        return messagesAsk(context)
    }

    /** Consent events, notice acceptances and the parental consents this person gave, newest first. */
    @Transactional(readOnly = true)
    fun history(): List<ConsentHistoryEntry> {
        val userId = userContext.require()
        return jdbc.query(
            """
            select created_at as at, 'consent' as kind, purpose, action, notice_version, null as subject,
                   case when purpose = '$MESSAGES' and action = 'given'
                        then array_to_string(channels, ',') end as channels,
                   asked_again
              from consent_events
            union all
            select accepted_at, 'notice', null, 'accepted', notice_version, null, null, false
              from privacy_notice_acceptances
            union all
            select pc.given_at, 'parental_consent', null, 'given', pc.notice_version, m.display_name, null, false
              from parental_consents pc join members m on m.id = pc.member_id
             where pc.given_by = :uid
            union all
            select pc.withdrawn_at, 'parental_consent', null, 'withdrawn', pc.notice_version, m.display_name, null, false
              from parental_consents pc join members m on m.id = pc.member_id
             where pc.given_by = :uid and pc.withdrawn_at is not null
            order by at desc
            limit 500
            """.trimIndent(),
            mapOf("uid" to userId),
        ) { rs, _ ->
            ConsentHistoryEntry(
                at = rs.getTimestamp("at").toInstant(),
                kind = rs.getString("kind"),
                purpose = rs.getString("purpose"),
                action = rs.getString("action"),
                noticeVersion = rs.getString("notice_version"),
                subject = rs.getString("subject"),
                channels = rs.getString("channels")?.split(','),
                askedAgain = rs.getBoolean("asked_again"),
            )
        }
    }

    // --- requests ------------------------------------------------------------

    @Transactional(readOnly = true)
    fun requests(): List<RightsRequest> {
        userContext.require()
        return jdbc.query(
            "$REQUEST_SELECT order by created_at desc limit 200",
            emptyMap<String, Any>(),
        ) { rs, _ -> request(rs) }
    }

    /**
     * The reply-by date is fixed now, from the period published today, and in
     * India's calendar: "we reply by 14 October" means the 14th where the
     * person is.
     */
    @Transactional
    fun createRequest(kind: String, details: String): RightsRequest {
        val userId = userContext.require()
        if (kind !in REQUEST_KINDS) {
            throw ApiException.badRequest("kind_invalid", "Choose one of: ${REQUEST_KINDS.joinToString()}.")
        }
        val text = details.trim()
        if (text.isEmpty()) {
            throw ApiException.badRequest(
                "details_required",
                if (kind == "correction") "Say what is wrong and what it should say." else "Tell us what went wrong.",
            )
        }
        if (text.length > MAX_DETAILS) {
            throw ApiException.badRequest("details_too_long", "Keep it under $MAX_DETAILS characters.")
        }
        val openCount = jdbc.queryForObject(
            "select count(*) from data_rights_requests where status = 'open'",
            emptyMap<String, Any>(), Int::class.java,
        ) ?: 0
        if (openCount >= MAX_OPEN_REQUESTS) {
            throw ApiException.conflict(
                "too_many_open_requests",
                "You have $openCount requests still open. We'll answer those first.",
            )
        }
        val id = UUID.randomUUID()
        jdbc.update(
            """
            insert into data_rights_requests (id, user_id, kind, details, respond_by)
            values (:id, :uid, :kind, :details,
                    (now() at time zone 'Asia/Kolkata')::date + :days)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id).addValue("uid", userId).addValue("kind", kind)
                .addValue("details", text).addValue("days", props.grievance.responseDays),
        )
        // What kind, never what it says: a complaint can name other people.
        audit.record(
            householdId = null, actorUserId = userId, action = "privacy.request_create",
            entityType = "data_rights_request", entityId = id, diff = mapOf("kind" to kind),
        )
        return findRequest(id)
    }

    @Transactional
    fun withdrawRequest(id: UUID): RightsRequest {
        val userId = userContext.require()
        findRequest(id)
        val withdrawn = jdbc.queryForObject(
            "select app.withdraw_data_rights_request(:id)", mapOf("id" to id), Boolean::class.java,
        ) ?: false
        if (!withdrawn) {
            throw ApiException.conflict("request_not_open", "That request has already been answered or withdrawn.")
        }
        audit.record(
            householdId = null, actorUserId = userId, action = "privacy.request_withdraw",
            entityType = "data_rights_request", entityId = id,
        )
        return findRequest(id)
    }

    private fun findRequest(id: UUID): RightsRequest = jdbc.query(
        "$REQUEST_SELECT where id = :id", mapOf("id" to id),
    ) { rs, _ -> request(rs) }.firstOrNull() ?: throw ApiException.notFound("We couldn't find that request.")

    // --- nominees ------------------------------------------------------------

    @Transactional(readOnly = true)
    fun nominees(): List<Nominee> {
        userContext.require()
        return jdbc.query(
            """
            select id, full_name, relationship, contact, created_at from data_rights_nominees
             where revoked_at is null order by created_at
            """.trimIndent(),
            emptyMap<String, Any>(),
        ) { rs, _ ->
            Nominee(
                id = rs.getObject("id", UUID::class.java),
                fullName = rs.getString("full_name"),
                relationship = rs.getString("relationship"),
                contact = rs.getString("contact"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
            )
        }
    }

    /**
     * Naming a nominee needs a fresh confirmation on this session. A nominee may
     * one day ask us to hand over or erase everything; a borrowed unlocked phone
     * should not be enough to arrange that.
     */
    @Transactional
    fun nominate(fullName: String, relationship: String?, contact: String): Nominee {
        val userId = userContext.require()
        stepUp.requireElevated(userId, userContext.currentSessionId())

        val name = fullName.trim()
        if (name.isEmpty()) throw ApiException.badRequest("name_required", "Give the person's name.")
        if (name.length > 120) throw ApiException.badRequest("name_too_long", "Keep the name under 120 characters.")
        val reach = contact.trim().let { raw ->
            if ('@' in raw) {
                EmailAddress.canonicalOrNull(raw)
                    ?: throw ApiException.badRequest("contact_invalid", "That doesn't look like an email address.")
            } else {
                PhoneNumber.normalize(raw)
            }
        }
        val relation = relationship?.trim()?.ifEmpty { null }
        if (relation != null && relation.length > 40) {
            throw ApiException.badRequest("relationship_too_long", "Keep the relationship short.")
        }
        if (nominees().size >= MAX_NOMINEES) {
            throw ApiException.conflict("too_many_nominees", "You can name up to $MAX_NOMINEES people.")
        }
        val id = UUID.randomUUID()
        jdbc.update(
            """
            insert into data_rights_nominees (id, user_id, full_name, relationship, contact)
            values (:id, :uid, :name, :relationship, :contact)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id).addValue("uid", userId).addValue("name", name)
                .addValue("relationship", relation).addValue("contact", reach),
        )
        // Who was named is the user's business; that someone was is the audit's.
        audit.record(
            householdId = null, actorUserId = userId, action = "privacy.nominee_add",
            entityType = "data_rights_nominee", entityId = id,
        )
        return nominees().first { it.id == id }
    }

    /** No step-up: taking a nominee away can only narrow who may act for you. */
    @Transactional
    fun revokeNominee(id: UUID) {
        val userId = userContext.require()
        val updated = jdbc.update(
            "update data_rights_nominees set revoked_at = now() where id = :id and revoked_at is null",
            mapOf("id" to id),
        )
        if (updated == 0) throw ApiException.notFound("We couldn't find that person.")
        audit.record(
            householdId = null, actorUserId = userId, action = "privacy.nominee_revoke",
            entityType = "data_rights_nominee", entityId = id,
        )
    }

    // --- helpers -------------------------------------------------------------

    private fun currentNotice(): Pair<String, Boolean> = jdbc.query(
        """
        select version, legally_reviewed from privacy_notice_versions
         where version = app.current_privacy_notice_version()
        """.trimIndent(),
        emptyMap<String, Any>(),
    ) { rs, _ -> rs.getString("version") to rs.getBoolean("legally_reviewed") }.first()

    private fun consents(): List<ConsentState> {
        val latest = jdbc.query(
            """
            select distinct on (purpose) purpose, action, created_at, array_to_string(channels, ',') as channels
              from consent_events order by purpose, seq desc
            """.trimIndent(),
            emptyMap<String, Any>(),
        ) { rs, _ ->
            rs.getString("purpose") to Latest(
                rs.getString("action"), rs.getTimestamp("created_at").toInstant(), rs.getString("channels")?.split(','),
            )
        }.toMap()
        return PURPOSES.map { purpose ->
            val event = latest[purpose]
            // A yes to messages that names no channels is from before they were chosen:
            // it counts for nothing, and the person is asked again (V142).
            val askingAgain = purpose == MESSAGES && event?.action == "given" && event.channels == null
            val given = if (askingAgain) null else event?.action?.let { it == "given" }
            ConsentState(
                purpose = purpose,
                required = purpose in REQUIRED_PURPOSES,
                given = given,
                changedAt = event?.at,
                channels = if (purpose == MESSAGES && given == true) event?.channels else null,
                askingAgain = askingAgain,
            )
        }
    }

    private data class Latest(val action: String, val at: Instant, val channels: List<String>?)

    private fun request(rs: ResultSet) = RightsRequest(
        id = rs.getObject("id", UUID::class.java),
        kind = rs.getString("kind"),
        details = rs.getString("details"),
        status = rs.getString("status"),
        respondBy = rs.getDate("respond_by").toLocalDate(),
        response = rs.getString("response"),
        answeredAt = rs.getTimestamp("answered_at")?.toInstant(),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        grievance = grievance(),
    )

    companion object {
        /** In the order the page lists them. */
        val PURPOSES = listOf("records", "messages")
        val REQUIRED_PURPOSES = setOf("records")
        const val MESSAGES = "messages"
        /** Every channel a yes to messages can name, in the order they are shown. The same as V125's check. */
        val MESSAGE_CHANNELS = listOf("email", "sms", "whatsapp", "push")
        val ASKED_IN = listOf("settings", "in_context")
        const val STILL_TRUE_DIGEST = "still_true_digest"
        /** What "Not now" can be said beside (V141's check). */
        val ASK_CONTEXTS = listOf("investment", "liability", STILL_TRUE_DIGEST)
        /** How long "Not now" keeps the question away. */
        val NOT_NOW_FOR: java.time.Duration = java.time.Duration.ofDays(90)
        val REQUEST_KINDS = listOf("correction", "grievance")
        val OBLIGATIONS_COMMENCE: LocalDate = LocalDate.of(2027, 5, 13)
        const val MAX_DETAILS = 2000
        const val MAX_NOMINEES = 5
        const val MAX_OPEN_REQUESTS = 10

        private const val REQUEST_SELECT = """
            select id, kind, details, status, respond_by, response, answered_at, created_at
              from data_rights_requests
        """
    }
}

@RestController
@RequestMapping("/api/v1/me/privacy")
class DataRightsController(
    private val service: DataRightsService,
    private val summaries: AccessSummaryService,
) {

    @GetMapping
    fun privacyOverview(): PrivacyOverview = service.overview()

    @PostMapping("/notice/accept")
    fun acceptPrivacyNotice(@RequestBody body: AcceptNoticeBody): PrivacyOverview = service.acceptNotice(body.version)

    @PostMapping("/consents")
    fun changePrivacyConsent(@RequestBody body: ChangeConsentBody): PrivacyOverview =
        service.changeConsent(body.purpose, body.given, body.channels, body.askedIn)

    /** Whether to ask about reminders outside the app now (docs/23 "Asked when it helps"). */
    @GetMapping("/messages-ask")
    fun privacyMessagesAsk(
        @RequestParam(required = false) contextType: String?,
        @RequestParam(required = false) contextId: UUID?,
    ): MessagesAsk = service.messagesAsk(contextType, contextId)

    /** "Not now": not asked again about the same thing for 90 days (V141). Records no consent. */
    @PostMapping("/messages-ask/not-now")
    fun privacyMessagesNotNow(@RequestBody(required = false) body: MessagesAskContextBody?): MessagesAsk =
        service.messagesNotNow(body?.contextType, body?.contextId)

    @GetMapping("/history")
    fun privacyConsentHistory(): List<ConsentHistoryEntry> = service.history()

    /** s.11: what is held about you, what it is used for, and who else can see it. */
    @GetMapping("/summary")
    fun privacyAccessSummary(): AccessSummary = summaries.summary()

    @GetMapping("/requests")
    fun listRightsRequests(): List<RightsRequest> = service.requests()

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    fun createRightsRequest(@RequestBody @Valid body: CreateRightsRequestBody): RightsRequest =
        service.createRequest(body.kind, body.details)

    @PostMapping("/requests/{id}/withdraw")
    fun withdrawRightsRequest(@PathVariable id: UUID): RightsRequest = service.withdrawRequest(id)

    @GetMapping("/nominees")
    fun listRightsNominees(): List<Nominee> = service.nominees()

    @PostMapping("/nominees")
    @ResponseStatus(HttpStatus.CREATED)
    fun nominateRightsNominee(@RequestBody @Valid body: NominateBody): Nominee =
        service.nominate(body.fullName, body.relationship, body.contact)

    @DeleteMapping("/nominees/{id}")
    fun revokeRightsNominee(@PathVariable id: UUID): ResponseEntity<Void> {
        service.revokeNominee(id)
        return ResponseEntity.noContent().build()
    }
}
