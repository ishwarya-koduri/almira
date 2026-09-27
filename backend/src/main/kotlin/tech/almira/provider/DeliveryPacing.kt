package tech.almira.provider

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import javax.sql.DataSource

/** What a person has chosen about being told things. A person with no row has [DeliveryDirectory.DEFAULTS]. */
data class DeliveryPreferences(
    val smsEnabled: Boolean,
    val emailEnabled: Boolean,
    val pushEnabled: Boolean,
    val quietFrom: LocalTime,
    val quietUntil: LocalTime,
    val stillTruePausedUntil: LocalDate?,
) {
    fun channelEnabled(channel: String): Boolean = when (channel) {
        "sms" -> smsEnabled
        "email" -> emailEnabled
        "push" -> pushEnabled
        else -> true
    }

    /** Whether [time] falls inside the quiet window, which may cross midnight. */
    fun isQuiet(time: LocalTime): Boolean =
        if (quietFrom < quietUntil) time >= quietFrom && time < quietUntil
        else time >= quietFrom || time < quietUntil
}

/** Where a message for one person can go, read at send time. */
data class Recipient(
    /** A phone number, an email address, or a push token. Never logged whole. */
    val address: String,
    /** For push, the installation the token belongs to, so a dead token can be pruned. */
    val installationId: String? = null,
)

/**
 * Who a queued message is for, in the terms each channel needs (known-issues 13),
 * and what that person has chosen.
 *
 * On the OWNER connection, by explicit qualifier, for the reason the outbox
 * worker is: it has no user, and on the runtime pool row-level security would
 * show it nobody's number, address, token or preferences — and every message
 * would go nowhere, successfully. It is only ever called by the worker, for the
 * user a message was already written for, so it tells no one anything new.
 */
@Component
class DeliveryDirectory(@Qualifier("ownerDataSource") dataSource: DataSource) {

    private val jdbc = NamedParameterJdbcTemplate(dataSource)

    fun recipients(userId: UUID, channel: String): List<Recipient> = when (channel) {
        "sms" -> jdbc.query(
            "select phone from users where id = :id and status = 'active' and deleted_at is null and phone is not null",
            mapOf("id" to userId),
        ) { rs, _ -> Recipient(rs.getString(1)) }
        "email" -> jdbc.query(
            "select email::text from users where id = :id and status = 'active' and deleted_at is null and email is not null",
            mapOf("id" to userId),
        ) { rs, _ -> Recipient(rs.getString(1)) }
        "push" -> jdbc.query(
            """
            select d.token, d.installation_id from user_devices d
              join users u on u.id = d.user_id
            where d.user_id = :id and u.status = 'active' and u.deleted_at is null
            order by d.last_seen_at desc
            limit :max
            """.trimIndent(),
            mapOf("id" to userId, "max" to MAX_DEVICES),
        ) { rs, _ -> Recipient(rs.getString(1), rs.getString(2)) }
        else -> emptyList()
    }

    /** A token the platform said is dead. Otherwise every reminder is sent to it, and refused, forever. */
    fun forgetDevice(userId: UUID, installationId: String) {
        jdbc.update(
            "delete from user_devices where user_id = :uid and installation_id = :iid",
            mapOf("uid" to userId, "iid" to installationId),
        )
    }

    fun locale(userId: UUID): String? = jdbc.query(
        "select locale from users where id = :id", mapOf("id" to userId),
    ) { rs, _ -> rs.getString(1) }.firstOrNull()

    fun preferences(userId: UUID): DeliveryPreferences = jdbc.query(
        """
        select sms_enabled, email_enabled, push_enabled, quiet_from, quiet_until, still_true_paused_until
        from notification_preferences where user_id = :id
        """.trimIndent(),
        mapOf("id" to userId),
    ) { rs, _ ->
        DeliveryPreferences(
            smsEnabled = rs.getBoolean("sms_enabled"),
            emailEnabled = rs.getBoolean("email_enabled"),
            pushEnabled = rs.getBoolean("push_enabled"),
            quietFrom = rs.getTime("quiet_from").toLocalTime(),
            quietUntil = rs.getTime("quiet_until").toLocalTime(),
            stillTruePausedUntil = rs.getDate("still_true_paused_until")?.toLocalDate(),
        )
    }.firstOrNull() ?: DEFAULTS

    companion object {
        /** The same defaults as the columns in V60. */
        val DEFAULTS = DeliveryPreferences(
            smsEnabled = true, emailEnabled = true, pushEnabled = true,
            quietFrom = LocalTime.of(21, 0), quietUntil = LocalTime.of(8, 0),
            stillTruePausedUntil = null,
        )

        /** More phones than anyone has; a bound so a bug that registers in a loop cannot fan out. */
        const val MAX_DEVICES = 10
    }
}

/**
 * When a queued message may go (docs/13 "Pacing"). The promise it keeps is the
 * one Settings shows: quiet hours are quiet, at most one non-essential message a
 * day reaches a person, and a channel they switched off stays off.
 *
 * Decided by the worker as it claims a row, not when the row is written, so
 * every producer — a reminder, a digest, anything written later — is paced the
 * same way without having to remember to ask.
 *
 * **Which channels are paced.** Every channel whose adapter is live. A sandbox
 * reaches nobody's phone, and pacing it would only make the development
 * experience and the suites depend on the time of day; [paceSandboxChannels]
 * turns it on for the tests that prove the rules. A switched-off channel is
 * honoured in every mode.
 *
 * **Essential messages** are not paced and not switched off: someone asking for
 * emergency access to your records has to be heard about at 23:00, not the next
 * morning. Which they are is `app.message_is_essential` (V125), asked by the
 * worker as it claims the row and passed in as [Queued.essential] — the same
 * answer that decided whether the message needed consent to be queued at all
 * ([MessageTemplates.ESSENTIAL_TEMPLATES]).
 */
@Component
class DeliveryPacing(
    @Qualifier("ownerDataSource") dataSource: DataSource,
    private val directory: DeliveryDirectory,
) {
    private val jdbc = NamedParameterJdbcTemplate(dataSource)

    /** Test seam: the time pacing believes it is. */
    @Volatile internal var clock: Clock = Clock.systemUTC()

    /** Test seam: pace sandbox channels as if they were live. Off in a running server. */
    @Volatile internal var paceSandboxChannels: Boolean = false

    fun now(): Instant = clock.instant()

    sealed interface Decision {
        data object Send : Decision
        data class Skip(val reason: String) : Decision
        data class Defer(val until: Instant, val reason: String) : Decision
    }

    data class Queued(
        val userId: UUID,
        val channel: String,
        val template: String,
        /** `app.message_is_essential(template)`, asked of the database with the row. */
        val essential: Boolean,
        /** The idempotency key without its channel: one logical message. */
        val logicalKey: String,
        val timeZone: ZoneId,
        val createdAt: Instant,
    )

    /**
     * Decides one row. [chosenToday] is what this worker has already let through
     * in the same pass, per user, so two different messages claimed together are
     * not both treated as the first of the day.
     */
    fun decide(row: Queued, sender: ChannelSender, chosenToday: MutableMap<UUID, String>): Decision {
        if (row.essential) return Decision.Send
        val preferences = directory.preferences(row.userId)
        if (!preferences.channelEnabled(row.channel)) return Decision.Skip(TURNED_OFF)
        if (sender.mode != ProviderMode.LIVE && !paceSandboxChannels) return Decision.Send

        val here = ZonedDateTime.ofInstant(now(), row.timeZone)
        if (preferences.isQuiet(here.toLocalTime())) {
            return Decision.Defer(quietEnds(here, preferences), QUIET_HOURS)
        }

        val first = chosenToday[row.userId]
        val alreadyToday = when {
            first == null -> anotherStartedToday(row, here)
            else -> first != row.logicalKey
        }
        if (!alreadyToday) {
            chosenToday[row.userId] = row.logicalKey
            return Decision.Send
        }
        if (Duration.between(row.createdAt, now()) > STALE_AFTER) return Decision.Skip(DAILY_LIMIT)
        val tomorrow = here.toLocalDate().plusDays(1).atTime(preferences.quietUntil).atZone(row.timeZone)
        return Decision.Defer(tomorrow.toInstant(), DAILY_LIMIT)
    }

    /** The next moment the window ends, today or tomorrow. */
    private fun quietEnds(here: ZonedDateTime, preferences: DeliveryPreferences): Instant {
        val today = here.toLocalDate().atTime(preferences.quietUntil).atZone(here.zone)
        return (if (today.isAfter(here)) today else today.plusDays(1)).toInstant()
    }

    /** Has a different non-essential message already started out to this person, on any channel, today? */
    private fun anotherStartedToday(row: Queued, here: ZonedDateTime): Boolean {
        val dayStart = here.toLocalDate().atStartOfDay(row.timeZone).toInstant()
        return jdbc.queryForObject(
            """
            select exists (
              select 1 from outbound_messages o
              where o.user_id = :uid
                and o.channel <> 'in_app'
                and o.send_started_at is not null
                and o.send_started_at >= :dayStart
                -- started and then found to have nowhere to go: nothing arrived
                and o.status <> 'skipped'
                and not app.message_is_essential(o.template)
                and o.idempotency_key is not null
                and left(o.idempotency_key, length(o.idempotency_key) - length(o.channel) - 1) <> :logical
            )
            """.trimIndent(),
            mapOf(
                "uid" to row.userId,
                "dayStart" to java.sql.Timestamp.from(dayStart),
                "logical" to row.logicalKey,
            ),
            Boolean::class.java,
        ) == true
    }

    companion object {
        const val TURNED_OFF = "turned_off"
        const val QUIET_HOURS = "quiet_hours"
        const val DAILY_LIMIT = "daily_limit"
        const val NO_RECIPIENT = "no_recipient"

        /**
         * A reminder held back a day at a time for a week is out of date; it is
         * dropped from the channel, and stays in the person's in-app list.
         */
        val STALE_AFTER: Duration = Duration.ofDays(7)

        /** The logical message a channel row belongs to: its key without `:<channel>`. */
        fun logicalKey(idempotencyKey: String, channel: String): String =
            idempotencyKey.removeSuffix(":$channel")
    }
}
