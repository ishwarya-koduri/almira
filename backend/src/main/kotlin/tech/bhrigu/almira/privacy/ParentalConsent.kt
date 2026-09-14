package tech.bhrigu.almira.privacy

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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
import tech.bhrigu.almira.household.HouseholdService
import tech.bhrigu.almira.security.RequestUserContext
import java.time.Instant
import java.util.UUID

/** The dated consent line on a child's profile. */
data class ParentalConsentLine(
    val id: UUID,
    val memberId: UUID,
    /** The name the household knows the adult by, or null if they have since left it. */
    val givenByName: String?,
    val givenByMe: Boolean,
    /** parent | lawful_guardian */
    val capacity: String,
    /** How the adult was checked: step_up_code today. */
    val verification: String,
    val noticeVersion: String,
    val givenAt: Instant,
    val withdrawnAt: Instant?,
)

data class GiveParentalConsentBody(
    val capacity: String,
    /** "I am 18 or older, and this child's parent or lawful guardian." Must be true. */
    val confirmAdult: Boolean = false,
)

/**
 * A parent's consent for a child's records (DPDP Act s.9, Rule 10; docs/05 §6).
 *
 * A child in Almira has no login and says nothing for themselves: an adult
 * types their name, date of birth and holdings in. The Act asks for verifiable
 * consent from a parent before any of a child's personal data is processed.
 * There is no official guidance for the case where the parent IS the person
 * entering the data, so this is designed for it: the adult records, once, that
 * they are doing so as the child's parent or lawful guardian, and confirms it
 * is really them with a fresh code to their own sign-in address.
 *
 * What that code proves is who is signed in, not that they are over eighteen.
 * docs/known-issues.md ("Parental consent checks the adult's sign-in, not their
 * age") says what a stronger check would take.
 *
 * Reads follow the roster: whoever can see the child sees the consent line.
 * Only the adult who gave consent may withdraw it; anyone else in the household
 * gets 403, because the line itself is visible to them (a 404 would be a lie
 * about something they can see, not a protection).
 */
@Service
class ParentalConsentService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
    private val stepUp: StepUpService,
    private val audit: AuditService,
    private val userContext: RequestUserContext,
) {

    @Transactional(readOnly = true)
    fun list(householdId: UUID): List<ParentalConsentLine> {
        val userId = userContext.require()
        households.get(householdId)
        return jdbc.query(
            "$SELECT where pc.household_id = :hid order by pc.given_at desc",
            mapOf("hid" to householdId, "uid" to userId),
        ) { rs, _ -> line(rs) }
    }

    @Transactional
    fun give(householdId: UUID, memberId: UUID, capacity: String, confirmAdult: Boolean): ParentalConsentLine {
        val userId = userContext.require()
        households.get(householdId)
        val child = jdbc.query(
            """
            select m.user_id, app.is_minor(m.date_of_birth) as is_minor
              from members m
             where m.id = :mid and m.household_id = :hid and m.deleted_at is null
            """.trimIndent(),
            mapOf("mid" to memberId, "hid" to householdId),
        ) { rs, _ -> (rs.getObject("user_id", UUID::class.java) != null) to rs.getBoolean("is_minor") }
            .firstOrNull() ?: throw ApiException.notFound("We couldn't find that person.")

        if (capacity !in CAPACITIES) {
            throw ApiException.badRequest("capacity_invalid", "Choose parent or lawful guardian.")
        }
        if (child.first) {
            throw ApiException.badRequest(
                "has_own_login",
                "This person signs in for themselves, so nobody consents on their behalf.",
            )
        }
        if (!child.second) {
            throw ApiException.badRequest(
                "not_a_minor",
                "Parental consent is for someone under 18. Add their date of birth first.",
            )
        }
        if (!confirmAdult) {
            throw ApiException.badRequest(
                "adult_confirmation_required",
                "Confirm that you are 18 or older and this child's parent or guardian.",
            )
        }
        // After the checks that need no code, so a mistake is not paid for with one.
        stepUp.requireElevated(userId, userContext.currentSessionId())

        val live = jdbc.queryForObject(
            "select count(*) from parental_consents where member_id = :mid and withdrawn_at is null",
            mapOf("mid" to memberId), Int::class.java,
        ) ?: 0
        if (live > 0) {
            throw ApiException.conflict("consent_exists", "A parent's consent is already recorded for this child.")
        }

        val id = UUID.randomUUID()
        jdbc.update(
            """
            insert into parental_consents
              (id, household_id, member_id, given_by, capacity, verification, notice_version)
            values (:id, :hid, :mid, :uid, :capacity, 'step_up_code', app.current_privacy_notice_version())
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id).addValue("hid", householdId).addValue("mid", memberId)
                .addValue("uid", userId).addValue("capacity", capacity),
        )
        audit.record(
            householdId = householdId, actorUserId = userId, action = "privacy.parental_consent_give",
            entityType = "member", entityId = memberId, diff = mapOf("capacity" to capacity),
        )
        return find(householdId, id, userId)
    }

    @Transactional
    fun withdraw(householdId: UUID, consentId: UUID): ParentalConsentLine {
        val userId = userContext.require()
        households.get(householdId)
        val current = find(householdId, consentId, userId)
        if (!current.givenByMe) {
            throw ApiException(
                HttpStatus.FORBIDDEN, "not_your_consent",
                "Only the person who gave this consent can withdraw it.",
            )
        }
        if (current.withdrawnAt != null) return current
        jdbc.queryForObject(
            "select app.withdraw_parental_consent(:id)", mapOf("id" to consentId), Boolean::class.java,
        )
        audit.record(
            householdId = householdId, actorUserId = userId, action = "privacy.parental_consent_withdraw",
            entityType = "member", entityId = current.memberId,
        )
        return find(householdId, consentId, userId)
    }

    private fun find(householdId: UUID, id: UUID, userId: UUID): ParentalConsentLine = jdbc.query(
        "$SELECT where pc.household_id = :hid and pc.id = :id",
        mapOf("hid" to householdId, "id" to id, "uid" to userId),
    ) { rs, _ -> line(rs) }.firstOrNull() ?: throw ApiException.notFound("We couldn't find that consent.")

    private fun line(rs: java.sql.ResultSet) = ParentalConsentLine(
        id = rs.getObject("id", UUID::class.java),
        memberId = rs.getObject("member_id", UUID::class.java),
        givenByName = rs.getString("given_by_name"),
        givenByMe = rs.getBoolean("given_by_me"),
        capacity = rs.getString("capacity"),
        verification = rs.getString("verification"),
        noticeVersion = rs.getString("notice_version"),
        givenAt = rs.getTimestamp("given_at").toInstant(),
        withdrawnAt = rs.getTimestamp("withdrawn_at")?.toInstant(),
    )

    private companion object {
        val CAPACITIES = setOf("parent", "lawful_guardian")

        const val SELECT = """
            select pc.id, pc.member_id, pc.capacity, pc.verification, pc.notice_version,
                   pc.given_at, pc.withdrawn_at, pc.given_by = :uid as given_by_me,
                   (select a.display_name from members a
                     where a.household_id = pc.household_id and a.user_id = pc.given_by
                       and a.deleted_at is null limit 1) as given_by_name
              from parental_consents pc
        """
    }
}

@RestController
@RequestMapping("/api/v1/households/{householdId}")
class ParentalConsentController(private val service: ParentalConsentService) {

    @GetMapping("/parental-consents")
    fun listParentalConsents(@PathVariable householdId: UUID): List<ParentalConsentLine> = service.list(householdId)

    /** Needs a recent step-up on this session: that is the one-time check. */
    @PostMapping("/members/{memberId}/parental-consent")
    @ResponseStatus(HttpStatus.CREATED)
    fun giveParentalConsent(
        @PathVariable householdId: UUID,
        @PathVariable memberId: UUID,
        @RequestBody body: GiveParentalConsentBody,
    ): ParentalConsentLine = service.give(householdId, memberId, body.capacity, body.confirmAdult)

    @PostMapping("/parental-consents/{consentId}/withdraw")
    fun withdrawParentalConsent(@PathVariable householdId: UUID, @PathVariable consentId: UUID): ParentalConsentLine =
        service.withdraw(householdId, consentId)
}
