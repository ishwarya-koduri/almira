package tech.bhrigu.almira.invitation

import tech.bhrigu.almira.measurement.ProductEvent
import tech.bhrigu.almira.measurement.ProductMeasurement
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.UncategorizedSQLException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.common.PhoneNumber
import tech.bhrigu.almira.household.HouseholdService
import tech.bhrigu.almira.security.JwtService
import tech.bhrigu.almira.security.RequestUserContext
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class InvitationRow(
    val id: UUID,
    val householdId: UUID,
    val memberId: UUID?,
    val memberName: String?,
    val phone: String?,
    val email: String?,
    val role: String,
    val expiresAt: Instant,
    val acceptedAt: Instant?,
    val revokedAt: Instant?,
)

data class CreatedInvitation(val invitation: InvitationRow, val token: String, val link: String)

data class AcceptedInvitation(val householdId: UUID, val memberId: UUID?, val role: String)

@Service
class InvitationService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
    private val jwt: JwtService,
    private val audit: AuditService,
    private val userContext: RequestUserContext,
    private val measurement: ProductMeasurement,
) {
    // "advisor" is a colleague rather than a member of the family: they see only
    // what has been explicitly shared with them, household visibility does not
    // reach them, and they cannot write. Enforced in app.can_read_record, not
    // here (V23).
    private val roles = setOf("owner", "admin", "editor", "viewer", "restricted", "advisor")

    /**
     * The invitee's consent is what makes storing an adult's data lawful under
     * the DPDP Act (docs/05 §8) — so an invitation is an explicit, expiring,
     * revocable artefact rather than an admin quietly adding an account.
     *
     * Only the token's hash is stored. If the database leaks, no invitation in
     * it can be redeemed.
     */
    @Transactional
    fun create(
        householdId: UUID,
        memberId: UUID?,
        phone: String?,
        email: String?,
        role: String,
    ): CreatedInvitation {
        val userId = userContext.require()
        val household = households.get(householdId)
        if (household.myRole !in setOf("owner", "admin")) {
            throw ApiException.forbidden("Only the household owner or an admin can invite people.")
        }
        households.refuseWhileDormant(household)
        if (role !in roles) {
            throw ApiException.badRequest("role_invalid", "Choose one of: ${roles.joinToString()}.")
        }
        if (phone == null && email == null) {
            throw ApiException.badRequest(
                "contact_required", "Add a phone number or email so we can send the invite.",
            )
        }
        val normalisedPhone = phone?.let(PhoneNumber::normalize)
        // Somebody who already signs in here cannot be invited here again. An
        // invitation that names their number reads as though it will give them a
        // login; accepting it can only hand back the member row they already
        // have, and it spends a single-use link to do nothing. Refused before the
        // row is written, so nothing is spent and nobody is told.
        alreadySignsIn(householdId, normalisedPhone, email)?.let { name ->
            throw ApiException.badRequest(
                "already_a_member", "$name already signs in to this household.",
            )
        }

        memberId?.let { target ->
            val member = households.members(householdId).firstOrNull { it.id == target }
                ?: throw ApiException.notFound("We couldn't find that person.")
            if (!member.isManaged) {
                throw ApiException.badRequest(
                    "member_already_linked", "${member.displayName} already has their own login.",
                )
            }
        }

        val token = jwt.newRefreshToken() // same CSPRNG; opaque, single-use
        val expiresAt = Instant.now().plus(14, ChronoUnit.DAYS)
        val id = jdbc.queryForObject(
            """
            insert into invitations
              (household_id, member_id, phone, email, role, token_hash, invited_by, expires_at)
            values (:hid, :memberId, :phone, :email, :role, :hash, :by, :expiresAt)
            returning id
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("hid", householdId)
                .addValue("memberId", memberId)
                .addValue("phone", normalisedPhone)
                .addValue("email", email)
                .addValue("role", role)
                .addValue("hash", jwt.hash(token))
                .addValue("by", userId)
                .addValue("expiresAt", java.sql.Timestamp.from(expiresAt)),
            UUID::class.java,
        )!!

        audit.record(
            householdId = householdId, actorUserId = userId, action = "invitation.create",
            entityType = "invitation", entityId = id, diff = mapOf("role" to role),
        )
        measurement.record(ProductEvent.INVITE_SENT, memberIds = listOf(memberId))
        return CreatedInvitation(
            invitation = list(householdId).first { it.id == id },
            token = token,
            link = "almira://invite/$token",
        )
    }

    /**
     * The name this household knows an active member by, when the number or
     * address is already theirs. Null when nobody here signs in with it.
     */
    private fun alreadySignsIn(householdId: UUID, phone: String?, email: String?): String? {
        if (phone == null && email == null) return null
        return jdbc.query(
            """
            select coalesce(m.display_name, u.full_name, 'Someone') as name
              from household_memberships hm
              join users u on u.id = hm.user_id
              left join members m on m.household_id = hm.household_id and m.user_id = hm.user_id
                                 and m.deleted_at is null
             where hm.household_id = :hid and hm.status = 'active'
               and ((cast(:phone as text) is not null and u.phone = cast(:phone as text))
                 or (cast(:email as text) is not null and u.email = cast(:email as text)))
             limit 1
            """.trimIndent(),
            MapSqlParameterSource().addValue("hid", householdId)
                .addValue("phone", phone).addValue("email", email),
        ) { rs, _ -> rs.getString("name") }.firstOrNull()
    }

    @Transactional(readOnly = true)
    fun list(householdId: UUID): List<InvitationRow> {
        households.get(householdId)
        return jdbc.query(
            """
            select i.*, m.display_name as member_name
            from invitations i
            left join members m on m.id = i.member_id
            where i.household_id = :hid
            order by i.created_at desc
            """.trimIndent(),
            mapOf("hid" to householdId),
        ) { rs, _ ->
            InvitationRow(
                id = rs.getObject("id", UUID::class.java),
                householdId = rs.getObject("household_id", UUID::class.java),
                memberId = rs.getObject("member_id", UUID::class.java),
                memberName = rs.getString("member_name"),
                phone = rs.getString("phone")?.let(PhoneNumber::mask),
                email = rs.getString("email"),
                role = rs.getString("role"),
                expiresAt = rs.getTimestamp("expires_at").toInstant(),
                acceptedAt = rs.getTimestamp("accepted_at")?.toInstant(),
                revokedAt = rs.getTimestamp("revoked_at")?.toInstant(),
            )
        }
    }

    @Transactional
    fun revoke(householdId: UUID, invitationId: UUID) {
        val userId = userContext.require()
        val household = households.get(householdId)
        if (household.myRole !in setOf("owner", "admin")) {
            throw ApiException.forbidden("Only the household owner or an admin can do that.")
        }
        households.refuseWhileDormant(household)
        val updated = jdbc.update(
            """
            update invitations set revoked_at = now()
            where id = :id and household_id = :hid and accepted_at is null and revoked_at is null
            """.trimIndent(),
            mapOf("id" to invitationId, "hid" to householdId),
        )
        if (updated == 0) throw ApiException.notFound("We couldn't find that invitation.")
        audit.record(
            householdId = householdId, actorUserId = userId, action = "invitation.revoke",
            entityType = "invitation", entityId = invitationId,
        )
    }

    /** Claims the managed member row where one was named — merge, never duplicate. */
    @Transactional
    fun accept(token: String): AcceptedInvitation = redeem(token)

    private fun redeem(token: String): AcceptedInvitation = try {
        jdbc.queryForObject(
            """
            select out_household_id as household_id,
                   out_member_id    as member_id,
                   out_role         as role
            from app.accept_invitation(:hash)
            """.trimIndent(),
            mapOf("hash" to jwt.hash(token)),
        ) { rs, _ ->
            AcceptedInvitation(
                householdId = rs.getObject("household_id", UUID::class.java),
                memberId = rs.getObject("member_id", UUID::class.java),
                role = rs.getString("role"),
            )
        }!!.also { measurement.record(ProductEvent.INVITE_ACCEPTED, memberIds = listOf(it.memberId)) }
    } catch (e: EmptyResultDataAccessException) {
        throw ApiException.notFound("That invitation link isn't valid.")
    } catch (e: UncategorizedSQLException) {
        throw translate(e.sqlException?.message ?: e.mostSpecificCause.message ?: "")
    } catch (e: org.springframework.dao.DataAccessException) {
        // Every refusal the function raises comes back as one of these, and which
        // subclass depends on the SQLSTATE it chose. What it says is the thing to
        // read: joining a dormant household waits (V135), and an invitation for
        // somebody who already signs in here is refused (V150). Both are raised
        // before anything is written.
        throw translate(e.mostSpecificCause.message ?: "")
    }

    private fun joiningWaits() = ApiException(
        org.springframework.http.HttpStatus.FORBIDDEN, HouseholdService.DORMANT_CODE,
        "Nobody runs that household at the moment, so nobody can join it until someone there " +
            "takes it on. Ask them again after that.",
    )

    private fun translate(text: String): ApiException {
        return when {
            "household_dormant" in text -> joiningWaits()
            "invitation_not_found" in text ->
                ApiException.notFound("That invitation link isn't valid.")
            "already_a_member" in text ->
                ApiException.conflict(
                    "already_a_member",
                    "You already sign in to this household, so this invitation is for somebody else.",
                )
            "invitation_used" in text ->
                ApiException.conflict("invitation_used", "That invitation has already been used.")
            "invitation_revoked" in text ->
                ApiException.badRequest("invitation_revoked", "That invitation was cancelled.")
            "invitation_expired" in text ->
                ApiException.badRequest(
                    "invitation_expired", "That invitation has expired. Ask for a new one.",
                )
            "invitation_not_found" in text -> ApiException.notFound("That invitation link isn't valid.")
            else -> ApiException.badRequest("invitation_invalid", "We couldn't use that invitation.")
        }
    }
}
