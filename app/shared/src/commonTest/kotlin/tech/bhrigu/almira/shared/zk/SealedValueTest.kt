package tech.bhrigu.almira.shared.zk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The plaintext encoding, agreed byte for byte with the browser.
 *
 * Every expected value here was produced by running the shipped `e2e.js`
 * `valueBytes` against the running stack — not by reasoning about what UTF-8
 * ought to be. The two clients have to agree on the *bytes*, and the only way
 * to know they do is to ask one and assert against the other.
 */
class SealedValueTest {

    private fun hex(bytes: ByteArray) = hexOf(bytes)

    private fun roundTrip(text: String) = SealedValue.textOf(SealedValue.bytesOf(text))

    /** The one the whole ruling exists for. */
    @Test
    fun `a value that looks like JSON is a string not JSON`() {
        val value = """{"a":1}"""
        assertEquals("7b2261223a317d", hex(SealedValue.bytesOf(value)))
        assertEquals(value, roundTrip(value))
        assertEquals(7, value.length)
    }

    @Test
    fun `the byte encoding agrees with the web client`() {
        assertEquals(
            "e0b096e0b09ce0b0bee0b0a8e0b0be20e0b0a4e0b0bee0b0b3e0b082",
            hex(SealedValue.bytesOf("ఖజానా తాళం")),
        )
        assertEquals("f09f9490206c6f636b657220", hex(SealedValue.bytesOf("🔐 locker ")))
        assertEquals("", hex(SealedValue.bytesOf("")))
        assertEquals("202020", hex(SealedValue.bytesOf("   ")))
    }

    /**
     * The mirror of the passphrase rule, and the reason both are written down.
     *
     * A decomposed value stays decomposed. If someone ever "tidies" this the way
     * the passphrase is tidied, this test goes red — which is the only thing
     * standing between a note somebody wrote and a note we rewrote for them.
     */
    @Test
    fun `a value is never normalised unlike the passphrase`() {
        val decomposed = "café"
        assertEquals("63616665cc81", hex(SealedValue.bytesOf(decomposed)))
        assertNotEquals(hex(SealedValue.bytesOf("café")), hex(SealedValue.bytesOf(decomposed)))

        // And the contrast, stated in one place: the passphrase *is* folded.
        assertEquals(
            hex(PassphraseKey.bytesOf("café")),
            hex(PassphraseKey.bytesOf(decomposed)),
        )
    }

    @Test
    fun `every value in the matrix round-trips unchanged`() {
        listOf("""{"a":1}""", "ఖజానా తాళం", "🔐 locker ", "", "   ", "café", "line\nbreak", "tab\there")
            .forEach { assertEquals(it, roundTrip(it), "round trip changed the value") }
    }

    /** The ceiling, from B8 — proved here at the encoding layer. */
    @Test
    fun `a value at the size ceiling survives the encoding`() {
        // 64000 base64 characters is 48000 bytes of envelope; minus the 17-byte
        // header and the 16-byte tag leaves 47967 bytes of plaintext.
        val big = "ఖ".repeat(15_000)
        assertTrue(SealedValue.bytesOf(big).size > 44_000, "Telugu is three bytes a character")
        assertEquals(big, roundTrip(big))
    }
}
