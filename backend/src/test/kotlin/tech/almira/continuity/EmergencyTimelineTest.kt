package tech.almira.continuity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

@DisplayName("Emergency access as a dated picture")
class EmergencyTimelineTest {

    private val asked = Instant.parse("2026-09-01T04:30:00Z")
    private val opens = asked.plus(Duration.ofDays(7))
    private val closes = opens.plus(Duration.ofDays(30))

    private fun timeline(status: String, byMe: Boolean = false, stoppedAt: Instant? = null, active: Boolean = false) =
        EmergencyTimeline.forRequest(
            status = status, requestedByMe = byMe, requesterName = "Ravi", subjectName = "Ishwarya",
            requestedAt = asked, unlockAt = opens, expiresAt = closes, stoppedAt = stoppedAt, subjectActive = active,
        )

    @Test
    fun `the same five steps, in order, with dates`() {
        val steps = timeline("waiting")
        assertThat(steps.map { it.key }).containsExactly("asked", "told", "say_no", "opens", "closes")
        assertThat(steps.map { it.at }).containsExactly(asked, asked, opens, opens, closes)
        assertThat(steps.first { it.key == "say_no" }.title).isEqualTo("7 days to say no")
    }

    @Test
    fun `the owner reads it as being told, and sees it is on the part where they can say no`() {
        val steps = timeline("waiting")
        assertThat(steps[0].title).isEqualTo("Ravi asked")
        assertThat(steps[1].title).isEqualTo("You were told")
        assertThat(steps.map { it.state }).containsExactly("done", "done", "now", "next", "next")
    }

    @Test
    fun `what opens is the family plan and never the private entries`() {
        val opensStep = timeline("open", byMe = true).first { it.key == "opens" }
        assertThat(opensStep.title).isEqualTo("You see the family plan")
        assertThat(opensStep.detail).contains("Never the private entries")
        assertThat(opensStep.state).isEqualTo("now")
    }

    @Test
    fun `a veto is shown as the end of it, and nothing after it happens`() {
        val stopped = asked.plus(Duration.ofDays(2))
        val steps = timeline("vetoed", stoppedAt = stopped)
        val sayNo = steps.first { it.key == "say_no" }
        assertThat(sayNo.detail).contains("said no").contains("Nothing was opened")
        assertThat(sayNo.at).isEqualTo(stopped)
        assertThat(steps.filter { it.key in setOf("opens", "closes") }.map { it.state }).containsOnly("skipped")
    }

    @Test
    fun `being active since keeps it shut, and the picture says why`() {
        assertThat(timeline("waiting", active = true).first { it.key == "say_no" }.detail)
            .contains("used Almira since")
    }

    @Test
    fun `a preview is every step still to come, and counts rather than names`() {
        val now = Instant.parse("2026-09-14T04:30:00Z")
        val steps = EmergencyTimeline.preview("Ravi", now, now.plus(Duration.ofDays(14)), now.plus(Duration.ofDays(44)), 14)
        assertThat(steps.map { it.state }).containsOnly("next")
        assertThat(steps.map { it.title }).contains("Ravi asks", "You're told", "14 days to say no", "Ravi sees the family plan")

        assertThat(EmergencyTimeline.willSee(included = 1, debts = 2, paperwork = 1))
            .contains("1 thing marked for the family plan, with how to claim each")
            .anyMatch { it.startsWith("2 loans") }
        assertThat(EmergencyTimeline.neverSee(leftOut = 3)).first().isEqualTo("The 3 entries you left out of the family plan")
        assertThat(EmergencyTimeline.neverSee(leftOut = 0)).noneMatch { it.contains("left out") }
    }
}
