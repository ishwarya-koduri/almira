package tech.bhrigu.almira.auth

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Passkeys, end to end, against a software authenticator written here: a P-256
 * key pair, `none` attestation, and the exact bytes a browser hands the server.
 * Nothing about the ceremony is mocked — the server's WebAuthn library checks
 * the challenge, origin, RP ID hash, flags and signature it would check for a
 * phone.
 */
@DisplayName("Passkeys")
@TestPropertySource(
    properties = [
        "almira.webauthn.rp-id=localhost",
        "almira.webauthn.origins=http://localhost:8080",
    ],
)
class PasskeyApiTest : SignInApiTestBase() {

    /** One authenticator: a key pair and a credential id, and a counter it keeps. */
    private class SoftAuthenticator(val origin: String = "http://localhost:8080", val rpId: String = "localhost") {
        val keys: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val credentialId: ByteArray = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        var counter = 0

        fun register(options: JsonNode): Map<String, Any?> {
            val challenge = options.path("publicKey").path("challenge").asText()
            val clientData = clientData("webauthn.create", challenge)
            val authData = ByteArrayOutputStream().apply {
                write(sha256(rpId.toByteArray()))
                write(0x45) // user present, user verified, attested credential data
                write(ByteBuffer.allocate(4).putInt(counter).array())
                write(ByteArray(16)) // AAGUID: none
                write(ByteBuffer.allocate(2).putShort(credentialId.size.toShort()).array())
                write(credentialId)
                write(coseKey())
            }.toByteArray()
            val attestation = Cbor.map(
                listOf(
                    Cbor.text("fmt") to Cbor.text("none"),
                    Cbor.text("attStmt") to Cbor.map(emptyList()),
                    Cbor.text("authData") to Cbor.bytes(authData),
                ),
            )
            return mapOf(
                "type" to "public-key",
                "id" to b64(credentialId),
                "rawId" to b64(credentialId),
                "response" to mapOf(
                    "clientDataJSON" to b64(clientData),
                    "attestationObject" to b64(attestation),
                    "transports" to listOf("internal"),
                ),
                "clientExtensionResults" to emptyMap<String, Any>(),
            )
        }

        fun assert(options: JsonNode, userHandle: ByteArray?, tamper: Boolean = false): Map<String, Any?> {
            counter += 1
            val challenge = options.path("publicKey").path("challenge").asText()
            val clientData = clientData("webauthn.get", challenge)
            val authData = ByteArrayOutputStream().apply {
                write(sha256(rpId.toByteArray()))
                write(0x05) // user present, user verified
                write(ByteBuffer.allocate(4).putInt(counter).array())
            }.toByteArray()
            val signed = Signature.getInstance("SHA256withECDSA").run {
                initSign(keys.private)
                update(authData)
                update(sha256(clientData))
                sign()
            }
            if (tamper) signed[signed.size - 1] = (signed[signed.size - 1].toInt() xor 0x01).toByte()
            return mapOf(
                "type" to "public-key",
                "id" to b64(credentialId),
                "rawId" to b64(credentialId),
                "response" to buildMap {
                    put("clientDataJSON", b64(clientData))
                    put("authenticatorData", b64(authData))
                    put("signature", b64(signed))
                    userHandle?.let { put("userHandle", b64(it)) }
                },
                "clientExtensionResults" to emptyMap<String, Any>(),
            )
        }

        private fun clientData(type: String, challenge: String) =
            """{"type":"$type","challenge":"$challenge","origin":"$origin","crossOrigin":false}""".toByteArray()

        private fun coseKey(): ByteArray {
            val point = (keys.public as ECPublicKey).w
            return Cbor.map(
                listOf(
                    Cbor.int(1) to Cbor.int(2), // kty: EC2
                    Cbor.int(3) to Cbor.int(-7), // alg: ES256
                    Cbor.int(-1) to Cbor.int(1), // crv: P-256
                    Cbor.int(-2) to Cbor.bytes(unsigned32(point.affineX)),
                    Cbor.int(-3) to Cbor.bytes(unsigned32(point.affineY)),
                ),
            )
        }

        private fun unsigned32(n: BigInteger): ByteArray {
            val raw = n.toByteArray()
            return when {
                raw.size == 32 -> raw
                raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
                else -> ByteArray(32 - raw.size) + raw
            }
        }
    }

    /** Just enough CBOR (RFC 8949) for an attestation object and a COSE key. */
    private object Cbor {
        private fun head(major: Int, length: Long): ByteArray = when {
            length < 24 -> byteArrayOf(((major shl 5) or length.toInt()).toByte())
            length < 256 -> byteArrayOf(((major shl 5) or 24).toByte(), length.toByte())
            length < 65536 -> byteArrayOf(((major shl 5) or 25).toByte(), (length shr 8).toByte(), length.toByte())
            else -> error("too long for this test")
        }
        fun int(n: Long): ByteArray = if (n >= 0) head(0, n) else head(1, -1 - n)
        fun int(n: Int) = int(n.toLong())
        fun bytes(b: ByteArray) = head(2, b.size.toLong()) + b
        fun text(s: String) = s.toByteArray().let { head(3, it.size.toLong()) + it }
        fun map(entries: List<Pair<ByteArray, ByteArray>>) =
            entries.fold(head(5, entries.size.toLong())) { acc, (k, v) -> acc + k + v }
    }

    private fun addPasskey(account: Account, authenticator: SoftAuthenticator, name: String = "Test phone"): JsonNode {
        val options = post("/api/v1/auth/passkeys/options", account.token)
        assertThat(options.statusCode.value()).describedAs(options.body).isEqualTo(200)
        val added = post(
            "/api/v1/auth/passkeys", account.token,
            mapOf(
                "requestId" to options.json().path("requestId").asText(),
                "credential" to authenticator.register(options.json().path("options")),
                "name" to name,
            ),
        )
        assertThat(added.statusCode.value()).describedAs(added.body).isEqualTo(200)
        return added.json()
    }

    @Test
    fun `adding a passkey needs a confirmed session, and the first one comes with recovery codes`() {
        val account = signUp()
        assertThat(post("/api/v1/auth/passkeys/options", account.token).errorCode()).isEqualTo("step_up_required")

        stepUpByCode(account)
        // A ceremony run on another site is not a passkey for this one.
        val phishing = post("/api/v1/auth/passkeys/options", account.token).json()
        val elsewhere = post(
            "/api/v1/auth/passkeys", account.token,
            mapOf(
                "requestId" to phishing.path("requestId").asText(),
                "credential" to SoftAuthenticator(origin = "https://almira-login.example").register(phishing.path("options")),
            ),
        )
        assertThat(elsewhere.errorCode()).isEqualTo("passkey_invalid")

        val added = addPasskey(account, SoftAuthenticator(), name = "Asha's Pixel")
        assertThat(added.path("passkey").path("name").asText()).isEqualTo("Asha's Pixel")
        assertThat(added.path("recoveryCodes")).hasSize(10)

        val methods = get("/api/v1/auth/sign-in-methods", account.token).json()
        assertThat(methods.path("passkeysAvailable").asBoolean()).isTrue()
        assertThat(methods.path("passkeys").map { it.path("name").asText() }).containsExactly("Asha's Pixel")
        assertThat(methods.path("count").asInt()).isEqualTo(2)
    }

    @Test
    fun `a sign-in to an account with a passkey finishes only with that passkey's signature`() {
        val account = signUp()
        stepUpByCode(account)
        val authenticator = SoftAuthenticator()
        addPasskey(account, authenticator)

        val first = otpSignIn(account.phone)
        assertThat(first.errorCode()).isEqualTo("second_factor_required")
        val details = first.json().path("error").path("details")
        assertThat(details.path("methods").map(JsonNode::asText)).containsExactly("passkey", "recovery_code")
        val token = details.path("secondFactorToken").asText()

        // A stranger's key, for the right credential id, is refused.
        val impostor = SoftAuthenticator()
        val impostorOptions = post("/api/v1/auth/second-factor/passkey/options", body = mapOf("secondFactorToken" to token)).json()
        val forged = impostor.assert(impostorOptions.path("options"), PasskeyService.handle(java.util.UUID.fromString(account.userId)).bytes)
            .toMutableMap().also { it["id"] = b64(authenticator.credentialId); it["rawId"] = b64(authenticator.credentialId) }
        val refused = post(
            "/api/v1/auth/second-factor/passkey",
            body = mapOf("secondFactorToken" to token, "requestId" to impostorOptions.path("requestId").asText(), "credential" to forged),
        )
        assertThat(refused.errorCode()).isEqualTo("second_factor_invalid")

        // A signature that does not verify is refused.
        val options = post("/api/v1/auth/second-factor/passkey/options", body = mapOf("secondFactorToken" to token)).json()
        val tampered = post(
            "/api/v1/auth/second-factor/passkey",
            body = mapOf(
                "secondFactorToken" to token,
                "requestId" to options.path("requestId").asText(),
                "credential" to authenticator.assert(options.path("options"), null, tamper = true),
            ),
        )
        assertThat(tampered.errorCode()).isEqualTo("second_factor_invalid")

        // The real one.
        val options2 = post("/api/v1/auth/second-factor/passkey/options", body = mapOf("secondFactorToken" to token)).json()
        assertThat(options2.path("options").path("publicKey").path("allowCredentials").map { it.path("id").asText() })
            .containsExactly(b64(authenticator.credentialId))
        val signedIn = post(
            "/api/v1/auth/second-factor/passkey",
            body = mapOf(
                "secondFactorToken" to token,
                "requestId" to options2.path("requestId").asText(),
                "credential" to authenticator.assert(options2.path("options"), null),
            ),
        )
        assertThat(signedIn.statusCode.value()).describedAs(signedIn.body).isEqualTo(200)
        assertThat(signedIn.json().path("user").path("id").asText()).isEqualTo(account.userId)

        // A ceremony is answered once.
        val replay = post(
            "/api/v1/auth/second-factor/passkey",
            body = mapOf(
                "secondFactorToken" to token,
                "requestId" to options2.path("requestId").asText(),
                "credential" to authenticator.assert(options2.path("options"), null),
            ),
        )
        assertThat(replay.errorCode()).isEqualTo("second_factor_expired")
        assertThat(
            db.queryForObject(
                "select signature_count from user_passkeys where user_id = ?::uuid", Long::class.java, account.userId,
            ),
        ).isEqualTo(authenticator.counter.toLong() - 1)
    }

    @Test
    fun `a passkey confirms it's you, and removing one needs a passkey and keeps two ways in`() {
        val account = signUp()
        stepUpByCode(account)
        val phone = SoftAuthenticator()
        val laptop = SoftAuthenticator()
        addPasskey(account, phone, "Phone")
        val second = otpSignIn(account.phone) // a fresh session would need the passkey; this one is still elevated by a text
        assertThat(second.errorCode()).isEqualTo("second_factor_required")

        // Adding a second passkey now needs the first.
        assertThat(post("/api/v1/auth/passkeys/options", account.token).json().path("error").path("details").path("requires").asText())
            .isEqualTo("second_factor")
        val options = post("/api/v1/auth/step-up/passkey/options", account.token).json()
        val confirmed = post(
            "/api/v1/auth/step-up/passkey", account.token,
            mapOf("requestId" to options.path("requestId").asText(), "credential" to phone.assert(options.path("options"), null)),
        )
        assertThat(confirmed.statusCode.value()).describedAs(confirmed.body).isEqualTo(200)
        assertThat(addPasskey(account, laptop, "Laptop").path("recoveryCodes")).describedAs("codes already given stand").isEmpty()

        val methods = get("/api/v1/auth/sign-in-methods", account.token).json()
        assertThat(methods.path("count").asInt()).isEqualTo(3)
        val laptopId = methods.path("passkeys").first { it.path("name").asText() == "Laptop" }.path("id").asText()
        val phoneId = methods.path("passkeys").first { it.path("name").asText() == "Phone" }.path("id").asText()

        assertThat(delete("/api/v1/auth/passkeys/$laptopId", account.token).statusCode.value()).isEqualTo(204)
        val lastOne = delete("/api/v1/auth/passkeys/$phoneId", account.token)
        assertThat(lastOne.statusCode.value()).isEqualTo(409)
        assertThat(lastOne.errorCode()).isEqualTo("sign_in_methods_minimum")

        // Someone else's passkey is not found, whatever their session can do.
        val stranger = signUp()
        stepUpByCode(stranger)
        assertThat(delete("/api/v1/auth/passkeys/$phoneId", stranger.token).statusCode.value()).isEqualTo(404)
        assertThat(
            db.queryForObject("select count(*) from user_passkeys where id = ?::uuid", Int::class.java, phoneId),
        ).isEqualTo(1)
    }

    private companion object {
        fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}
