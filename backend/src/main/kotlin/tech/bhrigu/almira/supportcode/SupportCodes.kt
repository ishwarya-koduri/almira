package tech.bhrigu.almira.supportcode

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.config.OpenApiConfig
import tech.bhrigu.almira.plans.HouseholdPlanService
import tech.bhrigu.almira.security.RequestUserContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

/**
 * What the client says about itself. Every field is optional, and every field
 * is checked against a shape that cannot carry a name, an amount or a title:
 * versions, route names, error codes, and switches that are only on or off.
 */
data class SupportCodeBody(
    val appVersion: String? = null,
    /** `web`, `android` or `ios`. */
    val platform: String? = null,
    /** The route the person was on, e.g. `investments`. Never an address with an id in it. */
    val screen: String? = null,
    /** `en`, `te` or `hi`. */
    val language: String? = null,
    /** The last error codes the client was answered with, newest first, e.g. `plan_read_only`. */
    val errorCodes: List<String>? = null,
    /** Named switches, on or off: `dataSaver`, `largerText`, `zeroKnowledge`… */
    val flags: Map<String, Boolean>? = null,
)

data class SupportCodeView(
    val id: UUID,
    val createdAt: Instant,
    val expiresAt: Instant,
    val active: Boolean,
    val revokedAt: Instant?,
    /** How many times support has opened it. The person sees every look. */
    val lookups: Int,
    val lastLookedUpAt: Instant?,
    /** Exactly what support sees, key for key. */
    val diagnostics: Map<String, Any?>,
)

/** The code itself is in this answer and nowhere else, ever: only its hash is kept. */
data class CreatedSupportCode(val code: String, val supportCode: SupportCodeView)

/** What a code would share, before it exists. */
data class SupportCodePreview(val diagnostics: Map<String, Any?>, val validForHours: Int, val never: List<String>)

/**
 * "Share a support code" (docs/28 §4).
 *
 * A person makes a code that carries diagnostics and nothing else, sees what
 * it carries first, can take it back, and it ends by itself after 24 hours.
 * Support reads it as the schema owner with `scripts/support-code.sh`
 * (ops.lookup_support_code, V102) — there is no operator endpoint, so there is
 * nothing new on the internet to attack.
 *
 * What the server adds is as bare as what the client sends: the API version,
 * how many households the person is in, and whether any is read-only. Never
 * the person's id, phone, email or name, and never anything about a record.
 *
 * Every statement runs as the caller, and row-level security keeps it to their
 * own codes (V102): another person's code is 404, as a code that does not exist.
 */
@Service
class SupportCodeService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val userContext: RequestUserContext,
    private val audit: AuditService,
    private val mapper: ObjectMapper,
    private val plans: HouseholdPlanService,
) {
    private val random = SecureRandom()

    @Transactional(readOnly = true)
    fun preview(body: SupportCodeBody): SupportCodePreview {
        requirePerson()
        return SupportCodePreview(diagnostics(body), VALID_HOURS, NEVER)
    }

    @Transactional
    fun create(body: SupportCodeBody): CreatedSupportCode {
        val userId = requirePerson()
        val diagnostics = diagnostics(body)
        val live = jdbc.queryForObject(
            "select count(*) from support_codes where revoked_at is null and expires_at > now()",
            emptyMap<String, Any>(), Int::class.java,
        ) ?: 0
        if (live >= MAX_LIVE) {
            throw ApiException.conflict(
                "too_many_support_codes",
                "You already have $MAX_LIVE support codes that are still working. Take one back first.",
            )
        }

        val id = UUID.randomUUID()
        val code = newCode()
        jdbc.update(
            """
            insert into support_codes (id, user_id, code_hash, diagnostics, expires_at)
            values (:id, :user, :hash, cast(:diagnostics as jsonb), now() + make_interval(hours => :hours))
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id).addValue("user", userId).addValue("hash", hash(code))
                .addValue("diagnostics", mapper.writeValueAsString(diagnostics))
                .addValue("hours", VALID_HOURS),
        )
        audit.record(null, userId, "support_code.create", "support_code", id)
        return CreatedSupportCode(code, find(id))
    }

    @Transactional(readOnly = true)
    fun list(): List<SupportCodeView> {
        requirePerson()
        return jdbc.query("$SELECT order by created_at desc limit 20", emptyMap<String, Any>(), ::toView)
    }

    @Transactional
    fun revoke(id: UUID): SupportCodeView {
        val userId = requirePerson()
        val current = find(id)
        if (current.revokedAt == null) {
            jdbc.update("update support_codes set revoked_at = now() where id = :id", mapOf("id" to id))
            audit.record(null, userId, "support_code.revoke", "support_code", id)
        }
        return find(id)
    }

    private fun find(id: UUID): SupportCodeView =
        jdbc.query("$SELECT where id = :id", mapOf("id" to id), ::toView).firstOrNull()
            ?: throw ApiException.notFound("We couldn't find that support code.")

    private fun toView(rs: java.sql.ResultSet, @Suppress("UNUSED_PARAMETER") row: Int): SupportCodeView {
        val expires = rs.getTimestamp("expires_at").toInstant()
        val revoked = rs.getTimestamp("revoked_at")?.toInstant()
        return SupportCodeView(
            id = rs.getObject("id", UUID::class.java),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            expiresAt = expires,
            active = revoked == null && expires.isAfter(Instant.now()),
            revokedAt = revoked,
            lookups = rs.getInt("lookups"),
            lastLookedUpAt = rs.getTimestamp("last_looked_up_at")?.toInstant(),
            diagnostics = mapper.readValue(rs.getString("diagnostics"), object : TypeReference<Map<String, Any?>>() {}),
        )
    }

    /** A guest session borrows the sharer's identity; it gets no support codes. */
    private fun requirePerson(): UUID {
        val userId = userContext.require()
        if (userContext.currentGuestShareId() != null) throw ApiException.notFound()
        return userId
    }

    /**
     * The closed list. Anything outside a shape is refused with the field named,
     * rather than trimmed quietly: a client that sends a title here has a bug
     * worth hearing about.
     */
    private fun diagnostics(body: SupportCodeBody): Map<String, Any?> {
        val problems = linkedMapOf<String, String>()
        body.appVersion?.let { if (!VERSION.matches(it)) problems["appVersion"] = "This isn't a version" }
        body.platform?.let { if (it !in PLATFORMS) problems["platform"] = "Use web, android or ios" }
        body.screen?.let { if (!SCREEN.matches(it)) problems["screen"] = "Use the screen's route name" }
        body.language?.let { if (it !in LANGUAGES) problems["language"] = "Use en, te or hi" }
        body.errorCodes?.let { codes ->
            if (codes.size > MAX_ERROR_CODES) problems["errorCodes"] = "At most $MAX_ERROR_CODES"
            // Typed String, but JSON can still put a null in a list; that is not an error code either.
            else if (codes.any { code -> (code as String?)?.let { ERROR_CODE.matches(it) } != true }) {
                problems["errorCodes"] = "Only error codes, like plan_read_only"
            }
        }
        body.flags?.let { flags ->
            if (flags.size > MAX_FLAGS) problems["flags"] = "At most $MAX_FLAGS"
            else if (flags.any { (key, value) -> !FLAG.matches(key) || (value as Boolean?) == null }) {
                problems["flags"] = "Only switch names, like dataSaver, each on or off"
            }
        }
        if (problems.isNotEmpty()) {
            throw ApiException.badRequest(
                "validation_failed", "Some details need another look.", mapOf("fields" to problems),
            )
        }

        val households = jdbc.queryForList(
            "select household_id from household_memberships where user_id = :user and status = 'active'",
            mapOf("user" to userContext.require()), UUID::class.java,
        )
        return linkedMapOf(
            "appVersion" to body.appVersion,
            "platform" to body.platform,
            "screen" to body.screen,
            "language" to body.language,
            "errorCodes" to body.errorCodes.orEmpty(),
            "flags" to body.flags.orEmpty().toSortedMap(),
            "apiVersion" to OpenApiConfig.API_VERSION,
            "households" to households.size,
            "anyHouseholdReadOnly" to households.any { plans.isReadOnly(it) },
        )
    }

    private fun newCode(): String {
        val chars = CharArray(CODE_LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }
        return String(chars, 0, CODE_LENGTH / 2) + "-" + String(chars, CODE_LENGTH / 2, CODE_LENGTH / 2)
    }

    companion object {
        const val VALID_HOURS = 24
        const val MAX_LIVE = 5
        private const val MAX_ERROR_CODES = 10
        private const val MAX_FLAGS = 20
        private const val CODE_LENGTH = 10

        /** No 0/O, 1/I/L or U: read aloud over a phone, a code must survive being misheard. */
        private const val ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789"

        private val VERSION = Regex("^[0-9A-Za-z][0-9A-Za-z.+_-]{0,31}$")
        private val SCREEN = Regex("^[a-z][a-z0-9-]{0,39}$")
        private val ERROR_CODE = Regex("^[a-z][a-z0-9_]{1,63}$")
        private val FLAG = Regex("^[a-z][A-Za-z0-9]{0,39}$")
        private val PLATFORMS = setOf("web", "android", "ios")
        private val LANGUAGES = setOf("en", "te", "hi")

        val NEVER = listOf(
            "Amounts",
            "Names: yours, your family's, or a bank's",
            "What your records are called",
            "Documents, or anything in them",
            "Your phone number or email",
        )

        /** The same normalisation as ops.lookup_support_code: letters and digits, upper case. */
        fun hash(code: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(code.uppercase().filter { it.isLetterOrDigit() }.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        private const val SELECT =
            "select id, created_at, expires_at, revoked_at, lookups, last_looked_up_at, diagnostics::text as diagnostics " +
                "from support_codes "
    }
}

@RestController
@RequestMapping("/api/v1/me/support-codes")
class SupportCodeController(private val service: SupportCodeService) {

    @GetMapping
    fun supportCodes(): List<SupportCodeView> = service.list()

    /** Exactly what a code made from this would share. Stores nothing. */
    @PostMapping("/preview")
    fun previewSupportCode(@RequestBody body: SupportCodeBody): SupportCodePreview = service.preview(body)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createSupportCode(@RequestBody body: SupportCodeBody): CreatedSupportCode = service.create(body)

    /** Takes a code back. It stops working at once, and stays listed as taken back. */
    @PostMapping("/{id}/revoke")
    fun revokeSupportCode(@PathVariable id: UUID): SupportCodeView = service.revoke(id)
}
