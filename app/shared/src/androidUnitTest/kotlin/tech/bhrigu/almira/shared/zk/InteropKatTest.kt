package tech.bhrigu.almira.shared.zk

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * One known answer that pins the entire chain (B4).
 *
 * Everything is fixed — passphrase, salt, iteration count, IV, the four AAD
 * components, the plaintext — so the envelope is a constant, and both clients
 * assert the same constant. It was produced by the shipped `e2e.js` running in
 * a browser against this stack; the identical assertion lives in that file's
 * `selfTest()`.
 *
 * A single value, but it covers: NFC on the passphrase, UTF-8 after it, the
 * trailing space surviving both, PBKDF2-HMAC-SHA256 at 600 000 rounds, the AAD
 * field order, **UUIDs lowercased inside it**, AES-256-GCM with a 128-bit tag,
 * the envelope byte layout, big-endian key version, and base64url without
 * padding. Any one of those drifting turns this red.
 *
 * The plaintext carries Telugu and a trailing space, and the ids are supplied
 * uppercase, precisely because those are the three things that would otherwise
 * differ silently between two implementations.
 */
class InteropKatTest {

    private val passphrase = "correct horse battery staple "
    private val salt = ByteArray(16) { it.toByte() }
    private val iterations = 600_000
    private val iv = ByteArray(12) { (0xA0 + it).toByte() }
    private val household = "58276CAE-2448-4D51-8C9D-29FEFD3225D4"
    private val record = "167D9136-E238-48CF-B093-0F51D9A43C8D"
    private val fieldKey = "locker_address"
    private val plaintext = "Locker 12, ఖజానా, Kakinada "

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `the key, the AAD and the envelope all match the web client`() {
        val key = PassphraseKey.derive(passphrase, salt, iterations)
        assertEquals(
            "17c0b45fe7d3dcc10b70395e28a8cc533a0c8113691b174d39b8a205f2085f6f",
            hex(key),
            "the derived key drifted",
        )

        val aad = Aad.of(household, "investment", record, fieldKey)
        assertEquals(
            "58276cae-2448-4d51-8c9d-29fefd3225d4|investment|" +
                "167d9136-e238-48cf-b093-0f51d9a43c8d|locker_address",
            aad.decodeToString(),
            "the additional data drifted",
        )

        val body = aesGcmSeal(key, iv, SealedValue.bytesOf(plaintext), aad)
        assertEquals(
            "AQAAAAGgoaKjpKWmp6ipqqvPXvr272LHpln2v1MfVTtWxjXLbZR0eNYAsS5bJYmnCrpDPstqzByPY2RZI1X1WKjF52IsjQ",
            Envelope.build(1, iv, body),
            "the envelope drifted",
        )
    }

    /** And the same value opens, which is the half that matters to a person. */
    @Test
    fun `the web client's envelope opens here`() {
        val key = PassphraseKey.derive(passphrase, salt, iterations)
        val parsed = Envelope.parse(
            "AQAAAAGgoaKjpKWmp6ipqqvPXvr272LHpln2v1MfVTtWxjXLbZR0eNYAsS5bJYmnCrpDPstqzByPY2RZI1X1WKjF52IsjQ",
        )
        assertEquals(1, parsed.keyVersion)
        assertEquals(
            plaintext,
            SealedValue.textOf(
                aesGcmOpen(key, parsed.iv, parsed.body, Aad.of(household, "investment", record, fieldKey)),
            ),
        )
    }
}
