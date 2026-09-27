package tech.almira.continuity

import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import tech.almira.support.ApiTestBase
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

/**
 * A household with an owner (Ishwarya) and a trusted contact who can sign in
 * (Ravi), for the continuity-signal suites (docs/27).
 */
abstract class ContinuitySignalsTestBase : ApiTestBase() {

    @Autowired protected lateinit var sweep: ContinuitySweep

    protected lateinit var owner: String
    protected lateinit var trusted: String
    protected lateinit var householdId: String
    protected lateinit var ownerMemberId: String
    protected lateinit var trustedMemberId: String

    @BeforeEach
    fun household() {
        owner = signIn()
        trusted = signIn()
        val household = createHousehold(owner, "Koduri", "private", "Ishwarya")
        householdId = household.path("id").asText()
        ownerMemberId = household.path("myMemberId").asText()
        trustedMemberId = addMember(owner, householdId, "Ravi").path("id").asText()
        joinHousehold(owner, householdId, trustedMemberId, trusted)
        // Noon where the household lives, so the sweep's daytime rule never
        // depends on when the suite runs. `Etc/GMT-5` is UTC+5 (POSIX signs).
        val utcHour = Instant.now().atOffset(ZoneOffset.UTC).hour
        var offset = 12 - utcHour
        if (offset < -12) offset += 24
        if (offset > 14) offset -= 24
        val zone = if (offset >= 0) "Etc/GMT-$offset" else "Etc/GMT+${-offset}"
        db.update("update households set time_zone = ? where id = ?::uuid", zone, householdId)
    }

    protected fun nameContact(waitDays: Int = 14): String = post(
        "/api/v1/households/$householdId/emergency/contacts", owner,
        mapOf("trustedMemberId" to trustedMemberId, "waitDays" to waitDays),
    ).json().path("id").asText()

    protected fun stepUp(token: String) {
        val challenge = post("/api/v1/auth/step-up/request", token).json()
        post(
            "/api/v1/auth/step-up/verify", token,
            mapOf(
                "code" to challenge.path("developmentCode").asText(),
                "requestId" to challenge.path("requestId").asText(),
            ),
        )
    }

    protected fun userOf(memberId: String): String =
        db.queryForObject("select user_id::text from members where id = ?::uuid", String::class.java, memberId)!!

    /** A token the way the server makes one, and the hash it stores. */
    protected fun newToken(): Pair<String, String> {
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val hash = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return token to hash
    }

    protected fun inAppMessages(memberId: String, template: String): Int = db.queryForObject(
        """
        select count(*) from outbound_messages
        where user_id = ?::uuid and household_id = ?::uuid and channel = 'in_app' and template = ?
        """.trimIndent(),
        Int::class.java, userOf(memberId), householdId, template,
    )!!

    protected fun audited(action: String): Int = db.queryForObject(
        "select count(*) from activity_log where household_id = ?::uuid and action = ?",
        Int::class.java, householdId, action,
    )!!
}
