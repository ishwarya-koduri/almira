package tech.almira.auth

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tech.almira.crypto.KeyManagementService
import tech.almira.crypto.UserSecretCipher
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@DisplayName("Recovery codes and sealed authenticator secrets")
class RecoveryCodesAndSecretsTest {

    @Test
    fun `ten distinct codes, readable off paper, and none stored as itself`() {
        val codes = RecoveryCodes.generate()
        assertThat(codes).hasSize(10).doesNotHaveDuplicates()
        codes.forEach { code ->
            assertThat(code).matches("^[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}$").doesNotContain("0", "O", "1", "I", "L", "U", "V")
            val stored = RecoveryCodes.store(code)
            assertThat(stored.hash).hasSize(32)
            assertThat(String(stored.hash, Charsets.ISO_8859_1)).doesNotContain(code.replace("-", ""))
            assertThat(RecoveryCodes.matches(RecoveryCodes.normalise(code)!!, stored.salt, stored.hash)).isTrue()
        }
    }

    @Test
    fun `typed loosely it still matches, and a near miss does not`() {
        val code = RecoveryCodes.generate().first()
        val stored = RecoveryCodes.store(code)
        val loose = " " + code.lowercase().replace("-", " ") + " "
        assertThat(RecoveryCodes.matches(RecoveryCodes.normalise(loose)!!, stored.salt, stored.hash)).isTrue()
        val other = RecoveryCodes.normalise(RecoveryCodes.generate().first())!!
        assertThat(RecoveryCodes.matches(other, stored.salt, stored.hash)).isFalse()
        assertThat(RecoveryCodes.normalise("not-a-code")).isNull()
        assertThat(RecoveryCodes.normalise("123456")).isNull()
    }

    @Test
    fun `the same code under two salts stores two different hashes`() {
        val code = RecoveryCodes.generate().first()
        assertThat(RecoveryCodes.store(code).hash).isNotEqualTo(RecoveryCodes.store(code).hash)
    }

    /** A KEK held in the test, doing what LocalKeyManagement does. */
    private val kms = object : KeyManagementService {
        private val key = SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES")
        override val kekId = "test:kek"
        override fun wrap(dataKey: ByteArray): ByteArray {
            val iv = ByteArray(12).also(SecureRandom()::nextBytes)
            return iv + Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)); doFinal(dataKey)
            }
        }
        override fun unwrap(wrappedDataKey: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrappedDataKey.copyOfRange(0, 12)))
            doFinal(wrappedDataKey.copyOfRange(12, wrappedDataKey.size))
        }
    }

    @Test
    fun `a sealed secret opens for its owner and column, and holds no plaintext`() {
        val cipher = UserSecretCipher(kms)
        val user = UUID.randomUUID()
        val secret = Totp.newSecret()
        val sealed = cipher.seal(user, SecondFactorService.TOTP_FIELD, secret)
        assertThat(sealed.kekId).isEqualTo("test:kek")
        assertThat(sealed.toString()).doesNotContain(Base32.encode(secret))
        assertThat(indexOf(sealed.blob, secret)).isEqualTo(-1)
        assertThat(cipher.open(user, SecondFactorService.TOTP_FIELD, sealed.blob)).isEqualTo(secret)
    }

    @Test
    fun `a sealed secret moved to another person, or another column, does not open`() {
        val cipher = UserSecretCipher(kms)
        val owner = UUID.randomUUID()
        val sealed = cipher.seal(owner, SecondFactorService.TOTP_FIELD, Totp.newSecret())
        assertThatThrownBy { cipher.open(UUID.randomUUID(), SecondFactorService.TOTP_FIELD, sealed.blob) }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("failed authentication")
        assertThatThrownBy { cipher.open(owner, "users.other", sealed.blob) }
            .isInstanceOf(IllegalStateException::class.java)
        val tampered = sealed.blob.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertThatThrownBy { cipher.open(owner, SecondFactorService.TOTP_FIELD, tampered) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int =
        (0..haystack.size - needle.size).firstOrNull { i -> needle.indices.all { haystack[i + it] == needle[it] } } ?: -1
}
