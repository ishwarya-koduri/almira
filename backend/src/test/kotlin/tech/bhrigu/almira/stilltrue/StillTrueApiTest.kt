package tech.bhrigu.almira.stilltrue

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tech.bhrigu.almira.support.ApiTestBase
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.sql.DataSource

/**
 * "Still true?" through the API, as each person sees it (docs/21).
 *
 * Records are aged by moving their creation back on the owner connection. That
 * is a claim about stored time, not about visibility, and every read that asks
 * "is this person asked?" still goes through the API.
 */
@DisplayName("Still true? — records that come back to be confirmed")
class StillTrueApiTest : ApiTestBase() {

    @Autowired private lateinit var runtimeDataSource: DataSource

    private lateinit var owner: String
    private lateinit var spouse: String
    private lateinit var householdId: String
    private lateinit var ownerMemberId: String
    private lateinit var spouseMemberId: String

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

    private fun base() = "/api/v1/households/$householdId/still-true"

    private fun due(token: String) = get(base(), token).json().path("items")

    private fun dueIds(token: String) = due(token).map { it.path("recordId").asText() }

    private fun age(table: String, id: String, months: Int) {
        val extra = if (table == "investments") ", last_verified_at = null" else ""
        db.update(
            "update $table set created_at = now() - make_interval(months => ?) $extra where id = ?::uuid",
            months, id,
        )
        // The record's history ages with it, so a later action stands out as later.
        db.update(
            "update activity_log set created_at = now() - make_interval(months => ?) where entity_id = ?::uuid",
            months, id,
        )
        if (table == "liabilities") {
            db.update(
                """
                update liability_balances
                   set created_at = now() - make_interval(months => ?),
                       as_of_date = (now() - make_interval(months => ?))::date
                 where liability_id = ?::uuid
                """.trimIndent(),
                months, months, id,
            )
        }
    }

    private fun fd(title: String = "SBI FD", visibility: String = "private") =
        capture(
            owner, householdId, "fd", title, BigDecimal(100_000), visibility = visibility,
            attributes = mapOf("interest_rate" to 7.1),
        ).path("id").asText()

    private fun loan(kind: String = "home"): String {
        val response = post(
            "/api/v1/households/$householdId/liabilities", owner,
            mapOf("title" to "Loan $kind", "kind" to kind, "outstanding" to 500_000),
        )
        check(response.statusCode.is2xxSuccessful) { "loan: ${response.body}" }
        return response.json().path("id").asText()
    }

    /** Null when absent: the API leaves null fields out rather than sending them. */
    private fun lastVerifiedAt(id: String): String? =
        get("/api/v1/households/$householdId/investments/$id", owner).json().path("lastVerifiedAt")
            .takeUnless { it.isMissingNode || it.isNull }?.asText()

    private fun today(): LocalDate = LocalDate.now(ZoneId.of("Asia/Kolkata"))

    // --- when a record comes back --------------------------------------------

    @Test
    fun `a record nobody has confirmed in twelve months comes back, and a new one does not`() {
        val old = fd("Old FD")
        val fresh = fd("New FD")
        age("investments", old, 13)

        val items = due(owner)
        assertThat(items.map { it.path("recordId").asText() }).contains(old).doesNotContain(fresh)
        val item = items.first { it.path("recordId").asText() == old }
        assertThat(item.path("recordType").asText()).isEqualTo("investment")
        assertThat(item.path("periodMonths").asInt()).isEqualTo(12)
        assertThat(item.path("reason").asText()).isEqualTo("period")
        assertThat(item.path("title").asText()).isEqualTo("Old FD")
    }

    @Test
    fun `confirming answers it for a year, and the holding says it was confirmed`() {
        val id = fd()
        age("investments", id, 13)

        val confirmed = post("${base()}/investment/$id/confirm", owner)
        assertThat(confirmed.status()).isEqualTo(HttpStatus.OK)
        assertThat(confirmed.json().path("isDue").asBoolean()).isFalse()
        assertThat(LocalDate.parse(confirmed.json().path("dueOn").asText()))
            .isEqualTo(today().plusMonths(12))
        assertThat(dueIds(owner)).doesNotContain(id)

        assertThat(lastVerifiedAt(id))
            .describedAs("the record's own 'Last confirmed' line moves with the answer")
            .isNotNull()
    }

    @Test
    fun `a value refresh counts as confirming, and a visibility change does not`() {
        val refreshed = fd("Refreshed")
        val reshared = fd("Reshared")
        age("investments", refreshed, 13)
        age("investments", reshared, 13)

        post(
            "/api/v1/households/$householdId/investments/$refreshed/valuations", owner,
            mapOf("value" to 107_100),
        )
        val visibility = patch(
            "/api/v1/households/$householdId/investments/$reshared/visibility", owner,
            mapOf("visibility" to "household"),
        )
        assertThat(visibility.status()).isEqualTo(HttpStatus.OK)

        assertThat(dueIds(owner))
            .describedAs("someone who looked at a statement has checked it; someone who shared it has not")
            .doesNotContain(refreshed)
            .contains(reshared)
    }

    @Test
    fun `recording a loan balance counts as confirming it`() {
        val id = loan()
        age("liabilities", id, 7)
        assertThat(dueIds(owner)).contains(id)

        post(
            "/api/v1/households/$householdId/liabilities/$id/balances", owner,
            mapOf("outstanding" to 480_000),
        )
        assertThat(dueIds(owner)).doesNotContain(id)
    }

    @Test
    fun `each kind of record keeps its own period`() {
        val cash = capture(owner, householdId, "savings_buffer", "Buffer", BigDecimal(50_000)).path("id").asText()
        val deposit = fd("Four-month FD")
        val homeLoan = loan("home")
        val card = loan("credit_card")
        val account = post(
            "/api/v1/households/$householdId/accounts", owner,
            mapOf("label" to "SBI savings", "accountKind" to "savings", "visibility" to "private"),
        ).json().path("id").asText()
        val will = post(
            "/api/v1/households/$householdId/estate/documents", owner,
            mapOf("memberId" to ownerMemberId, "kind" to "will", "title" to "Will"),
        ).json().path("id").asText()

        age("investments", cash, 4)
        age("investments", deposit, 4)
        age("liabilities", homeLoan, 4)
        age("liabilities", card, 4)
        age("accounts", account, 13)
        age("estate_documents", will, 13)

        val periods = due(owner).associate { it.path("recordId").asText() to it.path("periodMonths").asInt() }
        assertThat(periods).containsEntry(cash, 3).containsEntry(card, 3).containsEntry(account, 12)
        assertThat(periods.keys)
            .describedAs("an FD at four months, a home loan at four months and a will at thirteen are not due")
            .doesNotContain(deposit, homeLoan, will)

        age("liabilities", homeLoan, 7)
        age("estate_documents", will, 25)
        val later = due(owner).associate { it.path("recordId").asText() to it.path("periodMonths").asInt() }
        assertThat(later).containsEntry(homeLoan, 6).containsEntry(will, 24)
    }

    @Test
    fun `a maturity since the last confirmation brings it back a week later, on no flat clock`() {
        val matured = fd("Matured FD")
        val soon = fd("Maturing FD")
        // Set as stored facts: the capture form would refuse a maturity in the past.
        db.update("update investments set maturity_date = ? where id = ?::uuid", today().minusDays(10), matured)
        db.update("update investments set maturity_date = ? where id = ?::uuid", today().minusDays(3), soon)
        // Created before the maturity, so the maturity is news.
        age("investments", matured, 2)
        age("investments", soon, 2)

        val items = due(owner)
        val item = items.first { it.path("recordId").asText() == matured }
        assertThat(item.path("reason").asText()).isEqualTo("key_date")
        assertThat(item.path("dueOn").asText()).isEqualTo(today().minusDays(3).toString())
        assertThat(items.map { it.path("recordId").asText() })
            .describedAs("three days after maturity is inside the week's grace")
            .doesNotContain(soon)

        post("${base()}/investment/$matured/confirm", owner)
        assertThat(dueIds(owner))
            .describedAs("a maturity already answered is not asked about again")
            .doesNotContain(matured)
    }

    @Test
    fun `a renewal date typed as nonsense does not take the list down`() {
        val id = fd("Odd FD")
        db.update(
            "update investments set attributes = attributes || '{\"renewal_date\":\"2026-02-31\"}' where id = ?::uuid",
            id,
        )
        age("investments", id, 13)
        assertThat(get(base(), owner).status()).isEqualTo(HttpStatus.OK)
        assertThat(dueIds(owner)).contains(id)
    }

    // --- who is asked ----------------------------------------------------------

    @Test
    fun `only the owner is asked, even when the household can see the record`() {
        val shared = fd("Shared FD", visibility = "household")
        val private = fd("Private FD")
        age("investments", shared, 13)
        age("investments", private, 13)

        assertThat(get("/api/v1/households/$householdId/investments/$shared", spouse).status())
            .isEqualTo(HttpStatus.OK)
        assertThat(dueIds(spouse)).doesNotContain(shared, private)
        assertThat(post("${base()}/investment/$shared/confirm", spouse).status())
            .describedAs("seeing a record is not knowing whether it is still in force")
            .isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(post("${base()}/investment/$private/confirm", spouse).status())
            .describedAs("the same answer as for a record that does not exist")
            .isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(post("${base()}/investment/${UUID.randomUUID()}/confirm", spouse).status())
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `joint owners are both asked, and one answer is the record's answer`() {
        val joint = capture(
            owner, householdId, "fd", "Joint FD", BigDecimal(200_000),
            attributes = mapOf("interest_rate" to 7.0),
            owners = listOf(
                mapOf("memberId" to ownerMemberId, "sharePct" to 50),
                mapOf("memberId" to spouseMemberId, "sharePct" to 50),
            ),
        ).path("id").asText()
        age("investments", joint, 13)

        assertThat(dueIds(owner)).contains(joint)
        assertThat(dueIds(spouse)).contains(joint)

        assertThat(post("${base()}/investment/$joint/confirm", spouse).status()).isEqualTo(HttpStatus.OK)
        assertThat(dueIds(owner)).doesNotContain(joint)
    }

    @Test
    fun `a viewer is not asked, because they could not answer`() {
        val viewer = signIn()
        val viewerMember = addMember(owner, householdId, "Amma").path("id").asText()
        joinHousehold(owner, householdId, viewerMember, viewer, role = "viewer")
        val joint = capture(
            owner, householdId, "fd", "Amma's FD", BigDecimal(200_000),
            attributes = mapOf("interest_rate" to 7.0),
            owners = listOf(
                mapOf("memberId" to ownerMemberId, "sharePct" to 50),
                mapOf("memberId" to viewerMember, "sharePct" to 50),
            ),
        ).path("id").asText()
        age("investments", joint, 13)

        assertThat(dueIds(viewer)).doesNotContain(joint)
        assertThat(dueIds(owner)).contains(joint)
    }

    // --- records whose owners have no login ----------------------------------

    /** An FD recorded by the owner for someone who will never sign in. */
    private fun ammasFd(
        title: String,
        visibility: String,
        amma: String = addMember(owner, householdId, "Amma").path("id").asText(),
    ): Pair<String, String> {
        val response = post(
            "/api/v1/households/$householdId/investments", owner,
            mapOf(
                "typeId" to typeId(owner, householdId, "fd"), "title" to title,
                "investedAmount" to 300_000, "visibility" to visibility,
                "attributes" to mapOf("interest_rate" to 7.0),
                "owners" to listOf(mapOf("memberId" to amma, "sharePct" to 100)),
            ),
        )
        val id = response.json().path("id").asText().takeIf { it.isNotBlank() }
            // A private record for someone else is invisible to its recorder the
            // moment it is saved, so the response may not echo it back.
            ?: db.queryForObject(
                "select id::text from investments where household_id = ?::uuid and title = ?",
                String::class.java, householdId, title,
            )!!
        age("investments", id, 13)
        return id to amma
    }

    @Test
    fun `a record owned by someone with no login is asked of whoever recorded it`() {
        val (id, _) = ammasFd("Amma's LIC", visibility = "household")

        assertThat(dueIds(owner))
            .describedAs("the parent's policy recorded by the child is the main case; nobody else can answer it")
            .contains(id)
        assertThat(dueIds(spouse))
            .describedAs("seeing it is still not having recorded it")
            .doesNotContain(id)
        assertThat(post("${base()}/investment/$id/confirm", spouse).status()).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(post("${base()}/investment/$id/confirm", owner).status()).isEqualTo(HttpStatus.OK)
        assertThat(dueIds(owner)).doesNotContain(id)
    }

    @Test
    fun `the recorder stops being asked once the owner can answer, and never about what they cannot see`() {
        val (visible, amma) = ammasFd("Amma's FD", visibility = "household")
        val (hidden, _) = ammasFd("Amma's private FD", visibility = "private", amma = amma)

        assertThat(dueIds(owner))
            .describedAs("a private record for someone else is not the recorder's to see, so not theirs to answer")
            .contains(visible)
            .doesNotContain(hidden)
        assertThat(post("${base()}/investment/$hidden/confirm", owner).status()).isEqualTo(HttpStatus.NOT_FOUND)

        val ammaToken = signIn()
        joinHousehold(owner, householdId, amma, ammaToken, role = "editor")
        assertThat(dueIds(ammaToken)).contains(visible, hidden)
        assertThat(dueIds(owner))
            .describedAs("once the owner has a login and can write, the question is theirs")
            .doesNotContain(visible)
    }

    // --- snooze -----------------------------------------------------------------

    @Test
    fun `a snooze puts the question off without answering it`() {
        val id = fd()
        age("investments", id, 13)

        val until = today().plusMonths(1)
        val snoozed = post("${base()}/investment/$id/snooze", owner, mapOf("until" to until.toString()))
        assertThat(snoozed.status()).isEqualTo(HttpStatus.OK)
        assertThat(snoozed.json().path("dueOn").asText()).isEqualTo(until.toString())
        assertThat(dueIds(owner)).doesNotContain(id)

        assertThat(lastVerifiedAt(id))
            .describedAs("a snooze is not a confirmation")
            .isNull()

        // Snoozed until yesterday is the same as due today.
        db.update(
            "update record_confirmations set snoozed_until = ? where record_id = ?::uuid", today().minusDays(1), id,
        )
        assertThat(dueIds(owner)).contains(id)
    }

    @Test
    fun `a snooze must be after today and within a year, and the record type must be one we ask about`() {
        val id = fd()
        assertThat(post("${base()}/investment/$id/snooze", owner, mapOf("until" to today().toString())).errorCode())
            .isEqualTo("snooze_past")
        assertThat(
            post("${base()}/investment/$id/snooze", owner, mapOf("until" to today().plusYears(2).toString()))
                .errorCode(),
        ).isEqualTo("snooze_too_far")
        assertThat(post("${base()}/member/$id/confirm", owner).errorCode()).isEqualTo("record_type_invalid")
    }

    // --- the table's own policy ------------------------------------------------

    @Test
    fun `the database refuses a confirmation on a record the writer cannot see`() {
        val private = fd("Private FD")
        val transactions = TransactionTemplate(DataSourceTransactionManager(runtimeDataSource))
        val runtime = JdbcTemplate(runtimeDataSource)
        val spouseUserId = db.queryForObject(
            "select user_id::text from members where id = ?::uuid", String::class.java, spouseMemberId,
        )

        assertThatThrownBy {
            transactions.executeWithoutResult {
                runtime.queryForObject("select set_config('app.user_id', ?, true)", String::class.java, spouseUserId)
                runtime.update(
                    """
                    insert into record_confirmations (record_type, record_id, household_id, confirmed_at)
                    values ('investment', ?::uuid, ?::uuid, now())
                    """.trimIndent(),
                    private, householdId,
                )
            }
        }.rootCause().hasMessageContaining("row-level security")
    }
}
