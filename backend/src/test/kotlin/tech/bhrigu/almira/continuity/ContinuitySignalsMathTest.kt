package tech.bhrigu.almira.continuity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

@DisplayName("Continuity signals: the timeline and the ratio, as arithmetic")
class ContinuitySignalsMathTest {

    private val now = Instant.parse("2026-09-14T06:30:00Z")
    private fun daysAgo(days: Long) = now.minus(Duration.ofDays(days))

    @Test
    fun `the first reminder waits for the whole period`() {
        assertThat(InactivityTimeline.next(daysAgo(89), 90, null, null, null, now)).isNull()
        assertThat(InactivityTimeline.next(daysAgo(90), 90, null, null, null, now))
            .isEqualTo(InactivityTimeline.Step.FIRST_REMINDER)
    }

    @Test
    fun `each step counts from the one before it actually happening`() {
        val presence = daysAgo(400)
        assertThat(InactivityTimeline.next(presence, 90, daysAgo(29), null, null, now)).isNull()
        assertThat(InactivityTimeline.next(presence, 90, daysAgo(30), null, null, now))
            .isEqualTo(InactivityTimeline.Step.SECOND_REMINDER)
        assertThat(InactivityTimeline.next(presence, 90, daysAgo(60), daysAgo(29), null, now)).isNull()
        assertThat(InactivityTimeline.next(presence, 90, daysAgo(60), daysAgo(30), null, now))
            .isEqualTo(InactivityTimeline.Step.RAISE)
        assertThat(InactivityTimeline.next(presence, 90, daysAgo(60), daysAgo(30), daysAgo(1), now))
            .describedAs("raised once per cycle").isNull()
    }

    @Test
    fun `a server that was away does not skip straight to a request`() {
        assertThat(InactivityTimeline.next(daysAgo(500), 90, null, null, null, now))
            .isEqualTo(InactivityTimeline.Step.FIRST_REMINDER)
    }

    @Test
    fun `any presence after a stamp starts a new cycle`() {
        val presence = daysAgo(5)
        assertThat(InactivityTimeline.next(presence, 60, daysAgo(40), daysAgo(10), daysAgo(6), now)).isNull()
        assertThat(InactivityTimeline.stage(presence, daysAgo(40), daysAgo(10), daysAgo(6))).isEqualTo("quiet")
        assertThat(InactivityTimeline.next(daysAgo(70), 60, daysAgo(80), null, null, now))
            .describedAs("a reminder older than the presence belongs to the last cycle")
            .isEqualTo(InactivityTimeline.Step.FIRST_REMINDER)
    }

    @Test
    fun `the ratio rounds down and says it in words`() {
        assertThat(ProtectionMath.headline(BigDecimal("10000000"), BigDecimal("2500000")))
            .isEqualTo("Term cover is about 4× annual expenses.")
        assertThat(ProtectionMath.headline(BigDecimal("9999999"), BigDecimal("2500000")))
            .describedAs("3.99 is never called 4").isEqualTo("Term cover is about 3.9× annual expenses.")
        assertThat(ProtectionMath.headline(BigDecimal("100"), BigDecimal("2500000")))
            .isEqualTo("Term cover is less than a tenth of annual expenses.")
        assertThat(ProtectionMath.headline(BigDecimal.ZERO, BigDecimal("2500000")))
            .isEqualTo("No term cover is recorded that you can see.")
        assertThat(ProtectionMath.years(BigDecimal("2500000"), BigDecimal("2500000"))).contains("roughly 1 year ")
        assertThat(ProtectionMath.gaugePercent(BigDecimal("40"))).describedAs("a scale, capped").isEqualTo(100)
        assertThat(ProtectionMath.gaugePercent(null)).isNull()
    }
}
