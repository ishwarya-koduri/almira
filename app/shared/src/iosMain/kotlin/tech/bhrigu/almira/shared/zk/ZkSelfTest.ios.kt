package tech.bhrigu.almira.shared.zk

/**
 * The envelope half of the B4 known-answer vector, asserted where it can be.
 *
 * The derived-key and AAD halves are asserted in `commonTest`, so they run on
 * the JVM and natively on iOS from the same file and the same constants. The
 * envelope half cannot: it needs AES-GCM, which on iOS arrives from Swift at
 * runtime and is therefore absent from a Kotlin/Native test binary.
 *
 * So it is checked at launch instead, against the same constant the browser
 * produced — not against a fresh iOS round trip, which would only prove iOS
 * agrees with itself. The round trip is asserted too, but second, and it is the
 * weaker of the two.
 *
 * Returns a report rather than throwing so the caller can print every line.
 * A failure here means the three implementations have diverged, and reading
 * which line went red is the whole value of running it.
 */
fun zkSelfTest(): String {
    val passphrase = "correct horse battery staple "
    val salt = ByteArray(16) { it.toByte() }
    val iterations = 600_000
    val iv = ByteArray(12) { (0xA0 + it).toByte() }
    val household = "58276CAE-2448-4D51-8C9D-29FEFD3225D4"
    val record = "167D9136-E238-48CF-B093-0F51D9A43C8D"
    val fieldKey = "locker_address"
    val plaintext = "Locker 12, ఖజానా, Kakinada "
    val envelopeConstant =
        "AQAAAAGgoaKjpKWmp6ipqqvPXvr272LHpln2v1MfVTtWxjXLbZR0eNYAsS5bJYmnCrpDPstqzByPY2RZI1X1WKjF52IsjQ"
    val keyConstant = "17c0b45fe7d3dcc10b70395e28a8cc533a0c8113691b174d39b8a205f2085f6f"

    val lines = mutableListOf<String>()
    var failures = 0

    fun check(label: String, expected: String, produce: () -> String) {
        // Each check produces its own value inside its own guard, so one red
        // line cannot hide the four below it. Found by breaking the iteration
        // count on purpose: the derived key went red, the envelope went red,
        // and then the open threw — taking the remaining checks and the summary
        // line with it, which is a worse report than no report.
        val actual = try {
            produce()
        } catch (failure: Throwable) {
            "threw: ${failure.message}"
        }
        val verdict = if (expected == actual) "ok  " else "FAIL"
        if (expected != actual) failures += 1
        lines += "$verdict $label"
        lines += "       expected $expected"
        lines += "       actual   $actual"
    }

    val key = PassphraseKey.derive(passphrase, salt, iterations)
    val aad = Aad.of(household, "investment", record, fieldKey)

    check("B4 derived key", keyConstant) { hexOfBytes(key) }

    check(
        "B4 additional data",
        "58276cae-2448-4d51-8c9d-29fefd3225d4|investment|" +
            "167d9136-e238-48cf-b093-0f51d9a43c8d|locker_address",
    ) { aad.decodeToString() }

    // The one that needed CryptoKit, and the reason this runs at launch rather
    // than in a test: sealing here must produce the browser's exact bytes.
    check("B4 envelope (sealed here)", envelopeConstant) {
        Envelope.build(1, iv, aesGcmSeal(key, iv, SealedValue.bytesOf(plaintext), aad))
    }

    // And the browser's envelope opens here, which is the half that matters to
    // a person. Asserted against the constant in both directions, never
    // against a fresh round trip — a round trip only proves iOS agrees with
    // itself, which is exactly the failure this is looking for.
    check("B4 envelope (opened here)", plaintext) {
        val parsed = Envelope.parse(envelopeConstant)
        SealedValue.textOf(aesGcmOpen(key, parsed.iv, parsed.body, aad))
    }

    // A negative, so a self-test that always says ok gets caught: the same
    // ciphertext under a different field's additional data must refuse.
    check("B4 moved ciphertext", "refused") {
        val parsed = Envelope.parse(envelopeConstant)
        try {
            aesGcmOpen(key, parsed.iv, parsed.body, Aad.of(household, "investment", record, "nominee"))
            "opened — WRONG"
        } catch (failure: AeadFailure) {
            "refused"
        }
    }

    lines += if (failures == 0) {
        "zk self-test: all five checks match the checked-in constants"
    } else {
        "zk self-test: $failures CHECK(S) FAILED — do not trust this build"
    }
    return lines.joinToString("\n")
}

/** `"%02x".format` is JVM-only, same as in the test helper next door. */
private fun hexOfBytes(bytes: ByteArray): String {
    val digits = "0123456789abcdef"
    val out = StringBuilder(bytes.size * 2)
    bytes.forEach {
        val value = it.toInt() and 0xFF
        out.append(digits[value shr 4]).append(digits[value and 0x0F])
    }
    return out.toString()
}
