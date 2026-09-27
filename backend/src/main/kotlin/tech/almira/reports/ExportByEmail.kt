package tech.almira.reports

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tech.almira.audit.AuditService
import tech.almira.auth.StepUpService
import tech.almira.auth.VerifiedEmail
import tech.almira.auth.VerifiedEmailService
import tech.almira.common.ApiException
import tech.almira.common.EmailAddress
import tech.almira.common.RateLimit
import tech.almira.config.AlmiraProperties
import tech.almira.household.HouseholdService
import tech.almira.provider.ChannelSender
import tech.almira.provider.ProviderMode
import tech.almira.reminder.OutboundNotification
import tech.almira.security.RequestUserContext
import tech.almira.sharing.ShareService
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class EmailExportBody(
    @field:NotBlank(message = "Choose an address to send to")
    @field:Size(max = 254, message = "That address is too long")
    val address: String,
    @field:Pattern(regexp = "^(csv|xlsx|pdf)$", message = "Choose csv, xlsx or pdf")
    val format: String = "csv",
)

/** What was sent, and for how long it can be opened. Never the link itself. */
data class SentExport(
    /** The link's id in this household's shares, so it can be watched or withdrawn. */
    val shareId: UUID,
    /** Masked: the domain and one letter, the same as everywhere else. */
    val sentTo: String,
    val expiresAt: Instant,
    val opensAllowed: Int,
    val records: Int,
    /**
     * The link, echoed in the response ONLY in development and only when the
     * email went to the sandbox — exactly as a one-time code is
     * ([OtpChallenge.developmentCode]). There is nowhere else to read it: the
     * token is stored as a hash and exists in the person's inbox and nowhere
     * else, which is the point of it.
     */
    val developmentLink: String? = null,
)

/**
 * Emailing an export: a link that expires, never the file.
 *
 * A file emailed as an attachment is in that inbox forever, and in every relay
 * it passed through on the way, and no expiry, revocation or view limit this
 * product can offer reaches any of those copies. A link is the opposite: it
 * lives in one row that the person owns, it stops working on its own, they can
 * withdraw it, and every time it is opened is written down. So the email
 * carries a link, and the link is an ordinary guest share
 * ([ShareService.createExportLink]) — the same token, checks, view log and
 * revocation that a link to a CA has had since V20.
 *
 * Four refusals before anything is made, in this order, and the order is the
 * design:
 *
 *  1. **A step-up.** Emailing leaves the device for an inbox that can be
 *     forwarded and cannot be recalled, which is a larger disclosure than
 *     downloading to the phone already in your hand. The full-account export
 *     has needed one since it was written; this is the same rule for the same
 *     reason, and the owner's call (2026-09-27).
 *  2. **The household**, which one you cannot see is *not found* — asking for
 *     it to be emailed must not become a way to learn it exists.
 *  3. **The address**, which must be one this account has proved. Unconditional
 *     and not part of the feature flag: see [VerifiedEmailService.requireProved].
 *  4. **The hour's count**, through the product's one rate limiter — the same
 *     counter sign-in codes and guest links use, so there is one implementation
 *     of "too many" and one shape of 429.
 *
 * A send that cannot be delivered withdraws the link it just made. A live link
 * nobody was told about is not harmless: it is a token sitting in a database
 * with an expiry on it and no reason to exist.
 */
@Service
class ExportByEmailService(
    private val households: HouseholdService,
    private val destinations: VerifiedEmailService,
    private val shares: ShareService,
    private val stepUp: StepUpService,
    private val audit: AuditService,
    private val userContext: RequestUserContext,
    channels: List<ChannelSender>,
    redis: StringRedisTemplate,
    props: AlmiraProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val config = props.exports.email
    private val development = props.isDevelopment
    private val email: ChannelSender? = channels.firstOrNull { it.channel == "email" }

    /** The product's one rate limiter, the same one sign-in counts with. */
    private val limits = RateLimit(redis)

    fun send(
        userId: UUID,
        sessionId: UUID?,
        householdId: UUID,
        rawAddress: String,
        format: String,
        baseUrl: String,
        ip: String?,
        userAgent: String?,
    ): SentExport {
        stepUp.requireElevated(userId, sessionId, EMAIL_EXPORT_STEP_UP)
        val destination = check(userId, householdId, rawAddress)

        // After the guards and before the work: a caller refused above must not
        // spend one of the hour's sends, and a caller over the hour must not
        // reach the making of a link.
        limits.take(
            "exports:email:$userId", config.maxPerHour, Duration.ofHours(1),
            "You've sent a lot of exports in the last hour. Please try again later.",
        )

        val sender = email
        if (sender == null || sender.mode == ProviderMode.DISABLED || sender.mode == ProviderMode.OFF) {
            // Refused before a link exists, not after: a token minted for an
            // email that was never sent is a live credential nobody knows about.
            throw ApiException.serviceUnavailable(
                "email_unavailable",
                "We can't send email at the moment. Please download the file instead.",
            )
        }

        val link = shares.createExportLink(householdId, format, config.linkTtl, config.maxOpens, baseUrl)

        try {
            sender.send(
                OutboundNotification(
                    userId = userId,
                    householdId = householdId,
                    reminderId = null,
                    template = TEMPLATE,
                    title = SUBJECT,
                    body = body(link, format),
                ),
                destination.address,
                // One email per link, so a retry of this one call cannot send twice.
                "$TEMPLATE:${link.shareId}",
            )
        } catch (failure: Exception) {
            // The link was made for this email. Without the email it is a token
            // with an expiry and no purpose, so it goes back.
            runCatching { shares.revoke(householdId, link.shareId) }
                .onFailure { log.warn("could not withdraw the link for an export that was not sent", it) }
            throw failure
        }

        destinations.markSent(userId, destination.id)
        audit.record(
            householdId = householdId, actorUserId = userId, action = "export.emailed",
            entityType = "guest_share", entityId = link.shareId,
            diff = mapOf(
                "format" to format,
                "records" to link.records,
                "to" to EmailAddress.mask(destination.address),
                "expiresAt" to link.expiresAt.toString(),
            ),
            ip = ip, userAgent = userAgent,
        )

        return SentExport(
            shareId = link.shareId,
            sentTo = EmailAddress.mask(destination.address),
            expiresAt = link.expiresAt,
            opensAllowed = link.opensAllowed,
            records = link.records,
            developmentLink = link.url.takeIf { development && sender.mode == ProviderMode.SANDBOX },
        )
    }

    /**
     * The household and the address, both read under row-level security, so
     * both need a transaction: a statement outside one carries no identity and
     * the household would be missing for its own owner (RlsTransactionManager).
     *
     * Its own method, and not around the sending, because the sending talks to
     * a mail server and a database connection must not be held open across
     * somebody else's SMTP conversation.
     */
    @Transactional(readOnly = true)
    fun check(userId: UUID, householdId: UUID, rawAddress: String): VerifiedEmail {
        households.get(householdId)
        return destinations.requireProved(userId, rawAddress)
    }

    private fun body(link: ShareService.ExportLink, format: String): String {
        val days = link.expiresAt.let { Duration.between(Instant.now(), it).toDays().coerceAtLeast(1) }
        return buildString {
            append("You asked Almira for your records as ${format.uppercase()}. ")
            append("Here they are: ${link.url}\n\n")
            append("${link.records} ${if (link.records == 1) "record" else "records"}, ")
            append("as they were when you asked.\n")
            append("The link works for $days ${if (days == 1L) "day" else "days"}, ")
            append("or ${link.opensAllowed} opens, whichever comes first. ")
            append("You can withdraw it sooner from Almira.\n\n")
            append("If you didn't ask for this, withdraw the link and check who can get into your account.")
        }
    }

    companion object {
        const val TEMPLATE = "export.link"
        /** No household name in it: a subject line is shown in inbox lists and logged. */
        const val SUBJECT = "Your Almira export"
        const val EMAIL_EXPORT_STEP_UP =
            "For your security, confirm it's you before emailing your records."
    }
}

@RestController
@RequestMapping("/api/v1/households/{householdId}/reports/export")
@ConditionalOnProperty(name = ["almira.exports.email.enabled"], havingValue = "true")
class ExportByEmailController(
    private val emails: ExportByEmailService,
    private val userContext: RequestUserContext,
) {

    @PostMapping("/email")
    fun email(
        @PathVariable householdId: UUID,
        @RequestBody @Valid body: EmailExportBody,
        request: HttpServletRequest,
    ): SentExport = emails.send(
        userId = userContext.require(),
        sessionId = userContext.currentSessionId(),
        householdId = householdId,
        rawAddress = body.address,
        format = body.format,
        baseUrl = baseUrl(request),
        ip = request.remoteAddr,
        userAgent = request.getHeader("User-Agent"),
    )

    /** The same origin the share links use, so one link in an email is like another. */
    private fun baseUrl(request: HttpServletRequest): String {
        val scheme = request.getHeader("X-Forwarded-Proto") ?: request.scheme
        val host = request.getHeader("X-Forwarded-Host") ?: request.getHeader("Host")
            ?: "${request.serverName}:${request.serverPort}"
        return "$scheme://$host"
    }
}
