package tech.almira.reports

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import tech.almira.auth.SignInApiTestBase
import tech.almira.provider.SandboxEmailSender
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicLong

/**
 * What arrives is a link, and the link is the file.
 *
 * An attachment would sit in that inbox forever and in every relay it passed
 * through, where no expiry, withdrawal or view limit could ever reach it. So
 * the email carries an ordinary guest share — the same token, expiry, view
 * limit, view log and withdrawal a link to a CA has had since V20 — and these
 * tests are mostly about proving it really is the same machinery rather than a
 * second copy that looks like it.
 *
 * `max-opens` and `max-per-hour` are turned down here so the limits can be
 * reached in a test rather than described.
 */
@DisplayName("Emailing an export: a link that expires")
@TestPropertySource(
    properties = [
        "almira.exports.email.enabled=true",
        "almira.exports.email.max-opens=2",
        "almira.exports.email.max-per-hour=3",
    ],
)
// A context of its own, closed after this class so its connection pools do not
// stay open beside every cached context.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ExportEmailLinkApiTest : SignInApiTestBase() {

    @Autowired private lateinit var email: SandboxEmailSender

    private lateinit var owner: Account
    private lateinit var householdId: String
    private lateinit var address: String

    @BeforeEach
    fun setUp() {
        owner = signUp()
        householdId = createHousehold(owner.token, "Koduri", "private", "Ishwarya").path("id").asText()
        address = prove(owner)
        redis.delete("exports:email:${owner.userId}")
        record("Gold bangles", "180000")
    }

    private fun prove(account: Account): String {
        val at = "link.${counter.incrementAndGet()}.${System.nanoTime()}@example.test"
        stepUpByCode(account)
        redis.delete("otp:email:cooldown:verify_email:$at")
        val challenge = post("/api/v1/me/email-addresses", account.token, mapOf("address" to at))
        check(challenge.statusCode.value() == 200) { "no code: ${challenge.body}" }
        val done = post(
            "/api/v1/me/email-addresses/confirm", account.token,
            mapOf(
                "address" to at,
                "code" to challenge.json().path("developmentCode").asText(),
                "requestId" to challenge.json().path("requestId").asText(),
            ),
        )
        check(done.statusCode.value() == 200) { "not proved: ${done.body}" }
        return at
    }

    private fun record(title: String, amount: String) =
        capture(owner.token, householdId, "gold_physical", title, BigDecimal(amount), visibility = "household")

    /**
     * The link as the test client can ask for it. [get] builds its own origin,
     * and the link carries the server's, so the path is the part that travels.
     */
    private fun linkPath(link: String): String = java.net.URI(link).path

    private fun send(format: String = "csv") = post(
        "/api/v1/households/$householdId/reports/export/email", owner.token,
        mapOf("address" to address, "format" to format),
    )

    private fun sent(format: String = "csv") = send(format).also {
        check(it.statusCode.value() == 200) { "send failed: ${it.body}" }
    }.json()

    @Test
    fun `the email carries a link, and the link is the file`() {
        val response = sent()

        assertThat(response.path("records").asInt()).isEqualTo(1)
        assertThat(response.path("opensAllowed").asInt()).isEqualTo(2)
        assertThat(response.path("expiresAt").asText()).isNotBlank()
        assertThat(response.path("sentTo").asText()).doesNotContain(address).contains("@example.test")

        // The email went to the proved address, once, through the one sender.
        val key = "export.link:${response.path("shareId").asText()}"
        assertThat(email.deliveries.times(key)).describedAs("one email per link").isEqualTo(1)
        assertThat(email.deliveries.recipientEnding(key)).isEqualTo(address.takeLast(4))

        // And the link is the export itself, not a page about it.
        val file = get(linkPath(response.path("developmentLink").asText()))
        assertThat(file.statusCode.value()).describedAs(file.body).isEqualTo(200)
        assertThat(file.headers.getFirst("Content-Disposition")).contains("almira-holdings").contains(".csv")
        assertThat(file.headers.getFirst("Cache-Control")).isEqualTo("no-store")
        assertThat(file.body).contains("Gold bangles").contains("180000")
    }

    @Test
    fun `the file is a snapshot - what was there when it was sent, not what is there now`() {
        val link = linkPath(sent().path("developmentLink").asText())
        record("Bought afterwards", "999")

        val file = get(link)
        assertThat(file.body).contains("Gold bangles")
        assertThat(file.body)
            .describedAs("the guest clamp admits only the records the link names")
            .doesNotContain("Bought afterwards")
    }

    @Test
    fun `the link stops working when it has been opened enough times`() {
        val link = linkPath(sent().path("developmentLink").asText())

        assertThat(get(link).statusCode.value()).isEqualTo(200)
        assertThat(get(link).statusCode.value()).isEqualTo(200)

        val third = get(link)
        assertThat(third.statusCode.value()).describedAs(third.body).isEqualTo(404)
        assertThat(third.json().path("error").path("message").asText()).contains("expired or been withdrawn")
    }

    @Test
    fun `the link stops working when it expires`() {
        val response = sent()
        val link = linkPath(response.path("developmentLink").asText())
        assertThat(get(link).statusCode.value()).isEqualTo(200)

        // Moved back into the past whole. Only the expiry would be refused by
        // `share_expires_in_the_future`, which is the constraint doing its job:
        // a link cannot be minted already dead, so the test ages it instead.
        db.update(
            """
            update guest_shares
               set created_at = now() - interval '8 days', expires_at = now() - interval '1 minute'
             where id = ?::uuid
            """,
            response.path("shareId").asText(),
        )
        assertThat(get(link).statusCode.value()).isEqualTo(404)
    }

    @Test
    fun `the link can be withdrawn from the app, and then it is gone`() {
        val response = sent()
        val link = linkPath(response.path("developmentLink").asText())
        val shareId = response.path("shareId").asText()

        // It is an ordinary share: it is in the household's list, and the
        // ordinary withdrawal takes it away.
        val shares = get("/api/v1/households/$householdId/shares", owner.token).json()
        assertThat(shares.map { it.path("id").asText() }).contains(shareId)
        assertThat(shares.first { it.path("id").asText() == shareId }.path("scope").asText()).isEqualTo("export")

        assertThat(delete("/api/v1/households/$householdId/shares/$shareId", owner.token).statusCode.value())
            .isEqualTo(204)
        assertThat(get(link).statusCode.value()).isEqualTo(404)
    }

    @Test
    fun `every open is written down`() {
        val response = sent()
        get(linkPath(response.path("developmentLink").asText()))

        val views = get(
            "/api/v1/households/$householdId/shares/${response.path("shareId").asText()}/views",
            owner.token,
        )
        assertThat(views.statusCode.value()).describedAs(views.body).isEqualTo(200)
        assertThat(views.json()).describedAs("the open is in the log").hasSize(1)
    }

    @Test
    fun `sending is limited by the hour, and says so`() {
        sent()
        sent()
        sent()

        val refused = send()
        assertThat(refused.statusCode.value()).describedAs(refused.body).isEqualTo(429)
        assertThat(refused.errorCode()).isEqualTo("rate_limited")
        assertThat(refused.json().path("error").path("details").path("retryAfterSeconds").asLong())
            .describedAs("when to come back, not merely no").isGreaterThan(0)
    }

    @Test
    fun `a refusal does not spend one of the hour's sends`() {
        val refused = post(
            "/api/v1/households/$householdId/reports/export/email", owner.token,
            mapOf("address" to "never.proved.${System.nanoTime()}@example.test", "format" to "csv"),
        )
        assertThat(refused.errorCode()).isEqualTo("email_not_proved")

        // Three good ones still fit: the refusal above counted for nothing.
        sent(); sent(); sent()
        assertThat(send().statusCode.value()).isEqualTo(429)
    }

    @Test
    fun `the format asked for is the format that arrives`() {
        val link = linkPath(sent("xlsx").path("developmentLink").asText())
        val file = get(link)
        assertThat(file.headers.getFirst("Content-Disposition")).contains(".xlsx")
        assertThat(file.headers.contentType.toString()).contains("spreadsheetml")
    }

    @Test
    fun `a household with nothing in it sends nothing`() {
        val empty = createHousehold(owner.token, "Empty", "private", "Ishwarya").path("id").asText()
        val refused = post(
            "/api/v1/households/$empty/reports/export/email", owner.token,
            mapOf("address" to address, "format" to "csv"),
        )
        assertThat(refused.statusCode.value()).describedAs(refused.body).isEqualTo(400)
        assertThat(refused.errorCode()).isEqualTo("nothing_to_export")
    }

    private companion object {
        val counter = AtomicLong(0)
    }
}
