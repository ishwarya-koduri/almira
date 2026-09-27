package tech.almira.privacy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import tech.almira.provider.NotificationOutbox
import tech.almira.provider.SandboxEmailSender
import tech.almira.stilltrue.StillTrue
import tech.almira.stilltrue.StillTrueSweep
import tech.almira.support.ApiTestBase
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * "Not now" is about what was asked (V141; docs/23 "Asked when it helps"), and the
 * Still true? digest is asked about on Home before it would first go outside the app.
 *
 * The owner's rule: never ask again about the SAME holding sooner than 90 days after
 * "Not now" to it; a DIFFERENT holding's first due date may ask again, even inside
 * those 90 days. The digest is a context of its own.
 */
@DisplayName("Consent to messages: asked about the thing in front of you")
class MessagesAskContextTest : ApiTestBase() {

    @Autowired private lateinit var sweep: StillTrueSweep
    @Autowired private lateinit var outbox: NotificationOutbox
    @Autowired private lateinit var email: SandboxEmailSender

    private lateinit var owner: String
    private lateinit var spouse: String
    private lateinit var householdId: String
    private lateinit var ownerUserId: String

    @BeforeEach
    fun setUp() {
        outbox.drain()
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

    @AfterEach
    fun tearDown() { outbox.drain() }

    /** A private fixed deposit with a maturity date: saving it makes a reminder. */
    private fun maturingFd(title: String, token: String = owner): String {
        val response = post(
            "/api/v1/households/$householdId/investments", token,
            mapOf(
                "typeId" to typeId(token, householdId, "fd"), "title" to title,
                "investedAmount" to 200_000, "visibility" to "private",
                "maturityDate" to LocalDate.now().plusMonths(6).toString(),
                "attributes" to mapOf("interest_rate" to 7.1),
            ),
        )
        check(response.statusCode.is2xxSuccessful) { "capture failed: ${response.body}" }
        return response.json().path("id").asText()
    }

    private fun askAbout(type: String, id: String? = null, token: String = owner) =
        get("/api/v1/me/privacy/messages-ask?contextType=$type" + (id?.let { "&contextId=$it" } ?: ""), token)

    private fun notNow(type: String, id: String? = null, token: String = owner) =
        post("/api/v1/me/privacy/messages-ask/not-now", token, buildMap {
            put("contextType", type)
            id?.let { put("contextId", it) }
        })

    private fun contextRows(userId: String = ownerUserId): Int = db.queryForObject(
        "select count(*) from messages_consent_ask_contexts where user_id = ?::uuid", Int::class.java, userId,
    )!!

    // --- a holding -----------------------------------------------------------------

    @Test
    fun `not now to one holding keeps the question from it for ninety days, and a different holding still asks`() {
        val first = maturingFd("SBI FD 2027")
        assertThat(askAbout("investment", first).json().path("ask").asBoolean()).describedAs("its first due date asks").isTrue()

        val answered = notNow("investment", first)
        assertThat(answered.status()).isEqualTo(HttpStatus.OK)
        assertThat(answered.json().path("ask").asBoolean()).isFalse()
        assertThat(answered.json().path("notNowUntil").asText()).isNotEmpty()
        assertThat(askAbout("investment", first).json().path("ask").asBoolean())
            .describedAs("the same holding, straight after").isFalse()

        // Inside the ninety days, a different holding's first due date asks again.
        val second = maturingFd("HDFC FD 2028")
        assertThat(askAbout("investment", second).json().path("ask").asBoolean())
            .describedAs("a different holding is a different question").isTrue()

        db.update(
            "update messages_consent_ask_contexts set not_now_at = now() - interval '89 days' where user_id = ?::uuid",
            ownerUserId,
        )
        assertThat(askAbout("investment", first).json().path("ask").asBoolean()).describedAs("89 days on").isFalse()
        db.update(
            "update messages_consent_ask_contexts set not_now_at = now() - interval '91 days' where user_id = ?::uuid",
            ownerUserId,
        )
        assertThat(askAbout("investment", first).json().path("ask").asBoolean()).describedAs("91 days on").isTrue()

        // Twice to the same holding is one row, moved on; nothing is a consent record.
        notNow("investment", first)
        notNow("investment", first)
        assertThat(contextRows()).isEqualTo(1)
        assertThat(get("/api/v1/me/privacy/history", owner).json().size()).isZero()
        assertThat(
            db.queryForObject(
                "select count(*) from activity_log where actor_user_id = ?::uuid and action = 'privacy.messages_not_now' and entity_type = 'investment'",
                Int::class.java, ownerUserId,
            ),
        ).describedAs("each not now is audited, with what it was said beside").isEqualTo(3)
    }

    @Test
    fun `a loan with an EMI day is its own context`() {
        val fd = maturingFd("SBI FD 2027")
        notNow("investment", fd)
        val loan = post(
            "/api/v1/households/$householdId/liabilities", owner,
            mapOf("title" to "Home loan", "kind" to "home", "outstanding" to BigDecimal(3_000_000),
                  "visibility" to "private", "emiAmount" to 30_000, "emiDay" to 5),
        ).json().path("id").asText()
        assertThat(askAbout("liability", loan).json().path("ask").asBoolean()).isTrue()
        notNow("liability", loan)
        assertThat(askAbout("liability", loan).json().path("ask").asBoolean()).isFalse()
        assertThat(contextRows()).isEqualTo(2)
    }

    @Test
    fun `a holding with no date to remind about is not the moment`() {
        val gold = capture(owner, householdId, "fd", "Undated FD", BigDecimal(90_000), attributes = mapOf("interest_rate" to 7.0)).path("id").asText()
        assertThat(askAbout("investment", gold).json().path("ask").asBoolean()).isFalse()
    }

    @Test
    fun `a holding the caller cannot see is a 404, and nothing is written for it`() {
        val mine = maturingFd("Private FD")
        val asked = askAbout("investment", mine, token = spouse)
        assertThat(asked.status()).isEqualTo(HttpStatus.NOT_FOUND)
        val said = notNow("investment", mine, token = spouse)
        assertThat(said.status()).isEqualTo(HttpStatus.NOT_FOUND)
        val spouseUserId = db.queryForObject(
            "select user_id::text from household_memberships where household_id = ?::uuid and user_id <> ?::uuid",
            String::class.java, householdId, ownerUserId,
        )!!
        assertThat(contextRows(spouseUserId)).describedAs("the refusal came before the write").isZero()
        assertThat(
            db.queryForObject("select count(*) from messages_consent_asks where user_id = ?::uuid", Int::class.java, spouseUserId),
        ).isZero()

        assertThat(askAbout("investment", "00000000-0000-0000-0000-000000000001").status()).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(askAbout("a_banner").errorCode()).isEqualTo("context_invalid")
        assertThat(askAbout("investment").errorCode()).isEqualTo("context_invalid")
        assertThat(askAbout(DataRightsService.STILL_TRUE_DIGEST, mine).errorCode()).isEqualTo("context_invalid")
    }

    @Test
    fun `someone else's not now is not yours, and a client that names no context is answered as before`() {
        val fd = maturingFd("SBI FD 2027")
        notNow("investment", fd)
        val spouseFd = maturingFd("Ravi's FD", token = spouse)
        assertThat(askAbout("investment", spouseFd, token = spouse).json().path("ask").asBoolean()).isTrue()

        // A v1 client with no context: any "Not now" in the last 90 days keeps it away (V125).
        assertThat(get("/api/v1/me/privacy/messages-ask", owner).json().path("ask").asBoolean()).isFalse()
        assertThat(get("/api/v1/me/privacy/messages-ask", spouse).json().path("ask").asBoolean()).isTrue()
        assertThat(post("/api/v1/me/privacy/messages-ask/not-now", spouse).status()).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `a yes ends the question in every context`() {
        val fd = maturingFd("SBI FD 2027")
        consentToMessages(owner, listOf("email"))
        assertThat(askAbout("investment", fd).json().path("ask").asBoolean()).isFalse()
    }

    // --- the Still true? digest ----------------------------------------------------

    /** Thirteen months unconfirmed: the digest would ask about it. */
    private fun unconfirmedFd(title: String): String {
        val id = capture(owner, householdId, "fd", title, BigDecimal(250_000), attributes = mapOf("interest_rate" to 7.1))
            .path("id").asText()
        db.update(
            "update investments set created_at = now() - interval '13 months', last_verified_at = null where id = ?::uuid", id,
        )
        return id
    }

    /** It is [localHour] o'clock in the household now, so the sweep runs in its daytime. */
    private fun livesAt(localHour: Int) {
        val utcHour = Instant.now().atOffset(ZoneOffset.UTC).hour
        var offset = localHour - utcHour
        if (offset < -12) offset += 24
        if (offset > 14) offset -= 24
        val zone = if (offset >= 0) "Etc/GMT-$offset" else "Etc/GMT+${-offset}"
        db.update("update households set time_zone = ? where id = ?::uuid", zone, householdId)
    }

    private fun digestsOutside(): List<String> = db.queryForList(
        "select channel from outbound_messages where user_id = ?::uuid and template = ? and channel <> 'in_app' order by channel",
        String::class.java, ownerUserId, StillTrue.DIGEST_TEMPLATE,
    )

    @Test
    fun `before the first digest the digest is asked about, and until a yes it stays in the app`() {
        val digest = DataRightsService.STILL_TRUE_DIGEST
        assertThat(askAbout(digest).json().path("ask").asBoolean()).describedAs("nothing to confirm yet").isFalse()

        unconfirmedFd("HDFC FD 2025")
        assertThat(askAbout(digest).json().path("ask").asBoolean()).describedAs("something is waiting").isTrue()
        assertThat(askAbout(digest, token = spouse).json().path("ask").asBoolean())
            .describedAs("an admin who holds none of it is not asked about a digest they would not get").isFalse()

        // The digest would be queued now: without a yes, only the in-app copy is.
        livesAt(localHour = 12)
        sweep.run()
        assertThat(digestsOutside()).describedAs("no digest outside the app without consent").isEmpty()
        assertThat(
            db.queryForObject(
                "select count(*) from outbound_messages where user_id = ?::uuid and template = ? and channel = 'in_app'",
                Int::class.java, ownerUserId, StillTrue.DIGEST_TEMPLATE,
            ),
        ).isEqualTo(1)

        // "Not now" beside the digest is about the digest and nothing else.
        notNow(digest)
        assertThat(askAbout(digest).json().path("ask").asBoolean()).isFalse()
        val fd = maturingFd("SBI FD 2027")
        assertThat(askAbout("investment", fd).json().path("ask").asBoolean())
            .describedAs("a holding's first due date may still ask").isTrue()
        db.update(
            "update messages_consent_ask_contexts set not_now_at = now() - interval '91 days' where user_id = ?::uuid and context_type = ?",
            ownerUserId, digest,
        )
        assertThat(askAbout(digest).json().path("ask").asBoolean()).isTrue()

        // A yes by email: the next digest goes by email and nothing else.
        consentToMessages(owner, listOf("email"))
        // A week and a month on, so the digest may be sent again (docs/21 §6).
        db.update(
            "update outbound_messages set created_at = now() - interval '8 days' where user_id = ?::uuid and template = ?",
            ownerUserId, StillTrue.DIGEST_TEMPLATE,
        )
        db.update("update record_confirmation_nudges set nudged_at = now() - interval '31 days' where user_id = ?::uuid", ownerUserId)
        sweep.run()
        assertThat(digestsOutside()).containsExactly("email")
        val key = db.queryForObject(
            "select idempotency_key from outbound_messages where user_id = ?::uuid and template = ? and channel = 'email'",
            String::class.java, ownerUserId, StillTrue.DIGEST_TEMPLATE,
        )!!
        outbox.drain()
        assertThat(email.deliveries.times(key)).isEqualTo(1)
    }
}
