package tech.almira.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tech.almira.audit.AuditService
import tech.almira.common.ApiException
import tech.almira.common.EmailAddress
import tech.almira.config.AlmiraProperties
import tech.almira.security.RequestUserContext
import java.time.Instant
import java.util.UUID

/** An address this account has proved it receives mail at. */
data class VerifiedEmail(
    val id: UUID,
    val address: String,
    val verifiedAt: Instant,
    val lastSentAt: Instant?,
)

data class VerifiedEmailsResponse(
    val addresses: List<VerifiedEmail>,
    /** So a client can grey the button out before the refusal rather than after it. */
    val max: Int,
)

// --- requests ---------------------------------------------------------------

data class AddEmailBody(
    @field:NotBlank(message = "Enter the email address to send to")
    @field:Size(max = 254, message = "That address is too long")
    val address: String,
)

data class ConfirmEmailBody(
    @field:NotBlank(message = "Enter the email address to send to")
    @field:Size(max = 254, message = "That address is too long")
    val address: String,
    @field:Pattern(regexp = "^[0-9]{6,8}$", message = "Enter the code we emailed you")
    val code: String,
    @field:Size(max = 100)
    val requestId: String? = null,
) {
    /** A data class prints every field. This one must never print the code. */
    override fun toString() = "ConfirmEmailBody(address=[redacted], code=[redacted], requestId=$requestId)"
}

/**
 * Addresses an account has proved it receives mail at.
 *
 * This exists for one reason: an export must never be emailed to an address
 * somebody merely typed. A transposed character in a domain sends a household's
 * whole financial record to a stranger, and an email cannot be recalled. So
 * before an address can be a destination, a one-time code goes to it and has to
 * come back — the proof a phone number needs before it can be the number on the
 * account, with the channel swapped.
 *
 * **It is not a way to sign in.** The identity column is `users.email`, it is
 * unique, and AuthRepository looks accounts up by it. Nothing here writes to it
 * and nothing in sign-in reads this table (V153). Somewhere to send a file and
 * a way into the account are different things, and the day they are the same
 * column is the day adding a destination quietly adds a front door.
 *
 * **Enumeration.** The request path answers identically for every address,
 * because it has nothing to find out: there is no global uniqueness to violate,
 * so an address already proved somewhere else is neither refused nor delayed
 * here, and two accounts may prove the same one. That is not a compromise for
 * privacy's sake — one mailbox shared by a couple is a household, not a
 * conflict.
 *
 * **Step-up, not spent.** Someone holding a stolen session must not be able to
 * add their own address and then mail themselves the money, so every change
 * here needs a session that has recently confirmed it is you. It does not
 * consume that elevation: within the window a person may prove two addresses
 * without confirming twice, exactly as they may reveal two account numbers
 * (StepUpService.requireElevated). Each proof is audited on its own, so the
 * record shows the addresses, not merely that a step-up happened.
 */
/**
 * The rows, in their own bean on purpose.
 *
 * Every method here is transactional because the identity row-level security
 * matches on is set with `set_config(..., is_local => true)`: a statement
 * outside a transaction carries none, every policy denies, and the read comes
 * back empty instead of failing (RlsTransactionManager says exactly this).
 *
 * And a bean of its own rather than private methods on the service, because
 * Spring's `@Transactional` is a proxy: a service calling its own annotated
 * method goes straight to the method and gets no transaction at all. That is
 * not theoretical here — it is the bug this class was extracted to fix, and it
 * presented as an address cap that counted zero however many addresses were
 * held.
 */
@Repository
class VerifiedEmailRepository(private val jdbc: NamedParameterJdbcTemplate) {

    @Transactional(readOnly = true)
    fun list(userId: UUID): List<VerifiedEmail> = jdbc.query(
        """
        select id, address, verified_at, last_sent_at from verified_email_addresses
        where user_id = :user order by verified_at desc
        """.trimIndent(),
        MapSqlParameterSource("user", userId),
    ) { rs, _ ->
        VerifiedEmail(
            id = rs.getObject("id", UUID::class.java),
            address = rs.getString("address"),
            verifiedAt = rs.getTimestamp("verified_at").toInstant(),
            lastSentAt = rs.getTimestamp("last_sent_at")?.toInstant(),
        )
    }

    @Transactional(readOnly = true)
    fun addresses(userId: UUID): List<String> = list(userId).map { it.address }

    /** Upsert, so proving an address a second time refreshes it rather than failing. */
    @Transactional
    fun prove(userId: UUID, address: String): UUID = jdbc.queryForObject(
        """
        insert into verified_email_addresses (user_id, address) values (:user, :address)
        on conflict on constraint verified_email_addresses_one_per_account
          do update set verified_at = now()
        returning id
        """.trimIndent(),
        MapSqlParameterSource("user", userId).addValue("address", address),
        UUID::class.java,
    )!!

    @Transactional(readOnly = true)
    fun addressOf(userId: UUID, id: UUID): String? = jdbc.query(
        "select address from verified_email_addresses where id = :id and user_id = :user",
        MapSqlParameterSource("id", id).addValue("user", userId),
    ) { rs, _ -> rs.getString("address") }.firstOrNull()

    @Transactional
    fun remove(userId: UUID, id: UUID): Int = jdbc.update(
        "delete from verified_email_addresses where id = :id and user_id = :user",
        MapSqlParameterSource("id", id).addValue("user", userId),
    )
}

@Service
class VerifiedEmailService(
    private val rows: VerifiedEmailRepository,
    private val otp: OtpService,
    private val stepUp: StepUpService,
    private val audit: AuditService,
    props: AlmiraProperties,
) {

    private val max = props.exports.email.maxAddressesPerAccount

    fun list(userId: UUID): List<VerifiedEmail> = rows.list(userId)

    /**
     * Step one: a code to the address, to find out whether it is really theirs.
     *
     * Deliberately not transactional. The send happens here, and holding a
     * database connection open across an SMTP conversation ties a pooled
     * resource to somebody else's mail server. The reads below take their own
     * short transactions instead.
     */
    fun add(userId: UUID, sessionId: UUID?, rawAddress: String, ip: String?): OtpChallenge {
        stepUp.requireElevated(userId, sessionId, PROVE_EMAIL_STEP_UP)
        val address = EmailAddress.normalize(rawAddress)
        val held = rows.addresses(userId)
        if (address in held) {
            throw ApiException.badRequest(
                "email_already_proved",
                "That address is already on your account.",
            )
        }
        // Before the code, not after: a refusal that arrives only once somebody
        // has gone to their inbox and typed six digits is a refusal that wasted
        // their time and sent a pointless email.
        if (held.size >= max) throw tooMany()
        return otp.requestByEmail(address, ip, OtpService.PROVE_EMAIL, OtpDelivery.REPORTED)
    }

    /**
     * Step two: the code from the address. Only now is it a destination.
     *
     * The cap is checked again here. Between the two steps a person can have
     * proved other addresses in another tab, and a limit enforced only on the
     * way in is a limit with a window in it.
     */
    fun confirm(
        userId: UUID,
        sessionId: UUID?,
        rawAddress: String,
        code: String,
        requestId: String?,
        ip: String?,
        userAgent: String?,
    ): VerifiedEmail {
        stepUp.requireElevated(userId, sessionId, PROVE_EMAIL_STEP_UP)
        val address = EmailAddress.normalize(rawAddress)
        otp.verifyByEmail(address, code, requestId, OtpService.PROVE_EMAIL, ip)
        val held = rows.addresses(userId)
        if (address !in held && held.size >= max) throw tooMany()

        val id = rows.prove(userId, address)
        audit.record(
            householdId = null, actorUserId = userId, action = "auth.email_proved",
            entityType = "verified_email_address", entityId = id,
            // Masked, like every other address this product writes down: the
            // domain and one letter say enough to recognise it, and an audit
            // trail is not a place to keep a list of people's addresses.
            diff = mapOf("address" to EmailAddress.mask(address)),
            ip = ip, userAgent = userAgent,
        )
        return rows.list(userId).first { it.id == id }
    }

    /**
     * The guard: this address, proved by this account, or a refusal.
     *
     * **Not part of the feature flag, and it cannot be made part of one.** The
     * flag decides whether emailing an export is offered; nothing decides
     * whether the destination has to be proved. So this lives on an
     * unconditional bean and `almira.exports.email.enabled` gates only the
     * controller in front of it — turning the feature on cannot turn this off,
     * because there is no setting that does.
     *
     * One refusal for both cases, on purpose. "You have no proved addresses"
     * and "you have some, but not that one" are the same `email_not_proved` in
     * the same words, and an address proved by somebody else is refused exactly
     * as one nobody has proved. Otherwise the difference between the answers is
     * a way to ask who has an account here.
     */
    fun requireProved(userId: UUID, rawAddress: String): VerifiedEmail {
        val address = EmailAddress.normalize(rawAddress)
        return rows.list(userId).firstOrNull { it.address.equals(address, ignoreCase = true) }
            ?: throw ApiException(
                HttpStatus.FORBIDDEN,
                "email_not_proved",
                "We only send to an address you have confirmed. Add it to your account, " +
                    "confirm the code we email you, and try again.",
            )
    }

    /**
     * Taking one off. No step-up: losing a destination cannot lose anybody
     * anything, and a person who suspects an address is no longer theirs should
     * be able to remove it in the moment rather than after a code.
     */
    fun remove(userId: UUID, id: UUID, ip: String?, userAgent: String?) {
        // The same answer for a row that is somebody else's and a row that
        // never existed.
        val address = rows.addressOf(userId, id)
            ?: throw ApiException.notFound("We couldn't find that address on your account.")
        rows.remove(userId, id)
        audit.record(
            householdId = null, actorUserId = userId, action = "auth.email_removed",
            entityType = "verified_email_address", entityId = id,
            diff = mapOf("address" to EmailAddress.mask(address)),
            ip = ip, userAgent = userAgent,
        )
    }

    private fun tooMany() = ApiException.badRequest(
        "too_many_addresses",
        "You can keep $max addresses to send to. Remove one you no longer use, then add this.",
    )

    companion object {
        const val PROVE_EMAIL_STEP_UP =
            "For your security, confirm it's you before adding an email address."
    }
}

/**
 * The account's destination addresses.
 *
 * The whole controller is conditional on `almira.exports.email.enabled`. With
 * the flag off the bean does not exist, so these paths are not routes and the
 * answer is the ordinary 404 for something that was never there — rather than a
 * 403 that tells anyone asking that the feature exists and is switched off.
 */
@RestController
@RequestMapping("/api/v1/me/email-addresses")
@ConditionalOnProperty(name = ["almira.exports.email.enabled"], havingValue = "true")
class VerifiedEmailController(
    private val emails: VerifiedEmailService,
    private val userContext: RequestUserContext,
    props: AlmiraProperties,
) {

    private val max = props.exports.email.maxAddressesPerAccount

    @GetMapping
    fun list(): VerifiedEmailsResponse =
        VerifiedEmailsResponse(emails.list(userContext.require()), max)

    @PostMapping
    fun add(
        @RequestBody @Valid body: AddEmailBody,
        request: HttpServletRequest,
    ): OtpChallengeResponse = emails.add(
        userContext.require(), userContext.currentSessionId(), body.address, request.remoteAddr,
    ).let {
        OtpChallengeResponse(
            it.requestId, it.expiresInSeconds, it.resendAfterSeconds, it.developmentCode, it.channel.key,
        )
    }

    @PostMapping("/confirm")
    fun confirm(
        @RequestBody @Valid body: ConfirmEmailBody,
        request: HttpServletRequest,
    ): VerifiedEmail = emails.confirm(
        userContext.require(), userContext.currentSessionId(),
        body.address, body.code, body.requestId,
        request.remoteAddr, request.getHeader("User-Agent"),
    )

    @DeleteMapping("/{id}")
    fun remove(
        @PathVariable id: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        emails.remove(userContext.require(), id, request.remoteAddr, request.getHeader("User-Agent"))
        return ResponseEntity.noContent().build()
    }
}
