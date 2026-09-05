package tech.bhrigu.almira.crypto

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
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
 * Field-level encryption (docs/05 §4).
 *
 * The properties here are what make it safe to put a real policy number in the
 * database, so they are tested as properties rather than as a happy path.
 */
@DisplayName("Envelope encryption")
class EnvelopeCipherTest : ApiTestBase() {

    @Autowired private lateinit var cipher: EnvelopeCipher
    @Autowired private lateinit var jdbc: NamedParameterJdbcTemplate
    @Autowired private lateinit var userContext: RequestUserContext
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    private lateinit var userA: UUID
    private lateinit var householdA: UUID
    private lateinit var userB: UUID
    private lateinit var householdB: UUID

    private val field = "accounts.number_enc"

    @BeforeEach
    fun setUp() {
        val tokenA = signIn()
        userA = UUID.fromString(get("/api/me", tokenA).json().path("id").asText())
        householdA = UUID.fromString(createHousehold(tokenA, "A").path("id").asText())

        val tokenB = signIn()
        userB = UUID.fromString(get("/api/me", tokenB).json().path("id").asText())
        householdB = UUID.fromString(createHousehold(tokenB, "B").path("id").asText())
    }

    /** Encryption reads and provisions keys, both of which are RLS-gated. */
    private fun <T> asUser(userId: UUID, block: () -> T): T =
        userContext.runAs(userId) {
            TransactionTemplate(transactionManager).execute { block() }!!
        }

    @Test
    fun `a value comes back exactly as it went in`() {
        val secret = "50100234567890"
        val blob = asUser(userA) { cipher.encrypt(householdA, field, secret) }
        val recovered = asUser(userA) { cipher.decrypt(householdA, field, blob) }
        assertThat(recovered).isEqualTo(secret)
    }

    @Test
    fun `the ciphertext bears no resemblance to the value`() {
        val secret = "50100234567890"
        val blob = asUser(userA) { cipher.encrypt(householdA, field, secret) }
        assertThat(String(blob, Charsets.ISO_8859_1)).doesNotContain(secret)
        assertThat(blob).isNotEqualTo(secret.toByteArray())
    }

    /**
     * A fresh IV every time. Deterministic ciphertext would leak equality: an
     * observer could tell that two households bank with the same account number
     * without decrypting anything.
     */
    @Test
    fun `encrypting the same value twice gives different ciphertext`() {
        val first = asUser(userA) { cipher.encrypt(householdA, field, "same") }
        val second = asUser(userA) { cipher.encrypt(householdA, field, "same") }
        assertThat(first).isNotEqualTo(second)
        assertThat(asUser(userA) { cipher.decrypt(householdA, field, first) }).isEqualTo("same")
        assertThat(asUser(userA) { cipher.decrypt(householdA, field, second) }).isEqualTo("same")
    }

    /**
     * The binding that matters most. Anyone who can write to the database could
     * otherwise copy a ciphertext into another household's row and have the
     * application decrypt it for the wrong family.
     */
    @Test
    fun `a ciphertext moved to another household will not decrypt`() {
        // B must already have a key of its own, otherwise the move fails for the
        // uninteresting reason that there is no key at all — and the test would
        // pass without ever exercising the binding it exists to check.
        asUser(userB) { cipher.encrypt(householdB, field, "b's own value") }

        val blob = asUser(userA) { cipher.encrypt(householdA, field, "50100234567890") }
        assertThatThrownBy { asUser(userB) { cipher.decrypt(householdB, field, blob) } }
            .hasRootCauseInstanceOf(javax.crypto.AEADBadTagException::class.java)
    }

    /** Same idea one column over: a policy number must not decrypt as an IFSC. */
    @Test
    fun `a ciphertext moved to another column will not decrypt`() {
        val blob = asUser(userA) { cipher.encrypt(householdA, field, "50100234567890") }
        assertThatThrownBy {
            asUser(userA) { cipher.decrypt(householdA, "accounts.ifsc_enc", blob) }
        }.hasRootCauseInstanceOf(javax.crypto.AEADBadTagException::class.java)
    }

    @Test
    fun `altering a single byte is detected`() {
        val blob = asUser(userA) { cipher.encrypt(householdA, field, "50100234567890") }
        val tampered = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertThatThrownBy { asUser(userA) { cipher.decrypt(householdA, field, tampered) } }
            .hasRootCauseInstanceOf(javax.crypto.AEADBadTagException::class.java)
    }

    @Test
    fun `each household gets its own data key`() {
        asUser(userA) { cipher.encrypt(householdA, field, "a") }
        asUser(userB) { cipher.encrypt(householdB, field, "b") }

        val wrappedA = wrappedKeyOf(householdA)
        val wrappedB = wrappedKeyOf(householdB)
        assertThat(wrappedA).isNotEqualTo(wrappedB)
    }

    /**
     * The database must never hold a key that opens its own data. If this ever
     * fails, a dump of Postgres is a dump of everyone's account numbers.
     */
    @Test
    fun `the stored key is wrapped, never a usable key`() {
        asUser(userA) { cipher.encrypt(householdA, field, "50100234567890") }
        val stored = wrappedKeyOf(householdA)

        assertThat(stored).isNotNull()
        // AES-256 raw would be exactly 32 bytes; a wrapped key carries an IV and
        // an auth tag, so it cannot be the bare key.
        assertThat(stored!!.size).isNotEqualTo(32)
        assertThat(stored.size).isGreaterThan(32)
    }

    @Test
    fun `a household cannot read another household's key`() {
        asUser(userB) { cipher.encrypt(householdB, field, "b") }
        val visibleToA = userContext.runAs(userA) {
            TransactionTemplate(transactionManager).execute {
                jdbc.queryForObject(
                    "select count(*) from encryption_keys where household_id = :hid",
                    mapOf("hid" to householdB), Int::class.java,
                )
            }
        }
        assertThat(visibleToA).describedAs("RLS covers encryption_keys too").isZero()
    }

    @Test
    fun `the application role cannot write encryption_keys directly`() {
        // No INSERT policy exists, so provisioning can only happen through the
        // SECURITY DEFINER function. A member must not be able to replace their
        // household's key and make everyone's records unreadable.
        assertThatThrownBy {
            userContext.runAs(userA) {
                TransactionTemplate(transactionManager).execute {
                    jdbc.update(
                        """
                        insert into encryption_keys (household_id, key_version, wrapped_dek, kek_id)
                        values (:hid, 99, decode('00', 'hex'), 'forged')
                        """.trimIndent(),
                        mapOf("hid" to householdA),
                    )
                }
            }
        }.rootCause().hasMessageContaining("row-level security")
    }

    private fun wrappedKeyOf(householdId: UUID): ByteArray? =
        userContext.runAs(if (householdId == householdA) userA else userB) {
            TransactionTemplate(transactionManager).execute {
                jdbc.query(
                    "select wrapped_dek from encryption_keys where household_id = :hid",
                    mapOf("hid" to householdId),
                ) { rs, _ -> rs.getBytes("wrapped_dek") }.firstOrNull()
            }
        }
}
