package tech.bhrigu.almira.e2e

import tech.bhrigu.almira.common.ApiException

/**
 * The plaintext "where it is kept" fields, retired (V33, docs/20 §1).
 *
 * `storageLocation` on holdings and templates and `location` on wills and
 * paperwork used to be plain text the server could read, search and print.
 * The columns are gone. The request fields stay in the v1 schema, because v1 is
 * additive-only, and the response fields stay too, always absent.
 *
 * A request that still carries text in one is **refused, not ignored**.
 * Ignoring it would answer 200 to a client that believes it just recorded
 * where the will is, when nothing was recorded; the person finds out on the
 * day the family needs it. A refusal is seen now, by the person typing. The
 * refusal never echoes the text — a message or a log line carrying it would
 * put the sentence back on the server in a new place.
 *
 * Null and blank carry no location, so they are accepted and do nothing: an
 * older client that sends `""` for an empty field, or the old "clear the note"
 * PATCH, keeps working.
 */
object RetiredPlaintextLocation {
    const val CODE = "plaintext_location_retired"

    fun refuseIfSent(field: String, value: String?) {
        if (value.isNullOrBlank()) return
        throw ApiException.badRequest(
            CODE,
            "Where the original is, and who holds the key, are recorded sealed now, on the " +
                "record's “Where the original is” card. This field no longer takes text, " +
                "and nothing was saved.",
            mapOf("field" to field),
        )
    }

    /**
     * The attribute keys a plaintext "where" sentence could live under: the
     * retired column's own name (V33), and the two seeded type fields that asked
     * the same question in plain text — "Where the agreement is" on a business
     * stake and "Where the keys are" on crypto (V34). They can never be a type
     * field, a custom field or an attribute again.
     */
    val ATTRIBUTE_KEYS: Set<String> = setOf("storage_location", "agreement_location", "wallet_hint")

    /**
     * Attributes on their way in — a holding's, before the schema check, and a
     * template's, which has no schema check. Text under a retired key is refused
     * like the field; an empty value is dropped, because the database refuses
     * the key itself.
     */
    fun withoutRetiredAttribute(attributes: Map<String, Any?>?): Map<String, Any?>? {
        if (attributes == null || ATTRIBUTE_KEYS.none { it in attributes }) return attributes
        ATTRIBUTE_KEYS.filter { it in attributes }.sorted().forEach { key ->
            refuseIfSent("attributes.$key", attributes[key]?.toString())
        }
        return attributes - ATTRIBUTE_KEYS
    }
}
