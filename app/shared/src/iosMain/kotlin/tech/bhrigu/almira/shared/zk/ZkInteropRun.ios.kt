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

        // --- a rotation, and values written before it ---
        //
        // docs/12 §8.5 listed this as proved on no client at all. It is the one
        // that cannot be discovered late: rotation rewraps the same content key
        // and rewrites no field, so if that is wrong, the first person to change
        // their passphrase loses every sealed value they have.
        //
        // Rotated to a temporary passphrase and then back, so the passphrase
        // docs/zk-interop-acceptance.md names still opens this household when
        // this returns. The key version rises either way — that is what a
        // rotation is — and every value keeps the version it was written under.
        val temporary = "a temporary passphrase for the rotation check ఖ "

        // Read again here rather than reusing the list from the top of this
        // run. That list was fetched before this run re-sealed
        // `sealed_from_the_iphone`, so it records that field's *previous* key
        // version — and the comparison below then fails on one field out of
        // eight and looks like a rotation defect. It was this harness holding a
        // stale baseline, which is a fair warning about how easy the mistake is.
        val beforeRotation = api.sealedValues(householdId, "investment", recordId)
        val versionsBefore = beforeRotation
            .associate { it.fieldKey to Envelope.parse(it.ciphertext).keyVersion }

        val rotated = vault.rotate(householdId, passphrase, temporary)
        check("rotate to a temporary passphrase", "rotated", when (rotated) {
            is RotateOutcome.Rotated -> "rotated"
            is RotateOutcome.Refused -> "refused (${rotated.because})"
        })

        // Forget everything and come back in with the new passphrase only, so
        // nothing below can be answered out of a key still in hand.
        vault.forget()
        val afterRotation = vault.unlock(householdId, temporary)
        check("unlock with the new passphrase", "Unlocked", afterRotation.toString().substringAfterLast('.'))

        // The old passphrase must now be refused, or the rotation did not happen.
        vault.forget()
        val oldRefused = vault.unlock(householdId, passphrase)
        check("the old passphrase no longer opens it", "WrongPassphrase",
            oldRefused.toString().substringAfterLast('.'))

        vault.forget()
        vault.unlock(householdId, temporary)
        val afterFields = api.sealedValues(householdId, "investment", recordId)
        var reopened = 0
        var keptVersion = 0
        afterFields.forEach { field ->
            val opened = vault.open(householdId, field)
            if (opened is OpenOutcome.Opened) reopened += 1
            if (Envelope.parse(field.ciphertext).keyVersion == versionsBefore[field.fieldKey]) keptVersion += 1
        }
        check("every field sealed before the rotation still opens",
            "${beforeRotation.size} of ${beforeRotation.size}", "$reopened of ${afterFields.size}")
        check("and each still carries the key version it was written under",
            "${beforeRotation.size} of ${beforeRotation.size}", "$keptVersion of ${afterFields.size}")

        // Back to the documented passphrase, so this run leaves the household
        // exactly as usable as it found it.
        val restored = vault.rotate(householdId, temporary, passphrase)
        check("rotate back to the documented passphrase", "rotated", when (restored) {
            is RotateOutcome.Rotated -> "rotated"
            is RotateOutcome.Refused -> "refused (${restored.because})"
        })
        vault.forget()
        check("the documented passphrase opens it again", "Unlocked",
            vault.unlock(householdId, passphrase).toString().substringAfterLast('.'))

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
