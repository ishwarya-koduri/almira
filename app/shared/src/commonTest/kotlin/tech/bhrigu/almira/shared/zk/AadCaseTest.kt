package tech.bhrigu.almira.shared.zk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The lowercasing rule in the AAD, on its own, with its own name.
 *
 * It exists separately from every other AAD test because of how it fails.
 * Get this wrong and a value seals fine, stores fine, comes back fine, and
 * then will not open **in the client that wrote it** — and what you see is
 * an authentication-tag failure, which reads exactly like a broken cipher, a
 * wrong key or a corrupted database. The actual cause is that `record_id` is a
 * Postgres `uuid` column, so the server echoes it lowercase whatever it was
 * sent, and a client that sealed with an uppercase id is now building a
 * different AAD than the one it sealed under.
 *
 * Every failure message below says **AAD case mismatch** for that reason. The
 * next person to see one should spend a minute here rather than a day on the
 * cipher.
 *
 * docs/12 §4.
 */
class AadCaseTest {

    private val upper = "58276CAE-2448-4D51-8C9D-29FEFD3225D4"
    private val lower = "58276cae-2448-4d51-8c9d-29fefd3225d4"
    private val recordUpper = "167D9136-E238-48CF-B093-0F51D9A43C8D"
    private val recordLower = "167d9136-e238-48cf-b093-0f51d9a43c8d"

    @Test
    fun `AAD case mismatch - uuids in any case produce identical additional data`() {
        val sealed = Aad.of(upper, "investment", recordUpper, "locker_address").decodeToString()
        val opened = Aad.of(lower, "investment", recordLower, "locker_address").decodeToString()
        val mixed = Aad.of(
            "58276Cae-2448-4D51-8c9D-29fefd3225d4", "investment",
            "167d9136-E238-48cf-B093-0f51D9a43c8d", "locker_address",
        ).decodeToString()

        assertEquals(
            sealed, opened,
            "AAD case mismatch: the same ids in different case produced different additional " +
                "data. A value sealed with an uppercase id will not open against the server's " +
                "lowercase echo, and the symptom is a tag failure that looks like a broken " +
                "cipher. Aad.of must lowercase both uuids — docs/12 §4.",
        )
        assertEquals(
            sealed, mixed,
            "AAD case mismatch: a mixed-case id produced different additional data from a " +
                "lowercase one. See docs/12 §4.",
        )
        assertTrue(
            sealed.startsWith(lower),
            "AAD case mismatch: the additional data does not begin with the lowercase " +
                "household id. It is '$sealed'.",
        )
    }

    @Test
    fun `AAD case mismatch - the record type and field key are used verbatim`() {
        // The other half of the rule, and the reason it cannot simply be
        // `lowercase()` on the whole string: a field key is chosen by the
        // client and is not a uuid, so folding its case would make
        // `Locker_Address` and `locker_address` the same field — silently
        // opening one value under another's name.
        val asWritten = Aad.of(lower, "investment", recordLower, "Locker_Address").decodeToString()
        val folded = Aad.of(lower, "investment", recordLower, "locker_address").decodeToString()

        assertTrue(
            asWritten != folded,
            "AAD case mismatch, in the opposite direction: the field key was case-folded. " +
                "Only the two uuids are lowercased; recordType and fieldKey are verbatim, or " +
                "two different fields become one. docs/12 §4.",
        )
        assertTrue(
            asWritten.endsWith("|Locker_Address"),
            "AAD case mismatch: the field key was altered on the way in. It is '$asWritten'.",
        )
    }

    @Test
    fun `AAD case mismatch - the conformance vector's additional data is exactly this`() {
        // The same assertion as the vector in docs/12 §8.1, reached from the
        // uppercase ids the vector deliberately supplies — so this test fails
        // if the lowercasing is removed, even though the vector's own test
        // might be read as being about the key.
        assertEquals(
            "58276cae-2448-4d51-8c9d-29fefd3225d4|investment|" +
                "167d9136-e238-48cf-b093-0f51d9a43c8d|locker_address",
            Aad.of(upper, "investment", recordUpper, "locker_address").decodeToString(),
            "AAD case mismatch against the conformance vector in docs/12 §8.1. The ids are " +
                "supplied uppercase there on purpose, precisely to catch this.",
        )
    }
}
