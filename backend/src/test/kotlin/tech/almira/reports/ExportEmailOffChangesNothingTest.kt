package tech.almira.reports

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tech.almira.auth.SignInApiTestBase
import tech.almira.security.JwtService
import java.math.BigDecimal
import java.util.UUID

/**
 * With emailing switched off, an export is exactly what it was.
 *
 * This runs in the ordinary suite context, where `almira.exports.email.enabled`
 * is unset — which is the state every environment is in, so this is the
 * behaviour that actually ships. Everything the last three stages added is
 * either absent from here or provably inert.
 *
 * "Absent" is meant literally. The controllers are `@ConditionalOnProperty`, so
 * with the flag off they are not beans, their paths are not routes, and the
 * answer is the ordinary 404 of something that was never written — not a 403
 * that tells a caller the feature exists and is switched off.
 */
@DisplayName("Emailing exports off: an export is what it always was")
class ExportEmailOffChangesNothingTest : SignInApiTestBase() {

    @Autowired private lateinit var jwt: JwtService

    private lateinit var owner: Account
    private lateinit var householdId: String

    @BeforeEach
    fun setUp() {
        owner = signUp()
        householdId = createHousehold(owner.token, "Koduri", "private", "Ishwarya").path("id").asText()
        capture(owner.token, householdId, "gold_physical", "Bangles", BigDecimal("180000"), visibility = "household")
    }

    @Test
    fun `there is no way to email an export`() {
        val send = post(
            "/api/v1/households/$householdId/reports/export/email", owner.token,
            mapOf("address" to "someone@example.test", "format" to "csv"),
        )
        assertThat(send.statusCode.value()).describedAs(send.body).isEqualTo(404)
        assertThat(send.errorCode())
            .describedAs("never written, rather than written and refused")
            .isEqualTo("not_found")
    }

    @Test
    fun `there is no way to add an address to send to`() {
        val address = mapOf("address" to "someone@example.test")
        assertThat(get("/api/v1/me/email-addresses", owner.token).statusCode.value()).isEqualTo(404)
        assertThat(post("/api/v1/me/email-addresses", owner.token, address).statusCode.value()).isEqualTo(404)
        assertThat(
            post(
                "/api/v1/me/email-addresses/confirm", owner.token,
                address + mapOf("code" to "123456"),
            ).statusCode.value(),
        ).isEqualTo(404)
        assertThat(
            delete("/api/v1/me/email-addresses/${UUID.randomUUID()}", owner.token).statusCode.value(),
        ).isEqualTo(404)
    }

    @Test
    fun `nor is any of it in the API the clients are built from`() {
        val served = get("/v3/api-docs").json().path("paths").fieldNames().asSequence().toList()

        assertThat(served).describedAs("the shape of the API a client generates from")
            .noneMatch { it.contains("email-addresses") }
            .noneMatch { it.endsWith("/reports/export/email") }

        // And what a client does see is unchanged: the download, and the one
        // guest route that serves a link already sent.
        assertThat(served).contains("/api/v1/households/{householdId}/reports/export")
        assertThat(served).contains("/api/v1/share/{token}/export")
    }

    @Test
    fun `the download is exactly what it was, in every format`() {
        val csv = get("/api/v1/households/$householdId/reports/export?format=csv", owner.token)
        assertThat(csv.statusCode.value()).describedAs(csv.body).isEqualTo(200)
        assertThat(csv.headers.getFirst("Content-Disposition")).contains("almira-holdings").contains(".csv")
        assertThat(csv.body).contains("Bangles").contains("180000")

        val xlsx = get("/api/v1/households/$householdId/reports/export?format=xlsx", owner.token)
        assertThat(xlsx.statusCode.value()).isEqualTo(200)
        assertThat(xlsx.headers.contentType.toString()).contains("spreadsheetml")

        val pdf = get("/api/v1/households/$householdId/reports/export?format=pdf", owner.token)
        assertThat(pdf.statusCode.value()).isEqualTo(200)
        assertThat(pdf.headers.contentType.toString()).contains("pdf")
    }

    @Test
    fun `the guest export route is served, and has nothing to serve`() {
        val nobodys = get("/api/v1/share/${UUID.randomUUID()}/export")
        assertThat(nobodys.statusCode.value()).isEqualTo(404)
        assertThat(nobodys.json().path("error").path("message").asText())
            .describedAs("the same sentence every dead link gets")
            .contains("expired or been withdrawn")
    }

    @Test
    fun `a link sent while it was on keeps working after it is switched off`() {
        // The decision this proves: the flag stops new links being made, and
        // must not reach into somebody's inbox and break one already sent. The
        // row is written directly because with the flag off there is no
        // endpoint to mint one — which is the state under test.
        val token = "off-but-alive-${UUID.randomUUID()}"
        val shareId = UUID.randomUUID()
        db.update(
            """
            insert into guest_shares (id, household_id, label, scope, scope_detail, token_hash,
                                      expires_at, max_views, created_by)
            values (?::uuid, ?::uuid, 'Your export', 'export', 'csv', ?, now() + interval '7 days', 5, ?::uuid)
            """.trimIndent(),
            shareId, householdId, jwt.hash(token), owner.userId,
        )
        db.update(
            """
            insert into guest_share_items (share_id, record_type, record_id)
            select ?::uuid, 'investment', id from investments where household_id = ?::uuid
            """.trimIndent(),
            shareId, householdId,
        )

        val file = get("/api/v1/share/$token/export")
        assertThat(file.statusCode.value()).describedAs(file.body).isEqualTo(200)
        assertThat(file.body).contains("Bangles")
    }
}
