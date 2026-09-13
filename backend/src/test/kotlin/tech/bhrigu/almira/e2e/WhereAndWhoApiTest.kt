package tech.bhrigu.almira.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestTemplate
import tech.bhrigu.almira.support.ApiTestBase
import java.math.BigDecimal
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * "Where is the original, and who holds the key" (docs/20).
 *
 * The owner called this the single most damaging sentence in the database if it
 * leaks, so the tests are about the negatives: the server never holds the words,
 * refuses a client that tries to hand them over, cannot move them between
 * records or fields unnoticed, never shows them past the record's visibility, and
 * does not lose them when the passphrase changes.
 *
 * The client half is done for real, as in [E2eApiTest] — PBKDF2 over
 * `Mac("HmacSHA256")` and AES-GCM with the docs/12 envelope and AAD.
 */
@DisplayName("Where the original is, and who holds the key")
class WhereAndWhoApiTest : ApiTestBase() {

    private lateinit var owner: String
    private lateinit var spouse: String
    private lateinit var householdId: String
    private lateinit var ownerMemberId: String
    private val random = SecureRandom()

    private val passphrase = "the tamarind tree by the old well"
    // No fixture title or label repeats a word from these two, so a match
    // anywhere in the database can only be one of them leaking.
    private val location = "Original in the steel almirah, second shelf"
    private val keyHolder = "Amma — the locker key at SBI Ameerpet"

    @BeforeEach
    fun setUp() {
        owner = signIn()
        spouse = signIn()
        val household = createHousehold(owner, "Koduri", "private", "Ishwarya")
        householdId = household.path("id").asText()
        ownerMemberId = household.path("myMemberId").asText()
        val spouseMemberId = addMember(owner, householdId, "Ravi").path("id").asText()
        joinHousehold(owner, householdId, spouseMemberId, spouse)
    }

    // --- the client's half (docs/12) --------------------------------------------

    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** RFC 8018 §5.2 over HMAC-SHA256, from NFC UTF-8 bytes — never `SecretKeyFactory`. */
    private fun derive(passphrase: String, salt: ByteArray, iterations: Int): SecretKey {
        val password = Normalizer.normalize(passphrase, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
        var u = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
        val out = u.copyOf()
        repeat(iterations - 1) {
            u = mac.doFinal(u)
            for (i in out.indices) out[i] = (out[i].toInt() xor u[i].toInt()).toByte()
        }
        return SecretKeySpec(out, "AES")
    }

    private fun seal(key: SecretKey, plaintext: ByteArray, aad: ByteArray?, keyVersion: Int = 1): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        aad?.let(cipher::updateAAD)
        val header = byteArrayOf(
            1, (keyVersion ushr 24).toByte(), (keyVersion ushr 16).toByte(),
            (keyVersion ushr 8).toByte(), keyVersion.toByte(),
        )
        return b64(header + iv + cipher.doFinal(plaintext))
    }

    private fun openBytes(key: SecretKey, envelope: String, aad: ByteArray?): ByteArray {
        val raw = Base64.getUrlDecoder().decode(envelope)
        require(raw.size >= 33 && raw[0].toInt() == 1) { "not an envelope" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw.copyOfRange(5, 17)))
        aad?.let(cipher::updateAAD)
        return cipher.doFinal(raw.copyOfRange(17, raw.size))
    }

    private fun open(key: SecretKey, envelope: String, aad: ByteArray?) =
        String(openBytes(key, envelope, aad), Charsets.UTF_8)

    /** household|recordType|recordId|fieldKey, uuids lowercased (docs/12 §4). */
    private fun aad(recordType: String, recordId: String, fieldKey: String, household: String = householdId) =
        "${household.lowercase()}|$recordType|${recordId.lowercase()}|$fieldKey".toByteArray(Charsets.UTF_8)

    private data class Vault(val salt: ByteArray, val contentKey: SecretKey)

    private fun enable(token: String = owner, iterations: Int = 100_000): Vault {
        val salt = ByteArray(16).also(random::nextBytes)
        val contentKey = SecretKeySpec(ByteArray(32).also(random::nextBytes), "AES")
        val response = call(
            HttpMethod.PUT, "/api/v1/households/$householdId/e2e/key", token,
            mapOf(
                "kdfSalt" to b64(salt), "iterations" to iterations,
                "wrappedKey" to seal(derive(passphrase, salt, iterations), contentKey.encoded, null),
                "verifier" to seal(contentKey, "almira".toByteArray(), null),
            ),
        )
        assertThat(response.status()).isEqualTo(HttpStatus.OK)
        return Vault(salt, contentKey)
    }

    private fun put(token: String, recordType: String, recordId: String, fieldKey: String, ciphertext: String) =
        call(
            HttpMethod.PUT,
            "/api/v1/households/$householdId/e2e/values/$recordType/$recordId/$fieldKey",
            token, mapOf("ciphertext" to ciphertext),
        )

    private fun sealBoth(key: SecretKey, recordType: String, recordId: String) {
        assertThat(
            put(owner, recordType, recordId, "original_location",
                seal(key, location.toByteArray(), aad(recordType, recordId, "original_location"))).status(),
        ).describedAs("sealing a location on a $recordType").isEqualTo(HttpStatus.OK)
        assertThat(
            put(owner, recordType, recordId, "key_holder",
                seal(key, keyHolder.toByteArray(), aad(recordType, recordId, "key_holder"))).status(),
        ).describedAs("sealing a key holder on a $recordType").isEqualTo(HttpStatus.OK)
    }

    private fun index(token: String) = get("/api/v1/households/$householdId/where-and-who", token)
        .also { assertThat(it.status()).isEqualTo(HttpStatus.OK) }
        .json().path("records")

    private fun entry(token: String, recordType: String, recordId: String) =
        index(token).firstOrNull {
            it.path("recordType").asText() == recordType && it.path("recordId").asText() == recordId
        }

    // --- fixtures: one of every record kind with a physical original -------------

    private fun investment(visibility: String) = capture(
        owner, householdId, "stock_unlisted", "Share certificates", BigDecimal("100000"),
        visibility = visibility, attributes = mapOf("company_name" to "Andhra Sugars"),
    ).path("id").asText()

    private fun liability(visibility: String) = post(
        "/api/v1/households/$householdId/liabilities", owner,
        mapOf("title" to "Home loan papers", "kind" to "home", "outstanding" to 100, "visibility" to visibility),
    ).also { check(it.statusCode.is2xxSuccessful) { it.body!! } }.json().path("id").asText()

    private fun account(visibility: String) = post(
        "/api/v1/households/$householdId/accounts", owner,
        mapOf("label" to "Bank locker", "accountKind" to "locker", "visibility" to visibility),
    ).also { check(it.statusCode.is2xxSuccessful) { it.body!! } }.json().path("id").asText()

    private fun estateDocument(visibility: String) = post(
        "/api/v1/households/$householdId/estate/documents", owner,
        mapOf("memberId" to ownerMemberId, "kind" to "will", "title" to "Ishwarya's will", "visibility" to visibility),
    ).also { check(it.statusCode.is2xxSuccessful) { it.body!! } }.json().path("id").asText()

    private val upload = RestTemplate().apply {
        errorHandler = object : org.springframework.web.client.ResponseErrorHandler {
            override fun hasError(r: org.springframework.http.client.ClientHttpResponse) = false
            override fun handleError(r: org.springframework.http.client.ClientHttpResponse) = Unit
        }
    }

    /** A document with no links is as visible as its own visibility (V20). */
    private fun document(visibility: String): String {
        val body = LinkedMultiValueMap<String, Any>().apply {
            add("file", object : ByteArrayResource("a scanned deed".toByteArray()) {
                override fun getFilename() = "deed.pdf"
            })
        }
        val headers = HttpHeaders().apply {
            contentType = MediaType.MULTIPART_FORM_DATA
            setBearerAuth(owner)
        }
        val response = upload.exchange(
            url("/api/v1/households/$householdId/documents?docType=deed&visibility=$visibility"),
            HttpMethod.POST, HttpEntity(body, headers), String::class.java,
        )
        check(response.statusCode.is2xxSuccessful) { "upload failed: ${response.body}" }
        val id = mapper.readTree(response.body).path("id").asText()
        // The upload endpoint's own visibility default is the household's; set
        // it exactly, so the test is about what it says rather than a default.
        db.update("update documents set visibility = ? where id = ?::uuid", visibility, id)
        return id
    }

    // --- the tests ----------------------------------------------------------------

    /**
     * Every record kind that has a physical original takes both fields, the
     * words are nowhere in the database — not in the sealed rows, not in the
     * audit log — and they come back through the index to the person who sealed
     * them.
     */
    @Test
    fun `every record kind takes both fields, and the words never reach the database`() {
        val vault = enable()
        val records = mapOf(
            "investment" to investment("household"),
            "liability" to liability("household"),
            "account" to account("household"),
            "document" to document("household"),
            "estate_document" to estateDocument("household"),
        )
        records.forEach { (type, id) -> sealBoth(vault.contentKey, type, id) }

        // As the schema owner, with row-level security out of the way: is any
        // fragment of either sentence anywhere it could be read?
        for (fragment in listOf("almirah", "second shelf", "Amma", "Ameerpet")) {
            assertThat(
                db.queryForObject(
                    "select count(*) from sealed_values where household_id = ?::uuid and ciphertext ilike ?",
                    Int::class.java, householdId, "%$fragment%",
                ),
            ).describedAs("'$fragment' in sealed_values").isZero()
            assertThat(
                db.queryForObject(
                    "select count(*) from activity_log where household_id = ?::uuid and diff::text ilike ?",
                    Int::class.java, householdId, "%$fragment%",
                ),
            ).describedAs("'$fragment' in the audit log").isZero()
        }

        records.forEach { (type, id) ->
            val row = entry(owner, type, id)
            assertThat(row).describedAs("$type is in the index").isNotNull
            val loc = row!!.path("originalLocation")
            val holder = row.path("keyHolder")
            assertThat(loc.path("sealedByMe").asBoolean()).isTrue()
            assertThat(open(vault.contentKey, loc.path("ciphertext").asText(), aad(type, id, "original_location")))
                .isEqualTo(location)
            assertThat(open(vault.contentKey, holder.path("ciphertext").asText(), aad(type, id, "key_holder")))
                .isEqualTo(keyHolder)
        }

        val fieldKeys = get("/api/v1/households/$householdId/where-and-who", owner).json().path("fieldKeys")
        assertThat(fieldKeys.path("originalLocation").asText()).isEqualTo("original_location")
        assertThat(fieldKeys.path("keyHolder").asText()).isEqualTo("key_holder")
    }

    /**
     * The failure this feature must not have: a client bug that sends "key with
     * Amma" where ciphertext belongs, and a server that stores it and calls it
     * sealed. Each of these is text, or text-shaped, and each is refused — and
     * the refusal never quotes what it was sent.
     */
    @Test
    fun `anything that is not shaped like an envelope is refused, without being echoed`() {
        enable()
        val id = investment("household")
        val header = byteArrayOf(1, 0, 0, 0, 1)

        val refusals = mapOf(
            "the sentence itself" to location,
            // Valid base64url, 23 bytes: slipped under the old 17-byte floor.
            "a run of letters that happens to be base64" to "KeyWithAmmaAtSBIAmeerpet1",
            // Valid base64url of 43 bytes, long enough — but it decodes to a
            // version byte that is not 1, as words almost always do.
            "a long run of letters" to "LockerAtSBIAmeerpetKeyWithAmmaSecondShelfOfTheSteelAlmirahX",
            "a header and no tag" to b64(header + ByteArray(12)),
            "32 bytes, one short of the smallest envelope" to b64(header + ByteArray(27)),
            "version 0" to b64(byteArrayOf(0, 0, 0, 0, 1) + ByteArray(28)),
            "version 2" to b64(byteArrayOf(2, 0, 0, 0, 1) + ByteArray(28)),
            "key version 0" to b64(byteArrayOf(1, 0, 0, 0, 0) + ByteArray(28)),
        )
        refusals.forEach { (what, value) ->
            val response = put(owner, "investment", id, "original_location", value)
            assertThat(response.status()).describedAs(what).isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(response.errorCode()).describedAs(what).isEqualTo("not_ciphertext")
            assertThat(response.body).describedAs("$what is not quoted back").doesNotContain(value)
        }
        assertThat(db.queryForObject(
            "select count(*) from sealed_values where record_id = ?::uuid", Int::class.java, id,
        )).isZero()

        // And the smallest legal envelope — an empty string, sealed — is a value.
        assertThat(put(owner, "investment", id, "original_location", b64(header + ByteArray(28))).status())
            .describedAs("33 bytes is the smallest envelope and must be accepted")
            .isEqualTo(HttpStatus.OK)
    }

    /**
     * Underneath the service, the table refuses a value too short to be an
     * envelope, for any write that does not go through it.
     */
    @Test
    fun `the table itself refuses a value shorter than an envelope`() {
        enable()
        val id = investment("household")
        val userId = db.queryForObject(
            "select user_id from e2e_keys where household_id = ?::uuid", UUID::class.java, householdId,
        )
        val insert = { ciphertext: String ->
            db.update(
                "insert into sealed_values (household_id, record_type, record_id, field_key, ciphertext, sealed_by) " +
                    "values (?::uuid, 'investment', ?::uuid, 'original_location', ?, ?)",
                householdId, id, ciphertext, userId,
            )
        }
        val refused = runCatching { insert("A".repeat(43)) }.exceptionOrNull()
        assertThat(refused).isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(refused!!.message).contains("ciphertext_is_at_least_an_envelope")
        assertThat(insert("A".repeat(44))).isEqualTo(1)
    }

    /**
     * The AAD binds all four of household, record type, record id and field key.
     * A location moved into the key-holder slot, onto another record, into
     * another kind of record or another household must fail to open — which is
     * the only thing that tells a working AAD from one silently ignored.
     */
    @Test
    fun `a sealed value opens only in the exact place it was sealed for`() {
        val vault = enable()
        val first = investment("household")
        val second = investment("household")
        sealBoth(vault.contentKey, "investment", first)

        val sealedLocation = entry(owner, "investment", first)!!.path("originalLocation").path("ciphertext").asText()
        assertThat(open(vault.contentKey, sealedLocation, aad("investment", first, "original_location")))
            .isEqualTo(location)

        val elsewhere = mapOf(
            "the key-holder field" to aad("investment", first, "key_holder"),
            "another record" to aad("investment", second, "original_location"),
            "another record type" to aad("liability", first, "original_location"),
            "another household" to aad("investment", first, "original_location", household = UUID.randomUUID().toString()),
            "the same place, uppercased ids" to aad("investment", first.uppercase(), "original_location"),
        )
        elsewhere.forEach { (where, boundTo) ->
            val opened = runCatching { open(vault.contentKey, sealedLocation, boundTo) }
            if (where.startsWith("the same place")) {
                // Lowercasing is part of the AAD rule, so this one must open.
                assertThat(opened.getOrNull()).describedAs(where).isEqualTo(location)
            } else {
                assertThat(opened.isFailure).describedAs("moved to $where, it must not open").isTrue()
            }
        }

        // And physically moved through the API: the location's bytes written
        // into the key-holder slot are stored (the server cannot know) and then
        // refuse to open there.
        assertThat(put(owner, "investment", first, "key_holder", sealedLocation).status()).isEqualTo(HttpStatus.OK)
        val moved = entry(owner, "investment", first)!!.path("keyHolder").path("ciphertext").asText()
        assertThat(runCatching { open(vault.contentKey, moved, aad("investment", first, "key_holder")) }.isFailure)
            .describedAs("a location swapped into the key-holder field must not read as a key holder")
            .isTrue()
    }

    /**
     * The fields follow the record's visibility. Another member of the same
     * household must not learn that a private record has a location — not from
     * the index, not from the raw values list, and not by trying to write one.
     */
    @Test
    fun `another member never sees where a private record's original is`() {
        val vault = enable()
        val privateRecords = mapOf(
            "investment" to investment("private"),
            "liability" to liability("private"),
            "account" to account("private"),
            "document" to document("private"),
            "estate_document" to estateDocument("private"),
        )
        privateRecords.forEach { (type, id) -> sealBoth(vault.contentKey, type, id) }
        val shared = investment("household")
        sealBoth(vault.contentKey, "investment", shared)

        privateRecords.forEach { (type, id) ->
            assertThat(entry(owner, type, id)).describedAs("the owner sees their private $type").isNotNull
            assertThat(entry(spouse, type, id)).describedAs("the spouse does not see the private $type").isNull()
        }
        val theirValues = get("/api/v1/households/$householdId/e2e/values", spouse).json()
        assertThat(theirValues.map { it.path("recordId").asText() })
            .describedAs("no sealed value of a private record reaches another member")
            .doesNotContainAnyElementsOf(privateRecords.values)
            .contains(shared)

        // Writing to it is refused as though it did not exist.
        enable(token = spouse)
        val probe = put(spouse, "investment", privateRecords.getValue("investment"), "original_location",
            b64(byteArrayOf(1, 0, 0, 0, 1) + ByteArray(28)))
        assertThat(probe.status()).isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN)
        assertThat(probe.errorCode()).isNotEqualTo("sealed_by_someone_else")
    }

    /**
     * On a record both can see, the other member sees that a location exists,
     * is told plainly it is not theirs to open, and cannot overwrite it — they
     * never held the key it was sealed under, so they could not know what they
     * would be destroying.
     */
    @Test
    fun `on a shared record the other member sees presence, not content, and cannot overwrite it`() {
        val vault = enable()
        val shared = investment("household")
        sealBoth(vault.contentKey, "investment", shared)

        val theirs = entry(spouse, "investment", shared)
        assertThat(theirs).isNotNull
        assertThat(theirs!!.path("originalLocation").path("sealedByMe").asBoolean()).isFalse()
        assertThat(theirs.path("keyHolder").path("sealedByMe").asBoolean()).isFalse()

        val spouseVault = enable(token = spouse)
        assertThat(
            runCatching {
                open(spouseVault.contentKey, theirs.path("originalLocation").path("ciphertext").asText(),
                    aad("investment", shared, "original_location"))
            }.isFailure,
        ).describedAs("another member's content key does not open it").isTrue()

        val overwrite = put(spouse, "investment", shared, "original_location",
            seal(spouseVault.contentKey, "somewhere else".toByteArray(), aad("investment", shared, "original_location")))
        assertThat(overwrite.status()).isEqualTo(HttpStatus.CONFLICT)
        assertThat(overwrite.errorCode()).isEqualTo("sealed_by_someone_else")
        assertThat(open(vault.contentKey,
            entry(owner, "investment", shared)!!.path("originalLocation").path("ciphertext").asText(),
            aad("investment", shared, "original_location")))
            .describedAs("the owner's value is untouched")
            .isEqualTo(location)
    }

    /**
     * Rotation goes through the existing `/e2e/key` path and must carry both
     * fields: not one byte of either is rewritten, the new passphrase recovers
     * the same content key, both still open, and the old passphrase no longer
     * unwraps anything.
     */
    @Test
    fun `changing the passphrase carries both fields, byte for byte`() {
        val vault = enable()
        val gold = investment("private")
        val will = estateDocument("private")
        sealBoth(vault.contentKey, "investment", gold)
        sealBoth(vault.contentKey, "estate_document", will)

        val storedBefore = db.queryForList(
            "select record_id::text as rid, field_key, ciphertext, key_version from sealed_values " +
                "where household_id = ?::uuid order by 1, 2",
            householdId,
        )
        assertThat(storedBefore).hasSize(4)

        val newPassphrase = "a new sentence, ఖజానా "
        val newSalt = ByteArray(16).also(random::nextBytes)
        val rotated = call(
            HttpMethod.PUT, "/api/v1/households/$householdId/e2e/key", owner,
            mapOf(
                "kdfSalt" to b64(newSalt), "iterations" to 100_000,
                "wrappedKey" to seal(derive(newPassphrase, newSalt, 100_000), vault.contentKey.encoded, null, keyVersion = 2),
                "verifier" to seal(vault.contentKey, "almira".toByteArray(), null, keyVersion = 2),
                "keyVersion" to 2,
            ),
        )
        assertThat(rotated.status()).isEqualTo(HttpStatus.OK)

        val storedAfter = db.queryForList(
            "select record_id::text as rid, field_key, ciphertext, key_version from sealed_values " +
                "where household_id = ?::uuid order by 1, 2",
            householdId,
        )
        assertThat(storedAfter).describedAs("rotation rewrites no sealed field").isEqualTo(storedBefore)

        // Unlock the way a client would after rotating: stored salt, new passphrase.
        val key = get("/api/v1/households/$householdId/e2e", owner).json().path("key")
        assertThat(key.path("keyVersion").asInt()).isEqualTo(2)
        val storedSalt = Base64.getUrlDecoder().decode(key.path("kdfSalt").asText())
        val recovered = SecretKeySpec(
            openBytes(derive(newPassphrase, storedSalt, key.path("iterations").asInt()), key.path("wrappedKey").asText(), null),
            "AES",
        )
        assertThat(
            runCatching { openBytes(derive(passphrase, storedSalt, 100_000), key.path("wrappedKey").asText(), null) }.isFailure,
        ).describedAs("the old passphrase no longer opens the vault").isTrue()

        for ((type, id) in listOf("investment" to gold, "estate_document" to will)) {
            val row = entry(owner, type, id)!!
            assertThat(open(recovered, row.path("originalLocation").path("ciphertext").asText(), aad(type, id, "original_location")))
                .describedAs("the $type location opens under the new passphrase").isEqualTo(location)
            assertThat(open(recovered, row.path("keyHolder").path("ciphertext").asText(), aad(type, id, "key_holder")))
                .describedAs("the $type key holder opens under the new passphrase").isEqualTo(keyHolder)
            assertThat(row.path("originalLocation").path("keyVersion").asInt())
                .describedAs("and still says what wrote it").isEqualTo(1)
        }
    }

    /**
     * What the server may reveal is presence, which a readiness score will need,
     * and never content. A record with nothing recorded is in the index with
     * both slots empty.
     */
    @Test
    fun `the index says which records have a location recorded, and nothing about what it says`() {
        val vault = enable()
        val recorded = investment("household")
        val bare = liability("household")
        put(owner, "investment", recorded, "original_location",
            seal(vault.contentKey, location.toByteArray(), aad("investment", recorded, "original_location")))

        val withLocation = entry(owner, "investment", recorded)!!
        assertThat(withLocation.path("originalLocation").isObject).isTrue()
        assertThat(withLocation.path("keyHolder").let { it.isNull || it.isMissingNode }).isTrue()
        assertThat(withLocation.path("title").asText()).isEqualTo("Share certificates")

        val without = entry(owner, "liability", bare)!!
        assertThat(without.path("originalLocation").let { it.isNull || it.isMissingNode }).isTrue()
        assertThat(without.path("keyHolder").let { it.isNull || it.isMissingNode }).isTrue()

        assertThat(get("/api/v1/households/$householdId/where-and-who", owner).body)
            .doesNotContain("almirah").doesNotContain("Amma")

        // One record's own screen asks for just its entry.
        val one = get(
            "/api/v1/households/$householdId/where-and-who?recordType=investment&recordId=$recorded", owner,
        ).json().path("records")
        assertThat(one.map { it.path("recordId").asText() }).containsExactly(recorded)
        assertThat(
            get("/api/v1/households/$householdId/where-and-who?recordType=investment&recordId=$recorded", spouse)
                .json().path("records").map { it.path("recordId").asText() },
        ).describedAs("the filter is still under the spouse's visibility").containsExactly(recorded)
    }
}
