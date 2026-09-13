package tech.bhrigu.almira.crypto

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.mock.env.MockEnvironment
import tech.bhrigu.almira.config.AlmiraProperties
import tech.bhrigu.almira.crypto.KeyEncryptionKeyCheck.Verdict
import tech.bhrigu.almira.support.ApiTestBase
import java.security.SecureRandom
import java.util.Base64

/**
 * The wrong KMS key refuses at startup instead of looking healthy until the
 * first reveal. Found by the restore drill; docs/17 §6.
 */
@DisplayName("KEK check: the wrong key refuses to start, not to decrypt")
class KeyEncryptionKeyCheckTest : ApiTestBase() {

    @Autowired private lateinit var kms: KeyManagementService
    /**
     * The owner connection, built from ApiTestBase's `db`, which is asserted to
     * be the owner. This first autowired `systemJdbcBypassingRls` — and that
     * bean was itself wired to the runtime pool (see DatabaseConfig), so both
     * real-table cases passed against an empty, RLS-filtered view. The guard in
     * the first case is what caught it.
     */
    private val system by lazy { NamedParameterJdbcTemplate(db) }
    @Autowired private lateinit var flyway: Flyway
    @Autowired private lateinit var properties: AlmiraProperties

    private val production = MockEnvironment().apply {
        setProperty("ALMIRA_ENV", "production")
        setProperty("almira.environment", "production")
    }

    @Test
    fun `an empty key table passes`() {
        assertThat(KeyEncryptionKeyCheck.decide("local:aaaa", emptyMap(), null)).isInstanceOf(Verdict.Ok::class.java)
    }

    @Test
    fun `keys that all name another KEK refuse, and say which`() {
        val verdict = KeyEncryptionKeyCheck.decide("local:aaaa", mapOf("local:bbbb" to 3L), null)
        assertThat((verdict as Verdict.Refuse).reason)
            .contains("not the key this database was encrypted with")
            .contains("local:aaaa").contains("local:bbbb")
    }

    @Test
    fun `a matching fingerprint that does not unwrap refuses`() {
        assertThat(KeyEncryptionKeyCheck.decide("local:aaaa", mapOf("local:aaaa" to 1L), unwraps = false))
            .isInstanceOf(Verdict.Refuse::class.java)
    }

    @Test
    fun `some keys under another KEK warn rather than take everyone down`() {
        assertThat(KeyEncryptionKeyCheck.decide("local:aaaa", mapOf("local:aaaa" to 5L, "local:bbbb" to 1L), true))
            .isInstanceOf(Verdict.Warn::class.java)
    }

    private fun householdWithAKey() {
        val token = signIn()
        val householdId = createHousehold(token)["id"].asText()
        // Storing a full number is what provisions the household's data key.
        post(
            "/api/v1/households/$householdId/accounts", token,
            mapOf("label" to "KEK check", "number" to "123456789012", "storeFullNumber" to true),
        )
    }

    @Test
    fun `against the real table, the key that wrote it starts`() {
        householdWithAKey()
        // Scoped so it cannot pass on an empty view: the key just written is visible
        // to this connection, and this connection is the owner.
        assertThat(db.queryForObject("select current_user", String::class.java)).isEqualTo("almira")
        assertThat(system.jdbcTemplate.queryForObject(
            "select count(*) from encryption_keys where kek_id = ?", Long::class.java, kms.kekId,
        )).isPositive()
        KeyEncryptionKeyCheck(kms, system, production, flyway).afterPropertiesSet()
    }

    @Test
    fun `against the real table, a different key refuses in production`() {
        householdWithAKey()
        val otherKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val wrongKms = LocalKeyManagement(
            properties.copy(
                encryption = properties.encryption.copy(masterKey = Base64.getEncoder().encodeToString(otherKey)),
            ),
        )
        assertThatThrownBy { KeyEncryptionKeyCheck(wrongKms, system, production, flyway).afterPropertiesSet() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("not the key this database was encrypted with")
    }
}
