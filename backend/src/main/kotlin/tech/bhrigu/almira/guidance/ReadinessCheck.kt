package tech.bhrigu.almira.guidance

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.security.RequestUserContext
import java.time.Instant

/**
 * "How ready is your family?" — eight questions before any data (X-30,
 * docs/03 §1.1).
 *
 * The questions are about the family's paperwork, never its money: nothing here
 * asks for an amount, a name or an account. The answer is four words wide on
 * purpose (yes, partly, no, not sure) so it can be given in three minutes by
 * someone who has never opened a spreadsheet.
 *
 * The three most important gaps are worked out here rather than in a client,
 * so a phone and the web cannot name different gaps for the same answers.
 * Importance is a fixed order, stated below and in docs/03, not a model: a will
 * nobody wrote matters more than a list nobody kept, and saying so plainly is
 * the whole value of the check.
 */
enum class ReadinessQuestion(
    val code: String,
    /** Higher is more important. Ties go to the earlier question. */
    val weight: Int,
    /** The first-session shelf that closes this gap, when one does. */
    val shelf: String?,
) {
    WILL("will", 10, "will"),
    NOMINEES("nominees", 9, "fixed_deposit"),
    PAPERS("papers", 8, "property"),
    SECOND_PERSON("second_person", 7, null),
    ONE_LIST("one_list", 6, "savings_account"),
    INSURANCE("insurance", 6, "life_insurance"),
    LOANS("loans", 5, "loans"),
    LOCKER("locker", 4, "locker"),
    ;

    companion object {
        fun of(code: String) = entries.firstOrNull { it.code == code }
    }
}

enum class ReadinessAnswer(val code: String, val severity: Double) {
    YES("yes", 0.0),
    PARTLY("partly", 0.5),
    NO("no", 1.0),
    // Not knowing whether there is a will is nearly as much of a gap as there
    // being none: the family would not know either.
    NOT_SURE("not_sure", 0.8),
    ;

    companion object {
        fun of(code: String) = entries.firstOrNull { it.code == code }
    }
}

data class ReadinessCheckGap(
    val question: String,
    val answer: String,
    /** Where the gap is closed in the first session, or null when it is a conversation, not a record. */
    val shelf: String?,
)

data class ReadinessCheckResponse(
    /** False until the check has been taken; then answers, gaps and answeredAt are filled in. */
    val answered: Boolean,
    val questions: List<String> = ReadinessQuestion.entries.map { it.code },
    val answerChoices: List<String> = ReadinessAnswer.entries.map { it.code },
    val answers: Map<String, String> = emptyMap(),
    /** At most three, most important first. Empty when every answer was yes. */
    val gaps: List<ReadinessCheckGap> = emptyList(),
    /** How many questions were answered yes. */
    val readyCount: Int = 0,
    val answeredAt: Instant? = null,
)

data class ReadinessCheckBody(@field:NotNull @field:Size(max = 20) val answers: Map<String, String>?)

/** Pure, so the order of importance can be tested without a database. */
object ReadinessScoring {
    fun gaps(answers: Map<ReadinessQuestion, ReadinessAnswer>, limit: Int = 3): List<ReadinessCheckGap> =
        answers.entries
            .filter { it.value.severity > 0 }
            .sortedWith(
                compareByDescending<Map.Entry<ReadinessQuestion, ReadinessAnswer>> { it.key.weight * it.value.severity }
                    .thenBy { it.key.ordinal },
            )
            .take(limit)
            .map { ReadinessCheckGap(it.key.code, it.value.code, it.key.shelf) }

    /** Unknown questions and answers are refused by name; an empty check is refused too. */
    fun parse(raw: Map<String, String>): Map<ReadinessQuestion, ReadinessAnswer> {
        if (raw.isEmpty()) {
            throw ApiException.badRequest("answers_required", "Answer at least one question.")
        }
        val problems = mutableMapOf<String, String>()
        val parsed = linkedMapOf<ReadinessQuestion, ReadinessAnswer>()
        for ((code, value) in raw) {
            val question = ReadinessQuestion.of(code)
            val answer = ReadinessAnswer.of(value)
            when {
                question == null -> problems[code.take(40)] = "That isn't one of the questions."
                answer == null -> problems[code] = "Answer yes, partly, no or not sure."
                else -> parsed[question] = answer
            }
        }
        if (problems.isNotEmpty()) {
            throw ApiException.badRequest(
                "answers_invalid", "Some answers couldn't be read.", mapOf("fields" to problems),
            )
        }
        return parsed
    }
}

@Service
class ReadinessCheckService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val userContext: RequestUserContext,
    private val mapper: ObjectMapper,
) {
    /** Row-level security keeps every statement here to the caller's own row. */
    @Transactional(readOnly = true)
    fun mine(): ReadinessCheckResponse {
        userContext.require()
        val row = jdbc.query(
            "select answers::text as answers, answered_at from readiness_check_answers",
            emptyMap<String, Any>(),
        ) { rs, _ -> mapper.readValue<Map<String, String>>(rs.getString("answers")) to rs.getTimestamp("answered_at").toInstant() }
            .firstOrNull() ?: return ReadinessCheckResponse(answered = false)
        // Stored answers were valid when written; a question retired since is
        // simply not shown rather than failing the read.
        val parsed = row.first.mapNotNull { (q, a) ->
            val question = ReadinessQuestion.of(q)
            val answer = ReadinessAnswer.of(a)
            if (question != null && answer != null) question to answer else null
        }.toMap()
        return response(parsed, row.second)
    }

    @Transactional
    fun answer(raw: Map<String, String>): ReadinessCheckResponse {
        val userId = userContext.require()
        val parsed = ReadinessScoring.parse(raw)
        val json = mapper.writeValueAsString(parsed.entries.associate { it.key.code to it.value.code })
        jdbc.update(
            """
            insert into readiness_check_answers (user_id, answers) values (:u, cast(:a as jsonb))
            on conflict (user_id) do update
              set answers = excluded.answers, answered_at = now(),
                  version = readiness_check_answers.version + 1
            """.trimIndent(),
            mapOf("u" to userId, "a" to json),
        )
        return mine()
    }

    private fun response(parsed: Map<ReadinessQuestion, ReadinessAnswer>, answeredAt: Instant) =
        ReadinessCheckResponse(
            answered = true,
            answers = parsed.entries.associate { it.key.code to it.value.code },
            gaps = ReadinessScoring.gaps(parsed),
            readyCount = parsed.values.count { it == ReadinessAnswer.YES },
            answeredAt = answeredAt,
        )
}

// Handler names are OpenAPI operationIds; v1 already has `mine` and `update`
// elsewhere, so these are named for what they are (known-issues 16).
@RestController
@RequestMapping("/api/v1/me/readiness-check")
class ReadinessCheckController(private val service: ReadinessCheckService) {

    @GetMapping
    fun readinessCheck(): ReadinessCheckResponse = service.mine()

    @PutMapping
    fun answerReadinessCheck(@Valid @RequestBody body: ReadinessCheckBody): ReadinessCheckResponse =
        service.answer(body.answers!!)
}
