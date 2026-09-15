package tech.bhrigu.almira.privacy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import tech.bhrigu.almira.provider.RecordingNotifier
import tech.bhrigu.almira.reminder.OutboundNotification
import tech.bhrigu.almira.support.ApiTestBase
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * The data rights as things a person can do (docs/23 "Your data rights"). Every
 * table behind them belongs to one person, so half of each test is somebody
 * else trying.
 */
@DisplayName("Data rights: consent, requests, nominees and the access summary")
class DataRightsApiTest : ApiTestBase() {

    @Autowired private lateinit var notifier: RecordingNotifier

    private lateinit var owner: String
    private lateinit var spouse: String
    private lateinit var householdId: String
    private lateinit var ownerUserId: String

    @BeforeEach
    fun setUp() {
        owner = signIn()
        spouse = signIn()
        val household = createHousehold(owner, "Koduri", "private", "Ishwarya")
        householdId = household.path("id").asText()
        ownerUserId = db.queryForObject(
            "select user_id::text from members where id = ?::uuid", String::class.java,
            household.path("myMemberId").asText(),
        )!!
        val spouseMember = addMember(owner, householdId, "Ravi").path("id").asText()
        joinHousehold(owner, householdId, spouseMember, spouse, role = "admin")
    }

    private fun stepUp(token: String) {
        val challenge = post("/api/v1/auth/step-up/request", token).json()
        val verified = post(
            "/api/v1/auth/step-up/verify", token,
            mapOf("code" to challenge.path("developmentCode").asText(),
                  "requestId" to challenge.path("requestId").asText()),
        )
        check(verified.statusCode.is2xxSuccessful) { "step-up failed: ${verified.body}" }
    }

    // --- consent ---------------------------------------------------------------

    @Test
    fun `a new account has been asked nothing yet, and the page says so`() {
        val overview = get("/api/v1/me/privacy", owner).json()

        assertThat(overview.path("consents").map { it.path("purpose").asText() })
            .containsExactly("records", "messages")
        assertThat(overview.path("consents").all { it.path("given").isNull || it.path("given").isMissingNode }).isTrue()
        assertThat(overview.path("consents")[0].path("required").asBoolean()).isTrue()
        assertThat(overview.path("noticeLegallyReviewed").asBoolean())
            .describedAs("the notice is a draft until counsel reads it").isFalse()
        assertThat(overview.path("obligationsCommenceOn").asText()).isEqualTo("2027-05-13")
        assertThat(overview.path("grievance").path("respondWithinDays").asInt()).isBetween(1, 90)
    }

    @Test
    fun `withdrawing is one call, the same as giving, and the history is dated`() {
        val given = post("/api/v1/me/privacy/consents", owner, mapOf("purpose" to "messages", "given" to true))
        assertThat(given.status()).isEqualTo(HttpStatus.OK)
        assertThat(given.json().path("consents")[1].path("given").asBoolean()).isTrue()

        // Pressing it again is not a second decision.
        post("/api/v1/me/privacy/consents", owner, mapOf("purpose" to "messages", "given" to true))

        val withdrawn = post("/api/v1/me/privacy/consents", owner, mapOf("purpose" to "messages", "given" to false))
        assertThat(withdrawn.status()).isEqualTo(HttpStatus.OK)
        assertThat(withdrawn.json().path("consents")[1].path("given").asBoolean()).isFalse()

        val history = get("/api/v1/me/privacy/history", owner).json()
        assertThat(history.map { it.path("action").asText() }).containsExactly("withdrawn", "given")
        assertThat(history.all { it.path("at").asText().isNotEmpty() && it.path("noticeVersion").asText().isNotEmpty() })
            .isTrue()

        assertThat(get("/api/v1/me/privacy/history", spouse).json().size())
            .describedAs("an admin of the same household sees none of it").isZero()
    }

    @Test
    fun `the service itself is withdrawn by closing the account, and the answer says so`() {
        val refused = post("/api/v1/me/privacy/consents", owner, mapOf("purpose" to "records", "given" to false))
        assertThat(refused.status()).isEqualTo(HttpStatus.CONFLICT)
        assertThat(refused.errorCode()).isEqualTo("withdraw_by_closing")

        val unknown = post("/api/v1/me/privacy/consents", owner, mapOf("purpose" to "marketing", "given" to true))
        assertThat(unknown.status()).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `accepting a notice records the version that was actually read`() {
        val current = get("/api/v1/me/privacy", owner).json().path("noticeVersion").asText()

        val stale = post("/api/v1/me/privacy/notice/accept", owner, mapOf("version" to "2020-01-01"))
        assertThat(stale.status()).isEqualTo(HttpStatus.CONFLICT)
        assertThat(stale.errorCode()).isEqualTo("notice_changed")

        val accepted = post("/api/v1/me/privacy/notice/accept", owner, mapOf("version" to current)).json()
        assertThat(accepted.path("acceptedNoticeVersion").asText()).isEqualTo(current)
        assertThat(get("/api/v1/me/privacy/history", owner).json()[0].path("kind").asText()).isEqualTo("notice")
    }

    @Test
    fun `withdrawn consent to messages stops email and text but keeps the in-app copy and safety notices`() {
        post("/api/v1/me/privacy/consents", owner, mapOf("purpose" to "messages", "given" to true))
        post("/api/v1/me/privacy/consents", owner, mapOf("purpose" to "messages", "given" to false))
        val user = UUID.fromString(ownerUserId)
        val household = UUID.fromString(householdId)

        notifier.deliver(
            OutboundNotification(user, household, null, "reminder.maturity", "FD matures", "Due soon", "dpdp:reminder:$user"),
        )
        notifier.deliver(
            OutboundNotification(user, household, null, "emergency.requested", "Asked", "You can stop it", "dpdp:emergency:$user"),
        )

        fun outside(template: String) = db.queryForObject(
            "select count(*) from outbound_messages where user_id = ?::uuid and template = ? and channel <> 'in_app'",
            Int::class.java, ownerUserId, template,
        )
        fun inApp(template: String) = db.queryForObject(
            "select count(*) from outbound_messages where user_id = ?::uuid and template = ? and channel = 'in_app'",
            Int::class.java, ownerUserId, template,
        )

        assertThat(outside("reminder.maturity")).describedAs("no email or text after withdrawal").isZero()
        assertThat(inApp("reminder.maturity")).describedAs("the in-app copy is still kept").isPositive()
        assertThat(outside("emergency.requested"))
            .describedAs("a safety notice is not under this consent").isPositive()

        // And giving it back is one call too.
        post("/api/v1/me/privacy/consents", owner, mapOf("purpose" to "messages", "given" to true))
        notifier.deliver(
            OutboundNotification(user, household, null, "reminder.maturity", "FD matures", "Due soon", "dpdp:reminder2:$user"),
        )
        assertThat(outside("reminder.maturity")).isPositive()
    }

    // --- asked when it helps ------------------------------------------------------

    @Test
    fun `someone never asked is asked, with every channel offered and none chosen for them`() {
        val ask = get("/api/v1/me/privacy/messages-ask", owner).json()
        assertThat(ask.path("ask").asBoolean()).isTrue()
        assertThat(ask.path("channels").map { it.asText() }).containsExactly("email", "sms", "push")
        assertThat(ask.path("notNowUntil").let { it.isNull || it.isMissingNode }).isTrue()
        assertThat(ask.path("noticeVersion").asText()).isNotEmpty()
    }

    @Test
    fun `a yes asked in context names its channels and where it was asked, and is not asked again`() {
        val given = post(
            "/api/v1/me/privacy/consents", owner,
            mapOf("purpose" to "messages", "given" to true, "channels" to listOf("sms", "email"), "askedIn" to "in_context"),
        )
        assertThat(given.status()).isEqualTo(HttpStatus.OK)
        assertThat(given.json().path("consents")[1].path("channels").map { it.asText() })
            .describedAs("in the order they are shown").containsExactly("email", "sms")
        assertThat(
            db.queryForObject(
                "select asked_in || ':' || array_to_string(channels, ',') || ':' || notice_version from consent_events where user_id = ?::uuid",
                String::class.java, ownerUserId,
            ),
        ).startsWith("in_context:email,sms:")
        assertThat(get("/api/v1/me/privacy/messages-ask", owner).json().path("ask").asBoolean()).isFalse()
        assertThat(get("/api/v1/me/privacy/history", owner).json()[0].path("channels").map { it.asText() })
            .containsExactly("email", "sms")
        assertThat(
            db.queryForObject(
                "select diff::text from activity_log where actor_user_id = ?::uuid and action = 'privacy.consent_give'",
                String::class.java, ownerUserId,
            ),
        ).contains("in_context", "sms")

        // The same yes again is not a second decision; a yes for other channels is.
        post("/api/v1/me/privacy/consents", owner, mapOf("purpose" to "messages", "given" to true, "channels" to listOf("email", "sms")))
        post("/api/v1/me/privacy/consents", owner, mapOf("purpose" to "messages", "given" to true, "channels" to listOf("email")))
        assertThat(get("/api/v1/me/privacy/history", owner).json().map { it.path("action").asText() })
            .containsExactly("given", "given")
    }

    @Test
    fun `a yes must choose somewhere this server can send, and a withdrawal is never asked about again`() {
        val none = post("/api/v1/me/privacy/consents", owner, mapOf("purpose" to "messages", "given" to true, "channels" to emptyList<String>()))
        assertThat(none.status()).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(none.errorCode()).isEqualTo("channels_required")
        val carrierPigeon = post(
            "/api/v1/me/privacy/consents", owner,
            mapOf("purpose" to "messages", "given" to true, "channels" to listOf("email", "pigeon")),
        )
        assertThat(carrierPigeon.errorCode()).isEqualTo("channel_not_offered")
        val where = post(
            "/api/v1/me/privacy/consents", owner,
            mapOf("purpose" to "messages", "given" to true, "channels" to listOf("email"), "askedIn" to "a_banner"),
        )
        assertThat(where.errorCode()).isEqualTo("asked_in_invalid")
        assertThat(db.queryForObject("select count(*) from consent_events where user_id = ?::uuid", Int::class.java, ownerUserId))
            .describedAs("nothing refused was recorded").isZero()

        post("/api/v1/me/privacy/consents", owner, mapOf("purpose" to "messages", "given" to false))
        assertThat(get("/api/v1/me/privacy/messages-ask", owner).json().path("ask").asBoolean())
            .describedAs("no is an answer").isFalse()
    }

    @Test
    fun `not now records no consent and keeps the question away for ninety days, for that person only`() {
        val answered = post("/api/v1/me/privacy/messages-ask/not-now", owner)
        assertThat(answered.status()).isEqualTo(HttpStatus.OK)
        assertThat(answered.json().path("ask").asBoolean()).isFalse()
        assertThat(answered.json().path("notNowUntil").asText()).isNotEmpty()
        assertThat(get("/api/v1/me/privacy", owner).json().path("consents")[1].path("given").let { it.isNull || it.isMissingNode })
            .describedAs("not now is not a decision").isTrue()
        assertThat(get("/api/v1/me/privacy/history", owner).json().size()).isZero()
        assertThat(get("/api/v1/me/privacy/messages-ask", spouse).json().path("ask").asBoolean())
            .describedAs("someone else's not now is not yours").isTrue()

        db.update("update messages_consent_asks set not_now_at = now() - interval '89 days' where user_id = ?::uuid", ownerUserId)
        assertThat(get("/api/v1/me/privacy/messages-ask", owner).json().path("ask").asBoolean()).isFalse()
        db.update("update messages_consent_asks set not_now_at = now() - interval '91 days' where user_id = ?::uuid", ownerUserId)
        assertThat(get("/api/v1/me/privacy/messages-ask", owner).json().path("ask").asBoolean()).isTrue()

        // Twice is one row, moved on.
        post("/api/v1/me/privacy/messages-ask/not-now", owner)
        assertThat(db.queryForObject("select count(*) from messages_consent_asks where user_id = ?::uuid", Int::class.java, ownerUserId))
            .isEqualTo(1)
        assertThat(get("/api/v1/me/privacy/messages-ask").status()).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    // --- requests --------------------------------------------------------------

    @Test
    fun `a correction request carries the date we reply by and who to complain to`() {
        val created = post(
            "/api/v1/me/privacy/requests", owner,
            mapOf("kind" to "correction", "details" to "My name is spelt Ishwaria on the change log."),
        )
        assertThat(created.status()).isEqualTo(HttpStatus.CREATED)
        val body = created.json()
        val todayInIndia = LocalDate.now(ZoneId.of("Asia/Kolkata"))
        val days = body.path("grievance").path("respondWithinDays").asLong()
        assertThat(LocalDate.parse(body.path("respondBy").asText())).isEqualTo(todayInIndia.plusDays(days))
        assertThat(body.path("status").asText()).isEqualTo("open")
        assertThat(body.path("grievance").has("configured")).isTrue()

        val audited = db.queryForObject(
            "select diff::text from activity_log where action = 'privacy.request_create' and entity_id = ?::uuid",
            String::class.java, body.path("id").asText(),
        )
        assertThat(audited).describedAs("the kind is audited, never the words").doesNotContain("Ishwaria")
    }

    @Test
    fun `someone else's request is not found, and cannot be withdrawn`() {
        val id = post(
            "/api/v1/me/privacy/requests", owner, mapOf("kind" to "grievance", "details" to "No reply to my email."),
        ).json().path("id").asText()

        assertThat(get("/api/v1/me/privacy/requests", spouse).json().size()).isZero()
        assertThat(post("/api/v1/me/privacy/requests/$id/withdraw", spouse).status()).isEqualTo(HttpStatus.NOT_FOUND)

        val withdrawn = post("/api/v1/me/privacy/requests/$id/withdraw", owner)
        assertThat(withdrawn.json().path("status").asText()).isEqualTo("withdrawn")
        assertThat(post("/api/v1/me/privacy/requests/$id/withdraw", owner).errorCode()).isEqualTo("request_not_open")
    }

    @Test
    fun `an empty or unknown request is refused in words`() {
        assertThat(post("/api/v1/me/privacy/requests", owner, mapOf("kind" to "correction", "details" to "   "))
            .errorCode()).isEqualTo("details_required")
        assertThat(post("/api/v1/me/privacy/requests", owner, mapOf("kind" to "erasure", "details" to "all of it"))
            .errorCode()).isEqualTo("kind_invalid")
    }

    // --- nominees --------------------------------------------------------------

    @Test
    fun `naming a nominee needs a fresh confirmation, and only you see who`() {
        val body = mapOf("fullName" to "Lakshmi Koduri", "relationship" to "sister", "contact" to "98765 43210")

        val refused = post("/api/v1/me/privacy/nominees", owner, body)
        assertThat(refused.status()).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(refused.errorCode()).isEqualTo("step_up_required")

        stepUp(owner)
        val named = post("/api/v1/me/privacy/nominees", owner, body)
        assertThat(named.status()).isEqualTo(HttpStatus.CREATED)
        assertThat(named.json().path("contact").asText()).isEqualTo("+919876543210")
        val id = named.json().path("id").asText()

        assertThat(get("/api/v1/me/privacy/nominees", spouse).json().size()).isZero()
        assertThat(delete("/api/v1/me/privacy/nominees/$id", spouse).status()).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(get("/api/v1/me/privacy/nominees", owner).json().size()).isEqualTo(1)

        val audited = db.queryForObject(
            "select count(*) from activity_log where action = 'privacy.nominee_add' and coalesce(diff::text, '') like '%Lakshmi%'",
            Int::class.java,
        )
        assertThat(audited).describedAs("that someone was named is audited; who is not").isZero()

        assertThat(delete("/api/v1/me/privacy/nominees/$id", owner).status()).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(get("/api/v1/me/privacy/nominees", owner).json().size()).isZero()
    }

    @Test
    fun `a nominee has to be reachable`() {
        stepUp(owner)
        val bad = post(
            "/api/v1/me/privacy/nominees", owner,
            mapOf("fullName" to "Lakshmi", "contact" to "lakshmi@"),
        )
        assertThat(bad.status()).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(bad.errorCode()).isEqualTo("contact_invalid")
    }

    // --- see -------------------------------------------------------------------

    @Test
    fun `the access summary counts what is about you, names who can see it, and never an amount`() {
        capture(owner, householdId, "gold_physical", "Wedding gold", BigDecimal("500000"))
        capture(spouse, householdId, "gold_physical", "Ravi's secret gold", BigDecimal("900000"))

        val summary = get("/api/v1/me/privacy/summary", owner)
        assertThat(summary.status()).isEqualTo(HttpStatus.OK)
        val body = summary.json()

        val held = body.path("held").associate { it.path("category").asText() to it.path("count").asInt() }
        assertThat(held["holdings"]).describedAs("only her own holding, not Ravi's private one").isEqualTo(1)
        assertThat(held).containsKeys("loans", "accounts", "documents", "sealed_values", "change_log", "sessions")
        assertThat(body.path("identity").map { it.asText() }).contains("phone")
        assertThat(body.path("sharedWith").path("householdMembers").map { it.path("displayName").asText() })
            .containsExactly("Ravi")
        assertThat(body.path("purposes").map { it.path("purpose").asText() }).contains("records", "messages")
        assertThat(body.has("grievance")).isTrue()
        assertThat(summary.body).doesNotContain("500000").doesNotContain("Wedding gold").doesNotContain("secret")

        val audited = db.queryForObject(
            "select count(*) from activity_log where action = 'privacy.access_summary' and actor_user_id = ?::uuid",
            Int::class.java, ownerUserId,
        )
        assertThat(audited).isEqualTo(1)
    }

    @Test
    fun `none of it answers without a sign-in`() {
        assertThat(get("/api/v1/me/privacy").status()).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(get("/api/v1/me/privacy/summary").status()).isEqualTo(HttpStatus.UNAUTHORIZED)
    }
}
