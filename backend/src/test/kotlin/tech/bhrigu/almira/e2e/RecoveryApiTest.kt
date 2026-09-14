package tech.bhrigu.almira.e2e

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import tech.bhrigu.almira.support.ApiTestBase
import java.math.BigDecimal
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Recovery for zero-knowledge mode (docs/12 §10), end to end over the real API.
 *
 * The same negative as `E2eApiTest`, one layer out: the server can store a
 * second way into everything sealed and still not be able to use it. So the
 * tests make the codes and shares the way a browser does (`RecoveryReference`),
 * prove each one opens the key through the API, and then look for the code,
 * the shares and the secret in every row the server wrote.
 */
@DisplayName("Recovery: a printed key and 2-of-3 shares the server never sees")
class RecoveryApiTest : ApiTestBase() {

    @Autowired private lateinit var redis: StringRedisTemplate

    private val r = RecoveryReference

    private lateinit var owner: String
    private lateinit var spouse: String
    private lateinit var householdId: String
    private lateinit var ownerMemberId: String
    private lateinit var spouseMemberId: String

    private val passphrase = "a passphrase for the recovery tests"
    private val iterations = 100_000

    @BeforeEach
    fun setUp() {
        owner = signIn()
        spouse = signIn()
        val household = createHousehold(owner, "Koduri", "private", "Ishwarya")
        householdId = household.path("id").asText()
        ownerMemberId = household.path("myMemberId").asText()
        spouseMemberId = addMember(owner, householdId, "Ravi").path("id").asText()
        joinHousehold(owner, householdId, spouseMemberId, spouse)
    }

    // --- the client's half ----------------------------------------------------

    private fun pbkdf2(password: ByteArray, salt: ByteArray, rounds: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
        var u = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
        val out = u.copyOf()
        repeat(rounds - 1) {
            u = mac.doFinal(u)
            for (i in out.indices) out[i] = (out[i].toInt() xor u[i].toInt()).toByte()
        }
        return out
    }

    private fun passphraseKey(salt: ByteArray, text: String = passphrase): SecretKey =
        SecretKeySpec(pbkdf2(text.toByteArray(), salt, iterations), "AES")

    private fun putKey(
        token: String,
        contentKey: ByteArray,
        text: String = passphrase,
        keyVersion: Int = 1,
        contentKeyId: String? = r.contentKeyId(contentKey),
    ) = run {
        val salt = r.randomBytes(16)
        val aes = SecretKeySpec(contentKey, "AES")
        call(
            HttpMethod.PUT, "/api/v1/households/$householdId/e2e/key", token,
            buildMap {
                put("kdfSalt", r.b64(salt))
                put("iterations", iterations)
                put("wrappedKey", r.seal(passphraseKey(salt, text), contentKey, keyVersion))
                put("verifier", r.seal(aes, "almira".toByteArray(), keyVersion))
                put("keyVersion", keyVersion)
                contentKeyId?.let { put("contentKeyId", it) }
            },
        )
    }

    private fun stepUp(token: String) {
        val challenge = post("/api/v1/auth/step-up/request", token).json()
        val verified = post(
            "/api/v1/auth/step-up/verify", token,
            mapOf(
                "code" to challenge.path("developmentCode").asText(),
                "requestId" to challenge.path("requestId").asText(),
            ),
        )
        check(verified.statusCode.is2xxSuccessful) { "step-up failed: ${verified.body}" }
    }

    /** What a browser sends for a copy of [contentKey] under [secret]. */
    private fun copyBody(kind: String, secret: ByteArray, contentKey: ByteArray, holders: List<String> = emptyList()) =
        run {
            val salt = r.randomBytes(16)
            mapOf(
                "kdfSalt" to r.b64(salt),
                "wrappedKey" to r.seal(r.wrappingKey(secret, salt, kind), contentKey),
                "verifier" to r.seal(SecretKeySpec(contentKey, "AES"), "almira".toByteArray()),
                "contentKeyId" to r.contentKeyId(contentKey),
                "holders" to holders,
            )
        }

    private fun putCopy(token: String, kind: String, body: Map<String, Any>) =
        call(HttpMethod.PUT, "/api/v1/households/$householdId/e2e/recovery/$kind", token, body)

    /** What a browser does with a slot and a secret: derive, unwrap, check the verifier and the id. */
    private fun openWith(slot: JsonNode, secret: ByteArray): ByteArray {
        val kind = slot.path("kind").asText()
        val salt = Base64.getUrlDecoder().decode(slot.path("kdfSalt").asText())
        val contentKey = r.open(r.wrappingKey(secret, salt, kind), slot.path("wrappedKey").asText())
        check(String(r.open(SecretKeySpec(contentKey, "AES"), slot.path("verifier").asText())) == "almira")
        check(r.contentKeyId(contentKey) == slot.path("contentKeyId").asText())
        return contentKey
    }

    private fun slots(token: String = owner) =
        get("/api/v1/households/$householdId/e2e/recovery", token).json().path("slots")

    private fun slot(kind: String, token: String = owner) = slots(token).first { it.path("kind").asText() == kind }

    /** Every text column the recovery tables and the audit log hold for this household. */
    private fun everythingStored(): String = buildString {
        for (table in listOf("e2e_recovery_wraps", "e2e_recovery_slots", "e2e_keys")) {
            db.queryForList("select * from $table where household_id = ?::uuid", householdId)
                .forEach { append(it.values.joinToString("|")) }
        }
        db.queryForList("select * from activity_log where household_id = ?::uuid", householdId)
            .forEach { append(it.values.joinToString("|")) }
    }

    // --- the tests ------------------------------------------------------------

    @Test
    fun `a recovery key opens the content key, and the server holds nothing that could`() {
        val contentKey = r.randomBytes(32)
        assertThat(putKey(owner, contentKey).status()).isEqualTo(HttpStatus.OK)
        val secret = r.randomBytes(RecoveryReference.SECRET_BYTES)
        val code = r.encodeCode(RecoveryReference.TYPE_KEY, 0, secret)

        val refused = putCopy(owner, Recovery.KEY, copyBody(Recovery.KEY, secret, contentKey, listOf("our lawyer")))
        assertThat(refused.status())
            .describedAs("a copy is a second way into everything sealed; a borrowed session is not enough")
            .isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(refused.errorCode()).isEqualTo("step_up_required")
        assertThat(slots()).isEmpty()

        stepUp(owner)
        val made = putCopy(owner, Recovery.KEY, copyBody(Recovery.KEY, secret, contentKey, listOf("our lawyer")))
        assertThat(made.status()).isEqualTo(HttpStatus.OK)
        assertThat(made.json().path("holders").map { it.asText() }).containsExactly("our lawyer")
        assertThat(made.json().path("current").asBoolean()).isTrue()
        assertThat(made.json().has("practicedAt")).isFalse()

        // The person with the sheet: the typed code, back to the secret, back to the key.
        val typed = code.lowercase().replace("-", " ")
        assertThat(openWith(slot(Recovery.KEY), r.decodeCode(typed).payload)).isEqualTo(contentKey)

        val stored = everythingStored()
        val plainCode = code.replace("-", "")
        assertThat(stored).doesNotContain(code).doesNotContain(plainCode).doesNotContain(plainCode.lowercase())
        assertThat(stored).doesNotContain(r.b64(secret))
        assertThat(stored.lowercase()).doesNotContain(secret.joinToString("") { "%02x".format(it) })
        assertThat(db.queryForList("select * from activity_log where household_id = ?::uuid and action like 'e2e.recovery%'", householdId).toString())
            .describedAs("the log records that a copy exists, not who has it")
            .contains("e2e.recovery.create").doesNotContain("our lawyer")
    }

    @Test
    fun `any two of three shares open the key, and no share reaches the server`() {
        val contentKey = r.randomBytes(32)
        putKey(owner, contentKey)
        val secret = r.randomBytes(RecoveryReference.SECRET_BYTES)
        val shares = r.split(secret, 2, 3)
        val codes = shares.map { r.encodeCode(RecoveryReference.TYPE_SHARE, it.x, it.y) }

        stepUp(owner)
        val made = putCopy(owner, Recovery.SHARES, copyBody(Recovery.SHARES, secret, contentKey, listOf("Amma", "Ravi", "our lawyer")))
        assertThat(made.status()).isEqualTo(HttpStatus.OK)
        assertThat(made.json().path("threshold").asInt()).isEqualTo(2)
        assertThat(made.json().path("shareCount").asInt()).isEqualTo(3)

        val slot = slot(Recovery.SHARES)
        for ((a, b) in listOf(0 to 1, 0 to 2, 1 to 2)) {
            val typed = listOf(codes[a], codes[b]).map { r.decodeCode(it) }.map { RecoveryReference.Share(it.x, it.payload) }
            assertThat(openWith(slot, r.combine(typed))).describedAs("shares ${a + 1} and ${b + 1}").isEqualTo(contentKey)
        }
        // One share alone, "combined" with itself under another x, is just wrong bytes: the tag refuses.
        val lonely = RecoveryReference.Share(1, shares[0].y)
        val guessed = r.combine(listOf(lonely, RecoveryReference.Share(2, ByteArray(RecoveryReference.SECRET_BYTES))))
        assertThat(runCatching { openWith(slot, guessed) }.isFailure).isTrue()

        val stored = everythingStored()
        for (code in codes) assertThat(stored).doesNotContain(code).doesNotContain(code.replace("-", ""))
        for (share in shares) assertThat(stored).doesNotContain(r.b64(share.y))
        // A secret for shares does not open a key copy, or the other way round: the kind is in the HKDF info.
        val salt = Base64.getUrlDecoder().decode(slot.path("kdfSalt").asText())
        assertThat(runCatching { r.open(r.wrappingKey(secret, salt, Recovery.KEY), slot.path("wrappedKey").asText()) }.isFailure)
            .isTrue()
    }

    @Test
    fun `practising records the date and changes nothing else`() {
        val contentKey = r.randomBytes(32)
        putKey(owner, contentKey)
        val secret = r.randomBytes(RecoveryReference.SECRET_BYTES)
        stepUp(owner)
        putCopy(owner, Recovery.KEY, copyBody(Recovery.KEY, secret, contentKey))
        val before = db.queryForMap(
            "select kdf_salt, wrapped_key, verifier, content_key_id from e2e_recovery_wraps where household_id = ?::uuid",
            householdId,
        )

        assertThat(openWith(slot(Recovery.KEY), secret)).isEqualTo(contentKey)
        val practised = post("/api/v1/households/$householdId/e2e/recovery/${Recovery.KEY}/practice", owner)
        assertThat(practised.status()).isEqualTo(HttpStatus.OK)
        assertThat(practised.json().path("practicedAt").asText()).isNotBlank()
        assertThat(
            db.queryForMap(
                "select kdf_salt, wrapped_key, verifier, content_key_id from e2e_recovery_wraps where household_id = ?::uuid",
                householdId,
            ),
        ).isEqualTo(before)

        assertThat(post("/api/v1/households/$householdId/e2e/recovery/${Recovery.SHARES}/practice", owner).status())
            .describedAs("there are no shares to practise")
            .isEqualTo(HttpStatus.NOT_FOUND)

        // A replaced copy has not been practised, whatever the old one had.
        val replaced = putCopy(owner, Recovery.KEY, copyBody(Recovery.KEY, r.randomBytes(21), contentKey))
        assertThat(replaced.status()).isEqualTo(HttpStatus.OK)
        assertThat(replaced.json().has("practicedAt")).isFalse()
        assertThat(runCatching { openWith(replaced.json(), secret) }.isFailure)
            .describedAs("the old sheet stops working when the copy is replaced")
            .isTrue()
    }

    @Test
    fun `changing the passphrase keeps every copy opening, and a different key is refused`() {
        val contentKey = r.randomBytes(32)
        putKey(owner, contentKey)
        val keySecret = r.randomBytes(21)
        val shareSecret = r.randomBytes(21)
        stepUp(owner)
        putCopy(owner, Recovery.KEY, copyBody(Recovery.KEY, keySecret, contentKey))
        putCopy(owner, Recovery.SHARES, copyBody(Recovery.SHARES, shareSecret, contentKey))

        // A rotation: the same content key under a new passphrase, docs/12 §6.
        assertThat(putKey(owner, contentKey, "a brand new passphrase", keyVersion = 2).status()).isEqualTo(HttpStatus.OK)
        assertThat(openWith(slot(Recovery.KEY), keySecret)).isEqualTo(contentKey)
        assertThat(openWith(slot(Recovery.SHARES), shareSecret)).isEqualTo(contentKey)
        assertThat(slots().map { it.path("current").asBoolean() }).containsOnly(true)

        val keyRow = db.queryForMap("select wrapped_key, key_version, content_key_id from e2e_keys where household_id = ?::uuid", householdId)

        val replaced = putKey(owner, r.randomBytes(32), "another", keyVersion = 3)
        assertThat(replaced.status())
            .describedAs("a different key would orphan the sheet in the drawer")
            .isEqualTo(HttpStatus.CONFLICT)
        assertThat(replaced.errorCode()).isEqualTo("recovery_copies_would_break")

        val unsaid = putKey(owner, contentKey, "another", keyVersion = 3, contentKeyId = null)
        assertThat(unsaid.status())
            .describedAs("a client that does not say which key it wraps cannot be trusted to keep them")
            .isEqualTo(HttpStatus.CONFLICT)

        assertThat(db.queryForMap("select wrapped_key, key_version, content_key_id from e2e_keys where household_id = ?::uuid", householdId))
            .describedAs("a refused write changes nothing")
            .isEqualTo(keyRow)
    }

    @Test
    fun `a copy of a key other than the one the passphrase opens is refused`() {
        val contentKey = r.randomBytes(32)
        putKey(owner, contentKey)
        stepUp(owner)
        val other = r.randomBytes(32)
        val refused = putCopy(owner, Recovery.KEY, copyBody(Recovery.KEY, r.randomBytes(21), other))
        assertThat(refused.status()).isEqualTo(HttpStatus.CONFLICT)
        assertThat(refused.errorCode()).isEqualTo("recovery_key_mismatch")
        assertThat(slots()).isEmpty()
    }

    @Test
    fun `a key row written without an id records it from the first copy`() {
        val contentKey = r.randomBytes(32)
        assertThat(putKey(owner, contentKey, contentKeyId = null).status()).isEqualTo(HttpStatus.OK)
        assertThat(get("/api/v1/households/$householdId/e2e", owner).json().path("key").has("contentKeyId")).isFalse()

        stepUp(owner)
        assertThat(putCopy(owner, Recovery.KEY, copyBody(Recovery.KEY, r.randomBytes(21), contentKey)).status())
            .isEqualTo(HttpStatus.OK)
        assertThat(get("/api/v1/households/$householdId/e2e", owner).json().path("key").path("contentKeyId").asText())
            .isEqualTo(r.contentKeyId(contentKey))
    }

    /**
     * The case the whole feature exists for: the passphrase is gone, the sheet
     * is not. The client opens the key with the sheet, sets a new passphrase
     * over the same key, and everything sealed opens again.
     */
    @Test
    fun `a forgotten passphrase is replaced using the sheet, and sealed values open again`() {
        val contentKey = r.randomBytes(32)
        putKey(owner, contentKey)
        val holding = capture(owner, householdId, "gold_physical", "Wedding gold", BigDecimal("1"), visibility = "private")
            .path("id").asText()
        val aad = "$householdId|investment|$holding|original_location".toByteArray()
        val words = "Steel almirah, second shelf"
        val sealed = run {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            val iv = r.randomBytes(12)
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, SecretKeySpec(contentKey, "AES"), javax.crypto.spec.GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            r.b64(byteArrayOf(1, 0, 0, 0, 1) + iv + cipher.doFinal(words.toByteArray()))
        }
        assertThat(
            call(HttpMethod.PUT, "/api/v1/households/$householdId/e2e/values/investment/$holding/original_location", owner,
                mapOf("ciphertext" to sealed)).status(),
        ).isEqualTo(HttpStatus.OK)

        val secret = r.randomBytes(21)
        stepUp(owner)
        putCopy(owner, Recovery.KEY, copyBody(Recovery.KEY, secret, contentKey))

        // Months later. Nothing on this device but the sheet.
        val recovered = openWith(slot(Recovery.KEY), secret)
        val version = get("/api/v1/households/$householdId/e2e", owner).json().path("key").path("keyVersion").asInt()
        assertThat(putKey(owner, recovered, "remembered this time", keyVersion = version + 1).status()).isEqualTo(HttpStatus.OK)

        val key = get("/api/v1/households/$householdId/e2e", owner).json().path("key")
        val unwrapped = r.open(
            passphraseKey(Base64.getUrlDecoder().decode(key.path("kdfSalt").asText()), "remembered this time"),
            key.path("wrappedKey").asText(),
        )
        val value = get("/api/v1/households/$householdId/e2e/values?recordType=investment&recordId=$holding", owner)
            .json().first().path("ciphertext").asText()
        val raw = Base64.getUrlDecoder().decode(value)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, SecretKeySpec(unwrapped, "AES"), javax.crypto.spec.GCMParameterSpec(128, raw.copyOfRange(5, 17)))
        cipher.updateAAD(aad)
        assertThat(String(cipher.doFinal(raw.copyOfRange(17, raw.size)))).isEqualTo(words)
        assertThat(openWith(slot(Recovery.KEY), secret)).describedAs("and the sheet still works").isEqualTo(contentKey)
    }

    @Test
    fun `someone else's copies are 404 unless an emergency window is open on that person`() {
        val contentKey = r.randomBytes(32)
        putKey(owner, contentKey)
        val secret = r.randomBytes(21)
        stepUp(owner)
        putCopy(owner, Recovery.KEY, copyBody(Recovery.KEY, secret, contentKey, listOf("Ravi")))

        val path = "/api/v1/households/$householdId/e2e/recovery/members/$ownerMemberId"
        assertThat(get(path, owner).status()).describedAs("your own, by member id").isEqualTo(HttpStatus.OK)
        assertThat(get(path, spouse).status()).describedAs("an admin spouse with no window").isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(get("/api/v1/households/$householdId/e2e/recovery/members/${java.util.UUID.randomUUID()}", spouse).status())
            .isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(slots(spouse)).describedAs("the spouse's own list is their own").isEmpty()

        // The spouse is named, asks, and the wait passes in silence.
        post("/api/v1/households/$householdId/emergency/contacts", owner,
            mapOf("trustedMemberId" to spouseMemberId, "waitDays" to 14))
        assertThat(post("/api/v1/households/$householdId/emergency/requests", spouse,
            mapOf("subjectMemberId" to ownerMemberId, "reason" to "Hospital")).status()).isEqualTo(HttpStatus.CREATED)
        assertThat(get(path, spouse).status()).describedAs("while waiting").isEqualTo(HttpStatus.NOT_FOUND)
        db.update(
            "update emergency_requests set requested_at = now() - interval '20 days', unlock_at = now() - interval '6 days' where household_id = ?::uuid",
            householdId,
        )
        db.update(
            "update user_sessions set last_used_at = now() - interval '30 days' where user_id in (select user_id from members where household_id = ?::uuid and user_id is not null)",
            householdId,
        )

        val theirs = get(path, spouse)
        assertThat(theirs.status()).isEqualTo(HttpStatus.OK)
        val heirSlot = theirs.json().path("slots").first()
        assertThat(heirSlot.has("current")).describedAs("only the owner learns whether a copy is current").isFalse()
        assertThat(openWith(heirSlot, secret)).describedAs("the sheet opens the key for the heir").isEqualTo(contentKey)

        assertThat(putCopy(spouse, Recovery.KEY, copyBody(Recovery.KEY, r.randomBytes(21), contentKey)).status())
            .describedAs("an heir reads a copy and never replaces it")
            .isIn(HttpStatus.BAD_REQUEST, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND)
        assertThat(openWith(get(path, spouse).json().path("slots").first(), secret)).isEqualTo(contentKey)
    }

    @Test
    fun `removing a copy needs a step-up, and then it is gone`() {
        val contentKey = r.randomBytes(32)
        putKey(owner, contentKey)
        stepUp(owner)
        putCopy(owner, Recovery.SHARES, copyBody(Recovery.SHARES, r.randomBytes(21), contentKey))

        // The five minutes pass for this person's session, and nobody else's.
        val ownerUserId = db.queryForObject("select user_id::text from members where id = ?::uuid", String::class.java, ownerMemberId)
        val elevated = redis.keys("session:elevated:*").filter { redis.opsForValue().get(it) == ownerUserId }
        elevated.forEach { redis.delete(it) }
        val refused = delete("/api/v1/households/$householdId/e2e/recovery/${Recovery.SHARES}", owner)
        assertThat(refused.status()).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(refused.errorCode()).isEqualTo("step_up_required")
        assertThat(slots()).hasSize(1)

        // Confirmed again (a second code inside a minute is rate limited, so the elevation is put back).
        elevated.forEach { redis.opsForValue().set(it, ownerUserId!!, java.time.Duration.ofMinutes(5)) }
        assertThat(delete("/api/v1/households/$householdId/e2e/recovery/${Recovery.SHARES}", owner).status())
            .isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(slots()).isEmpty()
        assertThat(delete("/api/v1/households/$householdId/e2e/recovery/${Recovery.SHARES}", owner).status())
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `what is not a copy is refused, without being echoed`() {
        val contentKey = r.randomBytes(32)
        putKey(owner, contentKey)
        stepUp(owner)
        val good = copyBody(Recovery.SHARES, r.randomBytes(21), contentKey)

        fun refusal(kind: String, body: Map<String, Any>): Pair<HttpStatus, String?> {
            val response = putCopy(owner, kind, body)
            assertThat(response.body ?: "").doesNotContain("KEY WITH AMMA")
            return HttpStatus.valueOf(response.statusCode.value()) to response.errorCode()
        }

        assertThat(refusal(Recovery.SHARES, good + ("wrappedKey" to "KEY WITH AMMA in the steel almirah")))
            .isEqualTo(HttpStatus.BAD_REQUEST to "not_ciphertext")
        assertThat(refusal(Recovery.SHARES, good + ("verifier" to "KEY WITH AMMA")))
            .isEqualTo(HttpStatus.BAD_REQUEST to "not_ciphertext")
        assertThat(refusal(Recovery.SHARES, good + ("kdfSalt" to "c2hvcnQ")))
            .isEqualTo(HttpStatus.BAD_REQUEST to "not_ciphertext")
        assertThat(refusal(Recovery.SHARES, good + ("contentKeyId" to "KEY WITH AMMA")))
            .isEqualTo(HttpStatus.BAD_REQUEST to "key_id_invalid")
        assertThat(refusal(Recovery.SHARES, good + ("holders" to listOf("a", "b", "c", "d"))))
            .isEqualTo(HttpStatus.BAD_REQUEST to "holders_invalid")
        assertThat(refusal(Recovery.KEY, copyBody(Recovery.KEY, r.randomBytes(21), contentKey, listOf("Amma", "Ravi"))))
            .isEqualTo(HttpStatus.BAD_REQUEST to "holders_invalid")
        assertThat(refusal(Recovery.SHARES, good + ("holders" to listOf("x".repeat(61)))))
            .isEqualTo(HttpStatus.BAD_REQUEST to "holders_invalid")
        assertThat(refusal(Recovery.SHARES, good + ("kdf" to "PBKDF2-SHA256")))
            .isEqualTo(HttpStatus.BAD_REQUEST to "recovery_scheme_unknown")
        assertThat(refusal("passphrase", good)).isEqualTo(HttpStatus.BAD_REQUEST to "recovery_kind_invalid")
        assertThat(slots()).isEmpty()
    }

    @Test
    fun `a copy needs a passphrase first`() {
        stepUp(owner)
        val refused = putCopy(owner, Recovery.KEY, copyBody(Recovery.KEY, r.randomBytes(21), r.randomBytes(32)))
        assertThat(refused.status()).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(refused.errorCode()).isEqualTo("no_key")
    }
}
