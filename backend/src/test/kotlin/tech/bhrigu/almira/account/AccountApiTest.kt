package tech.bhrigu.almira.account

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import tech.bhrigu.almira.support.ApiTestBase
import java.util.UUID

/**
 * Accounts are the first records that hold a real bank or demat number, so the
 * tests are as much about what does NOT come back as what does.
 */
@DisplayName("Accounts and the numbers they hold")
class AccountApiTest : ApiTestBase() {

    private lateinit var owner: String
    private lateinit var spouse: String
    private lateinit var householdId: String
    private lateinit var ownerMemberId: String
    private lateinit var spouseMemberId: String

    private val realNumber = "50100234567890"

    @BeforeEach
    fun setUp() {
        owner = signIn()
        spouse = signIn()
        val household = createHousehold(owner, "Koduri", "private", "Ishwarya")
        householdId = household.path("id").asText()
        ownerMemberId = household.path("myMemberId").asText()
        spouseMemberId = addMember(owner, householdId, "Ravi").path("id").asText()
        joinHousehold(owner, householdId, spouseMemberId, spouse, role = "admin")
    }

    private fun createAccount(
        token: String = owner,
        label: String = "SBI savings",
        number: String? = realNumber,
        storeFullNumber: Boolean = false,
        visibility: String = "private",
        holders: List<Map<String, Any>> = emptyList(),
    ) = post(
        "/api/v1/households/$householdId/accounts", token,
        buildMap {
            put("label", label)
            put("accountKind", "savings")
            put("visibility", visibility)
            if (number != null) put("number", number)
            put("storeFullNumber", storeFullNumber)
            if (holders.isNotEmpty()) put("holders", holders)
        },
    )

    // --- what is stored -------------------------------------------------------

    @Test
    fun `by default only the last four digits are kept`() {
        val account = createAccount(storeFullNumber = false).json()

        assertThat(account.path("numberMasked").asText()).isEqualTo("••••7890")
        assertThat(account.path("hasFullNumber").asBoolean())
            .describedAs("the minimum by default — the rest is not worth the risk unasked")
            .isFalse()

        val stored = db.queryForObject(
            "select number_enc is null from accounts where id = ?",
            Boolean::class.java, UUID.fromString(account.path("id").asText()),
        )
        assertThat(stored).describedAs("nothing beyond the mask reached the database").isTrue()
    }

    @Test
    fun `opting in stores the number encrypted, never as text`() {
        val account = createAccount(storeFullNumber = true).json()
        assertThat(account.path("hasFullNumber").asBoolean()).isTrue()

        val bytes = db.queryForObject(
            "select number_enc from accounts where id = ?",
            ByteArray::class.java, UUID.fromString(account.path("id").asText()),
        )!!

        // The exact assertion that matters: the digits are not in the column.
        assertThat(String(bytes, Charsets.ISO_8859_1)).doesNotContain(realNumber)
        assertThat(String(bytes, Charsets.ISO_8859_1)).doesNotContain("7890")
        assertThat(bytes.size).isGreaterThan(realNumber.length)
    }

    @Test
    fun `no response body ever carries the full number`() {
        createAccount(storeFullNumber = true)

        val listBody = get("/api/v1/households/$householdId/accounts", owner).body!!
        val detailId = get("/api/v1/households/$householdId/accounts", owner).json()[0].path("id").asText()
        val detailBody = get("/api/v1/households/$householdId/accounts/$detailId", owner).body!!

        assertThat(listBody).doesNotContain(realNumber)
        assertThat(detailBody).doesNotContain(realNumber)
        assertThat(listBody).contains("••••7890")
    }

    // --- revealing it ---------------------------------------------------------

    @Test
    fun `revealing the number needs a fresh confirmation of who you are`() {
        val id = createAccount(storeFullNumber = true).json().path("id").asText()

        val refused = post("/api/v1/households/$householdId/accounts/$id/reveal-number", owner)
        assertThat(refused.status()).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(refused.errorCode()).isEqualTo("step_up_required")
    }

    @Test
    fun `after confirming, the number comes back and the view is recorded`() {
        val id = createAccount(storeFullNumber = true).json().path("id").asText()

        val challenge = post("/api/v1/auth/step-up/request", owner).json()
        post(
            "/api/v1/auth/step-up/verify", owner,
            mapOf("code" to challenge.path("developmentCode").asText(),
                  "requestId" to challenge.path("requestId").asText()),
        )

        val revealed = post("/api/v1/households/$householdId/accounts/$id/reveal-number", owner)
        assertThat(revealed.status()).isEqualTo(HttpStatus.OK)
        assertThat(revealed.json().path("number").asText()).isEqualTo(realNumber)

        val audited = db.queryForObject(
            "select count(*) from activity_log " +
                "where action = 'account.number_revealed' and entity_id = ?",
            Int::class.java, UUID.fromString(id),
        )
        assertThat(audited).describedAs("which number was looked at, not just that a step-up happened")
            .isEqualTo(1)
    }

    /**
     * Elevation belongs to a session. Confirming on one device must not unlock
     * a different one — which is the whole reason a long-lived session is not
     * enough on its own.
     */
    @Test
    fun `confirming on one session does not elevate another`() {
        val id = createAccount(storeFullNumber = true, visibility = "household").json()
            .path("id").asText()

        val challenge = post("/api/v1/auth/step-up/request", owner).json()
        post(
            "/api/v1/auth/step-up/verify", owner,
            mapOf("code" to challenge.path("developmentCode").asText(),
                  "requestId" to challenge.path("requestId").asText()),
        )
        assertThat(post("/api/v1/households/$householdId/accounts/$id/reveal-number", owner).status())
            .isEqualTo(HttpStatus.OK)

        // The spouse can see the account, but has confirmed nothing.
        assertThat(post("/api/v1/households/$householdId/accounts/$id/reveal-number", spouse).errorCode())
            .isEqualTo("step_up_required")
    }

    @Test
    fun `a step-up can be requested straight after signing in`() {
        // Login and step-up share a phone but not a cooldown: colliding on one
        // would refuse a legitimate confirmation seconds after sign-in, and a
        // step-up code would silently invalidate a login code.
        val response = post("/api/v1/auth/step-up/request", owner)
        assertThat(response.status()).isEqualTo(HttpStatus.OK)
        assertThat(response.json().path("developmentCode").asText()).isNotBlank()
    }

    @Test
    fun `an account that kept only the mask has nothing to reveal`() {
        val id = createAccount(storeFullNumber = false).json().path("id").asText()
        val challenge = post("/api/v1/auth/step-up/request", owner).json()
        post(
            "/api/v1/auth/step-up/verify", owner,
            mapOf("code" to challenge.path("developmentCode").asText(),
                  "requestId" to challenge.path("requestId").asText()),
        )
        assertThat(post("/api/v1/households/$householdId/accounts/$id/reveal-number", owner).errorCode())
            .isEqualTo("no_full_number")
    }

    // --- privacy --------------------------------------------------------------

    @Test
    fun `a private account is invisible to an admin, mask included`() {
        val id = createAccount(visibility = "private", storeFullNumber = true).json()
            .path("id").asText()

        assertThat(get("/api/v1/households/$householdId/accounts/$id", spouse).status())
            .isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(get("/api/v1/households/$householdId/accounts", spouse).json()).isEmpty()
    }

    @Test
    fun `a joint holder always sees the account, even marked private`() {
        val id = createAccount(
            visibility = "private",
            holders = listOf(
                mapOf("memberId" to ownerMemberId, "holderType" to "joint"),
                mapOf("memberId" to spouseMemberId, "holderType" to "joint"),
            ),
        ).json().path("id").asText()

        assertThat(get("/api/v1/households/$householdId/accounts/$id", spouse).status())
            .describedAs("you cannot hide a joint account from your co-holder")
            .isEqualTo(HttpStatus.OK)
    }

    // --- linkage --------------------------------------------------------------

    @Test
    fun `an account in use cannot be removed out from under its holdings`() {
        val accountId = createAccount(visibility = "household").json().path("id").asText()
        post(
            "/api/v1/households/$householdId/investments", owner,
            mapOf(
                "typeId" to typeId(owner, householdId, "fd"),
                "title" to "SBI FD", "investedAmount" to 100000,
                "visibility" to "household", "accountId" to accountId,
                "attributes" to mapOf("interest_rate" to "7.1"),
            ),
        )

        val refused = delete("/api/v1/households/$householdId/accounts/$accountId", owner)
        assertThat(refused.status()).isEqualTo(HttpStatus.CONFLICT)
        assertThat(refused.errorCode()).isEqualTo("account_in_use")

        assertThat(get("/api/v1/households/$householdId/accounts/$accountId", owner).json()
            .path("linkedInvestmentCount").asInt()).isEqualTo(1)
    }

    @Test
    fun `replacing the number without opting in clears the stored one`() {
        val created = createAccount(storeFullNumber = true).json()
        val id = created.path("id").asText()
        assertThat(created.path("hasFullNumber").asBoolean()).isTrue()

        val response = patch(
            "/api/v1/households/$householdId/accounts/$id", owner,
            mapOf("version" to created.path("version").asInt(),
                  "number" to "50100299998888", "storeFullNumber" to false),
        )
        assertThat(response.status()).describedAs(response.body).isEqualTo(HttpStatus.OK)
        val updated = response.json()

        assertThat(updated.path("numberMasked").asText()).isEqualTo("••••8888")
        assertThat(updated.path("hasFullNumber").asBoolean())
            .describedAs("the old ciphertext must not linger and disagree with the mask")
            .isFalse()
    }
}
