package tech.bhrigu.almira.document

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.auth.StepUpService
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.config.AlmiraProperties
import tech.bhrigu.almira.crypto.EnvelopeCipher
import tech.bhrigu.almira.household.HouseholdService
import tech.bhrigu.almira.security.JwtService
import tech.bhrigu.almira.security.RequestUserContext
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

data class DocumentUpload(
    val fileName: String,
    val mimeType: String?,
    val bytes: ByteArray,
    val docType: String,
    val expiresOn: LocalDate?,
    val visibility: String?,
    val notes: String?,
    val linkTo: List<Pair<String, UUID>>,
)

data class MissingProof(val investmentId: UUID, val title: String)

/** A one-time, short-lived ticket to fetch one document's bytes. */
data class DownloadTicket(val token: String, val expiresInSeconds: Long, val fileName: String)

data class DocumentContent(val fileName: String, val mimeType: String, val bytes: ByteArray)

@Service
class DocumentService(
    private val repo: DocumentRepository,
    private val storage: DocumentStorage,
    private val cipher: EnvelopeCipher,
    private val households: HouseholdService,
    private val stepUp: StepUpService,
    private val audit: AuditService,
    private val redis: StringRedisTemplate,
    private val jwt: JwtService,
    private val userContext: RequestUserContext,
    transactionManager: org.springframework.transaction.PlatformTransactionManager,
    props: AlmiraProperties,
) {
    private val transactions =
        org.springframework.transaction.support.TransactionTemplate(transactionManager)

    private val maxBytes = props.storage.maxFileBytes
    private val docTypes = setOf(
        "certificate", "statement", "receipt", "policy", "deed", "photo", "kyc", "will", "other",
    )
    private val entityTypes = setOf("investment", "liability", "account", "member", "estate")

    /** The column the ciphertext occupies, authenticated alongside it. */
    private val contentField = "documents.content"

    @Transactional
    fun upload(householdId: UUID, input: DocumentUpload): DocumentRow {
        val userId = userContext.require()
        val household = households.get(householdId)

        if (input.bytes.isEmpty()) {
            throw ApiException.badRequest("file_empty", "That file appears to be empty.")
        }
        if (input.bytes.size > maxBytes) {
            throw ApiException.badRequest(
                "file_too_large",
                "That file is larger than we can store (${maxBytes / 1024 / 1024} MB).",
            )
        }
        if (input.docType !in docTypes) {
            throw ApiException.badRequest("doc_type_invalid", "We don't recognise that kind of document.")
        }
        input.linkTo.forEach { (type, _) ->
            if (type !in entityTypes) {
                throw ApiException.badRequest("link_type_invalid", "That isn't something a document can attach to.")
            }
        }

        val id = UUID.randomUUID()
        // Household-scoped key: a stray listing of the bucket groups by family
        // rather than spilling everything into one flat namespace.
        val storageKey = "$householdId/$id"

        // Encrypted BEFORE it reaches storage, so the backend never holds
        // anything readable — a misconfigured bucket leaks ciphertext.
        storage.put(storageKey, cipher.encryptBytes(householdId, contentField, input.bytes))

        try {
            repo.insert(
                id = id, householdId = householdId, storageKey = storageKey,
                fileName = sanitise(input.fileName), mimeType = input.mimeType ?: "application/octet-stream",
                sizeBytes = input.bytes.size.toLong(), contentSha256 = sha256(input.bytes),
                docType = input.docType, expiresOn = input.expiresOn,
                visibility = input.visibility ?: household.defaultVisibility,
                notes = input.notes, uploadedBy = userId,
            )
            input.linkTo.forEach { (type, entityId) -> repo.link(id, type, entityId) }
        } catch (e: Exception) {
            // The row is the index; without it the bytes are unreachable and
            // would sit in storage for ever. Roll the write back by hand,
            // because object storage does not join our transaction.
            runCatching { storage.delete(storageKey) }
            throw e
        }

        audit.record(
            householdId = householdId, actorUserId = userId, action = "document.upload",
            entityType = "document", entityId = id,
            diff = mapOf("fileName" to input.fileName, "docType" to input.docType),
        )
        return repo.find(householdId, id)
            ?: throw ApiException.forbidden("Saved, but it's private to what it's attached to.")
    }

    @Transactional(readOnly = true)
    fun list(householdId: UUID, entityType: String?, entityId: UUID?): List<DocumentRow> {
        households.get(householdId)
        return repo.list(householdId, entityType, entityId)
    }

    @Transactional(readOnly = true)
    fun get(householdId: UUID, id: UUID): DocumentRow {
        households.get(householdId)
        return repo.find(householdId, id) ?: throw ApiException.notFound()
    }

    /**
     * Issues a short-lived, single-use ticket for one document.
     *
     * The equivalent of a pre-signed URL, without needing object storage to
     * issue one: the bearer token is minted here, after the caller has passed
     * both the visibility check and a step-up re-authentication (docs/05 §4),
     * and it is consumed on use. It carries no authority beyond that one file.
     *
     * The two-step exists so the fetch itself can be a plain GET — an <img> or
     * an <iframe> can load it — without that GET being an authenticated
     * endpoint that a stray URL in a log or a referrer could reopen.
     */
    @Transactional
    fun issueTicket(householdId: UUID, id: UUID): DownloadTicket {
        val userId = userContext.require()
        val document = get(householdId, id)
        stepUp.requireElevated(userId, userContext.currentSessionId())

        val token = jwt.newRefreshToken()
        redis.opsForValue().set(ticketKey(token), "$householdId:$id:$userId", TICKET_TTL)

        audit.record(
            householdId = householdId, actorUserId = userId, action = "document.access_granted",
            entityType = "document", entityId = id, diff = mapOf("fileName" to document.fileName),
        )
        return DownloadTicket(token, TICKET_TTL.seconds, document.fileName)
    }

    /**
     * Redeems a ticket. Single use: the key is deleted before the bytes are
     * read, so a token that leaks through a log or a shared screenshot is
     * already spent.
     */
    fun redeem(token: String): DocumentContent {
        val key = ticketKey(token)
        val value = redis.opsForValue().get(key)
            ?: throw ApiException.notFound("That link has expired. Open the document again.")
        redis.delete(key)

        val (householdId, documentId, userId) = value.split(":").map(UUID::fromString)

        // ORDER MATTERS. The identity has to be in place BEFORE the transaction
        // opens, because RlsTransactionManager stamps app.user_id at BEGIN.
        // Annotating this method @Transactional would start the transaction
        // first, capture no identity, and every row-level security predicate
        // would deny — the request would 404 as if the document had been
        // deleted. Hence runAs on the outside, TransactionTemplate on the in.
        return userContext.runAs(userId) {
            transactions.execute {
                // Re-checked as the GRANTING user, not as whoever presents the
                // token: access could have been revoked in the seconds since the
                // ticket was issued, and a ticket must not outlive the
                // permission behind it.
                val document = repo.find(householdId, documentId)
                    ?: throw ApiException.notFound("That link is no longer valid.")
                DocumentContent(
                    document.fileName,
                    document.mimeType,
                    cipher.decryptBytes(householdId, contentField, storage.get(document.storageKey)),
                )
            }!!
        }
    }

    @Transactional
    fun link(householdId: UUID, id: UUID, entityType: String, entityId: UUID): DocumentRow {
        households.get(householdId)
        get(householdId, id)
        if (entityType !in entityTypes) {
            throw ApiException.badRequest("link_type_invalid", "That isn't something a document can attach to.")
        }
        repo.link(id, entityType, entityId)
        return get(householdId, id)
    }

    @Transactional
    fun unlink(householdId: UUID, id: UUID, entityType: String, entityId: UUID): DocumentRow {
        households.get(householdId)
        get(householdId, id)
        repo.unlink(id, entityType, entityId)
        return get(householdId, id)
    }

    /**
     * Soft delete only. The bytes stay until a purge runs: a proof deleted by
     * accident is exactly the thing someone needs back, and object storage has
     * no undo (docs/01 §11).
     */
    @Transactional
    fun archive(householdId: UUID, id: UUID) {
        val userId = userContext.require()
        households.get(householdId)
        get(householdId, id)
        repo.softDelete(householdId, id)
        audit.record(
            householdId = householdId, actorUserId = userId, action = "document.delete",
            entityType = "document", entityId = id,
        )
    }

    @Transactional(readOnly = true)
    fun missingProof(householdId: UUID): List<MissingProof> {
        households.get(householdId)
        return repo.holdingsWithoutProof(householdId).map { MissingProof(it.first, it.second) }
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Strips any path from a client-supplied name; only the name is kept. */
    private fun sanitise(fileName: String): String =
        fileName.substringAfterLast('/').substringAfterLast('\\').trim()
            .ifEmpty { "document" }
            .take(200)

    private fun ticketKey(token: String) = "document:ticket:${jwt.hash(token)}"

    private companion object {
        val TICKET_TTL: Duration = Duration.ofMinutes(2)
    }
}
