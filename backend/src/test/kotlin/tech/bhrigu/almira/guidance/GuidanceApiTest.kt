package tech.bhrigu.almira.guidance

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import tech.bhrigu.almira.support.ApiTestBase
import java.math.BigDecimal

/**
 * The first session over HTTP: the readiness check, the shelves, the welcome a
 * second family member gets, and checklists that tick themselves. Every count
 * here must agree with the visibility rules, so each is checked from the side
 * of someone who should not see a record as well as someone who should.
 */
@DisplayName("First session: readiness check, shelves, welcome, checklists")
class GuidanceApiTest : ApiTestBase() {

    private fun put(path: String, token: String, body: Any) = call(HttpMethod.PUT, path, token, body)

    // --- readiness check -----------------------------------------------------

    @Test
    fun `the readiness check works before a household exists and names the three most important gaps`() {
        val token = signIn()
        assertThat(get("/api/v1/me/readiness-check", token).json().path("answered").asBoolean()).isFalse()

        val answered = put(
            "/api/v1/me/readiness-check", token,
            mapOf(
                "answers" to mapOf(
                    "will" to "not_sure", "nominees" to "yes", "papers" to "no", "second_person" to "no",
                    "one_list" to "partly", "insurance" to "yes", "loans" to "no", "locker" to "no",
                ),
            ),
        )
        assertThat(answered.status()).isEqualTo(HttpStatus.OK)
        val body = answered.json()
        // will 10×0.8=8, papers 8×1=8 (will first: earlier), second_person 7, loans 5, locker 4, one_list 3
        assertThat(body.path("gaps").map { it.path("question").asText() })
            .containsExactly("will", "papers", "second_person")
        assertThat(body.path("gaps")[0].path("shelf").asText()).isEqualTo("will")
        assertThat(body.path("readyCount").asInt()).isEqualTo(2)

        // Stored: read back the same.
        assertThat(get("/api/v1/me/readiness-check", token).json().path("gaps").size()).isEqualTo(3)
    }

    @Test
    fun `someone else's answers are nobody else's, and nonsense is refused`() {
        val ish = signIn()
        put("/api/v1/me/readiness-check", ish, mapOf("answers" to mapOf("will" to "no")))
        val ravi = signIn()
        assertThat(get("/api/v1/me/readiness-check", ravi).json().path("answered").asBoolean()).isFalse()

        val unknown = put("/api/v1/me/readiness-check", ravi, mapOf("answers" to mapOf("net_worth" to "yes")))
        assertThat(unknown.status()).isEqualTo(HttpStatus.BAD_REQUEST)
        val badAnswer = put("/api/v1/me/readiness-check", ravi, mapOf("answers" to mapOf("will" to "maybe")))
        assertThat(badAnswer.status()).isEqualTo(HttpStatus.BAD_REQUEST)
        val empty = put("/api/v1/me/readiness-check", ravi, mapOf("answers" to emptyMap<String, String>()))
        assertThat(empty.status()).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    // --- shelves -------------------------------------------------------------

    @Test
    fun `a shelf ticks itself from a record, and a skipped shelf leaves the ring`() {
        val token = signIn()
        val hid = createHousehold(token).path("id").asText()
        val start = get("/api/v1/households/$hid/first-session", token).json()
        assertThat(start.path("total").asInt()).isEqualTo(15)
        assertThat(start.path("done").asInt()).isZero()
        assertThat(start.path("shelves")[0].path("code").asText()).isEqualTo("savings_account")
        assertThat(start.path("shelves")[14].path("code").asText()).isEqualTo("will")

        capture(token, hid, "fd", "SBI FD", BigDecimal("100000"), attributes = mapOf("interest_rate" to "7.1"))
        val skipped = put(
            "/api/v1/households/$hid/first-session", token,
            mapOf("skippedShelves" to listOf("epf", "nps", "shares", "small_savings", "locker")),
        ).json()
        val fd = skipped.path("shelves").first { it.path("code").asText() == "fixed_deposit" }
        assertThat(fd.path("done").asBoolean()).isTrue()
        assertThat(skipped.path("skipped").asInt()).isEqualTo(5)
        assertThat(skipped.path("percent").asInt()).isEqualTo(10) // 1 of 10

        val bad = put("/api/v1/households/$hid/first-session", token, mapOf("skippedShelves" to listOf("yacht")))
        assertThat(bad.status()).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `setting up for a parent counts only her records, and a stranger gets 404`() {
        val token = signIn()
        val household = createHousehold(token)
        val hid = household.path("id").asText()
        val amma = addMember(token, hid, "Amma").path("id").asText()

        val forAmma = put(
            "/api/v1/households/$hid/first-session", token,
            mapOf("settingUpFor" to "someone", "someoneMemberId" to amma),
        ).json()
        assertThat(forAmma.path("someoneName").asText()).isEqualTo("Amma")
        assertThat(forAmma.path("someoneCanBeInvited").asBoolean()).isTrue()

        capture(token, hid, "gold_jewelry", "My bangles", BigDecimal("50000"))
        fun goldDone() = get("/api/v1/households/$hid/first-session", token).json()
            .path("shelves").first { it.path("code").asText() == "gold" }.path("done").asBoolean()
        assertThat(goldDone()).describedAs("my gold is not Amma's").isFalse()

        // Amma's own record. Only a holder may grant scoped sight (V8), so a
        // helper records it shared with the household in order to read it back.
        capture(
            token, hid, "gold_jewelry", "Amma's necklace", BigDecimal("150000"), visibility = "household",
            owners = listOf(mapOf("memberId" to amma, "sharePct" to 100)),
        )
        assertThat(goldDone()).isTrue()

        val stranger = signIn()
        createHousehold(stranger)
        assertThat(get("/api/v1/households/$hid/first-session", stranger).status()).isEqualTo(HttpStatus.NOT_FOUND)
        val otherHid = createHousehold(stranger).path("id").asText()
        val foreign = put(
            "/api/v1/households/$otherHid/first-session", stranger,
            mapOf("settingUpFor" to "someone", "someoneMemberId" to amma),
        )
        assertThat(foreign.status()).describedAs("a member of another household is not found").isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `a shelf never ticks from someone else's private record`() {
        val ish = signIn()
        val hid = createHousehold(ish).path("id").asText()
        val raviMember = addMember(ish, hid, "Ravi").path("id").asText()
        val ravi = signIn()
        joinHousehold(ish, hid, raviMember, ravi, role = "admin")

        capture(ish, hid, "ppf", "Ish PPF", BigDecimal("150000"), visibility = "private")
        fun ppfDone(token: String) = get("/api/v1/households/$hid/first-session", token).json()
            .path("shelves").first { it.path("code").asText() == "ppf" }.path("done").asBoolean()
        assertThat(ppfDone(ish)).isTrue()
        assertThat(ppfDone(ravi)).describedAs("an admin cannot see a private PPF, so it is not on his shelf").isFalse()
    }

    // --- welcome ---------------------------------------------------------------

    @Test
    fun `the welcome counts what each side sees from the real visibility rules`() {
        val ish = signIn()
        val hid = createHousehold(ish, name = "Koduri", displayName = "Ishwarya").path("id").asText()
        val raviMember = addMember(ish, hid, "Ravi").path("id").asText()
        capture(ish, hid, "fd", "Shared FD", BigDecimal("100000"), visibility = "household",
            attributes = mapOf("interest_rate" to "7"))
        capture(ish, hid, "fd", "Secret FD", BigDecimal("500000"), visibility = "private",
            attributes = mapOf("interest_rate" to "7"))

        assertThat(get("/api/v1/households/$hid/welcome", ish).json().path("due").asBoolean())
            .describedAs("the owner is not welcomed to her own household").isFalse()

        val ravi = signIn()
        joinHousehold(ish, hid, raviMember, ravi, role = "admin")
        capture(ravi, hid, "ppf", "Ravi PPF", BigDecimal("20000"), visibility = "private")
        capture(ravi, hid, "gold_physical", "Ravi coins", BigDecimal("30000"), visibility = "household")

        val welcome = get("/api/v1/households/$hid/welcome", ravi).json()
        assertThat(welcome.path("householdName").asText()).isEqualTo("Koduri")
        assertThat(welcome.path("ownerName").asText()).isEqualTo("Ishwarya")
        assertThat(welcome.path("due").asBoolean()).isTrue()
        assertThat(welcome.path("youWillSee").path("count").asInt()).isEqualTo(1)
        assertThat(welcome.path("youWillSee").path("preview").map { it.path("title").asText() })
            .containsExactly("Shared FD")
        assertThat(welcome.path("staysYours").path("count").asInt()).isEqualTo(1)
        assertThat(welcome.path("theyWillSeeOfYours").path("preview").map { it.path("title").asText() })
            .containsExactly("Ravi coins")

        assertThat(post("/api/v1/households/$hid/welcome/seen", ravi).status()).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(get("/api/v1/households/$hid/welcome", ravi).json().path("due").asBoolean()).isFalse()

        val stranger = signIn()
        assertThat(get("/api/v1/households/$hid/welcome", stranger).status()).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(post("/api/v1/households/$hid/welcome/seen", stranger).status()).isEqualTo(HttpStatus.NOT_FOUND)
    }

    // --- checklists ------------------------------------------------------------

    @Test
    fun `checklists tick themselves from the household's records`() {
        val token = signIn()
        val hid = createHousehold(token).path("id").asText()
        fun item(list: String, code: String) = get("/api/v1/households/$hid/guidance/checklists", token).json()
            .first { it.path("code").asText() == list }
            .path("items").first { it.path("code").asText() == code }.path("done").asBoolean()

        assertThat(item("getting_started", "first_record")).isFalse()
        assertThat(item("getting_started", "readiness_check")).isFalse()
        capture(token, hid, "fd", "FD", BigDecimal("1000"), attributes = mapOf("interest_rate" to "7"))
        put("/api/v1/me/readiness-check", token, mapOf("answers" to mapOf("will" to "yes")))
        assertThat(item("getting_started", "first_record")).isTrue()
        assertThat(item("getting_started", "readiness_check")).isTrue()
        assertThat(item("for_the_family", "will")).isFalse()

        assertThat(get("/api/v1/households/$hid/guidance/checklists", signIn()).status())
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    // --- contact us ------------------------------------------------------------

    @Test
    fun `contact us is off unless configured`() {
        val body = get("/api/v1/support/contact", signIn()).json()
        assertThat(body.path("configured").asBoolean()).isFalse()
        assertThat(body.path("link").isNull || body.path("link").isMissingNode).isTrue()
    }
}
