package tech.bhrigu.almira.common

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@DisplayName("Phone normalisation")
class PhoneNumberTest {

    /**
     * All of these are one person. If normalisation missed any of them, that
     * person would end up with two accounts and half their holdings in each.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "9876543210", "98765 43210", "98765-43210", "09876543210",
            "+919876543210", "+91 98765 43210", "919876543210", "0091 9876543210",
        ],
    )
    fun `every way of typing one Indian number resolves to the same account`(input: String) {
        assertThat(PhoneNumber.normalize(input)).isEqualTo("+919876543210")
    }

    @Test
    fun `international numbers are kept as given`() {
        assertThat(PhoneNumber.normalize("+14155552671")).isEqualTo("+14155552671")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "12345", "abcdefghij", "+", "++919876543210"])
    fun `nonsense is refused with a message a person can act on`(input: String) {
        assertThatThrownBy { PhoneNumber.normalize(input) }
            .isInstanceOf(ApiException::class.java)
            .hasMessageContaining("phone number")
    }

    @Test
    fun `masking keeps enough to recognise and too little to dial`() {
        val masked = PhoneNumber.mask("+919876543210")
        assertThat(masked).isEqualTo("+91····3210")
        assertThat(masked).doesNotContain("98765")
    }
}
