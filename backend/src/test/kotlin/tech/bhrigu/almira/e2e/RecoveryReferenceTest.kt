package tech.bhrigu.almira.e2e

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tech.bhrigu.almira.e2e.RecoveryReference.Share

/**
 * The arithmetic under docs/12 §10, pinned three ways: published vectors for
 * the pieces that have them (FIPS-197 for GF(2^8), RFC 5869 for HKDF, the CRC
 * catalogue for CRC-16/CCITT-FALSE), properties that must hold for every input,
 * and fixed answers that `scripts/check-recovery.js` asserts identically in the
 * web client. A round trip alone proves only that an implementation agrees
 * with itself (docs/12 §8.1), so every constant here was produced by a third,
 * independent implementation before either client was asked.
 */
@DisplayName("Recovery arithmetic: GF(256), Shamir, the printed code, HKDF")
class RecoveryReferenceTest {

    private val r = RecoveryReference
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun unhex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun `GF(2^8) multiplication and inversion match FIPS-197`() {
        // FIPS-197 §4.2 and §4.2.1.
        assertThat(r.gfMul(0x57, 0x83)).isEqualTo(0xC1)
        assertThat(r.gfMul(0x57, 0x13)).isEqualTo(0xFE)
        // The multiplicative inverse used to build the AES S-box.
        assertThat(r.gfInv(0x53)).isEqualTo(0xCA)
        for (a in 1..255) {
            assertThat(r.gfMul(a, r.gfInv(a))).describedAs("a · a⁻¹ for a = $a").isEqualTo(1)
            assertThat(r.gfMul(a, 1)).isEqualTo(a)
            assertThat(r.gfMul(a, 0)).isEqualTo(0)
        }
    }

    @Test
    fun `HKDF matches RFC 5869 test case 1`() {
        val derived = r.hkdf(
            unhex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
            unhex("000102030405060708090a0b0c"),
            unhex("f0f1f2f3f4f5f6f7f8f9"),
            42,
        )
        assertThat(hex(derived))
            .isEqualTo("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")
    }

    @Test
    fun `the checksum is CRC-16 CCITT-FALSE and the alphabet is Crockford's`() {
        assertThat(r.crc16("123456789".toByteArray())).isEqualTo(0x29B1)
        // RFC 4648's "foobar" is MZXW6YTBOI; the same bits in Crockford's alphabet.
        assertThat(r.base32("foobar".toByteArray())).isEqualTo("CSQPYRK1E8")
    }

    @Test
    fun `any two of three shares give back the secret, for random secrets`() {
        repeat(500) {
            val secret = r.randomBytes(RecoveryReference.SECRET_BYTES)
            val shares = r.split(secret, 2, 3)
            for (pair in listOf(0 to 1, 0 to 2, 1 to 2, 2 to 0, 1 to 0)) {
                assertThat(r.combine(listOf(shares[pair.first], shares[pair.second]))).isEqualTo(secret)
            }
            assertThat(r.combine(shares)).describedAs("all three agree too").isEqualTo(secret)
        }
    }

    /**
     * The property that makes it a secret-sharing scheme rather than a
     * splitting one: with one share, every secret is equally likely. For
     * 2-of-3 each share byte is s ⊕ a·x with a uniform, so for any fixed s and
     * x ≠ 0 the map a → y must hit all 256 values exactly once. If it did not —
     * if a zero coefficient were excluded, say — some share bytes would rule
     * out some secret bytes.
     */
    @Test
    fun `a single share says nothing about the secret`() {
        for (x in 1..3) {
            for (s in 0..255) {
                val ys = (0..255).map { a -> r.split(byteArrayOf(s.toByte()), 2, 3) { _, _ -> a }[x - 1].y[0].toInt() and 0xFF }
                assertThat(ys.toSet()).describedAs("share $x of secret byte $s").hasSize(256)
            }
        }
    }

    @Test
    fun `a wrong pair of shares gives a different secret, not an error`() {
        val secret = r.randomBytes(RecoveryReference.SECRET_BYTES)
        val shares = r.split(secret, 2, 3)
        val other = r.split(r.randomBytes(RecoveryReference.SECRET_BYTES), 2, 3)
        // Which is why a combined secret is only ever trusted after the GCM tag
        // on the wrap has accepted it (docs/12 §10.3).
        assertThat(r.combine(listOf(shares[0], other[1]))).isNotEqualTo(secret)
    }

    @Test
    fun `the fixed answers the web client asserts`() {
        val secret = ByteArray(21) { it.toByte() }
        val shares = r.split(secret, 2, 3) { i, _ -> 0xA0 + i }
        assertThat(shares.map { hex(it.y) }).containsExactly(
            "a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0",
            "5b585d5e57545152434045464f4c494a6b686d6e67",
            "fbf9fffdf3f1f7f5ebe9efede3e1e7e5dbd9dfddd3",
        )
        assertThat(r.encodeCode(RecoveryReference.TYPE_KEY, 0, secret))
            .isEqualTo("04000-0820C-20A1G-7104G-M2RC1-M70Y4-0H289-H8JCE")
        assertThat(shares.map { r.encodeCode(RecoveryReference.TYPE_SHARE, it.x, it.y) }).containsExactly(
            "080T1-850M2-GA185-0M2GA-1850M-2GA18-50M2G-A0JJ3",
            "0815P-P2XBS-BN8MA-J8D04-AHJF9-H4MMT-V8DNQ-6E5SC",
            "081ZQ-YFZZQ-SZ3XZ-NXFMY-ZVF3W-7KYBP-YSVZE-X7EZW",
        )
        val salt = ByteArray(16) { it.toByte() }
        assertThat(hex(r.wrappingKey(secret, salt, Recovery.KEY).encoded))
            .isEqualTo("94b5b1c89dd4196ad9b6de8cdd669f446013310874b9eb4d0bcea169811061ce")
        assertThat(hex(r.wrappingKey(secret, salt, Recovery.SHARES).encoded))
            .isEqualTo("716457295b3df062e884456a9d4d37945b5cc88f5390a0da4d5045d3d50beb64")
        assertThat(r.contentKeyId(ByteArray(32) { (0x40 + it).toByte() })).isEqualTo("VrqqDb7VVhHIkN5-ZUdCvQ")
    }

    @Test
    fun `a code survives how people copy it, and a typo is caught`() {
        val secret = r.randomBytes(RecoveryReference.SECRET_BYTES)
        val code = r.encodeCode(RecoveryReference.TYPE_KEY, 0, secret)
        val sloppy = code.lowercase().replace("-", " ").replace('0', 'o').replace('1', 'l')
        assertThat(r.decodeCode(sloppy).payload).isEqualTo(secret)

        // Every single-character substitution, at every position.
        val plain = code.replace("-", "")
        var caught = 0
        var total = 0
        for (position in plain.indices) {
            for (replacement in "0123456789ABCDEFGHJKMNPQRSTVWXYZ") {
                if (replacement == plain[position]) continue
                total++
                val typo = plain.substring(0, position) + replacement + plain.substring(position + 1)
                if (runCatching { r.decodeCode(typo) }.isFailure) caught++
            }
        }
        // One character is five bits, a burst a CRC-16 always detects. Forty
        // characters are exactly 200 bits, so no character is padding and every
        // substitution changes a checked byte or the checksum itself.
        assertThat(caught).describedAs("single-character typos caught").isEqualTo(total)
        assertThatThrownBy { r.decodeCode(plain.dropLast(1)) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `shares are refused when they cannot be combined`() {
        val share = Share(1, ByteArray(21))
        assertThatThrownBy { r.combine(listOf(share, share)) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { r.combine(listOf(Share(0, ByteArray(21)))) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
