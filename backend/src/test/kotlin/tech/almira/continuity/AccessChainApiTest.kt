package tech.almira.continuity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.math.BigDecimal
import java.security.SecureRandom
import java.util.Base64

/**
 * The access chain's ticks. The names are sealed values the server cannot open,
 * so these tests store well-formed envelopes of random bytes: what is under test
 * is who may tick which position, and that a tick follows its name.
 */
@DisplayName("The access chain: first, second, third — sealed names, dated ticks")
class AccessChainApiTest : ContinuitySignalsTestBase() {

    private val random = SecureRandom()
    private lateinit var lockerId: String

    @BeforeEach
    fun locker() {
        lockerId = capture(owner, householdId, "gold_physical", "SBI locker", BigDecimal("1"), visibility = "household")
            .path("id").asText()
        enableKey(owner)
        enableKey(trusted)
    }

    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** Version 1, key version 1, a 12-byte IV and a 16-byte tag around a few bytes: shaped like docs/12 §3. */
    private fun envelope(): String =
        b64(byteArrayOf(1, 0, 0, 0, 1) + ByteArray(12 + 16 + 8).also(random::nextBytes))

    private fun enableKey(token: String) {
        val response = call(
            HttpMethod.PUT, "/api/v1/households/$householdId/e2e/key", token,
            mapOf(
                "kdfSalt" to b64(ByteArray(16).also(random::nextBytes)), "iterations" to 600_000,
                "wrappedKey" to envelope(), "verifier" to envelope(),
            ),
        )
        assertThat(response.status()).describedAs(response.body).isEqualTo(HttpStatus.OK)
    }

    private fun seal(fieldKey: String, token: String = owner, recordId: String = lockerId) = call(
        HttpMethod.PUT, "/api/v1/households/$householdId/e2e/values/investment/$recordId/$fieldKey", token,
        mapOf("ciphertext" to envelope()),
    )

    private fun tick(position: Int, token: String = owner, recordId: String = lockerId) = call(
        HttpMethod.PUT,
        "/api/v1/households/$householdId/where-and-who/investment/$recordId/chain/$position/confirmation", token,
    )

    private fun entry(token: String = owner) =
        get("/api/v1/households/$householdId/where-and-who?recordType=investment&recordId=$lockerId", token)
            .json().path("records").first()

    @Test
    fun `the backups are sealed like the first, and the person who sealed a name ticks it`() {
        assertThat(seal("key_holder").status()).isEqualTo(HttpStatus.OK)
        assertThat(seal("key_holder_2").status()).isEqualTo(HttpStatus.OK)

        val ticked = tick(2)
        assertThat(ticked.status()).describedAs(ticked.body).isEqualTo(HttpStatus.OK)
        val record = entry()
        assertThat(record.path("keyHolder2").path("sealedByMe").asBoolean()).isTrue()
        assertThat(record.has("keyHolder3")).isFalse()
        assertThat(record.path("chain").map { it.path("position").asInt() }).containsExactly(2)
        assertThat(audited("continuity.chain.confirm")).isEqualTo(1)

        val theirs = entry(trusted)
        assertThat(theirs.path("chain").map { it.path("position").asInt() })
            .describedAs("a tick is as visible as the record").containsExactly(2)
        assertThat(theirs.path("chain").first().path("confirmedByMe").asBoolean()).isFalse()

        assertThat(tick(2, trusted).errorCode())
            .describedAs("nobody else can read who position 2 names, so nobody else can vouch for it")
            .isEqualTo("sealed_by_someone_else")
        assertThat(tick(3).errorCode()).isEqualTo("nothing_to_confirm")
        assertThat(tick(4).errorCode()).isEqualTo("position_invalid")
    }

    @Test
    fun `writing a name again, or removing it, takes its tick away`() {
        seal("key_holder")
        tick(1)
        assertThat(entry().path("chain")).hasSize(1)

        seal("key_holder")
        assertThat(entry().path("chain")).describedAs("a new name has not been checked").isEmpty()

        tick(1)
        call(HttpMethod.DELETE, "/api/v1/households/$householdId/e2e/values/investment/$lockerId/key_holder", owner)
        assertThat(entry().path("chain")).isEmpty()
    }

    @Test
    fun `a private record's chain is not there for anyone else`() {
        val privateId = capture(owner, householdId, "gold_physical", "Her locker", BigDecimal("1"), visibility = "private")
            .path("id").asText()
        seal("key_holder_2", recordId = privateId)
        assertThat(tick(2, recordId = privateId).status()).isEqualTo(HttpStatus.OK)
        assertThat(tick(2, trusted, recordId = privateId).status()).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(
            call(
                HttpMethod.DELETE,
                "/api/v1/households/$householdId/where-and-who/investment/$privateId/chain/2/confirmation", trusted,
            ).status(),
        ).isEqualTo(HttpStatus.NOT_FOUND)
    }
}
