package tech.bhrigu.almira.provider

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tech.bhrigu.almira.security.RequestUserContext
import java.time.Instant
import java.util.UUID

data class OutboundMessageResponse(
    val id: UUID,
    val householdId: UUID?,
    val channel: String,
    val template: String,
    val title: String?,
    /** `sent`, `failed`, `queued` or `skipped`. */
    val status: String,
    /** When failed: `timeout`, `unavailable`, `rejected`, `insufficient_balance` or `error`. */
    val failure: String?,
    val attempts: Int,
    /** Plain words for [failure], ready to show. Null when nothing went wrong. */
    val failureMessage: String?,
    val createdAt: Instant,
)

/**
 * What you were told, on which channel, and whether it got there.
 *
 * `outbound_messages` has recorded every notification since V24, and nothing
 * read it back. Now that a failed row says which way it failed, the person it
 * was for can see that a reminder's text did not go — and whether that was
 * something to fix (a rejected number) or simply ours (an unpaid provider).
 *
 * Row-level security limits this to the caller's own messages; the query does
 * not filter by user on purpose, so the policy is what is being relied on and
 * a test that reads another person's list proves it.
 */
@Service
class OutboundMessages(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    @Transactional(readOnly = true)
    fun mine(limit: Int = 100): List<OutboundMessageResponse> = jdbc.query(
        """
        select id, household_id, channel, template, title, status, failure, attempts, created_at
        from outbound_messages
        order by created_at desc
        limit :limit
        """.trimIndent(),
        mapOf("limit" to limit),
    ) { rs, _ ->
        val status = rs.getString("status")
        val failure = rs.getString("failure")
        OutboundMessageResponse(
            id = rs.getObject("id", UUID::class.java),
            householdId = rs.getObject("household_id", UUID::class.java),
            channel = rs.getString("channel"),
            template = rs.getString("template"),
            title = rs.getString("title"),
            status = status,
            failure = failure,
            attempts = rs.getInt("attempts"),
            failureMessage = if (status == "failed") messageFor(failure) else null,
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )
    }

    companion object {
        /** docs/13 "When a provider fails" has the same table. */
        fun messageFor(failure: String?): String = when (failure) {
            "timeout" -> "Not confirmed — the service took too long to answer. It may still arrive."
            "unavailable" -> "Not sent — the service wasn't reachable. Nothing for you to do."
            "rejected" -> "Not delivered — it was refused for this address or number. Check your contact details."
            "insufficient_balance" -> "Not sent — a problem on our side, not with your details. We've been alerted."
            else -> "Not sent — something went wrong on our side."
        }
    }
}

@RestController
@RequestMapping("/api/v1")
class MessagesController(
    private val messages: OutboundMessages,
    private val userContext: RequestUserContext,
) {
    @GetMapping("/me/messages")
    fun mine(): List<OutboundMessageResponse> {
        userContext.require()
        return messages.mine()
    }
}
