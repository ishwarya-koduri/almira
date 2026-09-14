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
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.auth.StepUpService
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.common.EmailAddress
import tech.bhrigu.almira.common.PhoneNumber
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
 * [given] is null when they have never been asked — everyone who signed up
 * before this notice. `records` is [required]: it is the service itself, and
 * withdrawing it is closing the account.
 */
data class ConsentState(
    val purpose: String,
    val required: Boolean,
    val given: Boolean?,
    val changedAt: Instant?,
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
)

data class AcceptNoticeBody(val version: String)

data class ChangeConsentBody(val purpose: String, val given: Boolean)

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

    /**
     * One tap either way. Asking for the state you are already in writes
     * nothing, so the history shows decisions rather than button presses.
     */
    @Transactional
    fun changeConsent(purpose: String, given: Boolean): PrivacyOverview {
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
        val current = consents().first { it.purpose == purpose }
        if (current.given != given) {
            jdbc.update(
                """
                insert into consent_events (user_id, purpose, action, notice_version)
                values (:uid, :purpose, :action, app.current_privacy_notice_version())
                """.trimIndent(),
                mapOf("uid" to userId, "purpose" to purpose, "action" to if (given) "given" else "withdrawn"),
            )
            audit.record(
                householdId = null, actorUserId = userId,
                action = if (given) "privacy.consent_give" else "privacy.consent_withdraw",
                diff = mapOf("purpose" to purpose),
            )
        }
        return overview()
    }

    /** Consent events, notice acceptances and the parental consents this person gave, newest first. */
    @Transactional(readOnly = true)
    fun history(): List<ConsentHistoryEntry> {
        val userId = userContext.require()
        return jdbc.query(
            """
            select created_at as at, 'consent' as kind, purpose, action, notice_version, null as subject
              from consent_events
            union all
            select accepted_at, 'notice', null, 'accepted', notice_version, null
              from privacy_notice_acceptances
            union all
            select pc.given_at, 'parental_consent', null, 'given', pc.notice_version, m.display_name
              from parental_consents pc join members m on m.id = pc.member_id
             where pc.given_by = :uid
            union all
            select pc.withdrawn_at, 'parental_consent', null, 'withdrawn', pc.notice_version, m.display_name
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
            select distinct on (purpose) purpose, action, created_at
              from consent_events order by purpose, seq desc
            """.trimIndent(),
            emptyMap<String, Any>(),
        ) { rs, _ -> rs.getString("purpose") to (rs.getString("action") to rs.getTimestamp("created_at").toInstant()) }
            .toMap()
        return PURPOSES.map { purpose ->
            val event = latest[purpose]
            ConsentState(
                purpose = purpose,
                required = purpose in REQUIRED_PURPOSES,
                given = event?.first?.let { it == "given" },
                changedAt = event?.second,
            )
        }
    }

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
        service.changeConsent(body.purpose, body.given)

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
