package tech.almira.privacy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.springframework.test.annotation.DirtiesContext
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import tech.almira.support.ApiTestBase
import java.math.BigDecimal
import java.util.UUID

/**
 * A request refused for what it asks is refused before anything is written.
 *
 * Every refusal here used to come after the write it guards — the insert or
 * update ran, then a validation, a visibility read-back or a scope check threw,
 * and the transaction rolled the write back. Rollback hides that from the
 * database afterwards, so a row count cannot tell the two orders apart. The
 * application's JDBC template is spied instead, and each test asserts that the
 * statement the refusal guards was never sent.
 */
// A context of its own (a spy or a replaced bean), closed after this class so
// its connection pools do not stay open beside every cached context.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("A refused request writes nothing first")
class CheckedBeforeWrittenApiTest : ApiTestBase() {

    @MockitoSpyBean(name = "jdbc")
    private lateinit var jdbc: NamedParameterJdbcTemplate

    private lateinit var owner: String
    private lateinit var householdId: String
    private lateinit var ownerMemberId: String
    private lateinit var otherMemberId: String

    @BeforeEach
    fun setUp() {
        owner = signIn()
        val household = createHousehold(owner, "Koduri", "household", "Ishwarya")
        householdId = household.path("id").asText()
        ownerMemberId = household.path("myMemberId").asText()
        otherMemberId = addMember(owner, householdId, "Aarav").path("id").asText()
        Mockito.clearInvocations(jdbc)
    }

    /** Every SQL statement sent through the template since the last clear, flattened and lower-cased. */
    private fun statementsSent(): List<String> =
        Mockito.mockingDetails(jdbc).invocations
            .mapNotNull { it.arguments.firstOrNull() as? String }
            .map { it.replace(Regex("\\s+"), " ").trim().lowercase() }

    private fun assertNeverSent(prefix: String) {
        assertThat(statementsSent().filter { it.startsWith(prefix) })
            .describedAs("statements starting '$prefix' sent before the refusal")
            .isEmpty()
    }

    private fun <T> refusedWithoutWriting(prefix: String, block: () -> T): T {
        Mockito.clearInvocations(jdbc)
        val result = block()
        assertNeverSent(prefix)
        return result
    }

    // --- a record its creator could not see -----------------------------------

    @Test
    fun `an account private to someone else is refused before it is inserted`() {
        val refused = refusedWithoutWriting("insert into accounts") {
            post(
                "/api/v1/households/$householdId/accounts", owner,
                mapOf(
                    "label" to "Aarav's savings", "accountKind" to "savings", "visibility" to "private",
                    "holders" to listOf(mapOf("memberId" to otherMemberId, "holderType" to "primary")),
                ),
            )
        }
        assertThat(refused.status()).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(refused.body).doesNotContain("Saved")
    }

    @Test
    fun `a loan private to someone else is refused before it is inserted`() {
        val refused = refusedWithoutWriting("insert into liabilities") {
            post(
                "/api/v1/households/$householdId/liabilities", owner,
                mapOf(
                    "title" to "Aarav's loan", "kind" to "personal", "outstanding" to 50000,
                    "visibility" to "private",
                    "holders" to listOf(mapOf("memberId" to otherMemberId)),
                ),
            )
        }
        assertThat(refused.status()).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(refused.body).doesNotContain("Saved")
    }

    // --- holders, owners, roles: validated before the row is written ----------

    @Test
    fun `an account edit naming an unknown holder is refused before the row is updated`() {
        val account = post(
            "/api/v1/households/$householdId/accounts", owner,
            mapOf("label" to "Savings", "accountKind" to "savings", "visibility" to "household"),
        ).json()
        val refused = refusedWithoutWriting("update accounts") {
            patch(
                "/api/v1/households/$householdId/accounts/${account.path("id").asText()}", owner,
                mapOf(
                    "version" to account.path("version").asInt(), "label" to "Renamed",
                    "holders" to listOf(mapOf("memberId" to UUID.randomUUID(), "holderType" to "primary")),
                ),
            )
        }
        assertThat(refused.errorCode()).isEqualTo("holder_unknown")
    }

    @Test
    fun `a loan edit naming an unknown borrower is refused before the row is updated`() {
        val loan = post(
            "/api/v1/households/$householdId/liabilities", owner,
            mapOf("title" to "Car loan", "kind" to "personal", "outstanding" to 50000, "visibility" to "household"),
        ).json()
        val refused = refusedWithoutWriting("update liabilities") {
            patch(
                "/api/v1/households/$householdId/liabilities/${loan.path("id").asText()}", owner,
                mapOf(
                    "version" to loan.path("version").asInt(), "title" to "Renamed",
                    "holders" to listOf(mapOf("memberId" to UUID.randomUUID())),
                ),
            )
        }
        assertThat(refused.errorCode()).isEqualTo("holder_unknown")
    }

    @Test
    fun `a holding with an unreadable value writes none of its custom fields`() {
        val refused = refusedWithoutWriting("insert into custom_fields") {
            post(
                "/api/v1/households/$householdId/investments", owner,
                mapOf(
                    "typeId" to typeId(owner, householdId, "fd"),
                    "title" to "ICICI FD",
                    "investedAmount" to 100000,
                    "customFields" to listOf(mapOf("key" to "branch_code", "label" to "Branch code", "dataType" to "number")),
                    "attributes" to mapOf("interest_rate" to 7.1, "branch_code" to "not a number"),
                ),
            )
        }
        assertThat(refused.status()).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `a holding edit naming an unknown owner is refused before the row is updated`() {
        val created = capture(
            owner, householdId, "fd", "SBI FD", BigDecimal("50000"),
            visibility = "household", attributes = mapOf("interest_rate" to 6.8),
        )
        val holding = get("/api/v1/households/$householdId/investments/${created.path("id").asText()}", owner).json()
        val refused = refusedWithoutWriting("update investments") {
            patch(
                "/api/v1/households/$householdId/investments/${holding.path("id").asText()}", owner,
                mapOf(
                    "version" to holding.path("version").asInt(), "title" to "Renamed",
                    "owners" to listOf(mapOf("memberId" to UUID.randomUUID(), "sharePct" to 100)),
                ),
            )
        }
        assertThat(refused.errorCode()).isEqualTo("owner_unknown")
    }

    @Test
    fun `a will with a role nobody holds is refused before it is inserted`() {
        val refused = refusedWithoutWriting("insert into estate_documents") {
            post(
                "/api/v1/households/$householdId/estate/documents", owner,
                mapOf(
                    "memberId" to ownerMemberId, "kind" to "will", "title" to "Will",
                    "roles" to listOf(mapOf("role" to "executor")),
                ),
            )
        }
        assertThat(refused.status()).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `a will edit with a role nobody holds is refused before it is written`() {
        val will = post(
            "/api/v1/households/$householdId/estate/documents", owner,
            mapOf("memberId" to ownerMemberId, "kind" to "will", "title" to "Will"),
        ).json()
        val refusedEdit = refusedWithoutWriting("update estate_documents") {
            patch(
                "/api/v1/households/$householdId/estate/documents/${will.path("id").asText()}", owner,
                mapOf(
                    "version" to will.path("version").asInt(), "title" to "Renamed",
                    "roles" to listOf(mapOf("role" to "executor")),
                ),
            )
        }
        assertThat(refusedEdit.status()).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `a contact linked to a record that is not there is refused before it is inserted`() {
        val refused = refusedWithoutWriting("insert into contacts") {
            post(
                "/api/v1/households/$householdId/contacts", owner,
                mapOf(
                    "kind" to "ca", "name" to "Ramesh", "visibility" to "household",
                    "links" to listOf(mapOf("entityType" to "investment", "entityId" to UUID.randomUUID())),
                ),
            )
        }
        assertThat(refused.status()).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `a link with nothing to share is refused before a link row is written`() {
        val refused = refusedWithoutWriting("insert into guest_shares") {
            post(
                "/api/v1/households/$householdId/shares", owner,
                mapOf("label" to "Empty", "scope" to "records", "investmentIds" to emptyList<String>()),
            )
        }
        assertThat(refused.errorCode()).isEqualTo("scope_empty")
    }
}
