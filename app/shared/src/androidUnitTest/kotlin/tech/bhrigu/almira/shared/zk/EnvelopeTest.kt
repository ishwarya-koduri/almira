@file:OptIn(ExperimentalEncodingApi::class)

package tech.bhrigu.almira.shared.zk

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * The envelope, against envelopes the browser actually produced.
 *
 * The two vectors below were emitted by the shipped `e2e.js` `seal()` running
 * on this stack. They are parsed for *structure* rather than compared for
 * equality — the IV is random, so no two envelopes over the same plaintext are
 * ever the same, and a test that demanded they were would be testing the wrong
 * thing.
 */
class EnvelopeTest {

    /** keyVersion 1, nine bytes of plaintext. */
    private val fromWebV1 = "AQAAAAE_aHh-qgH2NL8_bTI9jUcYBYH_WCU22glzj6W68CdHZLtMr7xO"

    /** The same, written under key version 258 — the big-endian check. */
    private val fromWebV258 = "AQAAAQL3fSmbC3U2GVShWDaubzxWLGS-KHcSwQNFIX4ihYf1OYG0VNjN"

    @Test
    fun `parses an envelope the web client wrote`() {
        val parsed = Envelope.parse(fromWebV1)
        assertEquals(1, parsed.keyVersion)
        assertEquals(Envelope.IV_BYTES, parsed.iv.size)
        // 9 bytes of plaintext plus a 16-byte tag.
        assertEquals(25, parsed.body.size)
    }

    /**
     * 258 is 0x00000102 — two non-zero bytes, so a little-endian reader would
     * say 33555456 and be confidently wrong.
     */
    @Test
    fun `key version is big-endian`() {
        assertEquals(258, Envelope.parse(fromWebV258).keyVersion)
    }

    @Test
    fun `what we build, we can parse`() {
        val iv = ByteArray(12) { (it * 3).toByte() }
        val body = ByteArray(20) { it.toByte() }
        val parsed = Envelope.parse(Envelope.build(7, iv, body))

        assertEquals(7, parsed.keyVersion)
        assertContentEquals(iv, parsed.iv)
        assertContentEquals(body, parsed.body)
    }

    /** Emitted unpadded, as the contract says, whatever the length. */
    @Test
    fun `emits base64url without padding`() {
        val text = Envelope.build(1, ByteArray(12), ByteArray(17))
        assertTrue(!text.contains('='), "padding leaked into $text")
        assertTrue(!text.contains('+') && !text.contains('/'), "not url-safe: $text")
    }

    /** And reads padded, because the contract says it must read both. */
    @Test
    fun `accepts padding it would never write`() {
        // 17 header + 17 body = 34 bytes, which is not a multiple of three, so
        // the padded form really does carry an '='.
        val unpadded = Envelope.build(1, ByteArray(12), ByteArray(17))
        val padded = unpadded + "=".repeat((4 - unpadded.length % 4) % 4)
        assertTrue(padded.endsWith("="), "this vector was supposed to need padding")
        assertEquals(1, Envelope.parse(padded).keyVersion)
    }

    /**
     * B6, and the reason it is a ruling.
     *
     * A reader that skipped this byte would take offsets 5..17 as an IV out of
     * an envelope that may not have one there, and hand back plausible rubbish
     * rather than an error.
     */
    @Test
    fun `an unknown version is refused, not guessed at`() {
        val ours = Envelope.parse(fromWebV1)
        val future = Envelope.build(1, ours.iv, ours.body).let {
            // Flip the version byte to 2 and re-encode.
            val bytes = Envelope.parse(it).let { p ->
                byteArrayOf(2) + byteArrayOf(0, 0, 0, 1) + p.iv + p.body
            }
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)
        }

        val refused = assertFailsWith<UnsupportedEnvelopeVersion> { Envelope.parse(future) }
        assertEquals(2, refused.version)
        assertTrue(refused.message!!.contains("newer version"))
    }

    @Test
    fun `a truncated envelope is refused`() {
        assertFailsWith<MalformedEnvelope> { Envelope.parse(fromWebV1.substring(0, 20)) }
        assertFailsWith<MalformedEnvelope> { Envelope.parse("") }
    }

    @Test
    fun `something that is not base64 is refused`() {
        assertFailsWith<MalformedEnvelope> { Envelope.parse("this is a locker address, in the clear") }
    }

    /** A zero or negative key version is a misread envelope, not a version. */
    @Test
    fun `an impossible key version is refused`() {
        val zero = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
            .encode(byteArrayOf(1) + byteArrayOf(0, 0, 0, 0) + ByteArray(12) + ByteArray(16))
        assertFailsWith<MalformedEnvelope> { Envelope.parse(zero) }
    }

    @Test
    fun `build refuses what it could not read back`() {
        assertFailsWith<IllegalArgumentException> { Envelope.build(1, ByteArray(11), ByteArray(16)) }
        assertFailsWith<IllegalArgumentException> { Envelope.build(1, ByteArray(12), ByteArray(15)) }
        assertFailsWith<IllegalArgumentException> { Envelope.build(0, ByteArray(12), ByteArray(16)) }
    }
}
