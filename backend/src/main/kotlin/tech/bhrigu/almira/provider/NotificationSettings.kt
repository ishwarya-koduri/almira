package tech.bhrigu.almira.provider

import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.security.RequestUserContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

data class NotificationChannelStatus(
    /** `sms`, `email` or `push`. */
    val channel: String,
    /** Whether this server sends on the channel at all (`disabled` is not offered). */
    val offered: Boolean,
    /** Whether we have somewhere to send it for you: a number, an address, a registered device. */
    val reachable: Boolean,
    val enabled: Boolean,
)

data class NotificationPreferencesResponse(
    val smsEnabled: Boolean,
    val emailEnabled: Boolean,
    val pushEnabled: Boolean,
    /** `HH:mm`, in your household's own time. */
    val quietFrom: String,
    val quietUntil: String,
    /** No Still true? reminder before this date. Null when not paused. */
    val stillTruePausedUntil: LocalDate?,
    val channels: List<NotificationChannelStatus>,
)

data class UpdateNotificationPreferencesBody(
    val smsEnabled: Boolean? = null,
    val emailEnabled: Boolean? = null,
    val pushEnabled: Boolean? = null,
    @field:Size(max = 5) val quietFrom: String? = null,
    @field:Size(max = 5) val quietUntil: String? = null,
)

data class RegisterDeviceBody(
    /** `ios` or `android`. */
    val platform: String? = null,
    @field:Size(max = 4096) val token: String? = null,
    /** iOS only, and required there: `development` or `production`. */
    val environment: String? = null,
    @field:Size(max = 64) val appVersion: String? = null,
)

/** A registered device. Never the token. */
data class DeviceResponse(
    val installationId: String,
    val platform: String,
    val environment: String?,
    val appVersion: String?,
    val lastSeenAt: Instant,
)

/**
 * What a person has chosen about notifications, and the devices a push can reach
 * (docs/13 "Who a message is for", "Pacing").
 *
 * Everything here is the caller's own, and row-level security says so (V60): the
 * statements do not filter by user beyond what an insert must name, so the
 * policy is what is relied on. Another person's device is answered 404, as a
 * device that does not exist.
 */
@Service
class NotificationSettings(
    private val jdbc: NamedParameterJdbcTemplate,
    private val userContext: RequestUserContext,
    private val audit: AuditService,
    private val channels: List<ChannelSender>,
) {
    @Transactional(readOnly = true)
    fun preferences(): NotificationPreferencesResponse {
        val userId = userContext.require()
        rejectGuest()
        val row = jdbc.query(
            """
            select sms_enabled, email_enabled, push_enabled, quiet_from, quiet_until, still_true_paused_until
            from notification_preferences
            """.trimIndent(),
            emptyMap<String, Any>(),
        ) { rs, _ ->
            DeliveryPreferences(
                rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3),
                rs.getTime(4).toLocalTime(), rs.getTime(5).toLocalTime(), rs.getDate(6)?.toLocalDate(),
            )
        }.firstOrNull() ?: DeliveryDirectory.DEFAULTS
        return respond(userId, row)
    }

    @Transactional
    fun update(body: UpdateNotificationPreferencesBody): NotificationPreferencesResponse {
        val userId = userContext.require()
        rejectGuest()
        val current = preferences()
        val quietFrom = body.quietFrom?.let { time("quietFrom", it) } ?: LocalTime.parse(current.quietFrom)
        val quietUntil = body.quietUntil?.let { time("quietUntil", it) } ?: LocalTime.parse(current.quietUntil)
        if (quietFrom == quietUntil) {
            throw ApiException.badRequest(
                "quiet_hours_empty", "Quiet hours need a start and an end that are different.",
                mapOf("fields" to mapOf("quietUntil" to "Choose a different time from the start")),
            )
        }
        val next = mapOf(
            "sms_enabled" to (body.smsEnabled ?: current.smsEnabled),
            "email_enabled" to (body.emailEnabled ?: current.emailEnabled),
            "push_enabled" to (body.pushEnabled ?: current.pushEnabled),
        )
        jdbc.update(
            """
            insert into notification_preferences (user_id, sms_enabled, email_enabled, push_enabled, quiet_from, quiet_until)
            values (:uid, :sms, :email, :push, :from, :until)
            on conflict (user_id) do update set
              sms_enabled = excluded.sms_enabled, email_enabled = excluded.email_enabled,
              push_enabled = excluded.push_enabled, quiet_from = excluded.quiet_from,
              quiet_until = excluded.quiet_until, updated_at = now()
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("uid", userId)
                .addValue("sms", next["sms_enabled"]).addValue("email", next["email_enabled"])
                .addValue("push", next["push_enabled"])
                .addValue("from", java.sql.Time.valueOf(quietFrom))
                .addValue("until", java.sql.Time.valueOf(quietUntil)),
        )
        audit.record(
            householdId = null, actorUserId = userId, action = "notifications.preferences_changed",
            entityType = "user", entityId = userId,
            diff = next + mapOf("quiet_from" to quietFrom.toString(), "quiet_until" to quietUntil.toString()),
        )
        return preferences()
    }

    /**
     * "Ask me later" on a Still true? reminder: none for a week (docs/21 §6). The
     * records stay in the app, and each can still be answered or snoozed there.
     */
    @Transactional
    fun askLaterAboutStillTrue(): NotificationPreferencesResponse {
        val userId = userContext.require()
        rejectGuest()
        val until = LocalDate.now(INDIA).plusDays(ASK_LATER_DAYS)
        jdbc.update(
            """
            insert into notification_preferences (user_id, still_true_paused_until)
            values (:uid, :until)
            on conflict (user_id) do update set still_true_paused_until = excluded.still_true_paused_until,
                                                updated_at = now()
            """.trimIndent(),
            mapOf("uid" to userId, "until" to java.sql.Date.valueOf(until)),
        )
        return preferences()
    }

    @Transactional(readOnly = true)
    fun devices(): List<DeviceResponse> {
        userContext.require()
        rejectGuest()
        return jdbc.query(
            "select installation_id, platform, environment, app_version, last_seen_at from user_devices order by last_seen_at desc",
            emptyMap<String, Any>(),
        ) { rs, _ ->
            DeviceResponse(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant(),
            )
        }
    }

    /**
     * Registers this installation's push token, or refreshes it. Idempotent by
     * installation: an app that registers on every launch keeps one row. Beyond
     * [DeliveryDirectory.MAX_DEVICES], the device not seen for longest is let go.
     */
    @Transactional
    fun registerDevice(installationId: String, body: RegisterDeviceBody): DeviceResponse {
        val userId = userContext.require()
        rejectGuest()
        val fields = mutableMapOf<String, String>()
        if (!INSTALLATION_ID.matches(installationId)) fields["installationId"] = "Use 8 to 128 letters, digits, dots, dashes or colons"
        val platform = body.platform?.lowercase()
        if (platform !in PLATFORMS) fields["platform"] = "Use ios or android"
        val token = body.token?.trim()
        if (token == null || !TOKEN.matches(token)) fields["token"] = "This isn't a push token"
        val environment = body.environment?.lowercase()
        if (environment != null && environment !in ENVIRONMENTS) fields["environment"] = "Use development or production"
        if (platform == "ios" && environment == null) fields["environment"] = "An iOS device says which APNs environment it uses"
        if (body.appVersion != null && body.appVersion.any { it.isISOControl() }) fields["appVersion"] = "This isn't a version"
        if (fields.isNotEmpty()) {
            throw ApiException.badRequest("validation_failed", "Some details need another look.", mapOf("fields" to fields))
        }

        jdbc.update(
            """
            insert into user_devices (user_id, installation_id, platform, token, environment, app_version, last_seen_at)
            values (:uid, :iid, :platform, :token, :env, :version, now())
            on conflict (user_id, installation_id) do update set
              platform = excluded.platform, token = excluded.token, environment = excluded.environment,
              app_version = excluded.app_version, last_seen_at = now()
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("uid", userId).addValue("iid", installationId).addValue("platform", platform)
                .addValue("token", token).addValue("env", environment).addValue("version", body.appVersion),
        )
        jdbc.update(
            """
            delete from user_devices where id in (
              select id from user_devices where user_id = :uid order by last_seen_at desc offset :max
            )
            """.trimIndent(),
            mapOf("uid" to userId, "max" to DeliveryDirectory.MAX_DEVICES),
        )
        audit.record(
            householdId = null, actorUserId = userId, action = "device.registered",
            entityType = "user", entityId = userId,
            diff = mapOf("platform" to platform, "environment" to environment),
        )
        return devices().first { it.installationId == installationId }
    }

    /** On sign-out, or from Settings. Someone else's installation is 404, the same as none. */
    @Transactional
    fun removeDevice(installationId: String) {
        val userId = userContext.require()
        rejectGuest()
        val removed = jdbc.update("delete from user_devices where installation_id = :iid", mapOf("iid" to installationId))
        if (removed == 0) throw ApiException.notFound("We couldn't find that device.")
        audit.record(
            householdId = null, actorUserId = userId, action = "device.removed",
            entityType = "user", entityId = userId,
        )
    }

    private fun respond(userId: UUID, p: DeliveryPreferences): NotificationPreferencesResponse {
        val reach = jdbc.query(
            """
            select u.phone is not null, u.email is not null,
                   exists (select 1 from user_devices d where d.user_id = u.id)
            from users u where u.id = :uid
            """.trimIndent(),
            mapOf("uid" to userId),
        ) { rs, _ -> Triple(rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3)) }.firstOrNull()
            ?: Triple(false, false, false)
        val offered = channels.filter { it.mode != ProviderMode.DISABLED }.map { it.channel }.toSet()
        return NotificationPreferencesResponse(
            smsEnabled = p.smsEnabled, emailEnabled = p.emailEnabled, pushEnabled = p.pushEnabled,
            quietFrom = p.quietFrom.toString(), quietUntil = p.quietUntil.toString(),
            stillTruePausedUntil = p.stillTruePausedUntil?.takeUnless { it.isBefore(LocalDate.now(INDIA)) },
            channels = listOf(
                NotificationChannelStatus("sms", "sms" in offered, reach.first, p.smsEnabled),
                NotificationChannelStatus("email", "email" in offered, reach.second, p.emailEnabled),
                NotificationChannelStatus("push", "push" in offered, reach.third, p.pushEnabled),
            ),
        )
    }

    /** A guest link acts for the person who shared it, and must not change where their messages go. */
    private fun rejectGuest() {
        if (userContext.currentGuestShareId() != null) throw ApiException.notFound()
    }

    private fun time(field: String, value: String): LocalTime {
        if (!TIME.matches(value)) {
            throw ApiException.badRequest(
                "validation_failed", "Some details need another look.",
                mapOf("fields" to mapOf(field to "Use a 24-hour time like 21:00")),
            )
        }
        return LocalTime.parse(value)
    }

    private companion object {
        val INSTALLATION_ID = Regex("^[A-Za-z0-9._:-]{8,128}$")
        /** APNs tokens are hex, FCM tokens are URL-safe base64 with a colon; nothing else, and no whitespace. */
        val TOKEN = Regex("^[A-Za-z0-9_:.\\-]{16,4096}$")
        val TIME = Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$")
        val PLATFORMS = setOf("ios", "android")
        val ENVIRONMENTS = setOf("development", "production")
        const val ASK_LATER_DAYS = 7L
        val INDIA: ZoneId = ZoneId.of("Asia/Kolkata")
    }
}

@RestController
@RequestMapping("/api/v1/me")
class NotificationSettingsController(private val settings: NotificationSettings) {

    @GetMapping("/notification-preferences")
    fun preferences(): NotificationPreferencesResponse = settings.preferences()

    @PutMapping("/notification-preferences")
    fun updatePreferences(@RequestBody @Valid body: UpdateNotificationPreferencesBody): NotificationPreferencesResponse =
        settings.update(body)

    @PostMapping("/notification-preferences/still-true/ask-later")
    fun askLaterAboutStillTrue(): NotificationPreferencesResponse = settings.askLaterAboutStillTrue()

    @GetMapping("/devices")
    fun devices(): List<DeviceResponse> = settings.devices()

    @PutMapping("/devices/{installationId}")
    fun registerDevice(
        @PathVariable installationId: String,
        @RequestBody @Valid body: RegisterDeviceBody,
    ): DeviceResponse = settings.registerDevice(installationId, body)

    @DeleteMapping("/devices/{installationId}")
    fun removeDevice(@PathVariable installationId: String): ResponseEntity<Void> {
        settings.removeDevice(installationId)
        return ResponseEntity.noContent().build()
    }
}
