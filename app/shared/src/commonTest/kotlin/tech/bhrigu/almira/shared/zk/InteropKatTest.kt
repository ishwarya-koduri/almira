package tech.bhrigu.almira.shared.zk

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The known answer from B4, asserted on **every** target this module builds for.
 *
 * This file lives in `commonTest` deliberately: the same constants, the same
 * assertions, run on the JVM and on Kotlin/Native. The constants came from
 * running the shipped `e2e.js` in a browser, so a pass here means the browser,
 * the JVM and iOS all produced the identical bytes — not that each is
 * internally consistent.
 *
 * The derived key is the one that matters most. A round trip proves two
 * implementations interoperate; only this proves they derived the key *the same
 * way* rather than a compatible-looking one. If a round trip ever passes while
 * this fails, something is compensating, and a compensating difference in a key
 * derivation is the failure that cannot be recovered from.
 *
 * The envelope half of the vector needs AES-GCM, which is reachable from Kotlin
 * on Android but not on iOS — see `AeadUnavailableOnIos` in the report and
 * docs/known-issues.md. It is asserted in `androidUnitTest` and at runtime in
 * the iOS app.
 */
class InteropKatTest {

    private val passphrase = "correct horse battery staple "
    private val salt = ByteArray(16) { it.toByte() }
    private val iterations = 600_000
    private val household = "58276CAE-2448-4D51-8C9D-29FEFD3225D4"
    private val record = "167D9136-E238-48CF-B093-0F51D9A43C8D"
    private val fieldKey = "locker_address"

    @Test
    fun `the derived key matches the constant on every platform`() {
        val expected = "17c0b45fe7d3dcc10b70395e28a8cc533a0c8113691b174d39b8a205f2085f6f"
        val actual = hexOf(PassphraseKey.derive(passphrase, salt, iterations))
        // Printed as well as asserted, so a review can read the bytes rather
        // than trust that a comparison happened.
        println("KAT derived key  expected $expected")
        println("KAT derived key  actual   $actual")
        assertEquals(expected, actual, "the derived key drifted from the checked-in constant")
    }

    @Test
    fun `the additional data matches the constant on every platform`() {
        val expected = "58276cae-2448-4d51-8c9d-29fefd3225d4|investment|" +
            "167d9136-e238-48cf-b093-0f51d9a43c8d|locker_address"
        val actual = Aad.of(household, "investment", record, fieldKey).decodeToString()
        println("KAT aad          expected $expected")
        println("KAT aad          actual   $actual")
        assertEquals(expected, actual, "the additional data drifted from the checked-in constant")
    }
}
