package tech.bhrigu.almira.shared.zk

import tech.bhrigu.almira.shared.api.AlmiraApi
import tech.bhrigu.almira.shared.api.E2eKeyEnvelope
import tech.bhrigu.almira.shared.api.SealedField

/**
 * The content key, and the only place it ever exists.
 *
 * ### Memory only. Never persisted — not even Keystore-wrapped.
 *
 * This app now has a hardware-backed key store with a biometric in front of it,
 * and wrapping the content key with it would be the natural-looking thing to
 * do. It would also be wrong, and wrong in the way that quietly removes the
 * feature: it would make *device compromise plus a biometric* enough to read
 * zero-knowledge fields. The passphrase is a **second secret the device never
 * holds** — that is the entire difference between "sealed" and "stored
 * somewhere convenient", and it is why the browser asks again after a reload
 * rather than keeping the key in `localStorage`. The two clients therefore have
 * the same posture and not merely the same ciphertext
 * (docs/zk-interop-acceptance.md B9).
 *
 * So: held in a field while unlocked, dropped by [forget] when the app leaves
 * the foreground — the same moment the session's data key is dropped — and
 * re-derived from the passphrase next time. There is no code path here that
 * writes it anywhere.
 */
class ZkVault(private val api: AlmiraApi) {

    private var contentKey: ByteArray? = null
    private var currentKeyVersion: Int = 1

    val isUnlocked: Boolean get() = contentKey != null
    val keyVersion: Int get() = currentKeyVersion

    /**
     * Derive, unwrap, and **prove** before accepting.
     *
     * The verifier is the difference between "the wrap opened" and "this key
     * works": without it the first failure would land on somebody's actual
     * record, which is both a worse error and a worse place to discover it.
     */
    suspend fun unlock(householdId: String, passphrase: String): UnlockOutcome {
        val status = api.e2eStatus(householdId)
        val stored: E2eKeyEnvelope = status.key?.takeIf { status.enabled }
            ?: return UnlockOutcome.NotSetUp

        // A scheme we do not implement is not one to attempt. Guessing here
        // means deriving with the wrong primitive and reporting a wrong
        // passphrase to somebody who typed the right one.
        if (stored.kdf != KDF || stored.wrapAlgorithm != WRAP) return UnlockOutcome.UnknownScheme

        val salt = try {
            Envelope.decodeBase64Url(stored.kdfSalt)
        } catch (_: Exception) {
            return UnlockOutcome.Unreadable
        }

        val wrappingKey = PassphraseKey.derive(passphrase, salt, stored.iterations)

        val wrapped = try {
            Envelope.parse(stored.wrappedKey)
        } catch (_: UnsupportedEnvelopeVersion) {
            return UnlockOutcome.NewerVersion
        } catch (_: MalformedEnvelope) {
            return UnlockOutcome.Unreadable
        }

        val raw = try {
            aesGcmOpen(wrappingKey, wrapped.iv, wrapped.body, null)
        } catch (_: AeadFailure) {
            // Only a failed decrypt is a wrong passphrase. Everything above
            // returned its own answer, because telling someone their passphrase
            // is wrong when it is not is the worst sentence in a feature whose
            // data cannot be recovered.
            return UnlockOutcome.WrongPassphrase
        }
        if (raw.size != KEY_BYTES) return UnlockOutcome.Unreadable

        val verifier = try {
            Envelope.parse(stored.verifier)
        } catch (_: Exception) {
            return UnlockOutcome.Unreadable
        }
        val proof = try {
            aesGcmOpen(raw, verifier.iv, verifier.body, null).decodeToString()
        } catch (_: AeadFailure) {
            return UnlockOutcome.WrongPassphrase
        }
        if (proof != VERIFIER) return UnlockOutcome.WrongPassphrase

        contentKey = raw
        currentKeyVersion = stored.keyVersion
        return UnlockOutcome.Unlocked
    }

    /** Everything this object holds, gone. Called when the app leaves the foreground. */
    fun forget() {
        // Overwritten before being dropped: a reference the collector has not
        // got to yet is still the key sitting in the heap.
        contentKey?.fill(0)
        contentKey = null
    }

    suspend fun seal(
        householdId: String,
        recordType: String,
        recordId: String,
        fieldKey: String,
        value: String,
    ): SealedField {
        val key = contentKey ?: throw IllegalStateException("Unlock zero-knowledge mode first.")
        val iv = secureRandomBytes(Envelope.IV_BYTES)
        val aad = Aad.of(householdId, recordType, recordId, fieldKey)
        val body = aesGcmSeal(key, iv, SealedValue.bytesOf(value), aad)
        return api.putSealedValue(
            householdId = householdId,
            recordType = recordType,
            recordId = recordId,
            fieldKey = fieldKey,
            ciphertext = Envelope.build(currentKeyVersion, iv, body),
            keyVersion = currentKeyVersion,
        )
    }

    /**
     * The AAD is built from what the **server** echoed, not from what we asked
     * for, because that is what the other client will have used and what the
     * database actually holds.
     */
    fun open(householdId: String, field: SealedField): OpenOutcome {
        val key = contentKey ?: return OpenOutcome.Locked
        val envelope = try {
            Envelope.parse(field.ciphertext)
        } catch (_: UnsupportedEnvelopeVersion) {
            return OpenOutcome.NewerVersion
        } catch (_: MalformedEnvelope) {
            return OpenOutcome.Unreadable
        }
        val aad = Aad.of(householdId, field.recordType, field.recordId, field.fieldKey)
        return try {
            OpenOutcome.Opened(SealedValue.textOf(aesGcmOpen(key, envelope.iv, envelope.body, aad)))
        } catch (_: AeadFailure) {
            // A value that was moved, or written under a key we do not hold.
            // Shown as unreadable rather than thrown: one bad value must not
            // take a whole screen down with it.
            OpenOutcome.Unreadable
        }
    }

    companion object {
        const val KDF = "PBKDF2-SHA256"
        const val WRAP = "AES-GCM-256"
        const val VERIFIER = "almira"
        const val KEY_BYTES = 32
    }
}

sealed interface UnlockOutcome {
    data object Unlocked : UnlockOutcome
    data object WrongPassphrase : UnlockOutcome
    data object NotSetUp : UnlockOutcome
    data object NewerVersion : UnlockOutcome
    data object UnknownScheme : UnlockOutcome
    data object Unreadable : UnlockOutcome
}

sealed interface OpenOutcome {
    data class Opened(val text: String) : OpenOutcome
    data object Locked : OpenOutcome
    data object NewerVersion : OpenOutcome
    data object Unreadable : OpenOutcome
}
