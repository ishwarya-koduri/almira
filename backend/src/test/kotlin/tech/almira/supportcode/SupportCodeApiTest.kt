package tech.almira.supportcode

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tech.almira.support.ApiTestBase

/**
 * "Share a support code" (docs/28 §4): diagnostics only, shown before it exists,
 * revocable, dead after 24 hours, and read by support only through the owner's
 * ops.lookup_support_code — which audits every attempt and is seen by the person.
 */
@DisplayName("Support codes: help without seeing the family's record")
class SupportCodeApiTest : ApiTestBase() {

    private lateinit var asha: String
    private val phone = uniquePhone()

    private val body = mapOf(
        "appVersion" to "web-2026.09.14",
        "platform" to "web",
        "screen" to "investments",
        "language" to "te",
        "errorCodes" to listOf("plan_read_only", "validation_failed"),
        "flags" to mapOf("dataSaver" to true, "largerText" to false),
    )

    @BeforeEach
    fun setUp() {
        asha = signIn(phone)
        createHousehold(asha, "Koduri family", "private", "Asha Koduri")
    }

    private fun codes() = "/api/v1/me/support-codes"

    private fun rows(): Long = db.queryForObject("select count(*) from support_codes", Long::class.java)!!

    /** As support would: the owner connection, a name and a reason. */
    private fun lookUp(code: String, reason: String = "customer says import fails") =
        db.queryForList("select * from ops.lookup_support_code(?, 'support-1', ?)", code, reason)

    @Test
    fun `the preview is exactly what would be shared, and stores nothing`() {
        val before = rows()
        val preview = post("${codes()}/preview", asha, body)
        assertThat(preview.status()).isEqualTo(HttpStatus.OK)
        val diagnostics = preview.json().path("diagnostics")
        assertThat(diagnostics.path("screen").asText()).isEqualTo("investments")
        assertThat(diagnostics.path("errorCodes").map { it.asText() }).containsExactly("plan_read_only", "validation_failed")
        assertThat(diagnostics.path("flags").path("dataSaver").asBoolean()).isTrue()
        assertThat(diagnostics.path("households").asInt()).isEqualTo(1)
        assertThat(diagnostics.path("anyHouseholdReadOnly").asBoolean()).isFalse()
        assertThat(preview.json().path("validForHours").asInt()).isEqualTo(24)
        assertThat(preview.json().path("never").size()).isGreaterThan(3)
        assertThat(diagnostics.toString())
            .describedAs("nobody's name, number or household")
            .doesNotContain("Koduri").doesNotContain("Asha").doesNotContain(phone.takeLast(6))
        assertThat(rows()).isEqualTo(before)
    }

    @Test
    fun `a code is shown once, stored only as a hash, and lasts 24 hours`() {
        val created = post(codes(), asha, body)
        assertThat(created.status()).isEqualTo(HttpStatus.CREATED)
        val code = created.json().path("code").asText()
        assertThat(code).matches("^[A-HJKMNP-TV-Z2-9]{5}-[A-HJKMNP-TV-Z2-9]{5}$")
        val id = created.json().path("supportCode").path("id").asText()

        val stored = db.queryForMap(
            "select code_hash, diagnostics::text as diagnostics, extract(epoch from expires_at - created_at)::int as life from support_codes where id = ?::uuid",
            id,
        )
        assertThat(stored["code_hash"]).isEqualTo(SupportCodeService.hash(code))
        assertThat(stored.values.joinToString()).doesNotContain(code).doesNotContain(code.replace("-", ""))
        assertThat(stored["life"]).isEqualTo(86_400)
        assertThat(stored["diagnostics"] as String).doesNotContain("Koduri")

        val audit = db.queryForList(
            "select action from activity_log where entity_id = ?::uuid", String::class.java, id,
        )
        assertThat(audit).contains("support_code.create")
    }

    @Test
    fun `anything that is not a diagnostic shape is refused, with the field named`() {
        fun refused(overrides: Map<String, Any?>, field: String) {
            val response = post(codes(), asha, body + overrides)
            assertThat(response.status()).describedAs(field).isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(response.json().path("error").path("details").path("fields").has(field)).describedAs(field).isTrue()
        }
        val before = rows()
        refused(mapOf("screen" to "investments/3f1c-SBI FD"), "screen")
        refused(mapOf("errorCodes" to listOf("Ravi's FD of 5,00,000 failed")), "errorCodes")
        refused(mapOf("flags" to mapOf("Asha Koduri" to true)), "flags")
        refused(mapOf("appVersion" to "1.0 for Asha Koduri"), "appVersion")
        refused(mapOf("platform" to "windows"), "platform")
        refused(mapOf("errorCodes" to (1..11).map { "code_$it" }), "errorCodes")
        refused(mapOf("errorCodes" to listOf("plan_read_only", null)), "errorCodes")
        assertThat(rows()).isEqualTo(before)

        // A field outside the list is not stored, whatever it holds.
        val created = post(codes(), asha, body + mapOf("title" to "SBI FD 5,00,000", "amount" to 500000))
        assertThat(created.status()).isEqualTo(HttpStatus.CREATED)
        assertThat(created.json().path("supportCode").path("diagnostics").toString())
            .doesNotContain("SBI").doesNotContain("500000")
    }

    @Test
    fun `support reads the diagnostics and nothing else, and the person sees the look`() {
        val code = post(codes(), asha, body).json().path("code").asText()

        // Lower case and without the dash, the way it gets read over a phone.
        val found = lookUp(code.lowercase().replace("-", ""))
        assertThat(found).hasSize(1)
        assertThat(found.single().keys).containsExactlyInAnyOrder("diagnostics", "created_at", "expires_at")
        assertThat(found.single()["diagnostics"].toString()).contains("plan_read_only").doesNotContain("Koduri")

        val listed = get(codes(), asha).json().single()
        assertThat(listed.path("lookups").asInt()).isEqualTo(1)
        assertThat(listed.path("lastLookedUpAt").isMissingNode || listed.path("lastLookedUpAt").isNull).isFalse()

        val audit = db.queryForList(
            "select diff::text from activity_log where action = 'support_code.lookup' and entity_id = ?::uuid",
            String::class.java, listed.path("id").asText(),
        )
        assertThat(audit.single()).contains("support-1").contains("import fails").contains("\"found\": true")
    }

    @Test
    fun `a guessed code finds nothing, and the guess is audited`() {
        val before = db.queryForObject(
            "select count(*) from activity_log where action = 'support_code.lookup' and diff->>'found' = 'false'",
            Long::class.java,
        )!!
        assertThat(lookUp("AAAAA-BBBBB")).isEmpty()
        val after = db.queryForObject(
            "select count(*) from activity_log where action = 'support_code.lookup' and diff->>'found' = 'false'",
            Long::class.java,
        )!!
        assertThat(after).isEqualTo(before + 1)
    }

    @Test
    fun `support must say who is looking and why`() {
        val code = post(codes(), asha, body).json().path("code").asText()
        assertThatThrownBy { lookUp(code, reason = "") }.hasMessageContaining("say why")
    }

    @Test
    fun `a code taken back stops working at once, and stays listed as taken back`() {
        val created = post(codes(), asha, body).json()
        val id = created.path("supportCode").path("id").asText()
        val revoked = post("${codes()}/$id/revoke", asha)
        assertThat(revoked.status()).isEqualTo(HttpStatus.OK)
        assertThat(revoked.json().path("active").asBoolean()).isFalse()
        assertThat(lookUp(created.path("code").asText())).isEmpty()
        assertThat(get(codes(), asha).json().single().path("revokedAt").asText()).isNotBlank()
    }

    @Test
    fun `an expired code does not open`() {
        val created = post(codes(), asha, body).json()
        val id = created.path("supportCode").path("id").asText()
        // Moving a code's clock is exactly what the guard trigger forbids, so the
        // test steps around it for one statement, on the owner connection.
        db.execute(
            org.springframework.jdbc.core.ConnectionCallback { connection ->
                val auto = connection.autoCommit
                connection.autoCommit = false
                try {
                    connection.createStatement().use { it.execute("set local session_replication_role = replica") }
                    connection.prepareStatement(
                        "update support_codes set created_at = now() - interval '25 hours', " +
                            "expires_at = now() - interval '1 hour' where id = ?::uuid",
                    ).use { it.setString(1, id); it.executeUpdate() }
                    connection.commit()
                } finally {
                    connection.autoCommit = auto
                }
            },
        )
        assertThat(lookUp(created.path("code").asText())).isEmpty()
        assertThat(get(codes(), asha).json().single().path("active").asBoolean()).isFalse()
    }

    @Test
    fun `another person's code does not exist for you`() {
        val id = post(codes(), asha, body).json().path("supportCode").path("id").asText()
        val ravi = signIn()
        assertThat(get(codes(), ravi).json().size()).isZero()
        assertThat(post("${codes()}/$id/revoke", ravi).status()).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(get(codes(), asha).json().single().path("active").asBoolean()).isTrue()
    }

    @Test
    fun `five working codes at a time`() {
        repeat(5) { assertThat(post(codes(), asha, body).status()).isEqualTo(HttpStatus.CREATED) }
        val sixth = post(codes(), asha, body)
        assertThat(sixth.status()).isEqualTo(HttpStatus.CONFLICT)
        assertThat(sixth.errorCode()).isEqualTo("too_many_support_codes")
    }
}
