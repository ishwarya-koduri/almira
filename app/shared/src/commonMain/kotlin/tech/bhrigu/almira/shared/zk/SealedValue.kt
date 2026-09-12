package tech.bhrigu.almira.shared.zk

/**
 * What a sealed value **is**, on the wire: the raw UTF-8 bytes of a string.
 *
 * No JSON. No object. No key ordering. This is B5 of
 * docs/zk-interop-acceptance.md, and it is the good news of the whole scheme —
 * the canonicalisation problem everyone expects here does not exist, because
 * there is no structure to canonicalise. A value that looks like `{"a":1}` is
 * seven characters and comes back as seven characters, because no client parses
 * it.
 *
 * Two rules follow, and both are load-bearing:
 *
 * 1. **Nothing wraps a value.** Not "for convenience", not to carry a unit or a
 *    timestamp alongside it. The moment one client wraps and the other does
 *    not, every field written by one is unreadable by the other — and it will
 *    look like a decryption failure rather than a disagreement about format. If
 *    a sealed value ever genuinely needs structure, that is a **new version
 *    byte** in the envelope with both clients taught to read it (docs/12 §3).
 * 2. **Nothing normalises a value.** The passphrase is normalised, precisely
 *    and only because it is re-typed independently on each client
 *    ([PassphraseKey]). A value is bytes one client produced that another must
 *    reproduce exactly, so normalising it would silently rewrite what somebody
 *    wrote. Two rules pointing opposite ways; see [PassphraseKey] for the
 *    other one.
 *
 * It exists as a named pair rather than an inline `encodeToByteArray()` so that
 * the rules have somewhere to live and a test has something to hold.
 */
object SealedValue {

    /** The plaintext bytes. Verbatim — no trim, no normalise, no wrapper. */
    fun bytesOf(text: String): ByteArray = text.encodeToByteArray()

    /** And back, which is a decode and nothing else. */
    fun textOf(bytes: ByteArray): String = bytes.decodeToString()
}
