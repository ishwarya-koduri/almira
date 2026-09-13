package tech.bhrigu.almira.shared.zk

/**
 * What the app says under the two sealed lines of docs/20: where the original
 * is, and who holds the key or the papers.
 *
 * The key holder is another person, who never agreed to be written down
 * (docs/20 §4, docs/23). Until there is a legal answer on their consent, the
 * mitigation is in the words: ask for a role or a relationship, not a full
 * name, an address or a phone number, and say plainly that the line is
 * end-to-end encrypted so Almira cannot read it. The web client says the same
 * in English, Telugu and Hindi (`where.keyHolderHelp`, `where.locationHelp`).
 *
 * English only, like the rest of this app so far.
 */
object WhereAndWhoWording {
    /** The field keys the server hands out in `fieldKeys` (docs/20 §2). */
    const val ORIGINAL_LOCATION = "original_location"
    const val KEY_HOLDER = "key_holder"

    const val KEY_HOLDER_LABEL = "Key or papers with"
    const val ORIGINAL_LOCATION_LABEL = "Original is at"

    const val KEY_HOLDER_HELP =
        "Write a role or a relationship — “Amma”, “the CA”, “my brother” — not a full name, " +
            "an address or a phone number. It names another person, so say only what your family " +
            "needs. End-to-end encrypted: Almira can't read it, and we never contact them."

    const val ORIGINAL_LOCATION_HELP =
        "Enough for your family to find it — “steel almirah, second shelf”, “the SBI locker”. " +
            "No street address or locker number needed. End-to-end encrypted: Almira can't read it."

    /** The guidance for a sealed field, when it is one of the two; null otherwise. */
    fun helpFor(fieldKey: String): String? = when (fieldKey.trim()) {
        KEY_HOLDER -> KEY_HOLDER_HELP
        ORIGINAL_LOCATION -> ORIGINAL_LOCATION_HELP
        else -> null
    }

    /** A person's words for a known field key; the key itself for any other. */
    fun labelFor(fieldKey: String): String = when (fieldKey) {
        KEY_HOLDER -> KEY_HOLDER_LABEL
        ORIGINAL_LOCATION -> ORIGINAL_LOCATION_LABEL
        else -> fieldKey
    }
}
