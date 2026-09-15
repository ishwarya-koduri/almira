package tech.bhrigu.almira.provider

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tech.bhrigu.almira.reminder.ReminderWorker
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** The words around every notification (docs/13 "What a message says"). */
@DisplayName("Message templates")
class MessageTemplatesTest {

    private val templates = listOf(
        "reminder.maturity", "reminder.premium_due", "still_true.digest",
        "emergency.named", "emergency.requested", "lifecycle.memorial.marked", "lifecycle.successor.named", "something.new",
    )
    private val body = "₹2,40,000 at SBI\nTwo Lakh Forty Thousand Rupees"

    @Test
    fun `every message on every channel, in every language, says why it came`() {
        MessageTemplates.ALL.forEach { wording ->
            templates.forEach { template ->
                val kind = MessageTemplates.kindOf(template)
                listOf("email", "sms", "push").forEach { channel ->
                    val composed = MessageTemplates.compose(template, "SBI FD matures Thursday", body, channel, wording)
                    val reason = if (channel == "email") wording.why.getValue(kind) else wording.whyShort.getValue(kind)
                    assertThat(composed.text).describedAs("${wording.language} $template $channel").contains(reason)
                    assertThat(reason).isNotBlank()
                }
            }
        }
    }

    @Test
    fun `an email carries the body and the quiet promise, a push and a text never carry the body`() {
        val email = MessageTemplates.compose("reminder.maturity", "SBI FD matures Thursday", body, "email", "en-IN")
        assertThat(email.subject).isEqualTo("SBI FD matures Thursday")
        assertThat(email.text).contains("₹2,40,000", "Two Lakh Forty Thousand", "at most one reminder a day", "never a sales message")

        listOf("push", "sms").forEach { channel ->
            val short = MessageTemplates.compose("reminder.maturity", "SBI FD matures Thursday", body, channel, "en-IN")
            assertThat(short.text).describedAs(channel).doesNotContain("2,40,000", "Lakh")
        }
    }

    @Test
    fun `Telugu and Hindi are drafts, marked for review, and never sent until reviewed`() {
        assertThat(MessageTemplates.TELUGU.needsReview).isTrue()
        assertThat(MessageTemplates.HINDI.needsReview).isTrue()
        assertThat(MessageTemplates.ENGLISH.needsReview).isFalse()
        assertThat(MessageTemplates.REVIEWED).containsExactly("en")

        listOf("te-IN", "hi-IN", "te", "xx-YY", null).forEach { locale ->
            assertThat(MessageTemplates.compose("still_true.digest", "t", "b", "email", locale).language)
                .describedAs("$locale").isEqualTo("en")
        }
    }

    @Test
    fun `the essential notices are a list, not a prefix, and only they are worded as always coming`() {
        assertThat(MessageTemplates.isEssential("emergency.requested")).isTrue()
        assertThat(MessageTemplates.isEssential("lifecycle.memorial.marked")).isTrue()
        assertThat(MessageTemplates.isEssential("auth.new_sign_in")).isTrue()
        assertThat(MessageTemplates.isEssential("auth.phone_changed")).isTrue()
        assertThat(MessageTemplates.isEssential("lifecycle.closure.requested")).isTrue()
        assertThat(MessageTemplates.isEssential("reminder.maturity")).isFalse()
        assertThat(MessageTemplates.isEssential("still_true.digest")).isFalse()
        // Household news, and under consent: someone else left, a child came of age, a new auth.* nobody classified.
        listOf("lifecycle.departure.completed", "lifecycle.coming_of_age.welcomed", "auth.something_new").forEach {
            assertThat(MessageTemplates.isEssential(it)).describedAs(it).isFalse()
            assertThat(MessageTemplates.kindOf(it)).describedAs(it).isEqualTo(MessageTemplates.Kind.OTHER)
        }
        // It changes your own rights or obligations (V140): essential, and worded as always coming.
        assertThat(MessageTemplates.kindOf("emergency.named")).isEqualTo(MessageTemplates.Kind.EMERGENCY)
        listOf(
            "lifecycle.departure.completed.you", "lifecycle.memorial.reversed", "lifecycle.successor.named",
            // V143: the dormancy notices change your access too.
            "lifecycle.household.dormant", "lifecycle.household.dormant.you", "lifecycle.household.running_again",
            "lifecycle.household.ownership_accepted",
            // V144: asked first to take it on, and an operator repair of your household.
            "lifecycle.household.asked_first", "lifecycle.household.repair_requested",
            "lifecycle.household.repair_done",
        ).forEach {
            assertThat(MessageTemplates.isEssential(it)).describedAs(it).isTrue()
            assertThat(MessageTemplates.kindOf(it)).describedAs(it).isEqualTo(MessageTemplates.Kind.YOUR_PLACE)
        }
        assertThat(MessageTemplates.kindOf("emergency.requested")).isEqualTo(MessageTemplates.Kind.EMERGENCY)
        assertThat(MessageTemplates.kindOf("auth.phone_changed")).isEqualTo(MessageTemplates.Kind.ACCOUNT)
    }

    @Test
    fun `a reminder's body gives the date in words and the amount in Indian grouping with its words`() {
        val body = ReminderWorker.bodyFor(
            ReminderWorker.DueReminder(
                id = UUID.randomUUID(), householdId = UUID.randomUUID(), investmentId = null, liabilityId = null,
                kind = "maturity", title = "SBI FD matures", dueDate = LocalDate.of(2026, 9, 17),
                firesOn = LocalDate.of(2026, 9, 17), amount = BigDecimal("240000.0000"),
            ),
        )
        assertThat(body).isEqualTo("Due on Thursday, 17 September 2026.\n₹2,40,000\nTwo Lakh Forty Thousand Rupees")
    }

    @Test
    fun `quiet hours that cross midnight, and ones that do not`() {
        val overnight = DeliveryDirectory.DEFAULTS
        assertThat(overnight.isQuiet(java.time.LocalTime.of(23, 0))).isTrue()
        assertThat(overnight.isQuiet(java.time.LocalTime.of(7, 59))).isTrue()
        assertThat(overnight.isQuiet(java.time.LocalTime.of(8, 0))).isFalse()
        assertThat(overnight.isQuiet(java.time.LocalTime.of(20, 59))).isFalse()
        val afternoon = overnight.copy(quietFrom = java.time.LocalTime.of(13, 0), quietUntil = java.time.LocalTime.of(15, 0))
        assertThat(afternoon.isQuiet(java.time.LocalTime.of(14, 0))).isTrue()
        assertThat(afternoon.isQuiet(java.time.LocalTime.of(23, 0))).isFalse()
    }
}
