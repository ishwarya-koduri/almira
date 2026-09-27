package tech.almira.e2e

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tech.almira.audit.AuditService
import tech.almira.auth.StepUpService
import tech.almira.common.ApiException
import tech.almira.household.HouseholdService
import tech.almira.security.RequestUserContext
import java.sql.ResultSet
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * A second copy of the content key, wrapped under a secret the server never
 * sees (docs/12 §10). Everything here is either useless without that secret —
 * the salt, the wrap, the verifier — or deliberately readable by the family:
 * who holds a share, and when the owner last checked it still works.
 */
data class RecoverySlotBody(
    val kdf: String = Recovery.KDF,
    val kdfSalt: String,
    val wrapAlgorithm: String = Recovery.WRAP_ALGORITHM,
    val wrappedKey: String,
    val verifier: String,
    /** HMAC-SHA256(contentKey, "almira content key id v1"), first 16 bytes, base64url: 22 characters. */
    val contentKeyId: String,
    /** Roles or relationships ("Amma", "our lawyer"). Not sealed. At most one for a key, three for shares. */
    val holders: List<String> = emptyList(),
)

data class RecoverySlot(
    /** `recovery_key` | `recovery_shares` */
    val kind: String,
    val kdf: String,
    val kdfSalt: String,
    val wrapAlgorithm: String,
    val wrappedKey: String,
    val verifier: String,
    val contentKeyId: String,
    /** 2 for shares, absent for a recovery key. */
    val threshold: Int?,
    /** 3 for shares, absent for a recovery key. */
    val shareCount: Int?,
    val holders: List<String>,
    /** When the owner last opened this copy without changing anything. The owner's word; see [RecoveryService.practice]. */
    val practicedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    /**
     * Whether this copy was made from the key the owner's passphrase opens now.
     * Known only to the owner, whose key row is theirs to read; absent otherwise.
     */
    val current: Boolean?,
)

data class RecoveryStatus(
    /** The person these copies belong to. */
    val memberId: UUID?,
    val slots: List<RecoverySlot>,
    val caveats: List<String> = Recovery.CAVEATS,
)

object Recovery {
    const val KEY = "recovery_key"
    const val SHARES = "recovery_shares"
    const val KDF = "HKDF-SHA256"
    const val WRAP_ALGORITHM = "AES-GCM-256"
    val KINDS = setOf(KEY, SHARES)

    /** One person keeps a sheet; three people keep shares. */
    fun maxHolders(kind: String) = if (kind == SHARES) 3 else 1

    val CAVEATS = listOf(
        "A recovery key or recovery shares are made on this device. We keep a copy of your key " +
            "locked with them, and we never see the code or a share.",
        "Anyone with the recovery key, or any two shares, can open what you sealed. Keep them " +
            "where the right people will find them and nobody else will.",
        "Who holds a share is not sealed. Family who can see something you sealed are told who " +
            "to ask, so write a role or a relationship, never where anything is.",
        "Practise once a year. We record that you did; we cannot check the paper is still there.",
    )
}

/**
 * Recovery for zero-knowledge mode (docs/12 §10).
 *
 * The same incuriosity as [SealedFieldService]: shape checks, ownership, and
 * nothing that would need the key. The judgement it does make is about
 * consistency. A copy made from one content key must never be left standing
 * after the passphrase row has moved to another, because that is a recovery
 * sheet that opens nothing — found out on the worst day. [put] refuses a copy
 * of the wrong key; [SealedFieldService.storeKey] refuses to move the key out
 * from under a copy.
 */
@Service
class RecoveryService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val households: HouseholdService,
    private val audit: AuditService,
    private val userContext: RequestUserContext,
    private val stepUp: StepUpService,
) {

    @Transactional(readOnly = true)
    fun mine(householdId: UUID): RecoveryStatus {
        val userId = userContext.require()
        if (userContext.currentGuestShareId() != null) throw ApiException.notFound()
        households.get(householdId)
        return RecoveryStatus(memberId = memberIdOf(householdId, userId), slots = slotsOf(householdId, userId))
    }

    /**
     * Another person's copies — for someone holding an open emergency window on
     * them, with the printed sheet or two shares in hand (docs/12 §10.5). Anyone
     * else gets 404, whether or not the person set recovery up.
     */
    @Transactional(readOnly = true)
    fun forMember(householdId: UUID, memberId: UUID): RecoveryStatus {
        val userId = userContext.require()
        if (userContext.currentGuestShareId() != null) throw ApiException.notFound()
        households.get(householdId)
        val subject = jdbc.query(
            """
            select user_id from members
            where id = :mid and household_id = :hid and deleted_at is null and user_id is not null
            """.trimIndent(),
            mapOf("mid" to memberId, "hid" to householdId),
        ) { rs, _ -> rs.getObject("user_id", UUID::class.java) }.firstOrNull() ?: throw ApiException.notFound()

        if (subject != userId) {
            val open = jdbc.queryForObject(
                "select app.emergency_open_on_user(:hid, :uid)",
                mapOf("hid" to householdId, "uid" to subject),
                Boolean::class.java,
            ) == true
            if (!open) throw ApiException.notFound()
        }
        return RecoveryStatus(memberId = memberId, slots = slotsOf(householdId, subject))
    }

    /**
     * Creates or replaces one copy. Both need a step-up: a copy is a second way
     * into everything sealed, and a borrowed unlocked browser should be enough
     * neither to make one and walk away with the paper, nor to replace the one
     * in the drawer with a dud.
     *
     * The wrap and the slot are written in one transaction, so a copy never
     * exists without its holders or the other way round.
     */
    @Transactional
    fun put(householdId: UUID, kind: String, body: RecoverySlotBody): RecoverySlot {
        val userId = userContext.require()
        if (userContext.currentGuestShareId() != null) throw ApiException.notFound()
        households.get(householdId)
        requireKind(kind)

        if (body.kdf != Recovery.KDF || body.wrapAlgorithm != Recovery.WRAP_ALGORITHM) {
            throw ApiException.badRequest(
                "recovery_scheme_unknown", "That recovery copy uses a scheme this server doesn't know.",
            )
        }
        Envelopes.requireBase64Url("kdfSalt", body.kdfSalt, minBytes = 16)
        // version + key version + iv + a 32-byte key + tag: a wrap of anything
        // shorter is not a wrap of a content key.
        Envelopes.requireEnvelope("wrappedKey", body.wrappedKey, minBytes = 17 + 32 + 16)
        Envelopes.requireEnvelope("verifier", body.verifier, minBytes = 33)
        Envelopes.requireKeyId(body.contentKeyId)
        val holders = cleanHolders(kind, body.holders)

        // Locked, so a passphrase write cannot move the key between reading its
        // id here and writing the copy below (SealedFieldService.storeKey).
        val keyRow = jdbc.query(
            "select content_key_id from e2e_keys where household_id = :hid and user_id = :uid for update",
            mapOf("hid" to householdId, "uid" to userId),
        ) { rs, _ -> KeyId(rs.getString("content_key_id")) }.firstOrNull()
            ?: throw ApiException.badRequest(
                "no_key", "Set up a passphrase for this household before making a recovery copy.",
            )

        requireStepUp(userId)

        when (keyRow.value) {
            // A key row that never recorded an id. The client unlocked before it
            // could wrap the key, so the first copy records it.
            null -> jdbc.update(
                "update e2e_keys set content_key_id = :kid where household_id = :hid and user_id = :uid",
                mapOf("kid" to body.contentKeyId, "hid" to householdId, "uid" to userId),
            )
            body.contentKeyId -> Unit
            else -> throw ApiException.conflict(
                "recovery_key_mismatch",
                "That copy was made from a different key than the one your passphrase opens now, " +
                    "so it would not open anything. Unlock again and make it afresh.",
            )
        }

        val replacing = jdbc.queryForObject(
            """
            select count(*) from e2e_recovery_wraps
            where household_id = :hid and user_id = :uid and kind = :kind
            """.trimIndent(),
            mapOf("hid" to householdId, "uid" to userId, "kind" to kind),
            Int::class.java,
        )!! > 0

        val wrapId = jdbc.queryForObject(
            """
            insert into e2e_recovery_wraps (household_id, user_id, kind, kdf, kdf_salt, wrap_algorithm,
                                            wrapped_key, verifier, content_key_id)
            values (:hid, :uid, :kind, :kdf, :salt, :wrapAlg, :wrapped, :verifier, :kid)
            on conflict (household_id, user_id, kind) do update set
              kdf = excluded.kdf, kdf_salt = excluded.kdf_salt, wrap_algorithm = excluded.wrap_algorithm,
              wrapped_key = excluded.wrapped_key, verifier = excluded.verifier,
              content_key_id = excluded.content_key_id
            returning id
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("hid", householdId).addValue("uid", userId).addValue("kind", kind)
                .addValue("kdf", body.kdf).addValue("salt", body.kdfSalt)
                .addValue("wrapAlg", body.wrapAlgorithm).addValue("wrapped", body.wrappedKey)
                .addValue("verifier", body.verifier).addValue("kid", body.contentKeyId),
            UUID::class.java,
        )!!

        // A new copy has not been practised, whatever the old one had.
        jdbc.update(
            """
            insert into e2e_recovery_slots (wrap_id, household_id, user_id, kind, threshold, share_count, holders)
            values (:wid, :hid, :uid, :kind, :threshold, :shareCount, :holders)
            on conflict (wrap_id) do update set
              threshold = excluded.threshold, share_count = excluded.share_count,
              holders = excluded.holders, practiced_at = null
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("wid", wrapId).addValue("hid", householdId).addValue("uid", userId)
                .addValue("kind", kind)
                .addValue("threshold", if (kind == Recovery.SHARES) 2 else null)
                .addValue("shareCount", if (kind == Recovery.SHARES) 3 else null)
                .addValue("holders", holders.toTypedArray()),
        )

        // Never the holders' words: the log records that a copy exists, not who has it.
        audit.record(
            householdId = householdId, actorUserId = userId,
            action = if (replacing) "e2e.recovery.replace" else "e2e.recovery.create",
            entityType = "household", entityId = householdId,
            diff = mapOf("kind" to kind, "holders" to holders.size),
        )
        return slotsOf(householdId, userId).first { it.kind == kind }
    }

    @Transactional
    fun remove(householdId: UUID, kind: String) {
        val userId = userContext.require()
        if (userContext.currentGuestShareId() != null) throw ApiException.notFound()
        households.get(householdId)
        requireKind(kind)
        val exists = jdbc.queryForObject(
            "select count(*) from e2e_recovery_wraps where household_id = :hid and user_id = :uid and kind = :kind",
            mapOf("hid" to householdId, "uid" to userId, "kind" to kind),
            Int::class.java,
        )!! > 0
        if (!exists) throw ApiException.notFound()
        requireStepUp(userId)
        jdbc.update(
            "delete from e2e_recovery_wraps where household_id = :hid and user_id = :uid and kind = :kind",
            mapOf("hid" to householdId, "uid" to userId, "kind" to kind),
        )
        audit.record(
            householdId = householdId, actorUserId = userId, action = "e2e.recovery.remove",
            entityType = "household", entityId = householdId, diff = mapOf("kind" to kind),
        )
    }

    /**
     * The client opened the key with the recovery copy, checked the verifier
     * and the key id, and changed nothing; this records the date. The server
     * cannot check that it happened — checking would need the key — so it is
     * the owner's word, and the interface says "you practised", never "we
     * confirmed".
     */
    @Transactional
    fun practice(householdId: UUID, kind: String): RecoverySlot {
        val userId = userContext.require()
        if (userContext.currentGuestShareId() != null) throw ApiException.notFound()
        households.get(householdId)
        requireKind(kind)
        val updated = jdbc.update(
            """
            update e2e_recovery_slots set practiced_at = now()
            where household_id = :hid and user_id = :uid and kind = :kind
            """.trimIndent(),
            mapOf("hid" to householdId, "uid" to userId, "kind" to kind),
        )
        if (updated == 0) throw ApiException.notFound()
        audit.record(
            householdId = householdId, actorUserId = userId, action = "e2e.recovery.practice",
            entityType = "household", entityId = householdId, diff = mapOf("kind" to kind),
        )
        return slotsOf(householdId, userId).first { it.kind == kind }
    }

    private fun slotsOf(householdId: UUID, userId: UUID): List<RecoverySlot> = jdbc.query(
        """
        select w.kind, w.kdf, w.kdf_salt, w.wrap_algorithm, w.wrapped_key, w.verifier, w.content_key_id,
               w.created_at, w.updated_at, s.threshold, s.share_count, s.holders, s.practiced_at,
               (select k.content_key_id from e2e_keys k
                 where k.household_id = w.household_id and k.user_id = w.user_id) as key_id_now,
               w.user_id = app.current_user_id() as mine
        from e2e_recovery_wraps w
        join e2e_recovery_slots s on s.wrap_id = w.id
        where w.household_id = :hid and w.user_id = :uid
        order by w.kind
        """.trimIndent(),
        mapOf("hid" to householdId, "uid" to userId),
    ) { rs, _ -> slot(rs) }

    private fun slot(rs: ResultSet): RecoverySlot {
        val keyIdNow = rs.getString("key_id_now")
        return RecoverySlot(
            kind = rs.getString("kind"),
            kdf = rs.getString("kdf"),
            kdfSalt = rs.getString("kdf_salt"),
            wrapAlgorithm = rs.getString("wrap_algorithm"),
            wrappedKey = rs.getString("wrapped_key"),
            verifier = rs.getString("verifier"),
            contentKeyId = rs.getString("content_key_id"),
            threshold = rs.getObject("threshold")?.let { (it as Number).toInt() },
            shareCount = rs.getObject("share_count")?.let { (it as Number).toInt() },
            holders = (rs.getArray("holders").array as Array<*>).map { it.toString() },
            practicedAt = rs.getTimestamp("practiced_at")?.toInstant(),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
            current = if (rs.getBoolean("mine")) keyIdNow == rs.getString("content_key_id") else null,
        )
    }

    private fun memberIdOf(householdId: UUID, userId: UUID): UUID? = jdbc.query(
        "select id from members where household_id = :hid and user_id = :uid and deleted_at is null",
        mapOf("hid" to householdId, "uid" to userId),
    ) { rs, _ -> rs.getObject("id", UUID::class.java) }.firstOrNull()

    private fun requireKind(kind: String) {
        if (kind !in Recovery.KINDS) {
            throw ApiException.badRequest("recovery_kind_invalid", "That isn't a kind of recovery copy.")
        }
    }

    /**
     * Holders are plain text the family may read, so they are held to the
     * shortest useful thing: a role or a relationship, one line each.
     */
    private fun cleanHolders(kind: String, holders: List<String>): List<String> {
        val cleaned = holders.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.size > Recovery.maxHolders(kind)) {
            throw ApiException.badRequest(
                "holders_invalid",
                if (kind == Recovery.SHARES) "Three shares, so at most three people." else "One sheet, so one person.",
            )
        }
        if (cleaned.any { it.length > 60 || it.any(Char::isISOControl) }) {
            throw ApiException.badRequest(
                "holders_invalid", "Write who holds it in a few words: \"Amma\", \"our lawyer\".",
            )
        }
        return cleaned
    }

    private fun requireStepUp(userId: UUID) {
        try {
            stepUp.requireElevated(userId, userContext.currentSessionId())
        } catch (refused: ApiException) {
            throw ApiException(
                HttpStatus.FORBIDDEN, "step_up_required",
                "For your security, confirm it's you before changing how your sealed notes can be recovered.",
                refused.details,
            )
        }
    }

    private data class KeyId(val value: String?)
}

/** The structural checks a recovery copy gets. None of them reads a byte of content. */
internal object Envelopes {
    private val BASE64URL = Regex("^[A-Za-z0-9_-]*$")

    /**
     * base64url without padding, strictly: only clients that know docs/12 §10
     * write these, so there is no older spelling to be lenient about.
     */
    private fun decodeOrNull(value: String): ByteArray? =
        if (!BASE64URL.matches(value)) null
        else runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull()

    fun requireBase64Url(field: String, value: String, minBytes: Int) {
        val decoded = decodeOrNull(value) ?: throw ApiException.badRequest(
            "not_ciphertext", "$field has to be base64url. This looks like plain text.",
        )
        if (decoded.size < minBytes) {
            throw ApiException.badRequest("not_ciphertext", "$field is too short to be what it claims to be.")
        }
    }

    /** docs/12 §3: version byte 1, key version ≥ 1, at least [minBytes]. Never echoes the value. */
    fun requireEnvelope(field: String, value: String, minBytes: Int) {
        requireBase64Url(field, value, minBytes)
        val decoded = decodeOrNull(value)!!
        val keyVersion = ((decoded[1].toInt() and 0xFF) shl 24) or ((decoded[2].toInt() and 0xFF) shl 16) or
            ((decoded[3].toInt() and 0xFF) shl 8) or (decoded[4].toInt() and 0xFF)
        if (decoded[0].toInt() != 1 || keyVersion < 1) {
            throw ApiException.badRequest("not_ciphertext", "$field isn't shaped like a sealed value.")
        }
    }

    /** Sixteen bytes, base64url without padding: 22 characters. */
    fun isKeyId(value: String): Boolean = value.length == 22 && decodeOrNull(value)?.size == 16

    fun requireKeyId(value: String) {
        if (!isKeyId(value)) {
            throw ApiException.badRequest("key_id_invalid", "contentKeyId has to be 16 bytes of base64url.")
        }
    }
}

@RestController
@RequestMapping("/api/v1/households/{householdId}/e2e/recovery")
class RecoveryController(private val service: RecoveryService) {

    /** Your own recovery copies in this household, if any. */
    @GetMapping
    fun recoveryCopies(@PathVariable householdId: UUID): RecoveryStatus = service.mine(householdId)

    /** Someone else's copies, for the person holding an open emergency window on them. 404 for anyone else. */
    @GetMapping("/members/{memberId}")
    fun recoveryCopiesOfMember(@PathVariable householdId: UUID, @PathVariable memberId: UUID): RecoveryStatus =
        service.forMember(householdId, memberId)

    /** Creates a copy, or replaces one. Needs a step-up either way. */
    @PutMapping("/{kind}")
    fun putRecoveryCopy(
        @PathVariable householdId: UUID,
        @PathVariable kind: String,
        @RequestBody body: RecoverySlotBody,
    ): RecoverySlot = service.put(householdId, kind, body)

    /** Removes a copy. The paper already handed out stops working. Needs a step-up. */
    @DeleteMapping("/{kind}")
    fun removeRecoveryCopy(@PathVariable householdId: UUID, @PathVariable kind: String): ResponseEntity<Void> {
        service.remove(householdId, kind)
        return ResponseEntity.noContent().build()
    }

    /** Records that the owner opened this copy on their device and changed nothing. */
    @PostMapping("/{kind}/practice")
    fun practiseRecoveryCopy(@PathVariable householdId: UUID, @PathVariable kind: String): RecoverySlot =
        service.practice(householdId, kind)
}
