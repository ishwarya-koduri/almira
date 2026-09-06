package tech.bhrigu.almira.account

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.auth.StepUpService
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.crypto.EnvelopeCipher
import tech.bhrigu.almira.household.HouseholdService
import tech.bhrigu.almira.security.RequestUserContext
import java.util.UUID

data class HolderInput(val memberId: UUID, val holderType: String = "primary")

data class CreateAccount(
    val id: UUID? = null,
    val institutionId: UUID? = null,
    val accountKind: String,
    val label: String,
    /** As typed. Masked always; kept in full only if [storeFullNumber]. */
    val number: String? = null,
    /**
     * Opt-in, defaulting to false. docs/05 §5 asks for the minimum by default:
     * the last four digits identify an account to its owner, and the rest is
     * only worth the risk if they say so.
     */
    val storeFullNumber: Boolean = false,
    val ifsc: String? = null,
    val notes: String? = null,
    val holders: List<HolderInput> = emptyList(),
    val visibility: String? = null,
    val visibleToMemberIds: List<UUID> = emptyList(),
)

data class UpdateAccount(
    val version: Int,
    val label: String? = null,
    val institutionId: UUID? = null,
    val number: String? = null,
    val storeFullNumber: Boolean? = null,
    val ifsc: String? = null,
    val notes: String? = null,
    val holders: List<HolderInput>? = null,
)

@Service
class AccountService(
    private val repo: AccountRepository,
    private val households: HouseholdService,
    private val cipher: EnvelopeCipher,
    private val stepUp: StepUpService,
    private val audit: AuditService,
    private val userContext: RequestUserContext,
) {
    private val kinds = setOf("savings", "current", "demat", "folio", "wallet", "locker", "other")
    private val visibilities = setOf("private", "household", "scoped")
    private val holderTypes = setOf("primary", "joint")

    /** The column this value occupies, authenticated alongside the ciphertext. */
    private val numberField = "accounts.number_enc"

    @Transactional
    fun create(householdId: UUID, input: CreateAccount): AccountRow {
        val userId = userContext.require()
        val household = households.get(householdId)

        if (input.label.isBlank()) {
            throw ApiException.badRequest("label_required", "Give the account a name you'll recognise.")
        }
        if (input.accountKind !in kinds) {
            throw ApiException.badRequest("kind_invalid", "Choose one of: ${kinds.joinToString()}.")
        }

        val id = input.id ?: UUID.randomUUID()
        repo.find(householdId, id)?.let { return it }   // idempotent retry

        val holders = resolveHolders(householdId, household.myMemberId, input.holders)
        val visibility = resolveVisibility(input.visibility, household.defaultVisibility, userId)
        val grants = resolveGrants(householdId, visibility, input.visibleToMemberIds, holders)

        val masked = input.number?.let(::mask)
        val encrypted = if (input.storeFullNumber && !input.number.isNullOrBlank()) {
            cipher.encrypt(householdId, numberField, input.number.trim())
        } else {
            null
        }

        repo.insert(
            id = id, householdId = householdId, institutionId = input.institutionId,
            accountKind = input.accountKind, label = input.label.trim(),
            numberMasked = masked, numberEnc = encrypted,
            ifsc = input.ifsc?.trim()?.uppercase(), notes = input.notes,
            visibility = visibility, createdBy = userId,
        )
        repo.replaceHolders(id, holders)
        if (grants.isNotEmpty()) repo.replaceVisibilityGrants(householdId, id, grants, userId)

        audit.record(
            householdId = householdId, actorUserId = userId, action = "account.create",
            entityType = "account", entityId = id,
            // The number itself is never audited — only whether one was kept.
            diff = mapOf(
                "label" to input.label, "kind" to input.accountKind,
                "visibility" to visibility, "fullNumberStored" to (encrypted != null),
            ),
        )
        return repo.find(householdId, id)
            ?: throw ApiException.forbidden("Saved, but it's private to its holders.")
    }

    @Transactional(readOnly = true)
    fun list(householdId: UUID): List<AccountRow> {
        households.get(householdId)
        return repo.list(householdId)
    }

    @Transactional(readOnly = true)
    fun get(householdId: UUID, id: UUID): AccountRow {
        households.get(householdId)
        return repo.find(householdId, id) ?: throw ApiException.notFound()
    }

    @Transactional
    fun update(householdId: UUID, id: UUID, input: UpdateAccount): AccountRow {
        val userId = userContext.require()
        val household = households.get(householdId)
        val current = get(householdId, id)

        val masked = input.number?.let(::mask)
        val storeFull = input.storeFullNumber ?: false
        val encrypted = if (storeFull && !input.number.isNullOrBlank()) {
            cipher.encrypt(householdId, numberField, input.number.trim())
        } else {
            null
        }
        // Supplying a new number without opting in to storing it is an explicit
        // request to keep only the mask — so the old ciphertext must go, not
        // linger and disagree with what is displayed.
        val clearFull = input.number != null && !storeFull

        val updated = repo.update(
            id = id, version = input.version, label = input.label?.trim(),
            institutionId = input.institutionId, ifsc = input.ifsc?.trim()?.uppercase(),
            notes = input.notes, numberMasked = masked, numberEnc = encrypted,
            clearFullNumber = clearFull,
        )
        if (updated == 0) {
            // Nothing was written for one of two very different reasons, and
            // saying the wrong one is worse than unhelpful: a viewer or an
            // advisor told "someone else changed this" will reload, try again,
            // and see the same thing forever. If the version they sent is still
            // the current one, nobody changed anything — the write was refused.
            if (input.version == current.version) {
                throw ApiException.forbidden("You can read this, but it isn't yours to change.")
            }
            throw ApiException.conflict(
                "stale_write",
                "Someone else changed this while you were editing. Reload and try again.",
                mapOf("currentVersion" to current.version),
            )
        }

        input.holders?.let {
            repo.replaceHolders(id, resolveHolders(householdId, household.myMemberId, it))
        }
        audit.record(
            householdId = householdId, actorUserId = userId, action = "account.update",
            entityType = "account", entityId = id,
        )
        return get(householdId, id)
    }

    @Transactional
    fun changeVisibility(
        householdId: UUID,
        id: UUID,
        visibility: String,
        visibleToMemberIds: List<UUID>,
    ): AccountRow {
        val userId = userContext.require()
        households.get(householdId)
        val current = get(householdId, id)
        if (visibility !in visibilities) {
            throw ApiException.badRequest(
                "visibility_invalid", "Visibility must be private, household or scoped.",
            )
        }

        val grants = if (visibility == "scoped") {
            resolveGrants(
                householdId, visibility, visibleToMemberIds,
                current.holders.map { it.memberId to it.holderType },
            )
        } else {
            emptyList()
        }

        repo.updateVisibility(id, visibility)
        repo.replaceVisibilityGrants(householdId, id, grants, userId)
        audit.record(
            householdId = householdId, actorUserId = userId, action = "account.visibility_change",
            entityType = "account", entityId = id,
            diff = mapOf("from" to current.visibility, "to" to visibility, "sharedWith" to grants.size),
        )
        return get(householdId, id)
    }

    /**
     * The full number, once the caller has proved it is them.
     *
     * Three gates, and all three matter: RLS decides whether they may see the
     * account at all, step-up decides whether this session has recently
     * confirmed who is holding the device, and the view is audited so the record
     * shows which number was looked at and when — not merely that someone
     * re-authenticated.
     */
    @Transactional
    fun revealNumber(householdId: UUID, id: UUID): String {
        val userId = userContext.require()
        households.get(householdId)
        val account = get(householdId, id)

        stepUp.requireElevated(userId, userContext.currentSessionId())

        val blob = repo.encryptedNumber(householdId, id)
            ?: throw ApiException.badRequest(
                "no_full_number",
                "Only the last four digits are saved for this account.",
            )

        audit.record(
            householdId = householdId, actorUserId = userId, action = "account.number_revealed",
            entityType = "account", entityId = id, diff = mapOf("label" to account.label),
        )
        return cipher.decrypt(householdId, numberField, blob)
    }

    @Transactional
    fun archive(householdId: UUID, id: UUID) {
        val userId = userContext.require()
        households.get(householdId)
        val account = get(householdId, id)
        if (account.linkedInvestmentCount > 0) {
            throw ApiException.conflict(
                "account_in_use",
                "${account.linkedInvestmentCount} " +
                    (if (account.linkedInvestmentCount == 1) "holding is" else "holdings are") +
                    " linked to this account. Unlink them first.",
                mapOf("linked" to account.linkedInvestmentCount),
            )
        }
        repo.softDelete(id)
        audit.record(
            householdId = householdId, actorUserId = userId, action = "account.delete",
            entityType = "account", entityId = id,
        )
    }

    // --- helpers --------------------------------------------------------------

    /**
     * Keeps the last four digits, which is what identifies an account to the
     * person who owns it, and discards the rest.
     */
    private fun mask(number: String): String {
        val digits = number.filter(Char::isLetterOrDigit)
        return if (digits.length <= 4) "••••" else "••••" + digits.takeLast(4)
    }

    private fun resolveHolders(
        householdId: UUID,
        myMemberId: UUID?,
        requested: List<HolderInput>,
    ): List<Pair<UUID, String>> {
        if (requested.isEmpty()) {
            val me = myMemberId
                ?: throw ApiException.badRequest("holder_required", "Choose whose account this is.")
            return listOf(me to "primary")
        }
        val known = households.members(householdId).map { it.id }.toSet()
        requested.forEach {
            if (it.memberId !in known) {
                throw ApiException.badRequest(
                    "holder_unknown", "One of the holders isn't part of this household.",
                )
            }
            if (it.holderType !in holderTypes) {
                throw ApiException.badRequest(
                    "holder_type_invalid", "A holder is either primary or joint.",
                )
            }
        }
        if (requested.map { it.memberId }.toSet().size != requested.size) {
            throw ApiException.badRequest("holder_duplicate", "The same person is listed twice.")
        }
        // More than one holder means a joint account, whatever was submitted.
        return requested.map { it.memberId to (if (requested.size > 1) "joint" else it.holderType) }
    }

    private fun resolveVisibility(requested: String?, householdDefault: String, userId: UUID): String {
        if (requested != null) {
            if (requested !in visibilities) {
                throw ApiException.badRequest(
                    "visibility_invalid", "Visibility must be private, household or scoped.",
                )
            }
            return requested
        }
        return householdDefault
    }

    private fun resolveGrants(
        householdId: UUID,
        visibility: String,
        requested: List<UUID>,
        holders: List<Pair<UUID, String>>,
    ): List<UUID> {
        if (visibility != "scoped") return emptyList()
        if (requested.isEmpty()) {
            throw ApiException.badRequest("scope_empty", "Choose who you'd like to share this with.")
        }
        val known = households.members(householdId).map { it.id }.toSet()
        requested.forEach {
            if (it !in known) {
                throw ApiException.badRequest(
                    "scope_unknown", "One of those people isn't part of this household.",
                )
            }
        }
        return (requested + holders.map { it.first }).distinct()
    }
}
