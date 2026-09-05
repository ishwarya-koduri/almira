package tech.bhrigu.almira.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.random.Random

/**
 * Properties, not examples.
 *
 * The ₹05,05,000 bug got past a reading of the code because the output still
 * looked like a number — the digits were rearranged, not obviously mangled.
 * Example-based tests only catch that if someone happens to pick an affected
 * value. These check invariants that must hold for every input, and hammer the
 * lakh and crore boundaries where the grouping rule changes shape.
 */
@DisplayName("Indian number formatting — properties")
class IndianNumbersPropertyTest {

    /**
     * THE property. Grouping only inserts separators: strip them and the digits
     * must be exactly what went in, in the same order. The old implementation
     * reversed the joined string, so 5050000 came back as 0505000 — same digits,
     * wrong order, and this catches it on the first value with an odd-length
     * prefix.
     */
    @Test
    fun `grouping only inserts separators and never reorders a digit`() {
        val random = Random(20260905) // fixed seed: a failure is reproducible
        val failures = mutableListOf<String>()

        for (digits in 1..15) {
            repeat(80) {
                val value = randomWithDigits(random, digits)
                val grouped = IndianNumbers.group(BigDecimal(value))
                val stripped = grouped.replace(",", "")
                if (stripped != value.toString()) {
                    failures += "$value grouped as '$grouped' -> '$stripped'"
                }
            }
        }
        assertThat(failures).describedAs("grouping must preserve the digit sequence").isEmpty()
    }

    /**
     * The Indian rule: a final group of three, then groups of two, with a
     * leading group of one or two. (Western grouping would give threes
     * throughout, and reads as a different number entirely.)
     */
    @Test
    fun `groups are three at the end and two thereafter`() {
        val random = Random(9052026)
        for (digits in 4..15) {
            repeat(60) {
                val value = randomWithDigits(random, digits)
                val parts = IndianNumbers.group(BigDecimal(value)).split(",")

                assertThat(parts.last())
                    .describedAs("$value: final group must be three digits")
                    .hasSize(3)
                parts.drop(1).dropLast(1).forEach { part ->
                    assertThat(part)
                        .describedAs("$value: middle groups must be pairs")
                        .hasSize(2)
                }
                assertThat(parts.first().length)
                    .describedAs("$value: leading group is one or two digits")
                    .isBetween(1, 2)
            }
        }
    }

    /**
     * Every place where the number of groups changes. These are the values a
     * hand-written test set tends to miss by one.
     */
    @ParameterizedTest(name = "boundary at 10^{0}")
    @ValueSource(ints = [2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12])
    fun `the boundaries either side of every power of ten hold`(exponent: Int) {
        val power = BigInteger.TEN.pow(exponent)
        listOf(power - BigInteger.ONE, power, power + BigInteger.ONE).forEach { value ->
            val grouped = IndianNumbers.group(BigDecimal(value))
            assertThat(grouped.replace(",", ""))
                .describedAs("$value grouped as $grouped")
                .isEqualTo(value.toString())
            assertThat(grouped).doesNotStartWith(",").doesNotEndWith(",")
            assertThat(grouped).doesNotContain(",,")
        }
    }

    /** A leading zero is the visible symptom the old bug produced. */
    @Test
    fun `no grouped number ever starts with a zero`() {
        val random = Random(31032029)
        for (digits in 1..14) {
            repeat(60) {
                val value = randomWithDigits(random, digits)
                assertThat(IndianNumbers.group(BigDecimal(value)))
                    .describedAs("$value")
                    .doesNotStartWith("0")
            }
        }
    }

    /** Formatting is a pure function of the value: same input, same output. */
    @Test
    fun `formatting is stable across equal values written differently`() {
        listOf("100000", "100000.00", "1.0E+5").forEach { text ->
            assertThat(IndianNumbers.group(BigDecimal(text))).isEqualTo("1,00,000")
        }
    }

    /**
     * The scale word has to change exactly at the boundary. Saying "Ninety Nine
     * Lakh" for a crore is the words-equivalent of the grouping bug.
     */
    @Test
    fun `the scale word turns over exactly at lakh and crore`() {
        assertThat(IndianNumbers.words(BigDecimal("99999"))).doesNotContain("Lakh")
        assertThat(IndianNumbers.words(BigDecimal("100000"))).contains("Lakh")
        assertThat(IndianNumbers.words(BigDecimal("9999999"))).contains("Lakh").doesNotContain("Crore")
        assertThat(IndianNumbers.words(BigDecimal("10000000"))).contains("Crore")
    }

    /** Never throws, never returns nothing — this text sits under the hero. */
    @Test
    fun `words are produced for every magnitude`() {
        val random = Random(14092039)
        for (digits in 1..12) {
            repeat(40) {
                val value = randomWithDigits(random, digits)
                val words = IndianNumbers.words(BigDecimal(value))
                assertThat(words).describedAs("$value").isNotBlank()
                assertThat(words).doesNotContain("null")
                // No double spaces: those come from an empty scale fragment
                // being joined in, which means a missing branch.
                assertThat(words).describedAs("$value -> '$words'").doesNotContain("  ")
            }
        }
    }

    private fun randomWithDigits(random: Random, digits: Int): BigInteger {
        if (digits == 1) return BigInteger.valueOf(random.nextLong(1, 10))
        val first = random.nextInt(1, 10).toString()
        val rest = (2..digits).map { random.nextInt(0, 10) }.joinToString("")
        return BigInteger(first + rest)
    }
}
