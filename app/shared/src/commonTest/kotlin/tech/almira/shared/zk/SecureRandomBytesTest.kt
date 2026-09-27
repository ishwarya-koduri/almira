package tech.almira.shared.zk

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `secureRandomBytes` answers the same way on both platforms, including for the
 * boring sizes.
 *
 * Written because it did not. Android's version is `ByteArray(size)` handed to
 * `SecureRandom`, which is happy with a size of zero; the iOS version pinned the
 * array and took `addressOf(0)`, which throws `IllegalArgumentException` on an
 * empty one. So a length shared code is allowed to ask for worked on one phone
 * and crashed on the other, and it crashed inside the crypto module, where the
 * first guess is never "the argument was zero".
 *
 * This test exists in `commonTest` rather than beside either actual precisely
 * so that it runs against both.
 */
class SecureRandomBytesTest {

    @Test
    fun `zero bytes is an empty array rather than an exception`() {
        val bytes = secureRandomBytes(0)

        assertEquals(
            0, bytes.size,
            "secureRandomBytes(0) returned ${bytes.size} bytes. Zero is an ordinary request " +
                "- an empty tail, an absent AAD - and both platforms must answer it with an " +
                "empty array.",
        )
        assertContentEquals(
            ByteArray(0), bytes,
            "secureRandomBytes(0) did not return an empty array.",
        )
    }

    @Test
    fun `an ordinary size comes back the right length and not all the same`() {
        val iv = secureRandomBytes(Envelope.IV_BYTES)
        assertEquals(
            Envelope.IV_BYTES, iv.size,
            "secureRandomBytes did not return the number of bytes it was asked for.",
        )

        val key = secureRandomBytes(32)
        assertEquals(
            32, key.size,
            "secureRandomBytes did not return the number of bytes it was asked for.",
        )

        // Not a randomness test - nothing here can be - but it does catch the
        // failure that matters: a platform that returns a zero-filled array
        // because the fill silently did not happen. An IV that is not random is
        // a GCM key recovered from two messages.
        assertTrue(
            key.any { it != 0.toByte() },
            "32 random bytes came back all zero. The platform's fill did not run.",
        )
        assertTrue(
            !secureRandomBytes(32).contentEquals(key),
            "two separate calls returned identical bytes, so the source is not random.",
        )
    }
}
