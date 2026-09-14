package tech.bhrigu.almira.auth

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.yubico.webauthn.AssertionRequest
import com.yubico.webauthn.CredentialRepository
import com.yubico.webauthn.FinishAssertionOptions
import com.yubico.webauthn.FinishRegistrationOptions
import com.yubico.webauthn.RegisteredCredential
import com.yubico.webauthn.RelyingParty
import com.yubico.webauthn.StartAssertionOptions
import com.yubico.webauthn.StartRegistrationOptions
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria
import com.yubico.webauthn.data.ByteArray
import com.yubico.webauthn.data.PublicKeyCredential
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor
import com.yubico.webauthn.data.RelyingPartyIdentity
import com.yubico.webauthn.data.ResidentKeyRequirement
import com.yubico.webauthn.data.UserIdentity
import com.yubico.webauthn.data.UserVerificationRequirement
import com.yubico.webauthn.exception.AssertionFailedException
import com.yubico.webauthn.exception.RegistrationFailedException
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import tech.bhrigu.almira.audit.AuditService
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.common.EmailAddress
import tech.bhrigu.almira.common.PhoneNumber
import tech.bhrigu.almira.config.AlmiraProperties
import tech.bhrigu.almira.security.RequestUserContext
import java.nio.ByteBuffer
import java.time.Duration
import java.util.Optional
import java.util.UUID

/** A passkey just added, and recovery codes when it is the person's first second factor. */
data class PasskeyAdded(val passkey: PasskeyRow, val recoveryCodes: List<String>)

/** What the browser's `navigator.credentials` call is given, and the id to answer with. */
data class PasskeyCeremony(val requestId: String, val options: Map<String, Any?>)

/**
 * Passkeys (WebAuthn Level 3), verified by Yubico's server library.
 *
 * A passkey is a second factor here, and a way to confirm it's you before
 * something sensitive; it is not yet a way to sign in with nothing else. That
 * keeps one front door (a one-time code) while taking away what a recycled
 * number used to give: a stranger with the texts has no key.
 *
 * **Off unless configured.** A passkey is bound to its domain for life, so the
 * domain is never defaulted ([AlmiraProperties.WebAuthn]). Nothing here makes a
 * network call; attestation is not checked against a metadata service, because
 * which authenticator someone uses is their business, and `none` attestation is
 * what browsers send by default.
 *
 * **Row-level security.** The library asks a [CredentialRepository] for
 * credentials; every answer comes from the V50 table as the person the ceremony
 * is for, inside [SecondFactorService.asUser]. `lookupAll` therefore sees only
 * that person's passkeys — the unique index on `credential_id` is what stops a
 * credential being registered twice across accounts.
 *
 * **Ceremonies are single-use.** The challenge the library made is kept in
 * Redis for [CEREMONY_TTL] under a random id, bound to the person and the
 * purpose, and taken (GETDEL) by the first answer, right or wrong.
 */
@Service
class PasskeyService(
    props: AlmiraProperties,
    private val repo: SecondFactorRepository,
    private val factors: SecondFactorService,
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
    private val userContext: RequestUserContext,
    private val audit: AuditService,
    private val notices: AccountNotices,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val config = props.webauthn

    val available: Boolean = config.enabled

    private val relyingParty: RelyingParty? = if (!available) null else RelyingParty.builder()
        .identity(RelyingPartyIdentity.builder().id(config.rpId.trim()).name(config.rpName).build())
        .credentialRepository(Credentials())
        .origins(config.origins.map { it.trim() }.filter { it.isNotEmpty() }.toSet())
        .build()

    enum class Purpose { SIGN_IN, STEP_UP }

    // --- adding one ---------------------------------------------------------------------

    fun startRegistration(user: UserRow): PasskeyCeremony {
        val rp = requireAvailable()
        val label = user.phone?.let(PhoneNumber::mask) ?: user.email?.let(EmailAddress::mask) ?: "Almira account"
        val options = factors.asUser(user.id) {
            rp.startRegistration(
                StartRegistrationOptions.builder()
                    .user(UserIdentity.builder().name(label).displayName(user.fullName ?: label).id(handle(user.id)).build())
                    .authenticatorSelection(
                        AuthenticatorSelectionCriteria.builder()
                            .residentKey(ResidentKeyRequirement.PREFERRED)
                            .userVerification(UserVerificationRequirement.PREFERRED)
                            .build(),
                    )
                    .timeout(CEREMONY_TTL.toMillis())
                    .build(),
            )
        }
        val requestId = UUID.randomUUID().toString()
        redis.opsForValue().set(registrationKey(requestId), "${user.id}|${options.toJson()}", CEREMONY_TTL)
        return PasskeyCeremony(requestId, toMap(options.toCredentialsCreateJson()))
    }

    fun finishRegistration(userId: UUID, requestId: String, credential: Map<String, Any?>, name: String?, ip: String?): PasskeyAdded {
        val rp = requireAvailable()
        val stored = redis.opsForValue().getAndDelete(registrationKey(requestId))
            ?.takeIf { it.substringBefore('|') == userId.toString() }
            ?: throw ceremonyExpired()
        val cleanName = name?.trim()?.filter { !it.isISOControl() }?.take(60)?.takeIf { it.isNotEmpty() } ?: "Passkey"
        // The library throws checked exceptions, which a transaction callback
        // would wrap; each is turned into an answer inside the transaction.
        val added = try {
            factors.asUser(userId) {
                val result = try {
                    rp.finishRegistration(
                        FinishRegistrationOptions.builder()
                            .request(PublicKeyCredentialCreationOptions.fromJson(stored.substringAfter('|')))
                            .response(PublicKeyCredential.parseRegistrationResponseJson(mapper.writeValueAsString(credential)))
                            .build(),
                    )
                } catch (e: RegistrationFailedException) {
                    log.info("passkey registration refused: {}", e.javaClass.simpleName)
                    throw passkeyInvalid()
                } catch (e: java.io.IOException) {
                    throw passkeyInvalid()
                }
                val id = repo.insertPasskey(
                    userId, result.keyId.id.bytes, result.publicKeyCose.bytes, result.signatureCount, cleanName,
                )
                // Codes already written down stay good; a person with none gets them now.
                val codes = if (repo.unusedRecoveryCodes(userId).isEmpty()) factors.newRecoveryCodes(userId) else emptyList()
                PasskeyAdded(repo.passkeys(userId).first { it.id == id }, codes)
            }
        } catch (e: DuplicateKeyException) {
            throw ApiException.conflict("passkey_exists", "That passkey is already added.")
        }
        audit.record(null, userId, "auth.passkey_added", "passkey", added.passkey.id, ip = ip)
        notices.changed(userId, AccountNotices.Change.PASSKEY_ADDED)
        return added
    }

    fun list(userId: UUID): List<PasskeyRow> = factors.asUser(userId) { repo.passkeys(userId) }

    fun remove(userId: UUID, passkeyId: UUID, ip: String?) {
        val removed = factors.asUser(userId) {
            val n = repo.deletePasskey(userId, passkeyId)
            if (n > 0 && repo.passkeys(userId).isEmpty() && repo.totp(userId)?.confirmedAt == null) {
                repo.deleteRecoveryCodes(userId)
            }
            n
        }
        // Not yours and not there read the same.
        if (removed == 0) throw ApiException.notFound("We couldn't find that passkey.")
        audit.record(null, userId, "auth.passkey_removed", "passkey", passkeyId, ip = ip)
        notices.changed(userId, AccountNotices.Change.PASSKEY_REMOVED)
    }

    // --- using one -------------------------------------------------------------------

    fun startAssertion(userId: UUID, purpose: Purpose): PasskeyCeremony {
        val rp = requireAvailable()
        val request = factors.asUser(userId) {
            if (repo.passkeys(userId).isEmpty()) {
                throw ApiException.badRequest("no_passkey", "There's no passkey on this account.")
            }
            rp.startAssertion(
                StartAssertionOptions.builder()
                    .username(userId.toString())
                    .userVerification(UserVerificationRequirement.PREFERRED)
                    .timeout(CEREMONY_TTL.toMillis())
                    .build(),
            )
        }
        val requestId = UUID.randomUUID().toString()
        redis.opsForValue().set(assertionKey(requestId), "$userId|${purpose.name}|${request.toJson()}", CEREMONY_TTL)
        return PasskeyCeremony(requestId, toMap(request.toCredentialsGetJson()))
    }

    /**
     * True when [credential] is a valid answer, from one of [userId]'s passkeys,
     * to the challenge [requestId] made for [purpose]. False for anything else,
     * including a ceremony that has expired or was already answered.
     */
    fun verifyAssertion(userId: UUID, purpose: Purpose, requestId: String, credential: Map<String, Any?>): Boolean {
        val rp = relyingParty ?: return false
        val stored = redis.opsForValue().getAndDelete(assertionKey(requestId)) ?: return false
        val parts = stored.split('|', limit = 3)
        if (parts.size != 3 || parts[0] != userId.toString() || parts[1] != purpose.name) return false
        return factors.asUser(userId) {
            val result = try {
                rp.finishAssertion(
                    FinishAssertionOptions.builder()
                        .request(AssertionRequest.fromJson(parts[2]))
                        .response(PublicKeyCredential.parseAssertionResponseJson(mapper.writeValueAsString(credential)))
                        .build(),
                )
            } catch (e: AssertionFailedException) {
                log.info("passkey assertion refused: {}", e.javaClass.simpleName)
                null
            } catch (e: java.io.IOException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
            val ok = result != null && result.isSuccess && result.username == userId.toString()
            if (ok) repo.markPasskeyUsed(userId, result!!.credentialId.bytes, result.signatureCount)
            ok
        }
    }

    // --- the library's view of the table ------------------------------------------------

    /**
     * Called by the library inside [SecondFactorService.asUser], so the current
     * user is the person the ceremony is for and row-level security limits
     * every read to their passkeys.
     */
    private inner class Credentials : CredentialRepository {
        override fun getCredentialIdsForUsername(username: String): Set<PublicKeyCredentialDescriptor> =
            userOf(username)?.let { uid ->
                repo.passkeys(uid).map { PublicKeyCredentialDescriptor.builder().id(ByteArray(it.credentialId)).build() }.toSet()
            } ?: emptySet()

        override fun getUserHandleForUsername(username: String): Optional<ByteArray> =
            Optional.ofNullable(userOf(username)?.let(::handle))

        override fun getUsernameForUserHandle(userHandle: ByteArray): Optional<String> =
            Optional.ofNullable(userFromHandle(userHandle)?.toString())

        override fun lookup(credentialId: ByteArray, userHandle: ByteArray): Optional<RegisteredCredential> {
            val uid = userFromHandle(userHandle) ?: return Optional.empty()
            return Optional.ofNullable(repo.passkeyByCredential(uid, credentialId.bytes)?.let { registered(it) })
        }

        override fun lookupAll(credentialId: ByteArray): Set<RegisteredCredential> {
            val uid = userContext.currentUserId() ?: return emptySet()
            return setOfNotNull(repo.passkeyByCredential(uid, credentialId.bytes)?.let { registered(it) })
        }

        private fun registered(row: PasskeyRow) = RegisteredCredential.builder()
            .credentialId(ByteArray(row.credentialId))
            .userHandle(handle(row.userId))
            .publicKeyCose(ByteArray(row.publicKeyCose))
            .signatureCount(row.signatureCount)
            .build()

        /** Only the person the ceremony runs as; a username naming anyone else resolves to nobody. */
        private fun userOf(username: String): UUID? =
            runCatching { UUID.fromString(username) }.getOrNull()?.takeIf { it == userContext.currentUserId() }

        private fun userFromHandle(handle: ByteArray): UUID? {
            if (handle.size() != 16) return null
            val buffer = ByteBuffer.wrap(handle.bytes)
            return UUID(buffer.long, buffer.long).takeIf { it == userContext.currentUserId() }
        }
    }

    private fun requireAvailable(): RelyingParty = relyingParty ?: throw ApiException.serviceUnavailable(
        "passkeys_unavailable",
        "Passkeys aren't available on this server yet. You can use an authenticator app instead.",
    )

    private fun toMap(json: String): Map<String, Any?> = mapper.readValue(json, object : TypeReference<Map<String, Any?>>() {})

    private fun passkeyInvalid() =
        ApiException.badRequest("passkey_invalid", "That passkey couldn't be added. Please try again.")

    private fun ceremonyExpired() = ApiException.badRequest(
        "passkey_expired",
        "That took too long. Please try adding the passkey again.",
    )

    private fun registrationKey(requestId: String) = "webauthn:registration:$requestId"
    private fun assertionKey(requestId: String) = "webauthn:assertion:$requestId"

    companion object {
        val CEREMONY_TTL: Duration = Duration.ofMinutes(5)

        /** The WebAuthn user handle: the account id's 16 bytes. Random, stable, and no personal data. */
        fun handle(userId: UUID): ByteArray =
            ByteArray(ByteBuffer.allocate(16).putLong(userId.mostSignificantBits).putLong(userId.leastSignificantBits).array())
    }
}
