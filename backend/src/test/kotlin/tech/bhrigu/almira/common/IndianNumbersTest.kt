package tech.bhrigu.almira.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal

/**
 * Indian grouping and amount-in-words.
 *
 * These cases exist because the first implementation reversed the joined string
 * instead of the chunk list, turning ₹50,50,000 into ₹05,05,000 — a wrong number
 * that still looks like a number, which is the kind of bug that reaches a user's
 * net worth and stays there.
 */
@DisplayName("Indian number formatting")
class IndianNumbersTest {

    @ParameterizedTest(name = "{0} groups as {1}")
    @CsvSource(
        "0, 0",
        "7, 7",
        "99, 99",
        "999, 999",
        "1000, '1,000'",
        "99999, '99,999'",
        "100000, '1,00,000'",
        "176875, '1,76,875'",
        "1000000, '10,00,000'",
        "5050000, '50,50,000'",
        "14937500, '1,49,37,500'",
        "100000000, '10,00,00,000'",
        "1234567890, '1,23,45,67,890'",
    )
    fun `groups digits the Indian way`(input: String, expected: String) {
        assertThat(IndianNumbers.group(BigDecimal(input))).isEqualTo(expected)
    }

    @ParameterizedTest(name = "{0} reads as {1}")
    @CsvSource(
        "0, Zero",
        "7, Seven",
        "15, Fifteen",
        "40, Forty",
        "99, 'Ninety Nine'",
        "100, 'One Hundred'",
        "1000, 'One Thousand'",
        "5050000, 'Fifty Lakh Fifty Thousand'",
        "1493750, 'Fourteen Lakh Ninety Three Thousand Seven Hundred Fifty'",
        "10000000, 'One Crore'",
        "17687500, 'One Crore Seventy Six Lakh Eighty Seven Thousand Five Hundred'",
    )
    fun `spells amounts in Indian scale`(input: String, expected: String) {
        assertThat(IndianNumbers.words(BigDecimal(input))).isEqualTo(expected)
    }

    @Test
    fun `negatives keep their sign in both forms`() {
        assertThat(IndianNumbers.group(BigDecimal("-176875"))).isEqualTo("-1,76,875")
        assertThat(IndianNumbers.words(BigDecimal("-1000"))).startsWith("Minus ")
    }

    @Test
    fun `paise are truncated, never rounded up into a rupee that is not there`() {
        assertThat(IndianNumbers.group(BigDecimal("176875.99"))).isEqualTo("1,76,875")
    }

    @Test
    fun `renders with the rupee sign`() {
        assertThat(IndianNumbers.rupees(BigDecimal(1_493_750))).isEqualTo("₹14,93,750")
    }
}
