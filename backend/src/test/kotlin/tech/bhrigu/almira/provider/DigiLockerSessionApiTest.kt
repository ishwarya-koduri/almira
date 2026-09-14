package tech.bhrigu.almira.provider

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.context.TestPropertySource
import tech.bhrigu.almira.support.ApiTestBase

/**
 * Known-issues 10: a DigiLocker connection is completed only with the `state`
 * this person was given, and the session it buys is kept the way V24 meant —
 * encrypted with the household's key, with its real expiry, and never in
 * `external_ref`.
 *
 * The same properties as ProviderApiTest, so the same cached context.
 */
@TestPropertySource(properties = ["almira.providers.aa.mode=sandbox", "almira.providers.digilocker.mode=sandbox"])
@DisplayName("DigiLocker: the OAuth state is checked and the session is sealed at rest")
class DigiLockerSessionApiTest : ApiTestBase() {

    private lateinit var owner: String
    private lateinit var admin: String
    private lateinit var householdId: String

    @BeforeEach
    fun setUp() {
        owner = signIn()
        admin = signIn()
        householdId = createHousehold(owner, "Koduri", "private", "Ishwarya").path("id").asText()
        val adminMemberId = addMember(owner, householdId, "Ravi").path("id").asText()
        joinHousehold(owner, householdId, adminMemberId, admin, role = "admin")
    }

    private fun connect(path: String) = "/api/v1/households/$householdId/connect/$path"

    private fun start(token: String = owner): String =
        post(connect("digilocker/start"), token).json().path("state").asText()

    private fun row(): Map<String, Any?> = db.queryForMap(
        """
        select status, external_ref, access_token_enc, expires_at, scope, detail::text as detail
        from provider_connections where household_id = ?::uuid and provider = 'digilocker'
        """.trimIndent(),
        householdId,
    )

    private fun assertRefusedAndNothingRedeemed(response: org.springframework.http.ResponseEntity<String>) {
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.errorCode()).isEqualTo("connect_state_mismatch")
        val stored = row()
        assertThat(stored["status"]).describedAs("a refused completion connects nothing").isEqualTo("pending")
        assertThat(stored["access_token_enc"]).isNull()
    }

    @Test
    fun `a completion without the state is refused before the code is spent`() {
        start()
        assertRefusedAndNothingRedeemed(post(connect("digilocker/complete"), owner, mapOf("code" to "theirs")))
    }

    @Test
    fun `a completion with somebody else's state is refused`() {
        start()
        assertRefusedAndNothingRedeemed(
            post(connect("digilocker/complete"), owner, mapOf("code" to "theirs", "state" to "not-the-one-we-gave")),
        )
    }

    @Test
    fun `a state is good only for the person who started, even inside the household`() {
        val state = start(owner)
        assertRefusedAndNothingRedeemed(
            post(connect("digilocker/complete"), admin, mapOf("code" to "c", "state" to state)),
        )
    }

    @Test
    fun `a stale state is refused`() {
        val state = start()
        db.update(
            """
            update provider_connections
            set detail = jsonb_set(detail, '{oauth_state_expires_at}', to_jsonb('2020-01-01T00:00:00Z'::text))
            where household_id = ?::uuid and provider = 'digilocker'
            """.trimIndent(),
            householdId,
        )
        assertRefusedAndNothingRedeemed(
            post(connect("digilocker/complete"), owner, mapOf("code" to "c", "state" to state)),
        )
    }

    @Test
    fun `the state is kept only as a hash`() {
        val state = start()
        assertThat(row()["detail"] as String).doesNotContain(state).contains("oauth_state_sha256")
        assertThat(row()["external_ref"]).isNull()
    }

    @Test
    fun `the session token is stored encrypted, with its real expiry, and the state is spent`() {
        val state = start()
        val completed = post(connect("digilocker/complete"), owner, mapOf("code" to "c", "state" to state))
        assertThat(completed.status()).isEqualTo(HttpStatus.OK)

        val stored = row()
        assertThat(stored["status"]).isEqualTo("active")
        assertThat(stored["external_ref"]).describedAs("V24: never a credential").isNull()
        val blob = stored["access_token_enc"] as ByteArray
        assertThat(String(blob, Charsets.ISO_8859_1))
            .describedAs("the sandbox token starts sandbox-; the stored bytes must not")
            .doesNotContain("sandbox-")
        val expires = (stored["expires_at"] as java.sql.Timestamp).toInstant()
        assertThat(expires).isAfter(java.time.Instant.now().plusSeconds(3000))
        assertThat(stored["scope"]).isEqualTo("files.issueddocs")
        assertThat(stored["detail"] as String).doesNotContain("oauth_state")

        // The stored session is the one used afterwards.
        assertThat(get(connect("digilocker/documents"), owner).status()).isEqualTo(HttpStatus.OK)

        // And the same state cannot complete a second time.
        val replay = post(connect("digilocker/complete"), owner, mapOf("code" to "c2", "state" to state))
        assertThat(replay.errorCode()).isEqualTo("connect_state_mismatch")
    }

    @Test
    fun `an expired session asks for a new connection instead of being sent`() {
        val state = start()
        post(connect("digilocker/complete"), owner, mapOf("code" to "c", "state" to state))
        db.update(
            "update provider_connections set expires_at = now() - interval '1 minute' " +
                "where household_id = ?::uuid and provider = 'digilocker'",
            householdId,
        )
        val listed = get(connect("digilocker/documents"), owner)
        assertThat(listed.status()).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(listed.errorCode()).isEqualTo("connection_expired")
    }

    @Test
    fun `a token copied into another household's row does not decrypt there`() {
        val state = start()
        post(connect("digilocker/complete"), owner, mapOf("code" to "c", "state" to state))

        val otherOwner = signIn()
        val other = createHousehold(otherOwner, "Strangers", "household", "Outsider").path("id").asText()
        val otherState = post("/api/v1/households/$other/connect/digilocker/start", otherOwner)
            .json().path("state").asText()
        assertThat(otherState).isNotBlank()
        db.update(
            """
            update provider_connections set status = 'active', expires_at = now() + interval '1 hour',
              access_token_enc = (select access_token_enc from provider_connections
                                  where household_id = ?::uuid and provider = 'digilocker')
            where household_id = ?::uuid and provider = 'digilocker'
            """.trimIndent(),
            householdId, other,
        )
        val listed = get("/api/v1/households/$other/connect/digilocker/documents", otherOwner)
        assertThat(listed.status().is2xxSuccessful).describedAs("a moved ciphertext must fail authentication").isFalse()
    }
}
