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

    /**
     * Change the passphrase without rewriting a single field (docs/12 §6).
     *
     * The content key does not change. A new wrapping key is derived from the
     * new passphrase with a **new salt**, the same content key is rewrapped
     * under it, and `keyVersion` goes up by one. Every value already sealed
     * stays exactly as it is and keeps the key version it was written under —
     * which is why rotation is instant and re-encryption is not.
     *
     * The order matters and is the reason this is not four lines inline: the
     * old passphrase is proved first, via [unlock], so a rotation started with
     * a typo fails before anything is written. A rotation that wrote first and
     * checked afterwards would replace the only wrapped key in existence with
     * one derived from a passphrase nobody meant to set — and there is no
     * recovery in this scheme, so that is not a bug, it is the data gone.
     *
     * The local [keyVersion] is only advanced after the server has accepted the
     * new key, so a failed call leaves this object describing what is actually
     * stored.
     */
    suspend fun rotate(
        householdId: String,
        currentPassphrase: String,
        newPassphrase: String,
    ): RotateOutcome {
        // Prove the old passphrase before touching anything. This also leaves
        // the content key in hand, which is the thing being rewrapped.
        when (val unlocked = unlock(householdId, currentPassphrase)) {
            is UnlockOutcome.Unlocked -> Unit
            else -> return RotateOutcome.Refused(unlocked)
        }
        val key = contentKey ?: return RotateOutcome.Refused(UnlockOutcome.Unreadable)

        val salt = secureRandomBytes(SALT_BYTES)
        val wrappingKey = PassphraseKey.derive(newPassphrase, salt, ITERATIONS)
        val nextVersion = currentKeyVersion + 1

        val wrappedIv = secureRandomBytes(Envelope.IV_BYTES)
        val verifierIv = secureRandomBytes(Envelope.IV_BYTES)

        val stored = api.putE2eKey(
            householdId,
            E2eKeyEnvelope(
                kdf = KDF,
                kdfSalt = Envelope.encodeBase64Url(salt),
                iterations = ITERATIONS,
                wrapAlgorithm = WRAP,
                // Both wrap envelopes are stamped with the version they belong
                // to, not with 1 — a reference half that always wrote 1 would
                // agree with the other client until the first rotation.
                wrappedKey = Envelope.build(
                    nextVersion, wrappedIv, aesGcmSeal(wrappingKey, wrappedIv, key, null),
                ),
                verifier = Envelope.build(
                    nextVersion, verifierIv,
                    aesGcmSeal(key, verifierIv, VERIFIER.encodeToByteArray(), null),
                ),
                keyVersion = nextVersion,
            ),
        )

        currentKeyVersion = stored.keyVersion
        return RotateOutcome.Rotated(stored.keyVersion)
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

        /** What a new or rotated key is stretched with. The server's floor is 100 000. */
        const val ITERATIONS = 600_000

        /** 16 bytes, new on enable and new on every rotation — never reused. */
        const val SALT_BYTES = 16
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

sealed interface RotateOutcome {
    data class Rotated(val keyVersion: Int) : RotateOutcome
    /**
     * The old passphrase did not open the vault, so nothing was written. Carries
     * the reason rather than flattening it, because "wrong passphrase" and "this
     * was sealed by a newer Almira" are different sentences to a person.
     */
    data class Refused(val because: UnlockOutcome) : RotateOutcome
}

sealed interface OpenOutcome {
    data class Opened(val text: String) : OpenOutcome
    data object Locked : OpenOutcome
    data object NewerVersion : OpenOutcome
    data object Unreadable : OpenOutcome
}
