package tech.bhrigu.almira.shared.zk

/**
 * Passphrase → wrapping key, the one way both clients must agree on.
 *
 * This file is the whole of B1 and B2 from docs/zk-interop-acceptance.md, and
 * it is deliberately the only place either step can happen.
 *
 * ### Two rules, pointing opposite ways
 *
 * **The passphrase is normalised. The value never is.**
 *
 * A passphrase is re-typed independently on every client. "ఖ" and "é" each have
 * more than one valid Unicode spelling, and a browser IME and an Android IME
 * can emit different bytes for the same keystrokes. PBKDF2 turns one differing
 * byte into an entirely different key, so the passphrase would work in the
 * browser, fail on the phone, be indistinguishable from a typo, and — because
 * there is deliberately no recovery — take the data with it. Normalising to NFC
 * first is what makes the same keystrokes derive the same key everywhere.
 *
 * A sealed value is the opposite case: bytes one client produced that another
 * must reproduce exactly. Normalising it on the way in or out would silently
 * rewrite what somebody wrote. So there is no `normalize` anywhere near a
 * value, and this doc comment exists so nobody adds one for symmetry.
 *
 * **Nothing is trimmed, either.** A trailing space belongs to the passphrase.
 * Both clients keep it byte for byte, and the interface warns rather than
 * quietly "helping" — a space removed by one client and not the other is the
 * same unrecoverable failure as a mismatched Unicode form.
 */
object PassphraseKey {

    /**
     * The bytes PBKDF2 is fed. NFC, then UTF-8, and nothing else — no trim, no
     * case folding, no width folding.
     */
    fun bytesOf(passphrase: String): ByteArray =
        normalizeNfc(passphrase).encodeToByteArray()

    /**
     * The 256-bit wrapping key.
     *
     * [iterations] comes from the server for an existing key and is 600 000 for
     * a new one — never a constant at the call site, because a key derived with
     * the wrong count is simply a different key.
     */
    fun derive(passphrase: String, salt: ByteArray, iterations: Int): ByteArray =
        pbkdf2HmacSha256(
            password = bytesOf(passphrase),
            salt = salt,
            iterations = iterations,
            keyLengthBits = KEY_LENGTH_BITS,
        )

    const val KEY_LENGTH_BITS = 256

    /** What a new key is derived with. Existing keys use the stored count. */
    const val NEW_KEY_ITERATIONS = 600_000
}

/**
 * Unicode NFC. Platform-provided because there is no correct small version of
 * this — the composition tables are the standard.
 */
expect fun normalizeNfc(text: String): String

/**
 * PBKDF2-HMAC-SHA256 over **bytes we encoded ourselves**.
 *
 * Deliberately not `PBEKeySpec` with `SecretKeyFactory`. That API takes a
 * `char[]`, and turning those chars into bytes is the *provider's* business —
 * Android's provider is not the JDK's, and the encoding it picks is not part of
 * any contract we can point at. Since that step is exactly the one that decides
 * whether two clients derive the same key, it does not get delegated: the
 * password arrives here as bytes, already canonical, and the primitive below is
 * only ever asked to do HMAC-SHA256, which is unambiguous.
 */
expect fun pbkdf2HmacSha256(
    password: ByteArray,
    salt: ByteArray,
    iterations: Int,
    keyLengthBits: Int,
): ByteArray
