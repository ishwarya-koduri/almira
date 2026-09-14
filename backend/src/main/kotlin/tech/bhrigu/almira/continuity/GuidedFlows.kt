package tech.bhrigu.almira.continuity

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.household.HouseholdService
import tech.bhrigu.almira.security.RequestUserContext
import java.time.Instant
import java.util.UUID

data class SaveGuidedFlowBody(
    val step: Int,
    val answers: Map<String, Any?> = emptyMap(),
)

data class GuidedFlowDraft(
    val flow: String,
    val subject: String,
    val step: Int,
    val answers: Map<String, Any?>,
    val updatedAt: Instant,
)

/**
 * Where someone is in a guided flow (X-58, docs/03 §8.4), saved at every step.
 *
 * The answers kept are only ever ordinary fields the server stores anyway when
 * the flow finishes, and only the ones named below for each flow, each checked
 * for shape. A where-and-who flow keeps no answers at all: its words are sealed
 * on the device and saved through the sealed path at each step, and the table
 * refuses anything else (V91).
 */
@Service
class GuidedFlowService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
    private val userContext: RequestUserContext,
    private val mapper: ObjectMapper,
) {

    @Transactional(readOnly = true)
    fun get(householdId: UUID, flow: String, subject: String): GuidedFlowDraft {
        households.get(householdId)
        requireFlow(flow)
        requireSubject(flow, subject)
        return find(householdId, flow, subject) ?: throw ApiException.notFound("Nothing saved for that yet.")
    }

    @Transactional
    fun save(householdId: UUID, flow: String, subject: String, body: SaveGuidedFlowBody): GuidedFlowDraft {
        val userId = userContext.require()
        households.get(householdId)
        val allowed = requireFlow(flow)
        requireSubject(flow, subject)
        if (body.step !in 0..50) throw ApiException.badRequest("step_invalid", "That step doesn't exist.")
        val answers = validated(allowed, body.answers)

        jdbc.update(
            """
            insert into guided_flow_drafts (household_id, user_id, flow, subject_key, step, answers)
            values (:hid, :me, :flow, :subject, :step, cast(:answers as jsonb))
            on conflict (household_id, user_id, flow, subject_key)
              do update set step = excluded.step, answers = excluded.answers
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("hid", householdId).addValue("me", userId).addValue("flow", flow)
                .addValue("subject", subject).addValue("step", body.step)
                .addValue("answers", mapper.writeValueAsString(answers)),
        )
        return find(householdId, flow, subject)!!
    }

    /** Finished, or started over. */
    @Transactional
    fun discard(householdId: UUID, flow: String, subject: String) {
        userContext.require()
        households.get(householdId)
        requireFlow(flow)
        requireSubject(flow, subject)
        jdbc.update(
            """
            delete from guided_flow_drafts
            where household_id = :hid and user_id = app.current_user_id() and flow = :flow and subject_key = :subject
            """.trimIndent(),
            mapOf("hid" to householdId, "flow" to flow, "subject" to subject),
        )
    }

    private fun find(householdId: UUID, flow: String, subject: String): GuidedFlowDraft? = jdbc.query(
        """
        select flow, subject_key, step, answers::text as answers, updated_at from guided_flow_drafts
        where household_id = :hid and user_id = app.current_user_id() and flow = :flow and subject_key = :subject
        """.trimIndent(),
        mapOf("hid" to householdId, "flow" to flow, "subject" to subject),
    ) { rs, _ ->
        GuidedFlowDraft(
            flow = rs.getString("flow"),
            subject = rs.getString("subject_key"),
            step = rs.getInt("step"),
            answers = mapper.readValue(rs.getString("answers")),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
        )
    }.firstOrNull()

    private fun requireFlow(flow: String): Map<String, Answer> =
        FLOWS[flow] ?: throw ApiException.notFound("We don't know that flow.")

    private fun requireSubject(flow: String, subject: String) {
        val perRecord = flow == "where_and_who"
        val ok = if (perRecord) SUBJECT.matches(subject) else subject.isEmpty()
        if (!ok) {
            throw ApiException.badRequest(
                "subject_invalid",
                if (perRecord) "Say which record this is about." else "This flow isn't about one record.",
            )
        }
    }

    private fun validated(allowed: Map<String, Answer>, answers: Map<String, Any?>): Map<String, Any?> {
        val unknown = answers.keys - allowed.keys
        if (unknown.isNotEmpty()) {
            throw ApiException.badRequest(
                "answers_invalid", "Those answers aren't kept for this flow.",
                mapOf("fields" to unknown.associateWith { "Not kept for this flow" }),
            )
        }
        return answers.filterValues { it != null }.mapValues { (key, value) ->
            if (!allowed.getValue(key).accepts(value)) {
                throw ApiException.badRequest(
                    "answers_invalid", "One of those answers isn't the right shape.",
                    mapOf("fields" to mapOf(key to "Not a valid answer")),
                )
            }
            value
        }
    }

    /** The shape one kept answer may have. */
    private enum class Answer {
        ID { override fun accepts(value: Any?) = value is String && UUID_TEXT.matches(value) },
        DAYS { override fun accepts(value: Any?) = value is Int && value in 1..90 },
        DATE { override fun accepts(value: Any?) = value is String && Regex("""^\d{4}-\d{2}-\d{2}$""").matches(value) },
        TEXT { override fun accepts(value: Any?) = value is String && value.length <= 200 },
        CHOICE { override fun accepts(value: Any?) = value is String && Regex("^[a-z_]{1,40}$").matches(value) };

        abstract fun accepts(value: Any?): Boolean
    }

    private companion object {
        val UUID_TEXT = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        val SUBJECT = Regex("^(investment|estate_document|liability|account|document):[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

        val FLOWS: Map<String, Map<String, Answer>> = mapOf(
            "emergency_setup" to mapOf(
                "trustedMemberId" to Answer.ID,
                "waitDays" to Answer.DAYS,
            ),
            "estate_document" to mapOf(
                "kind" to Answer.CHOICE,
                "title" to Answer.TEXT,
                "memberId" to Answer.ID,
                "executedOn" to Answer.DATE,
                "visibility" to Answer.CHOICE,
            ),
            // Sealed words are never kept here: only the step (V91).
            "where_and_who" to emptyMap(),
        )
    }
}

@RestController
@RequestMapping("/api/v1/households/{householdId}/guided-flows/{flow}")
class GuidedFlowController(private val service: GuidedFlowService) {

    @GetMapping
    fun guidedFlowDraft(
        @PathVariable householdId: UUID,
        @PathVariable flow: String,
        @RequestParam(defaultValue = "") subject: String,
    ): GuidedFlowDraft = service.get(householdId, flow, subject)

    @PutMapping
    fun saveGuidedFlowDraft(
        @PathVariable householdId: UUID,
        @PathVariable flow: String,
        @RequestParam(defaultValue = "") subject: String,
        @RequestBody body: SaveGuidedFlowBody,
    ): GuidedFlowDraft = service.save(householdId, flow, subject, body)

    @DeleteMapping
    fun discardGuidedFlowDraft(
        @PathVariable householdId: UUID,
        @PathVariable flow: String,
        @RequestParam(defaultValue = "") subject: String,
    ): ResponseEntity<Void> {
        service.discard(householdId, flow, subject)
        return ResponseEntity.noContent().build()
    }
}
