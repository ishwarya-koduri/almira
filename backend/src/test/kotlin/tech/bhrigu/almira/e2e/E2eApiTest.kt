package tech.bhrigu.almira.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import tech.bhrigu.almira.support.ApiTestBase
import java.math.BigDecimal
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The whole point of this feature is a negative: the server cannot read these
 * fields. So the tests do the client's job for real — PBKDF2 and AES-GCM,
 * exactly the scheme docs/12 specifies for the browser and the native app —
 * and then go looking in the database for the plaintext.
 */
@DisplayName("Zero-knowledge mode: fields the server cannot read")
class E2eApiTest : ApiTestBase() {

    private lateinit var owner: String
    private lateinit var spouse: String
    private lateinit var householdId: String
    private val random = SecureRandom()

    private val passphrase = "correct horse battery staple"
    private val secret = "Locker 214 at Karur Vysya, Kakinada. Key with Meera."

    @BeforeEach
    fun setUp() {
        owner = signIn()
        spouse = signIn()
        householdId = createHousehold(owner, "Koduri", "private", "Ishwarya").path("id").asText()
        val spouseMemberId = addMember(owner, householdId, "Ravi").path("id").asText()
        joinHousehold(owner, householdId, spouseMemberId, spouse)
    }

    // --- the client's half of the scheme, as docs/12 defines it ---------------

    private fun b64(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun deriveWrappingKey(salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256)
        return SecretKeySpec(
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
            "AES",
        )
    }

    private fun gcmSeal(key: SecretKey, plaintext: ByteArray, aad: ByteArray?): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        aad?.let(cipher::updateAAD)
        val body = cipher.doFinal(plaintext)
        // version(1) | keyVersion(4) | iv(12) | ciphertext+tag
        return b64(byteArrayOf(1) + byteArrayOf(0, 0, 0, 1) + iv + body)
    }

    private fun gcmOpen(key: SecretKey, envelope: String, aad: ByteArray?): String {
        val raw = Base64.getUrlDecoder().decode(envelope)
        val iv = raw.copyOfRange(5, 17)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        aad?.let(cipher::updateAAD)
        return String(cipher.doFinal(raw.copyOfRange(17, raw.size)))
    }

    private fun aadFor(recordId: String, fieldKey: String) =
        "$householdId|investment|$recordId|$fieldKey".toByteArray()

    private fun enable(): Pair<SecretKey, SecretKey> {
        val salt = ByteArray(16).also(random::nextBytes)
        val iterations = 600_000
        val wrappingKey = deriveWrappingKey(salt, iterations)
        val contentKey = SecretKeySpec(ByteArray(32).also(random::nextBytes), "AES")

        val response = call(
            HttpMethod.PUT, "/api/v1/households/$householdId/e2e/key", owner,
            mapOf(
                "kdfSalt" to b64(salt),
                "iterations" to iterations,
                "wrappedKey" to gcmSeal(wrappingKey, contentKey.encoded, null),
                "verifier" to gcmSeal(contentKey, "almira".toByteArray(), null),
            ),
        )
        assertThat(response.status()).isEqualTo(HttpStatus.OK)
        return wrappingKey to contentKey
    }

    // --- the tests ------------------------------------------------------------

    @Test
    fun `a sealed field reaches the database as ciphertext and nothing else`() {
        val (_, contentKey) = enable()
        val holding = capture(
            owner, householdId, "gold_physical", "Wedding gold", BigDecimal("500000"),
            visibility = "household",
        )
        val id = holding.path("id").asText()

        val sealed = call(
            HttpMethod.PUT, "/api/v1/households/$householdId/e2e/values/investment/$id/where_it_is",
            owner,
            mapOf("ciphertext" to gcmSeal(contentKey, secret.toByteArray(), aadFor(id, "where_it_is"))),
        )
        assertThat(sealed.status()).isEqualTo(HttpStatus.OK)

        // The database, read as the owner role with RLS out of the way — the
        // strongest form of the question: is the plaintext anywhere at all?
        val stored = db.queryForList(
            "select ciphertext from sealed_values where record_id = ?::uuid", id,
        ).single()["ciphertext"] as String
        assertThat(stored).doesNotContain("Locker").doesNotContain("Karur").doesNotContain("Meera")
        assertThat(
            db.queryForObject(
                "select count(*) from sealed_values where ciphertext like '%Locker%'",
                Int::class.java,
            ),
        ).isZero()

        // And the client can still read it back, which is the other half of the
        // claim — unreadable to the server is worthless if it is unreadable
        // to its owner too.
        val fetched = get(
            "/api/v1/households/$householdId/e2e/values?recordType=investment&recordId=$id", owner,
        ).json().first().path("ciphertext").asText()
        assertThat(gcmOpen(contentKey, fetched, aadFor(id, "where_it_is"))).isEqualTo(secret)
    }

    /**
     * The additional data binds a ciphertext to the exact field it was written
     * for, so a value moved to another record — by anyone who can write the
     * database directly — fails to open rather than decrypting somewhere it
     * does not belong.
     */
    @Test
    fun `ciphertext moved to another record no longer opens`() {
        val (_, contentKey) = enable()
        val first = capture(
            owner, householdId, "gold_physical", "Gold", BigDecimal("1"), visibility = "household",
        ).path("id").asText()
        val second = capture(
            owner, householdId, "gold_physical", "Other gold", BigDecimal("1"), visibility = "household",
        ).path("id").asText()

        val envelope = gcmSeal(contentKey, secret.toByteArray(), aadFor(first, "where_it_is"))
        assertThat(gcmOpen(contentKey, envelope, aadFor(first, "where_it_is"))).isEqualTo(secret)

        assertThat(
            runCatching { gcmOpen(contentKey, envelope, aadFor(second, "where_it_is")) }.isFailure,
        ).isTrue()
    }

    @Test
    fun `the passphrase is never sent, so the wrapped key is all the server holds`() {
        enable()
        // Other tests in this class leave keys behind, so this asks about ours.
        val stored = db.queryForList(
            "select * from e2e_keys where household_id = ?::uuid", householdId,
        ).single()
        assertThat(stored.values.map { it.toString() })
            .describedAs("nothing that resembles the passphrase")
            .noneMatch { it.contains("correct horse") }
        assertThat(stored["iterations"] as Int).isGreaterThanOrEqualTo(100_000)

        val status = get("/api/v1/households/$householdId/e2e", owner).json()
        assertThat(status.path("enabled").asBoolean()).isTrue()
        assertThat(status.path("caveats").map { it.asText() })
            .describedAs("the trade-offs are stated, not buried")
            .anyMatch { it.contains("no recovery") || it.contains("There is no recovery") }
    }

    @Test
    fun `a stretch too short to be worth doing is refused`() {
        val salt = ByteArray(16).also(random::nextBytes)
        val key = deriveWrappingKey(salt, 1000)
        val refused = call(
            HttpMethod.PUT, "/api/v1/households/$householdId/e2e/key", owner,
            mapOf(
                "kdfSalt" to b64(salt), "iterations" to 1000,
                "wrappedKey" to gcmSeal(key, ByteArray(32), null),
                "verifier" to gcmSeal(key, "almira".toByteArray(), null),
            ),
        )
        assertThat(refused.status()).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(refused.errorCode()).isEqualTo("kdf_too_weak")
    }

    /**
     * The one failure mode this feature must not have: a client bug that posts
     * the note in the clear while the UI says it is sealed.
     */
    @Test
    fun `plain text posted as ciphertext is refused`() {
        enable()
        val id = capture(
            owner, householdId, "gold_physical", "Gold", BigDecimal("1"), visibility = "household",
        ).path("id").asText()

        val refused = call(
            HttpMethod.PUT, "/api/v1/households/$householdId/e2e/values/investment/$id/notes", owner,
            mapOf("ciphertext" to "Locker 214 at Karur Vysya — this is obviously not ciphertext"),
        )
        assertThat(refused.status()).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(refused.errorCode()).isEqualTo("not_ciphertext")
    }

    @Test
    fun `you cannot seal a field on a record you cannot see`() {
        enable()
        val hers = capture(
            owner, householdId, "gold_physical", "Her private gold", BigDecimal("1"),
            visibility = "private",
        ).path("id").asText()

        // The spouse has their own passphrase, and still cannot reach the record.
        val salt = ByteArray(16).also(random::nextBytes)
        val key = deriveWrappingKey(salt, 200_000)
        call(
            HttpMethod.PUT, "/api/v1/households/$householdId/e2e/key", spouse,
            mapOf(
                "kdfSalt" to b64(salt), "iterations" to 200_000,
                "wrappedKey" to gcmSeal(key, ByteArray(32), null),
                "verifier" to gcmSeal(key, "almira".toByteArray(), null),
            ),
        )
        val refused = call(
            HttpMethod.PUT, "/api/v1/households/$householdId/e2e/values/investment/$hers/notes",
            spouse,
            mapOf("ciphertext" to gcmSeal(key, "mine now".toByteArray(), null)),
        )
        assertThat(refused.status()).isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN)
    }

    @Test
    fun `a sealed field is as visible as the record it belongs to, and no more`() {
        val (_, contentKey) = enable()
        val hers = capture(
            owner, householdId, "gold_physical", "Her private gold", BigDecimal("1"),
            visibility = "private",
        ).path("id").asText()
        call(
            HttpMethod.PUT, "/api/v1/households/$householdId/e2e/values/investment/$hers/notes", owner,
            mapOf("ciphertext" to gcmSeal(contentKey, secret.toByteArray(), aadFor(hers, "notes"))),
        )

        assertThat(get("/api/v1/households/$householdId/e2e/values", owner).json()).hasSize(1)
        assertThat(get("/api/v1/households/$householdId/e2e/values", spouse).json())
            .describedAs("that a private record has a sealed field is itself information")
            .isEmpty()
    }

    @Test
    fun `sealing needs a key to have been set up first`() {
        val id = capture(
            owner, householdId, "gold_physical", "Gold", BigDecimal("1"), visibility = "household",
        ).path("id").asText()
        val salt = ByteArray(16).also(random::nextBytes)
        val refused = call(
            HttpMethod.PUT, "/api/v1/households/$householdId/e2e/values/investment/$id/notes", owner,
            mapOf("ciphertext" to gcmSeal(deriveWrappingKey(salt, 200_000), "x".toByteArray(), null)),
        )
        assertThat(refused.status()).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(refused.errorCode()).isEqualTo("no_key")
    }

    @Test
    fun `unsealing removes it`() {
        val (_, contentKey) = enable()
        val id = capture(
            owner, householdId, "gold_physical", "Gold", BigDecimal("1"), visibility = "household",
        ).path("id").asText()
        call(
            HttpMethod.PUT, "/api/v1/households/$householdId/e2e/values/investment/$id/notes", owner,
            mapOf("ciphertext" to gcmSeal(contentKey, secret.toByteArray(), aadFor(id, "notes"))),
        )
        assertThat(
            delete("/api/v1/households/$householdId/e2e/values/investment/$id/notes", owner).status(),
        ).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(get("/api/v1/households/$householdId/e2e/values", owner).json()).isEmpty()
    }
}
