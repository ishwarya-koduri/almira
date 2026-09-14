package tech.bhrigu.almira.lifecycle

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.auth.StepUpService
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.household.HouseholdService
import tech.bhrigu.almira.reminder.Notifier
import tech.bhrigu.almira.reminder.OutboundNotification
import tech.bhrigu.almira.security.RequestUserContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

data class ClosureStatus(
    val pending: Boolean,
    val requestedAt: Instant?,
    /** The earliest the sweep will erase the account. Nothing happens before it. */
    val closesAfter: Instant?,
    val daysLeft: Long?,
)

data class ClosurePreview(
    /** Erased when the thirty days are up. */
    val erased: List<LifecycleLine>,
    /** Stays with the household, or with the other people who hold it. */
    val stays: List<LifecycleLine>,
    /** Has to be done first; a closure is refused while any of these stand. */
    val blockers: List<LifecycleBlocker>,
    val waitDays: Int,
    /** What is kept after erasure, and why — shown on the screen as it is written here. */
    val retention: String,
)

/**
 * Closing an account (docs/05 §12).
 *
 * The order is the promise. First the person is offered their copy — the
 * download and the family handbook are on the screen before anything else.
 * Then a preview in two columns: what will be erased, and what stays with the
 * household. Then a request that does nothing for thirty days except wait, and
 * that the person can take back by signing in and saying so. Only then does
 * [AccountPurge] erase it, on the owner connection, in one transaction, and
 * write down that it did.
 *
 * Two things are asked before it will even wait. A step-up, because a borrowed
 * unlocked phone should not be enough to start the end of someone's account. And
 * that no household is left without anyone to run it: an owner who is the only
 * one is asked to name a successor, or make someone an admin, first.
 */
@Service
class AccountClosureService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
    private val stepUp: StepUpService,
    private val audit: AuditService,
    private val notifiers: List<Notifier>,
    private val userContext: RequestUserContext,
) {
    private val records = LifecycleRecords(jdbc)

    @Transactional(readOnly = true)
    fun closureStatus(): ClosureStatus = current(userContext.require())

    @Transactional(readOnly = true)
    fun closurePreview(): ClosurePreview {
        val userId = userContext.require()
        val erased = mutableListOf(
            LifecycleLine(
                null, null, "account", null, "Your sign-in, profile and devices",
                "Your phone number or email, your name, your preferences and every signed-in device.",
            ),
        )
        val stays = mutableListOf<LifecycleLine>()
        val blockers = mutableListOf<LifecycleBlocker>()

        households.listMine().forEach { household ->
            val hid = household.id
            val others = records.otherPeopleWithLogin(hid, userId)
            val mine = records.memberIds(hid, userId)
            if (others == 0) {
                erased += LifecycleLine(
                    hid, household.name, "household", hid, household.name,
                    "Nobody else signs in here, so the household goes too — with everything in it, " +
                        "including what is recorded for people without a login.",
                )
                return@forEach
            }

            val sole = records.solelyHeld(hid, userId, mine)
            sole.forEach { erased += LifecycleLine(hid, household.name, lineKind(it.type), it.id, it.title) }
            records.documentsFollowing(hid, userId, sole).forEach {
                erased += LifecycleLine(hid, household.name, "document", it.id, it.title)
            }

            records.jointlyHeld(hid, mine).forEach {
                val holders = records.otherHolders(it.type, it.id, mine)
                stays += LifecycleLine(
                    hid, household.name, lineKind(it.type), it.id, it.title,
                    "Your part passes to ${holders.joinToString(" and ")}.",
                )
            }
            stays += LifecycleLine(
                hid, household.name, "household", hid, household.name,
                "The household carries on without you. What you added for other people stays with " +
                    "them, without your name on it.",
            )
            if (household.myRole == "owner") {
                when (val next = records.nextOwner(hid, userId)) {
                    is LifecycleRecords.Handover.To -> stays += LifecycleLine(
                        hid, household.name, "people", null, "${next.name} becomes the owner",
                        if (next.named) "You named them to carry the household on." else "They are an admin here.",
                    )
                    LifecycleRecords.Handover.NobodyChosen -> blockers += LifecycleBlocker(
                        hid, household.name, "owner_needs_successor",
                        "You're the only one who runs ${household.name}. Name who carries it on first.",
                    )
                    LifecycleRecords.Handover.NotNeeded -> Unit
                }
            }
        }

        return ClosurePreview(erased, stays, blockers, WAIT.toDays().toInt(), RETENTION)
    }

    @Transactional
    fun requestClosure(): ClosureStatus {
        val userId = userContext.require()
        current(userId).takeIf { it.pending }?.let { return it }

        val preview = closurePreview()
        if (preview.blockers.isNotEmpty()) {
            throw ApiException.conflict(
                "closure_blocked",
                preview.blockers.joinToString(" ") { it.message },
                mapOf("blockers" to preview.blockers),
            )
        }
        stepUp.requireElevated(userId, userContext.currentSessionId())

        val now = Instant.now()
        val closesAfter = now.plus(WAIT)
        jdbc.update(
            """
            insert into account_closures (user_id, requested_at, purge_after)
            values (:uid, :now, :after)
            """.trimIndent(),
            mapOf(
                "uid" to userId,
                "now" to java.sql.Timestamp.from(now),
                "after" to java.sql.Timestamp.from(closesAfter),
            ),
        )
        audit.record(
            householdId = null, actorUserId = userId, action = "account.closure.request",
            entityType = "user", entityId = userId,
            diff = mapOf(
                "closesAfter" to closesAfter.toString(),
                "erasedCount" to preview.erased.size,
                "staysCount" to preview.stays.size,
            ),
        )
        tell(
            userId, "lifecycle.closure.requested", "closure.requested:$userId:$now",
            "Your Almira account will close on ${day(closesAfter)}",
            "Nothing is erased before then. To keep your account, sign in and choose Keep my account.",
        )
        return current(userId)
    }

    @Transactional
    fun cancelClosure(): ClosureStatus {
        val userId = userContext.require()
        val cancelled = jdbc.update(
            """
            update account_closures set cancelled_at = now()
             where user_id = :uid and cancelled_at is null and purged_at is null
            """.trimIndent(),
            mapOf("uid" to userId),
        )
        if (cancelled > 0) {
            audit.record(
                householdId = null, actorUserId = userId, action = "account.closure.cancel",
                entityType = "user", entityId = userId,
            )
            tell(
                userId, "lifecycle.closure.cancelled", "closure.cancelled:$userId:${Instant.now()}",
                "Your Almira account is staying open", "Nothing was erased.",
            )
        }
        return current(userId)
    }

    private fun current(userId: UUID): ClosureStatus = jdbc.query(
        """
        select requested_at, purge_after from account_closures
         where user_id = :uid and cancelled_at is null and purged_at is null
        """.trimIndent(),
        mapOf("uid" to userId),
    ) { rs, _ ->
        val after = rs.getTimestamp("purge_after").toInstant()
        ClosureStatus(
            pending = true,
            requestedAt = rs.getTimestamp("requested_at").toInstant(),
            closesAfter = after,
            daysLeft = Duration.between(Instant.now(), after).toDays().coerceAtLeast(0),
        )
    }.firstOrNull() ?: ClosureStatus(false, null, null, null)

    private fun tell(userId: UUID, template: String, key: String, title: String, body: String) {
        notifiers.forEach { notifier ->
            runCatching {
                notifier.deliver(
                    OutboundNotification(
                        userId = userId, householdId = null, reminderId = null,
                        template = template, title = title, body = body, idempotencyKey = key,
                    ),
                )
            }
        }
    }

    private fun day(instant: Instant) =
        LocalDate.ofInstant(instant, INDIA).format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH))

    companion object {
        /** On the screen as "30 days". The database refuses anything shorter (V40). */
        val WAIT: Duration = Duration.ofDays(30)
        private val INDIA: ZoneId = ZoneId.of("Asia/Kolkata")

        /**
         * DPDP Rules 2025, rule 8(3): a data fiduciary keeps personal data, the
         * traffic data and the logs of processing for at least a year, for the
         * purposes in the Seventh Schedule. The audit log is that record here.
         */
        const val RETENTION =
            "We keep security records — sign-ins and the log of what was changed and when — for " +
                "at least one year after your account closes, because the DPDP Rules require it " +
                "(rule 8(3)). They are used for nothing else. In your household's activity log, " +
                "changes you made stay listed without your name."

        fun lineKind(type: String) = if (type == "account") "account_record" else type
    }
}

@RestController
@RequestMapping("/api/v1/me/closure")
class AccountClosureController(private val service: AccountClosureService) {

    @GetMapping
    fun closureStatus(): ClosureStatus = service.closureStatus()

    /** What would be erased and what would stay. Changes nothing. */
    @GetMapping("/preview")
    fun closurePreview(): ClosurePreview = service.closurePreview()

    /**
     * Starts the thirty days. Needs a recent step-up on this session
     * (403 step_up_required), and nothing standing in the way (409 closure_blocked).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun requestClosure(): ClosureStatus = service.requestClosure()

    /** "Keep my account." Any time before the thirty days are up. */
    @PostMapping("/cancel")
    fun cancelClosure(): ClosureStatus = service.cancelClosure()
}
