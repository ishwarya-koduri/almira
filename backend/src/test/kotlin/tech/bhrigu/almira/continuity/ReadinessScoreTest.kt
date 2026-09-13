package tech.bhrigu.almira.continuity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The arithmetic of docs/22 §1, apart from the database. The promise it holds
 * is the one that matters: nothing missing is the only way to 100.
 */
@DisplayName("Handover readiness — the score's arithmetic")
class ReadinessScoreTest {

    @Test
    fun `one gap in two hundred is not rounded up to 100`() {
        val score = ReadinessScore.of(listOf(199 to 200, 5 to 5, 3 to 3, 1 to 1))
        // 99.875: rounding to nearest would say 100 with a nominee still missing.
        assertThat(score).isEqualTo(99)
    }

    @Test
    fun `no possible single gap reaches 100, however large the household`() {
        for (applicable in 1..2_000) {
            assertThat(ReadinessScore.of(listOf(applicable - 1 to applicable, 1 to 1, 1 to 1, 1 to 1)))
                .describedAs("${applicable - 1} of $applicable")
                .isLessThan(100)
        }
    }

    @Test
    fun `each check that applies counts equally, and one that does not apply is left out`() {
        // docs/22 §1's worked example: 50, 100, 50, 100.
        assertThat(ReadinessScore.of(listOf(5 to 10, 10 to 10, 2 to 4, 1 to 1))).isEqualTo(75)
        assertThat(ReadinessScore.of(listOf(6 to 10, 10 to 10, 2 to 4, 1 to 1))).isEqualTo(77)
        // A check with nothing to apply to is not a free 100.
        assertThat(ReadinessScore.of(listOf(1 to 2, 0 to 0, 0 to 0, 0 to 0))).isEqualTo(50)
    }

    @Test
    fun `a household gap is a quarter of the score, not one item among many`() {
        assertThat(ReadinessScore.of(listOf(30 to 30, 30 to 30, 30 to 30, 0 to 1))).isEqualTo(75)
    }

    @Test
    fun `everything done is 100, and nothing applying is no number at all`() {
        assertThat(ReadinessScore.of(listOf(3 to 3, 4 to 4, 1 to 1, 1 to 1))).isEqualTo(100)
        assertThat(ReadinessScore.of(listOf(0 to 0, 0 to 0))).isNull()
        assertThat(ReadinessScore.of(emptyList())).isNull()
    }

    @Test
    fun `a check's own percentage is rounded down too`() {
        assertThat(ReadinessScore.percent(199, 200)).isEqualTo(99)
        assertThat(ReadinessScore.percent(0, 0)).isNull()
    }
}
