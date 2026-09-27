package tech.almira.e2e

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Who could actually open a sealed value, for the person reading about it
 * (docs/20 §1.2, docs/12 §10.5).
 *
 * A sealed line in the handbook used to be blank, because the server cannot
 * read it. Blank reads as "nothing was recorded", which is the one wrong thing
 * to tell a family. This says what the server does know: that something is
 * sealed, by whom, and who holds a way to open it — "Sealed by Ishwarya · Ravi
 * keeps the recovery sheet · ask Ravi".
 *
 * Every name here was already readable by the caller: the sealer is a member
 * of their household, and the holders come from `e2e_recovery_slots` under the
 * caller's own row-level security, which shows them only to someone who can
 * see a value that person sealed (V55).
 */
data class SealedLine(
    val fieldKey: String,
    val sealedByMemberId: UUID?,
    val sealedByName: String?,
    val sealedByMe: Boolean,
    /** A recovery sheet exists for the person who sealed it. */
    val hasRecoveryKey: Boolean,
    /** Who keeps that sheet, if they said. */
    val recoveryKeyHolder: String?,
    /** 2-of-3 recovery shares exist for the person who sealed it. */
    val hasRecoveryShares: Boolean,
    val shareHolders: List<String>,
    /** English; clients build their own from the fields above and fall back to this (Doc 14). */
    val sentence: String,
)

/** Who has made a recovery copy, as far as the caller can see. */
data class RecoveryPresence(val hasKey: Boolean, val keyHolder: String?, val hasShares: Boolean, val shareHolders: List<String>) {
    val any: Boolean get() = hasKey || hasShares
}

@Service
class SealedAccessService(private val jdbc: NamedParameterJdbcTemplate) {

    /** Every sealed value the caller can see on these records, grouped by record id. */
    fun lines(householdId: UUID, recordType: String, recordIds: Collection<UUID>): Map<UUID, List<SealedLine>> {
        if (recordIds.isEmpty()) return emptyMap()
        val rows = jdbc.query(
            """
            select sv.record_id, sv.field_key, sv.sealed_by,
                   sv.sealed_by = app.current_user_id() as mine,
                   m.id as member_id, m.display_name
            from sealed_values sv
            left join members m
              on m.household_id = sv.household_id and m.user_id = sv.sealed_by and m.deleted_at is null
            where sv.household_id = :hid and sv.record_type = :type and sv.record_id in (:ids)
            order by sv.record_id, sv.field_key
            """.trimIndent(),
            MapSqlParameterSource().addValue("hid", householdId).addValue("type", recordType)
                .addValue("ids", recordIds),
        ) { rs, _ ->
            Row(
                recordId = rs.getObject("record_id", UUID::class.java),
                fieldKey = rs.getString("field_key"),
                sealedBy = rs.getObject("sealed_by", UUID::class.java),
                mine = rs.getBoolean("mine"),
                memberId = rs.getObject("member_id", UUID::class.java),
                name = rs.getString("display_name"),
            )
        }
        val presence = presence(householdId)
        return rows.groupBy({ it.recordId }) { line(it.fieldKey, it.memberId, it.name, it.mine, presence[it.sealedBy]) }
    }

    /** Recovery copies by user id, for everyone whose slots the caller may read. */
    fun presence(householdId: UUID): Map<UUID, RecoveryPresence> = jdbc.query(
        """
        select user_id, kind, holders from e2e_recovery_slots where household_id = :hid
        """.trimIndent(),
        mapOf("hid" to householdId),
    ) { rs, _ ->
        Triple(
            rs.getObject("user_id", UUID::class.java),
            rs.getString("kind"),
            (rs.getArray("holders").array as Array<*>).map { it.toString() },
        )
    }.groupBy { it.first }.mapValues { (_, slots) ->
        val key = slots.firstOrNull { it.second == Recovery.KEY }
        val shares = slots.firstOrNull { it.second == Recovery.SHARES }
        RecoveryPresence(
            hasKey = key != null,
            keyHolder = key?.third?.firstOrNull(),
            hasShares = shares != null,
            shareHolders = shares?.third.orEmpty(),
        )
    }

    fun line(fieldKey: String, memberId: UUID?, name: String?, mine: Boolean, recovery: RecoveryPresence?) = SealedLine(
        fieldKey = fieldKey,
        sealedByMemberId = memberId,
        sealedByName = name,
        sealedByMe = mine,
        hasRecoveryKey = recovery?.hasKey == true,
        recoveryKeyHolder = recovery?.keyHolder,
        hasRecoveryShares = recovery?.hasShares == true,
        shareHolders = recovery?.shareHolders.orEmpty(),
        sentence = sentence(if (mine) null else (name ?: "someone who has left"), recovery),
    )

    private data class Row(
        val recordId: UUID,
        val fieldKey: String,
        val sealedBy: UUID,
        val mine: Boolean,
        val memberId: UUID?,
        val name: String?,
    )

    companion object {
        /**
         * "Sealed · Ravi keeps the recovery sheet · ask Ravi". Plain, and never
         * a pronoun: the server does not know anyone's, and a wrong one on this
         * page is worse than a repeated name. [sealer] is null for the caller.
         */
        fun sentence(sealer: String?, recovery: RecoveryPresence?): String {
            val head = if (sealer == null) "Sealed by you" else "Sealed by $sealer"
            val ways = buildList {
                if (recovery?.hasKey == true) {
                    add(
                        recovery.keyHolder?.let { "$it keeps the recovery sheet · ask $it" }
                            ?: if (sealer == null) "you made a recovery sheet"
                            else "$sealer made a recovery sheet · look for it with $sealer's papers",
                    )
                }
                if (recovery?.hasShares == true) {
                    add(
                        if (recovery.shareHolders.isEmpty()) "three recovery shares exist · any two open it"
                        else "${joinNames(recovery.shareHolders)} ${if (recovery.shareHolders.size == 1) "holds" else "each hold"} a recovery share · any two open it",
                    )
                }
            }
            return when {
                ways.isNotEmpty() -> "$head · ${ways.joinToString(" · or ")}"
                sealer == null -> "$head · only your passphrase opens it · make a recovery sheet so your family can"
                else -> "$head · only $sealer's passphrase opens it"
            }
        }

        private fun joinNames(names: List<String>) = when (names.size) {
            1 -> names[0]
            2 -> "${names[0]} and ${names[1]}"
            else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
        }
    }
}
