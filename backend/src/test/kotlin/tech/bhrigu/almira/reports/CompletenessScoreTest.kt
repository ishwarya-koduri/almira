package tech.bhrigu.almira.reports

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The arithmetic of the Reports completeness score, apart from the database
 * (known-issues 19). The promise: nothing missing is the only way to 100, and
 * nothing to score is no number at all.
 */
@DisplayName("Completeness — the score's arithmetic")
class CompletenessScoreTest {

    private fun check(code: String, weight: Int, done: Int, outstanding: Int) =
        CompletenessCheck(
            code = code, label = code, fix = code,
            done = done, outstanding = outstanding,
            investmentIds = emptyList(), weight = weight,
        )

    /** A household of [n] holdings, complete except for [gaps] in one check of weight [gapWeight]. */
    private fun household(n: Int, gapWeight: Int, gaps: Int = 1) = listOf(
        check("nominee", 3, if (gapWeight == 3) n - gaps else n, if (gapWeight == 3) gaps else 0),
        check("proof", 2, n, 0),
        check("value", 2, n, 0),
        check("continuity", 1, if (gapWeight == 1) n - gaps else n, if (gapWeight == 1) gaps else 0),
    )

    @Test
    fun `one gap in a thousand items is not rounded up to 100`() {
        // 250 holdings x 4 checks = 1000 items; one of them, the lightest, is missing.
        // 1999 of 2000 weighted points is 99.95%: rounding to nearest said 100.
        assertThat(CompletenessScore.of(household(250, gapWeight = 1))).isEqualTo(99)
        // One missing nominee in 200 holdings: 1597 of 1600, 99.81%.
        assertThat(CompletenessScore.of(household(200, gapWeight = 3))).isEqualTo(99)
    }

    @Test
    fun `no single gap reaches 100, however large the household`() {
        for (n in 1..2_000) {
            assertThat(CompletenessScore.of(household(n, gapWeight = 1)))
                .describedAs("one item missing among $n holdings")
                .isLessThan(100)
        }
    }

    @Test
    fun `everything done is 100`() {
        assertThat(CompletenessScore.of(household(250, gapWeight = 1, gaps = 0))).isEqualTo(100)
        assertThat(CompletenessScore.of(household(1, gapWeight = 1, gaps = 0))).isEqualTo(100)
    }

    @Test
    fun `nothing to score is no number, not 100 and not 0`() {
        assertThat(CompletenessScore.of(emptyList())).isNull()
        assertThat(CompletenessScore.of(listOf(check("nominee", 3, 0, 0)))).isNull()
    }

    @Test
    fun `the score is rounded down, and still weighted`() {
        // 2 of 3 weighted points: 66.67 rounds down to 66, not up to 67.
        assertThat(CompletenessScore.of(listOf(check("proof", 2, 1, 0), check("continuity", 1, 0, 1))))
            .isEqualTo(66)
        // Nothing done at all is an honest 0.
        assertThat(CompletenessScore.of(listOf(check("nominee", 3, 0, 4)))).isEqualTo(0)
    }
}
