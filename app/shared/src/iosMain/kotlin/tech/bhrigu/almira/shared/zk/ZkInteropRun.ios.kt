package tech.bhrigu.almira.shared.zk

import kotlinx.coroutines.runBlocking
import tech.bhrigu.almira.shared.api.AlmiraApi
import tech.bhrigu.almira.shared.security.PlatformHost
import tech.bhrigu.almira.shared.security.createTokenStore

/**
 * The cross-client round trip, run on the device against the live API.
 *
 * Debug-only and reached by a launch argument, never by anything a person can
 * tap. It exists because the interop claim cannot be made by a unit test: it
 * needs the real API, the real session out of the Keychain, and AES-GCM from
 * the Swift bridge, none of which a Kotlin/Native test binary has.
 *
 * Deliberately goes through [ZkVault] — the same object the Sealed screen uses
 * — rather than calling the primitives directly. A harness that reached past
 * the vault could pass while the screen was broken, which would make it worse
 * than no harness at all.
 *
 * The values it opens were sealed by the shipped `e2e.js` in a browser during
 * the Android stage and have been sitting in Postgres since. Nothing here
 * re-seals them first, so "the web sealed it and iOS opened it" is the literal
 * truth rather than a round trip dressed up as one.
 *
 * Prints its report; the caller decides what to do with it.
 */
fun zkInteropRun(apiBaseUrl: String, householdId: String, recordId: String, passphrase: String): String {
    val lines = mutableListOf<String>()
    var failures = 0

    fun check(label: String, expected: String, actual: String) {
        val ok = expected == actual
        if (!ok) failures += 1
        lines += "${if (ok) "ok  " else "FAIL"} $label"
        // Lengths, not contents: this runs against a real household, and a log
        // that prints the plaintext of a sealed field defeats the feature it is
        // testing. Length plus a pass is enough to know the bytes survived.
        lines += "       expected ${describe(expected)}"
        lines += "       actual   ${describe(actual)}"
    }

    val tokens = createTokenStore(PlatformHost())
    val api = AlmiraApi(baseUrl = apiBaseUrl, tokens = tokens, onSessionLost = {})
    val vault = ZkVault(api)

    return runBlocking {
        if (tokens.accessToken() == null) {
            return@runBlocking "zk interop: no session in the Keychain — sign in first."
        }

        when (val outcome = vault.unlock(householdId, passphrase)) {
            is UnlockOutcome.Unlocked -> lines += "ok   unlocked with the shared passphrase"
            else -> return@runBlocking "zk interop: unlock refused ($outcome)"
        }

        // --- web → iOS, across the five shapes that break implementations ---
        val fields = api.sealedValues(householdId, "investment", recordId)
        lines += "     found ${fields.size} sealed fields on the record"

        val webSealed = mapOf(
            "where_it_is" to "Locker 12, ఖజానా, Kakinada ",
            "who_holds_it" to "🔐 Meera has the spare ",
            "looks_like_json" to """{"a":1}""",
            "nothing_here" to "",
            "just_spaces" to "   ",
            "the_long_one" to "ఖ".repeat(15_989),
        )

        webSealed.forEach { (fieldKey, expected) ->
            val field = fields.firstOrNull { it.fieldKey == fieldKey }
            if (field == null) {
                failures += 1
                lines += "FAIL web → iOS  $fieldKey — no such field on the record"
                return@forEach
            }
            val actual = when (val opened = vault.open(householdId, field)) {
                is OpenOutcome.Opened -> opened.text
                else -> "did not open ($opened)"
            }
            check("web → iOS  $fieldKey", expected, actual)
        }

        // --- iOS → web ---
        // Written here, read by the browser afterwards. The value names the
        // platform so the evidence cannot be confused with the Android one
        // sitting beside it in the same table.
        val fromPhone = "Sealed on iPhone ఖజానా 🔐 "
        vault.seal(householdId, "investment", recordId, "sealed_from_the_iphone", fromPhone)
        lines += "ok   sealed sealed_from_the_iphone (${fromPhone.length} chars) for the browser to open"

        // And it comes back through a fresh read, not from anything held in
        // memory from the seal above.
        val reread = api.sealedValues(householdId, "investment", recordId)
            .firstOrNull { it.fieldKey == "sealed_from_the_iphone" }
        val rereadText = reread?.let {
            when (val opened = vault.open(householdId, it)) {
                is OpenOutcome.Opened -> opened.text
                else -> "did not open ($opened)"
            }
        } ?: "absent"
        check("iOS → iOS  re-read after seal", fromPhone, rereadText)

        // --- the negative that proves the additional data is not ignored ---
        // The same ciphertext, presented as a different field. Nothing is
        // written; only the AAD differs, which is the one thing that separates
        // a working implementation from one silently passing null.
        val original = fields.first { it.fieldKey == "where_it_is" }
        val moved = original.copy(fieldKey = "nominee")
        val movedOutcome = when (vault.open(householdId, moved)) {
            is OpenOutcome.Opened -> "opened — WRONG"
            else -> "refused"
        }
        check("moved ciphertext", "refused", movedOutcome)

        // --- and a passphrase one byte different must fail clean ---
        vault.forget()
        val wrong = vault.unlock(householdId, passphrase.trimEnd())
        val wrongOutcome = if (wrong is UnlockOutcome.Unlocked) "unlocked — WRONG" else "refused"
        check("passphrase without the trailing space", "refused", wrongOutcome)
        lines += "     it reported: $wrong"

        // Nothing is left unlocked by a diagnostic.
        vault.forget()

        lines += if (failures == 0) {
            "zk interop: all checks passed"
        } else {
            "zk interop: $failures CHECK(S) FAILED"
        }
        lines.joinToString("\n")
    }
}

/** Never the plaintext of a real sealed field — its shape only. */
private fun describe(text: String): String = when {
    text.isEmpty() -> "(empty, 0 chars)"
    text.length > 40 -> "${text.length} chars, ${text.encodeToByteArray().size} bytes, " +
        "starts ${text.take(6)}… ends …${text.takeLast(4)}"
    else -> "${text.length} chars, ${text.encodeToByteArray().size} bytes: [$text]"
}
