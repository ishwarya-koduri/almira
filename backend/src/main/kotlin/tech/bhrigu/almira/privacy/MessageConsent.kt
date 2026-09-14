package tech.bhrigu.almira.privacy

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import tech.bhrigu.almira.stilltrue.StillTrue
import java.util.UUID

/**
 * Whether an email or text may go to someone, given their consent to `messages`.
 *
 * Only the messages that consent covers: reminders and the "still true?"
 * digest. A sign-in code and an emergency-access notice are not optional — the
 * second exists so the person can say no in time — and the in-app copy of any
 * message leaves nothing outside Almira, so it is always recorded.
 *
 * Reads through `app.messages_consent_withdrawn`, a definer function, because
 * the recipient is usually not the person whose request or sweep caused the
 * message, and row-level security rightly shows nobody else's consent history.
 */
@Component
class MessageConsent(private val jdbc: NamedParameterJdbcTemplate) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun coveredBy(template: String): Boolean =
        template.startsWith("reminder.") || template == StillTrue.DIGEST_TEMPLATE

    /**
     * True when this message must not leave by email or text. A failure to read
     * the answer sends nothing outside: a reminder that did not go by email is a
     * smaller harm than one sent after someone said stop.
     */
    fun suppressOutside(userId: UUID, template: String): Boolean {
        if (!coveredBy(template)) return false
        return runCatching {
            jdbc.queryForObject(
                "select app.messages_consent_withdrawn(:uid)",
                mapOf("uid" to userId), Boolean::class.java,
            ) ?: false
        }.getOrElse {
            log.warn("could not read message consent; sending in-app only: {}", it.javaClass.simpleName)
            true
        }
    }
}
