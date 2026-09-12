package tech.bhrigu.almira.shared.zk

/**
 * The additional authenticated data that binds a sealed value to the one place
 * it belongs (docs/12 §4, docs/zk-interop-acceptance.md A4 and B3).
 *
 * ```
 * AAD = UTF-8 of  "{householdId}|{recordType}|{recordId}|{fieldKey}"
 * ```
 *
 * Without it, anyone able to write the database could move a ciphertext onto
 * another record and have the client decrypt it there, under a label it was
 * never written for. With it, a moved value fails to open — which is the
 * correct outcome, and the only thing that distinguishes an AAD that works from
 * one that is being silently ignored.
 *
 * Built in exactly one place so that sealing and opening cannot disagree, which
 * is precisely how the case below goes wrong.
 */
object Aad {

    /**
     * **UUIDs are lowercased, on seal and on open, whatever the source.**
     *
     * `household_id` and `record_id` are Postgres `uuid` columns, so the server
     * echoes them lowercase whatever it was sent. A client seals with the id it
     * happens to be holding and opens with the server's echo — so an id that
     * arrived uppercase from anywhere produces a value that will not open *in
     * the client that wrote it*, with no error to explain why. Canonicalising
     * here is what makes the two calls meet.
     *
     * [lowercase] without a locale on purpose: this must be the same mapping on
     * every device. The locale-sensitive variant is a different function, and on
     * a Turkish device it is a different answer.
     */
    fun of(householdId: String, recordType: String, recordId: String, fieldKey: String): ByteArray {
        val parts = listOf(householdId.lowercase(), recordType, recordId.lowercase(), fieldKey)

        // The separator has to stay a separator. Nothing reaching here can hold
        // one today — two of these are uuid columns and the third is a fixed
        // vocabulary, so the first three pipes always delimit exactly, and a
        // field key full of pipes still parses unambiguously. This is for the
        // day a fifth component is added and that reasoning quietly stops being
        // true. Refusing costs nothing; finding out later costs a collision
        // nobody can see.
        val offender = parts.firstOrNull { it.contains(SEPARATOR) }
        require(offender == null) {
            "A household id, record type, record id or field name may not contain '$SEPARATOR'."
        }

        return parts.joinToString(SEPARATOR.toString()).encodeToByteArray()
    }

    /** U+007C VERTICAL LINE. Not escaped, so it may not appear in any component. */
    const val SEPARATOR: Char = '|'
}
