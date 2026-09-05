package tech.bhrigu.almira.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tech.bhrigu.almira.security.RequestUserContext
import tech.bhrigu.almira.support.ApiTestBase
import java.util.UUID

/**
 * The identity a connection carries is the whole basis of row-level security,
 * so its lifetime deserves a test of its own rather than being an implicit
 * consequence of everything else passing.
 */
@DisplayName("The RLS identity is transaction-scoped")
class RlsIdentityScopeTest : ApiTestBase() {

    @Autowired private lateinit var jdbc: NamedParameterJdbcTemplate
    @Autowired private lateinit var userContext: RequestUserContext
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    private val readIdentity = "select coalesce(current_setting('app.user_id', true), '')"

    @Test
    fun `a transaction sees the caller's identity`() {
        val user = UUID.randomUUID()
        val seen = userContext.runAs(user) {
            TransactionTemplate(transactionManager).execute {
                jdbc.jdbcTemplate.queryForObject(readIdentity, String::class.java)
            }
        }
        assertThat(seen).isEqualTo(user.toString())
    }

    /**
     * The property that makes the design safe: PostgreSQL discards a
     * transaction-local setting at commit, so nothing is left on the connection
     * for the pool to hand to the next borrower. This is what the previous
     * design achieved by resetting on close — correct, but dependent on our own
     * bookkeeping running on every path rather than on the database's.
     */
    @Test
    fun `no identity survives the transaction, on any pooled connection`() {
        val user = UUID.randomUUID()
        repeat(24) {
            userContext.runAs(user) {
                TransactionTemplate(transactionManager).execute {
                    jdbc.jdbcTemplate.queryForObject(readIdentity, String::class.java)
                }
            }
        }

        // More iterations than the pool is wide, so every connection in it has
        // been used by `user` and then handed back.
        val leaked = (1..24).map {
            jdbc.jdbcTemplate.queryForObject(readIdentity, String::class.java)
        }.filter { !it.isNullOrEmpty() }

        assertThat(leaked)
            .describedAs("a pooled connection must not carry the last borrower's identity")
            .isEmpty()
    }

    @Test
    fun `an unauthenticated transaction carries no identity, so every policy denies`() {
        val seen = userContext.runAs(null) {
            TransactionTemplate(transactionManager).execute {
                jdbc.jdbcTemplate.queryForObject(readIdentity, String::class.java)
            }
        }
        assertThat(seen).isEmpty()

        val visible = userContext.runAs(null) {
            TransactionTemplate(transactionManager).execute {
                jdbc.jdbcTemplate.queryForObject(
                    "select count(*) from investments", Int::class.java,
                )
            }
        }
        assertThat(visible).isZero()
    }

    @Test
    fun `identity does not leak between two users on the same connection`() {
        val alice = UUID.randomUUID()
        val bob = UUID.randomUUID()

        val asAlice = userContext.runAs(alice) {
            TransactionTemplate(transactionManager).execute {
                jdbc.jdbcTemplate.queryForObject(readIdentity, String::class.java)
            }
        }
        val asBob = userContext.runAs(bob) {
            TransactionTemplate(transactionManager).execute {
                jdbc.jdbcTemplate.queryForObject(readIdentity, String::class.java)
            }
        }

        assertThat(asAlice).isEqualTo(alice.toString())
        assertThat(asBob).isEqualTo(bob.toString())
    }
}
