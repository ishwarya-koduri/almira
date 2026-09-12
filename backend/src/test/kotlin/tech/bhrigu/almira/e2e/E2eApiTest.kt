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
import java.text.Normalizer
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
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

    /**
     * PBKDF2-HMAC-SHA256, written out, over bytes this file produced itself.
     *
     * **Not** `PBEKeySpec` with `SecretKeyFactory`, which is what this used to
     * be and which docs/12 §2 forbids by name: that API takes a `char[]` and
     * the char-to-byte step belongs to the platform provider, so the one step
     * that decides whether two clients agree would be outside our control and
     * is not the same on Android as on the JVM. It passed here only because
     * this test's passphrase is ASCII — which is exactly the kind of accident
     * that holds until the first Telugu passphrase and then takes the data with
     * it.
     *
     * Caught by `scripts/check-spec.py`, which reads the prohibitions in
     * docs/12 §2 and §7 and checks the derivation paths — including this one,
     * because §7 points at this file as code to copy.
     */
    private fun deriveWrappingKey(
        salt: ByteArray,
        iterations: Int,
        from: String = passphrase,
    ): SecretKey = SecretKeySpec(pbkdf2(passphraseBytes(from), salt, iterations, 32), "AES")

    /** NFC, then UTF-8, and nothing else — no trim, no case folding (docs/12 §2). */
    private fun passphraseBytes(text: String): ByteArray =
        Normalizer.normalize(text, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)

    /** RFC 8018 §5.2, for one block, which is all a 256-bit key needs. */
    private fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
        val output = ByteArray(length)
        var written = 0
        var block = 1
        while (written < length) {
            // U1 = PRF(password, salt ‖ INT(block)), then xor in each U.
            var u = mac.doFinal(
                salt + byteArrayOf(
                    (block ushr 24).toByte(), (block ushr 16).toByte(),
                    (block ushr 8).toByte(), block.toByte(),
                ),
            )
            val accumulated = u.copyOf()
            repeat(iterations - 1) {
                u = mac.doFinal(u)
                for (i in accumulated.indices) accumulated[i] = (accumulated[i].toInt() xor u[i].toInt()).toByte()
            }
            val take = minOf(accumulated.size, length - written)
            accumulated.copyInto(output, written, 0, take)
            written += take
            block += 1
        }
        return output
    }

    /**
     * version(1) | keyVersion(4, big-endian) | iv(12) | ciphertext+tag
     *
     * [keyVersion] is a parameter rather than a hard-coded 1 because the
     * envelope's field must equal the key version it belongs to — the web
     * client stamps the current one, and a reference half that always wrote 1
     * would agree with it right up until the first rotation and not afterwards
     * (docs/zk-interop-acceptance.md B7).
     */
    private fun gcmSeal(
        key: SecretKey,
        plaintext: ByteArray,
        aad: ByteArray?,
        keyVersion: Int = 1,
    ): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        aad?.let(cipher::updateAAD)
        val body = cipher.doFinal(plaintext)
        return b64(byteArrayOf(1) + keyVersionBytes(keyVersion) + iv + body)
    }

    private fun keyVersionBytes(version: Int) = byteArrayOf(
        (version ushr 24).toByte(),
        (version ushr 16).toByte(),
        (version ushr 8).toByte(),
        version.toByte(),
    )

    /**
     * The one reader, refusing a version it does not know rather than slicing
     * offsets that may no longer mean what they meant (B6).
     */
    private fun gcmOpen(key: SecretKey, envelope: String, aad: ByteArray?): String {
        val raw = Base64.getUrlDecoder().decode(envelope)
        require(raw.size >= 33) { "not an envelope: ${raw.size} bytes" }
        require(raw[0].toInt() == 1) { "envelope version ${raw[0].toInt()} is not one this knows" }
        val iv = raw.copyOfRange(5, 17)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        aad?.let(cipher::updateAAD)
        return String(cipher.doFinal(raw.copyOfRange(17, raw.size)))
    }

    /** The same reader, when what comes out is a key rather than a sentence. */
    private fun gcmOpenBytes(key: SecretKey, envelope: String, aad: ByteArray?): ByteArray {
        val raw = Base64.getUrlDecoder().decode(envelope)
        require(raw.size >= 33) { "not an envelope: ${raw.size} bytes" }
        require(raw[0].toInt() == 1) { "envelope version ${raw[0].toInt()} is not one this knows" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw.copyOfRange(5, 17)))
        aad?.let(cipher::updateAAD)
        return cipher.doFinal(raw.copyOfRange(17, raw.size))
    }

    /** What the envelope says it was written under. */
    private fun envelopeKeyVersion(envelope: String): Int {
        val raw = Base64.getUrlDecoder().decode(envelope)
        return ((raw[1].toInt() and 0xFF) shl 24) or ((raw[2].toInt() and 0xFF) shl 16) or
            ((raw[3].toInt() and 0xFF) shl 8) or (raw[4].toInt() and 0xFF)
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

    /**
     * A rotation rewraps the same content key under a new passphrase, and the
     * envelopes it writes say so.
     *
     * The field exists to let a client *tell* which content key a value was
     * written under — never to choose one, since only one wrapped key exists at
     * a time. A reference half that always stamped 1 would agree with the web
     * client right up until the first rotation and not afterwards, which is
     * precisely the kind of divergence nobody finds until somebody changes
     * their passphrase (docs/zk-interop-acceptance.md B7).
     */
    @Test
    fun `rotating stamps the new key version into the wrap envelopes`() {
        val (_, contentKey) = enable()

        val newSalt = ByteArray(16).also(random::nextBytes)
        val newWrappingKey = deriveWrappingKey(newSalt, 600_000)
        val rotated = call(
            HttpMethod.PUT, "/api/v1/households/$householdId/e2e/key", owner,
            mapOf(
                "kdfSalt" to b64(newSalt),
                "iterations" to 600_000,
                "wrappedKey" to gcmSeal(newWrappingKey, contentKey.encoded, null, keyVersion = 2),
                "verifier" to gcmSeal(contentKey, "almira".toByteArray(), null, keyVersion = 2),
                "keyVersion" to 2,
            ),
        )
        assertThat(rotated.status()).isEqualTo(HttpStatus.OK)

        val stored = get("/api/v1/households/$householdId/e2e", owner).json().path("key")
        assertThat(stored.path("keyVersion").asInt()).isEqualTo(2)
        assertThat(envelopeKeyVersion(stored.path("wrappedKey").asText()))
            .describedAs("the wrapped key's envelope agrees with the stored key version")
            .isEqualTo(2)
        assertThat(envelopeKeyVersion(stored.path("verifier").asText()))
            .describedAs("and so does the verifier's")
            .isEqualTo(2)

        // The whole point of a rotation: the same content key comes back out,
        // so nothing already sealed has to be rewritten.
        val unwrapped = Cipher.getInstance("AES/GCM/NoPadding").let { cipher ->
            val raw = Base64.getUrlDecoder().decode(stored.path("wrappedKey").asText())
            cipher.init(
                Cipher.DECRYPT_MODE, newWrappingKey,
                GCMParameterSpec(128, raw.copyOfRange(5, 17)),
            )
            cipher.doFinal(raw.copyOfRange(17, raw.size))
        }
        assertThat(unwrapped).isEqualTo(contentKey.encoded)
        assertThat(gcmOpen(contentKey, stored.path("verifier").asText(), null)).isEqualTo("almira")
    }

    /**
     * The conformance vector from docs/12 §8.1, asserted here so that the
     * reference implementation the document points at is pinned to the same
     * bytes as the web client, Android and iOS.
     *
     * This exists because of what it caught. `deriveWrappingKey` used to be a
     * `PBEKeySpec`/`SecretKeyFactory` pair — the API §2 forbids by name — and
     * nothing noticed, because this suite only ever round-tripped its own
     * output. A reference half that agrees with itself is worth nothing; the
     * only question that matters is whether it agrees with the browser.
     */
    @Test
    fun `the reference half derives the conformance vector from docs 12`() {
        val salt = ByteArray(16) { it.toByte() }
        val key = deriveWrappingKey(salt, 600_000, "correct horse battery staple ")
        assertThat(key.encoded.joinToString("") { "%02x".format(it) })
            .describedAs(
                "the reference implementation must derive the same key as every other " +
                    "client. If this fails, this file's PBKDF2 disagrees with docs/12 §8.1 " +
                    "and anything it seals is unopenable by the real clients.",
            )
            .isEqualTo("17c0b45fe7d3dcc10b70395e28a8cc533a0c8113691b174d39b8a205f2085f6f")
    }

    /**
     * A truncated envelope, stored through the real API and read back through
     * it — the gap docs/12 §8.5 listed as asserted only in unit tests.
     *
     * The server cannot tell: a truncated envelope is still base64 and still
     * longer than the 17-byte floor, so it is accepted, which is correct. The
     * refusal has to happen in the client, on read, and it has to be a refusal
     * rather than a plausible-looking answer.
     */
    @Test
    fun `a truncated envelope survives the API and is refused by the client`() {
        val (_, contentKey) = enable()
        val id = capture(
            owner, householdId, "gold_physical", "Gold", BigDecimal("1"), visibility = "household",
        ).path("id").asText()

        val whole = gcmSeal(contentKey, secret.toByteArray(), aadFor(id, "where_it_is"))
        val rawWhole = Base64.getUrlDecoder().decode(whole)
        // Keep the header and the first half of the body: the version byte, the
        // key version and the iv all still parse, so nothing short-circuits
        // before the tag check.
        val truncated = b64(rawWhole.copyOfRange(0, 17 + (rawWhole.size - 17) / 2))

        val stored = call(
            HttpMethod.PUT, "/api/v1/households/$householdId/e2e/values/investment/$id/where_it_is",
            owner, mapOf("ciphertext" to truncated),
        )
        assertThat(stored.status())
            .describedAs("the server takes it, because it cannot know — that is the whole design")
            .isEqualTo(HttpStatus.OK)

        val fetched = get(
            "/api/v1/households/$householdId/e2e/values?recordType=investment&recordId=$id", owner,
        ).json().first().path("ciphertext").asText()
        assertThat(fetched).isEqualTo(truncated)

        assertThat(runCatching { gcmOpen(contentKey, fetched, aadFor(id, "where_it_is")) }.isFailure)
            .describedAs("a truncated envelope must fail to open, never open partially")
            .isTrue()

        // And a body shorter than a tag is refused before any cipher is asked,
        // which is the other half of truncation.
        val headerOnly = b64(rawWhole.copyOfRange(0, 17))
        val tooShort = call(
            HttpMethod.PUT, "/api/v1/households/$householdId/e2e/values/investment/$id/nothing",
            owner, mapOf("ciphertext" to headerOnly),
        )
        assertThat(tooShort.status())
            .describedAs("17 bytes is a header and no tag; the server's floor catches this one")
            .isEqualTo(HttpStatus.BAD_REQUEST)
    }

    /**
     * The gap docs/12 §8.5 said was proved on no client at all: a value sealed
     * **before** a passphrase rotation, opened **after** it.
     *
     * It must open, because rotation rewraps the same content key and rewrites
     * no field — and the value's envelope must still carry the old key version,
     * because that field records what wrote it rather than selecting what reads
     * it. If this ever fails, rotation is a data-loss operation and the first
     * person to change their passphrase finds out.
     */
    @Test
    fun `a value sealed before a rotation still opens after it`() {
        val (_, contentKey) = enable()
        val id = capture(
            owner, householdId, "gold_physical", "Gold", BigDecimal("1"), visibility = "household",
        ).path("id").asText()

        // Sealed under key version 1, before anything rotates.
        val before = gcmSeal(contentKey, secret.toByteArray(), aadFor(id, "where_it_is"), keyVersion = 1)
        assertThat(
            call(
                HttpMethod.PUT,
                "/api/v1/households/$householdId/e2e/values/investment/$id/where_it_is",
                owner, mapOf("ciphertext" to before),
            ).status(),
        ).isEqualTo(HttpStatus.OK)

        // Rotate to a new passphrase: new salt, new wrapping key, the same
        // content key, version 2. Exactly docs/12 §6, and no field is touched.
        val newPassphrase = "a different passphrase entirely ఖ"
        val newSalt = ByteArray(16).also(random::nextBytes)
        val newWrappingKey = deriveWrappingKey(newSalt, 600_000, newPassphrase)
        assertThat(
            call(
                HttpMethod.PUT, "/api/v1/households/$householdId/e2e/key", owner,
                mapOf(
                    "kdfSalt" to b64(newSalt),
                    "iterations" to 600_000,
                    "wrappedKey" to gcmSeal(newWrappingKey, contentKey.encoded, null, keyVersion = 2),
                    "verifier" to gcmSeal(contentKey, "almira".toByteArray(), null, keyVersion = 2),
                    "keyVersion" to 2,
                ),
            ).status(),
        ).isEqualTo(HttpStatus.OK)

        // Now unlock the way a client would after the rotation: fetch the key,
        // derive from the *new* passphrase and the *stored* salt, unwrap.
        val key = get("/api/v1/households/$householdId/e2e", owner).json().path("key")
        assertThat(key.path("keyVersion").asInt()).isEqualTo(2)
        val derived = deriveWrappingKey(
            Base64.getUrlDecoder().decode(key.path("kdfSalt").asText()),
            key.path("iterations").asInt(),
            newPassphrase,
        )
        val recovered = SecretKeySpec(
            gcmOpenBytes(derived, key.path("wrappedKey").asText(), null), "AES",
        )
        assertThat(recovered.encoded)
            .describedAs("a rotation must hand back the same content key, or every sealed field is lost")
            .isEqualTo(contentKey.encoded)
        assertThat(gcmOpen(recovered, key.path("verifier").asText(), null)).isEqualTo("almira")

        val fetched = get(
            "/api/v1/households/$householdId/e2e/values?recordType=investment&recordId=$id", owner,
        ).json().first().path("ciphertext").asText()

        assertThat(envelopeKeyVersion(fetched))
            .describedAs("the value still says what wrote it; rotation rewrites no field")
            .isEqualTo(1)
        assertThat(gcmOpen(recovered, fetched, aadFor(id, "where_it_is")))
            .describedAs("and it opens, under the content key recovered from the new passphrase")
            .isEqualTo(secret)
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
