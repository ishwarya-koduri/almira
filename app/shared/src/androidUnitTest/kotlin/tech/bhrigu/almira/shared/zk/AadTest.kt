package tech.bhrigu.almira.shared.zk

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * B3, proved against a real AEAD rather than against string equality.
 *
 * Comparing two AAD byte arrays would only show that this file agrees with
 * itself. What has to be true is that a value sealed one way **cannot be
 * opened** the other way — so these tests run AES-256-GCM with the envelope's
 * real parameters (12-byte IV, 128-bit tag) and watch the open fail.
 *
 * The cipher here is test scaffolding, not the shipping seal path: that arrives
 * with the zero-knowledge stage. It is spelled out so the negative is a genuine
 * cryptographic failure and not a claim about it.
 */
class AadTest {

    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val iv = ByteArray(12) { (it * 7).toByte() }

    private val household = "58276CAE-2448-4D51-8C9D-29FEFD3225D4"
    private val householdEchoed = "58276cae-2448-4d51-8c9d-29fefd3225d4"
    private val recordId = "167D9136-E238-48CF-B093-0F51D9A43C8D"
    private val recordIdEchoed = "167d9136-e238-48cf-b093-0f51d9a43c8d"

    private fun seal(plaintext: String, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext.encodeToByteArray())
    }

    private fun open(body: ByteArray, aad: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(body).decodeToString()
    }

    /** The AAD built from an uppercase id is the one built from the server's echo. */
    @Test
    fun `case does not change the AAD`() {
        assertContentEquals(
            Aad.of(householdEchoed, "investment", recordIdEchoed, "locker_address"),
            Aad.of(household, "investment", recordId, "locker_address"),
        )
    }

    /**
     * The whole point: seal holding an uppercase id, open with the lowercase
     * echo the server returns, and it works — because both went through [Aad].
     */
    @Test
    fun `a value sealed with an uppercase id opens against the lowercase echo`() {
        val sealed = seal("Locker 12, Kakinada branch", Aad.of(household, "investment", recordId, "locker_address"))
        assertEquals(
            "Locker 12, Kakinada branch",
            open(sealed, Aad.of(householdEchoed, "investment", recordIdEchoed, "locker_address")),
        )
    }

    /**
     * The control, and the reason B3 exists.
     *
     * Build the AAD the way a client that does not canonicalise would — raw
     * interpolation, uppercase preserved — and the server's lowercase echo
     * cannot open it. This is the failure being prevented, asserted, so that
     * removing the `lowercase()` turns this test red instead of shipping a
     * value nobody can read.
     */
    @Test
    fun `without canonicalising, the same client cannot open what it just sealed`() {
        val naive = "$household|investment|$recordId|locker_address".encodeToByteArray()
        val sealed = seal("Locker 12, Kakinada branch", naive)

        try {
            open(sealed, Aad.of(householdEchoed, "investment", recordIdEchoed, "locker_address"))
            fail("an uppercased AAD must not open against the server's lowercase echo")
        } catch (expected: javax.crypto.AEADBadTagException) {
            assertTrue(true)
        }
    }

    /** And a value really is bound to its own field, not merely to its record. */
    @Test
    fun `a value moved to another field does not open`() {
        val sealed = seal("Locker 12", Aad.of(household, "investment", recordId, "locker_address"))
        assertFailsWith<javax.crypto.AEADBadTagException> {
            open(sealed, Aad.of(household, "investment", recordId, "who_holds_the_key"))
        }
    }

    /** The separator stays a separator. */
    @Test
    fun `a component containing a pipe is refused`() {
        assertFailsWith<IllegalArgumentException> {
            Aad.of(householdEchoed, "investment", recordIdEchoed, "locker|address")
        }
        assertFailsWith<IllegalArgumentException> {
            Aad.of("58276cae|2448", "investment", recordIdEchoed, "locker_address")
        }
    }

    /** The exact bytes, so the other client has something to match. */
    @Test
    fun `the AAD is the joined string in UTF-8`() {
        assertEquals(
            "58276cae-2448-4d51-8c9d-29fefd3225d4|investment|167d9136-e238-48cf-b093-0f51d9a43c8d|locker_address",
            Aad.of(household, "investment", recordId, "locker_address").decodeToString(),
        )
    }
}
