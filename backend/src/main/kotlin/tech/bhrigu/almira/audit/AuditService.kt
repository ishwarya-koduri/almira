package tech.bhrigu.almira.audit

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Append-only audit trail (docs/05 §9). `activity_log` grants the application
 * INSERT but not UPDATE or DELETE, so the history cannot be rewritten by the
 * service that writes it — including by a compromised one.
 *
 * A failure to audit never fails the user's action. Losing one log line is bad;
 * refusing to save someone's investment because a log line failed is worse.
 */
@Service
class AuditService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun record(
        householdId: UUID?,
        actorUserId: UUID?,
        action: String,
        entityType: String? = null,
        entityId: UUID? = null,
        diff: Map<String, Any?>? = null,
        ip: String? = null,
        userAgent: String? = null,
    ) {
        try {
            jdbc.update(
                """
                insert into activity_log
                  (household_id, actor_user_id, action, entity_type, entity_id,
                   diff, ip, user_agent)
                values
                  (:householdId, :actor, :action, :entityType, :entityId,
                   cast(:diff as jsonb), cast(:ip as inet), :userAgent)
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("householdId", householdId)
                    .addValue("actor", actorUserId)
                    .addValue("action", action)
                    .addValue("entityType", entityType)
                    .addValue("entityId", entityId)
                    .addValue("diff", diff?.let { mapper.writeValueAsString(it) })
                    .addValue("ip", ip)
                    .addValue("userAgent", userAgent?.take(500)),
            )
        } catch (e: Exception) {
            log.warn("could not write audit entry {}: {}", action, e.message)
        }
    }
}
