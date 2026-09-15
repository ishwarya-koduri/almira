package tech.bhrigu.almira.shared.api

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A brand-new household must never see "0%" (or "100%") on the app. The
 * responses below are the shapes the server sends (ReportsApiTest,
 * HandoverReadinessApiTest), decoded with the app's own settings.
 */
class ScoreDisplayTest {

    // The settings AlmiraApi decodes with.
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        explicitNulls = false
    }

    private val nothingRecorded = """
        {"score":0,"scoreEarned":false,
         "scoreExplanation":"Nothing is recorded that you can see yet, so there is nothing to score.",
         "recordCount":0,"scoreLabel":"Nothing to check yet","checks":[],
         "nextStep":"Add your first holding and this will tell you what's missing.",
         "note":"A measure of how usable these records would be."}
    """.trimIndent()

    @Test
    fun a_new_household_is_shown_the_sentence_not_zero_percent() {
        val report = json.decodeFromString<Completeness>(nothingRecorded)
        assertEquals(
            ScoreDisplay.NoScore("Nothing is recorded that you can see yet, so there is nothing to score."),
            report.display(),
        )
    }

    @Test
    fun an_unearned_score_without_its_sentence_still_shows_a_sentence() {
        val report = json.decodeFromString<Completeness>(
            """{"score":0,"scoreEarned":false,"recordCount":0,"scoreLabel":"x","note":"n"}""",
        )
        assertEquals(ScoreDisplay.NoScore(Completeness.NOTHING_TO_SCORE), report.display())
    }

    @Test
    fun an_earned_score_is_shown_as_the_server_rounded_it() {
        val report = json.decodeFromString<Completeness>(
            """{"score":99,"scoreEarned":true,"recordCount":3,"scoreLabel":"x","note":"n"}""",
        )
        assertEquals(ScoreDisplay.Percent("99%"), report.display())
    }

    @Test
    fun an_earned_zero_is_still_a_number() {
        val report = json.decodeFromString<Completeness>(
            """{"score":0,"scoreEarned":true,"recordCount":3,"scoreLabel":"x","note":"n"}""",
        )
        assertEquals(ScoreDisplay.Percent("0%"), report.display())
    }

    @Test
    fun a_completeness_without_scoreEarned_is_refused_rather_than_guessed() {
        assertFailsWith<SerializationException> {
            json.decodeFromString<Completeness>(
                """{"score":100,"recordCount":0,"scoreLabel":"x","note":"n"}""",
            )
        }
    }

    @Test
    fun readiness_with_no_score_shows_its_sentence() {
        // `score` is left out of the JSON when null (non_null inclusion).
        val readiness = json.decodeFromString<HandoverReadiness>(
            """{"scoreExplanation":"Nothing is recorded for your family yet, so there is nothing to score.",
                "complete":false,"recordCount":0,"leftOutCount":0,"leftOut":[],
                "checks":[{"code":"nominee","label":"Nominee","done":0,"applicable":0}],
                "gaps":[],"caveats":[]}""",
        )
        assertEquals(
            ScoreDisplay.NoScore("Nothing is recorded for your family yet, so there is nothing to score."),
            readiness.display(),
        )
    }

    @Test
    fun readiness_with_a_score_shows_it() {
        val readiness = json.decodeFromString<HandoverReadiness>(
            """{"score":75,"scoreExplanation":"How it is counted.","complete":false,
                "recordCount":4,"leftOutCount":0,"leftOut":[],"checks":[],"gaps":[],"caveats":[]}""",
        )
        assertEquals(ScoreDisplay.Percent("75%"), readiness.display())
    }
}
