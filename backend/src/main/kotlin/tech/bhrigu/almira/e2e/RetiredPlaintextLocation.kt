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

    /** The one attribute key the retired column could hide under. */
    const val ATTRIBUTE_KEY = "storage_location"

    /**
     * Attributes a caller supplies without a schema to check them against (a
     * template's). Text under the retired key is refused like the field; an
     * empty value is dropped, because the database refuses the key itself.
     */
    fun withoutRetiredAttribute(attributes: Map<String, Any?>?): Map<String, Any?>? {
        if (attributes == null || ATTRIBUTE_KEY !in attributes) return attributes
        refuseIfSent("attributes.$ATTRIBUTE_KEY", attributes[ATTRIBUTE_KEY]?.toString())
        return attributes - ATTRIBUTE_KEY
    }
}
