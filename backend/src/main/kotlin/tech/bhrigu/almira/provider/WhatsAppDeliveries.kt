package tech.bhrigu.almira.provider

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

/**
 * Meta redelivers a webhook it thinks went unanswered, with the same message id
 * (known-issues 21). Without this, a slow reply meant the same message was
 * understood — and answered — twice.
 *
 * The first delivery of an id is remembered for [WINDOW] in Redis, which is
 * already where short-lived facts live (step-up, revoked sessions). Only a hash
 * of the id is kept: it is the provider's, not ours, and says nothing on its
 * own. A message with no id is always treated as new, which is what every
 * payload without one did before.
 *
 * If Redis cannot be reached, the message is treated as new and the failure
 * logged: a capture is only ever a proposal, so the worst case is the old one —
 * a second reply — never a lost message.
 */
@Component
class WhatsAppDeliveries(private val redis: StringRedisTemplate) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** True the first time [messageId] arrives for [householdId]; false for a redelivery. */
    fun firstDelivery(householdId: UUID, messageId: String?): Boolean {
        if (messageId.isNullOrBlank()) return true
        return try {
            redis.opsForValue().setIfAbsent(key(householdId, messageId), "1", WINDOW) != false
        } catch (e: RuntimeException) {
            log.warn("could not check a WhatsApp message for redelivery, treating it as new: {}", e.javaClass.simpleName)
            true
        }
    }

    private fun key(householdId: UUID, messageId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(messageId.toByteArray(Charsets.UTF_8))
        return "whatsapp:inbound:$householdId:" + digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Longer than Meta keeps retrying an unacknowledged webhook. */
        val WINDOW: Duration = Duration.ofDays(7)
    }
}
