package tech.bhrigu.almira.crypto

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

data class HouseholdKey(val keyVersion: Int, val wrappedDek: ByteArray, val kekId: String) {
    // Data classes compare arrays by identity; these are never compared, and an
    // equals that reads key bytes is a footgun waiting for a timing attack.
    override fun equals(other: Any?) = this === other
    override fun hashCode() = keyVersion
}

@Repository
class EncryptionKeyRepository(private val jdbc: NamedParameterJdbcTemplate) {

    /**
     * The household's active key, provisioning one on first use.
     *
     * [generate] is only called when there is no key yet, and even then the
     * result may be discarded: provisioning goes through a SECURITY DEFINER
     * function that returns whichever key won a concurrent race, so two
     * simultaneous first-writes cannot end up encrypting under different keys.
     */
    fun activeKey(householdId: UUID, generate: () -> Pair<ByteArray, String>): HouseholdKey {
        existingActive(householdId)?.let { return it }

        val (wrapped, kekId) = generate()
        return jdbc.queryForObject(
            """
            select out_key_version as key_version,
                   out_wrapped_dek as wrapped_dek,
                   out_kek_id      as kek_id
            from app.provision_household_dek(:hid, :wrapped, :kekId)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("hid", householdId)
                .addValue("wrapped", wrapped)
                .addValue("kekId", kekId),
        ) { rs, _ ->
            HouseholdKey(rs.getInt("key_version"), rs.getBytes("wrapped_dek"), rs.getString("kek_id"))
        }!!
    }

    /** A specific version, including retired ones — old data still decrypts. */
    fun wrappedKey(householdId: UUID, keyVersion: Int): ByteArray? = jdbc.query(
        """
        select wrapped_dek from encryption_keys
        where household_id = :hid and key_version = :version
        """.trimIndent(),
        mapOf("hid" to householdId, "version" to keyVersion),
    ) { rs, _ -> rs.getBytes("wrapped_dek") }.firstOrNull()

    private fun existingActive(householdId: UUID): HouseholdKey? = jdbc.query(
        """
        select key_version, wrapped_dek, kek_id from encryption_keys
        where household_id = :hid and retired_at is null
        order by key_version desc limit 1
        """.trimIndent(),
        mapOf("hid" to householdId),
    ) { rs, _ ->
        HouseholdKey(rs.getInt("key_version"), rs.getBytes("wrapped_dek"), rs.getString("kek_id"))
    }.firstOrNull()
}
