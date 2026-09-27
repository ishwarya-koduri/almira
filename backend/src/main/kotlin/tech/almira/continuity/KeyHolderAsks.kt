package tech.almira.continuity

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tech.almira.audit.AuditService
import tech.almira.common.ApiException
import tech.almira.household.HouseholdService
import tech.almira.security.RequestUserContext
import java.time.Instant
import java.util.UUID

data class KeyHolderAsk(
    val id: UUID,
    val recordType: String,
    val recordId: UUID,
    /** The record's title when asked. */
    val thing: String,
    /** "Do you know where the SBI locker key is?" — English; clients may build their own from [thing]. */
    val question: String,
    val askedMemberId: UUID,
    val askedName: String?,
    val askedByName: String?,
    val askedByMe: Boolean,
    /** `yes` | `not_sure`, or absent while unanswered. */
    val answer: String?,
    val answeredAt: Instant?,
    val createdAt: Instant,
)

data class KeyHolderAsks(
    /** Questions you asked, newest first. */
    val asked: List<KeyHolderAsk>,
    /** Questions for you, unanswered first. */
    val forMe: List<KeyHolderAsk>,
)

data class AskKeyHolderBody(val recordType: String, val recordId: UUID, val askedMemberId: UUID)

data class AnswerKeyHolderBody(val answer: String)

/**
 * Ask the key holder (docs/27 §4): one gentle question to a person in the
 * household — "Do you know where the SBI locker key is?" — answered Yes or Not
 * sure, and shown on the asker's readiness.
 *
 * **What the question can carry.** Only the record's title, taken by the
 * server from the record itself. Nobody types the question, so nobody can put
 * the sealed location or the sealed key holder into it by mistake; the server
 * could not read either of those if it tried (docs/20). The title was already
 * plaintext, and asking shows it to the person asked — who may not otherwise
 * see the record — which the sheet that asks says before it is sent. That is
 * only for a record the asker holds: someone who merely sees a record (a grant,
 * an emergency window) may ask only a member who already sees it too (V106).
 *
 * **Who learns what.** The asker and the person asked see the question and the
 * answer; nobody else in the household learns who was asked (V95's policy).
 * Only someone with a login can be asked: this is a question to a member, never
 * a message to the third party a sealed key-holder line may name (docs/20 §4).
 */
@Service
class KeyHolderAskService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
    private val audit: AuditService,
    private val notices: ContinuityNotices,
    private val userContext: RequestUserContext,
) {

    @Transactional(readOnly = true)
    fun list(householdId: UUID): KeyHolderAsks {
        val userId = userContext.require()
        households.get(householdId)
        val all = query("where k.household_id = :hid", mapOf("hid" to householdId), userId)
        return KeyHolderAsks(
            asked = all.filter { it.askedByMe }.sortedByDescending { it.createdAt },
            forMe = all.filter { !it.askedByMe }
                .sortedWith(compareBy<KeyHolderAsk>({ it.answer != null }).thenByDescending { it.createdAt }),
        )
    }

    @Transactional
    fun ask(householdId: UUID, body: AskKeyHolderBody): KeyHolderAsk {
        val userId = userContext.require()
        households.get(householdId)
        if (userContext.currentGuestShareId() != null) throw ApiException.notFound()
        val table = RECORDS[body.recordType]
            ?: throw ApiException.badRequest("record_type_invalid", "That kind of record can't be asked about.")

        // Under the asker's row-level security: a record they cannot see is a
        // record that does not exist.
        val title = jdbc.query(
            "select ${table.second} as title from ${table.first} where id = :id and household_id = :hid and deleted_at is null",
            mapOf("id" to body.recordId, "hid" to householdId),
        ) { rs, _ -> rs.getString("title") }.firstOrNull() ?: throw ApiException.notFound()

        val members = households.members(householdId)
        val me = members.firstOrNull { it.isMe }
        val asked = members.firstOrNull { it.id == body.askedMemberId }
            ?: throw ApiException.badRequest("member_unknown", "That person isn't in this household.")
        if (asked.id == me?.id) {
            throw ApiException.badRequest("asked_is_you", "Ask someone else. You already know what you know.")
        }
        if (asked.userId == null) {
            throw ApiException.badRequest(
                "cannot_sign_in", "${asked.displayName} can't sign in, so there is nowhere to ask them. Invite them first.",
            )
        }
        // Seeing a record is not holding it. Someone who reads a record through
        // a grant or an emergency window must not carry its title to a member
        // the owner never shared it with (V106).
        val mayCarryTitle = jdbc.queryForObject(
            "select app.holds_askable_record(:type, :rid) or app.member_would_see(:member, :type, :rid)",
            mapOf("type" to body.recordType, "rid" to body.recordId, "member" to asked.id),
            Boolean::class.java,
        ) ?: false
        if (!mayCarryTitle) throw ApiException.notFound()

        val open = query(
            """
            where k.household_id = :hid and k.record_type = :type and k.record_id = :rid
              and k.asked_member_id = :member and k.asked_by = :uid and k.answer is null
            """.trimIndent(),
            mapOf(
                "hid" to householdId, "type" to body.recordType, "rid" to body.recordId,
                "member" to asked.id, "uid" to userId,
            ),
            userId,
        ).firstOrNull()
        if (open != null) return open

        val waiting = jdbc.queryForObject(
            "select count(*) from key_holder_asks where asked_by = :uid and answer is null",
            mapOf("uid" to userId), Int::class.java,
        ) ?: 0
        if (waiting >= MAX_OPEN) {
            throw ApiException.conflict(
                "too_many_waiting", "You have $MAX_OPEN questions waiting for an answer. Let a few come back first.",
            )
        }

        val id = UUID.randomUUID()
        val thing = title.trim().take(200)
        jdbc.update(
            """
            insert into key_holder_asks (id, household_id, record_type, record_id, thing, asked_by, asked_member_id)
            values (:id, :hid, :type, :rid, :thing, :uid, :member)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id).addValue("hid", householdId).addValue("type", body.recordType)
                .addValue("rid", body.recordId).addValue("thing", thing).addValue("uid", userId)
                .addValue("member", asked.id),
        )
        audit.record(
            householdId = householdId, actorUserId = userId, action = "continuity.key_holder.ask",
            entityType = body.recordType, entityId = body.recordId, diff = mapOf("askedMemberId" to asked.id),
        )
        notices.send(
            asked.userId, householdId, ContinuityNotices.KEY_HOLDER_ASK,
            "${me?.displayName ?: "Someone in your household"} has a question for you",
            "${question(thing)}\n\nOpen Almira to answer Yes or Not sure. Only your answer is shared, " +
                "and nothing about where it is.",
            "continuity.key_holder_ask:$id",
        )
        return get(householdId, id, userId)
    }

    @Transactional
    fun answer(householdId: UUID, id: UUID, body: AnswerKeyHolderBody): KeyHolderAsk {
        val userId = userContext.require()
        households.get(householdId)
        if (body.answer !in ANSWERS) {
            throw ApiException.badRequest("answer_invalid", "Answer yes or not sure.")
        }
        val current = get(householdId, id, userId)
        if (current.askedByMe) throw ApiException.notFound()
        val updated = jdbc.update(
            "update key_holder_asks set answer = :answer, answered_at = now() where id = :id and household_id = :hid",
            mapOf("answer" to body.answer, "id" to id, "hid" to householdId),
        )
        if (updated == 0) throw ApiException.notFound()
        audit.record(
            householdId = householdId, actorUserId = userId, action = "continuity.key_holder.answer",
            entityType = current.recordType, entityId = current.recordId, diff = mapOf("answer" to body.answer),
        )
        val askedBy = jdbc.query(
            "select asked_by from key_holder_asks where id = :id",
            mapOf("id" to id),
        ) { rs, _ -> rs.getObject("asked_by", UUID::class.java) }.firstOrNull()
        if (askedBy != null && current.answer == null) {
            notices.send(
                askedBy, householdId, ContinuityNotices.KEY_HOLDER_ANSWER,
                "${current.askedName ?: "They"} answered your question",
                "${current.question} ${if (body.answer == "yes") "Yes." else "Not sure."}",
                "continuity.key_holder_answer:$id",
            )
        }
        return get(householdId, id, userId)
    }

    @Transactional
    fun withdraw(householdId: UUID, id: UUID) {
        val userId = userContext.require()
        households.get(householdId)
        val removed = jdbc.update(
            "delete from key_holder_asks where id = :id and household_id = :hid and asked_by = :uid",
            mapOf("id" to id, "hid" to householdId, "uid" to userId),
        )
        if (removed == 0) throw ApiException.notFound()
        audit.record(
            householdId = householdId, actorUserId = userId, action = "continuity.key_holder.withdraw",
            entityType = "key_holder_ask", entityId = id,
        )
    }

    private fun get(householdId: UUID, id: UUID, userId: UUID): KeyHolderAsk =
        query("where k.household_id = :hid and k.id = :id", mapOf("hid" to householdId, "id" to id), userId)
            .firstOrNull() ?: throw ApiException.notFound()

    private fun query(where: String, params: Map<String, Any?>, userId: UUID): List<KeyHolderAsk> = jdbc.query(
        """
        select k.*, am.display_name as asked_name,
               (select bm.display_name from members bm
                where bm.household_id = k.household_id and bm.user_id = k.asked_by and bm.deleted_at is null
                limit 1) as asked_by_name
        from key_holder_asks k
        left join members am on am.id = k.asked_member_id
        $where
        """.trimIndent(),
        params,
    ) { rs, _ ->
        val thing = rs.getString("thing")
        KeyHolderAsk(
            id = rs.getObject("id", UUID::class.java),
            recordType = rs.getString("record_type"),
            recordId = rs.getObject("record_id", UUID::class.java),
            thing = thing,
            question = question(thing),
            askedMemberId = rs.getObject("asked_member_id", UUID::class.java),
            askedName = rs.getString("asked_name"),
            askedByName = rs.getString("asked_by_name"),
            askedByMe = rs.getObject("asked_by", UUID::class.java) == userId,
            answer = rs.getString("answer"),
            answeredAt = rs.getTimestamp("answered_at")?.toInstant(),
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )
    }

    companion object {
        const val MAX_OPEN = 20
        val ANSWERS = setOf("yes", "not_sure")

        /** Table and title column per record kind — the same five as where-and-who (docs/20 §2). */
        val RECORDS = mapOf(
            "investment" to ("investments" to "title"),
            "liability" to ("liabilities" to "title"),
            "account" to ("accounts" to "label"),
            "document" to ("documents" to "file_name"),
            "estate_document" to ("estate_documents" to "title"),
        )

        fun question(thing: String) = "Do you know where $thing is?"
    }
}

@RestController
@RequestMapping("/api/v1/households/{householdId}/key-holder-asks")
class KeyHolderAskController(private val service: KeyHolderAskService) {

    @GetMapping
    fun keyHolderAsks(@PathVariable householdId: UUID): KeyHolderAsks = service.list(householdId)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun askKeyHolder(@PathVariable householdId: UUID, @RequestBody body: AskKeyHolderBody): KeyHolderAsk =
        service.ask(householdId, body)

    @PostMapping("/{id}/answer")
    fun answerKeyHolderAsk(
        @PathVariable householdId: UUID,
        @PathVariable id: UUID,
        @RequestBody body: AnswerKeyHolderBody,
    ): KeyHolderAsk = service.answer(householdId, id, body)

    @DeleteMapping("/{id}")
    fun withdrawKeyHolderAsk(@PathVariable householdId: UUID, @PathVariable id: UUID): ResponseEntity<Void> {
        service.withdraw(householdId, id)
        return ResponseEntity.noContent().build()
    }
}
