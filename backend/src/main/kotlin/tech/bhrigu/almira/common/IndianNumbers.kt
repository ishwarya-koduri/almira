package tech.bhrigu.almira.common

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Indian number formatting: ₹1,76,875 grouping, and the amount-in-words line
 * that sits beneath the net-worth hero (docs/02 §3, docs/03 §2).
 *
 * This lives on the server so the phone, a PDF export and the family handbook
 * all say the same thing — "Fourteen Lakh Ninety-Three Thousand" should never
 * depend on which client rendered it.
 */
object IndianNumbers {

    private val ONES = listOf(
        "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
        "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen",
        "Seventeen", "Eighteen", "Nineteen",
    )
    private val TENS = listOf(
        "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety",
    )

    /** 176875 -> "1,76,875" — last three digits, then pairs. */
    fun group(amount: BigDecimal): String {
        val whole = amount.setScale(0, RoundingMode.DOWN).abs().toBigInteger().toString()
        val sign = if (amount.signum() < 0) "-" else ""
        if (whole.length <= 3) return sign + whole

        val last3 = whole.takeLast(3)
        val rest = whole.dropLast(3)
        // Chunk from the RIGHT: 5050000 -> "5050" -> ["50","50"] -> "50,50,000".
        // Reversing the joined string instead of the chunk list is the subtle
        // way to get this wrong -- it yields "05,05,000", which reads as a
        // plausible number and so survives a casual glance.
        val pairs = rest.reversed()
            .chunked(2) { it.reversed() }
            .reversed()
            .joinToString(",")
        return "$sign$pairs,$last3"
    }

    /** 1493750 -> "Fourteen Lakh Ninety Three Thousand Seven Hundred Fifty". */
    fun words(amount: BigDecimal): String {
        val whole = amount.setScale(0, RoundingMode.DOWN).abs().toBigInteger()
        if (whole.signum() == 0) return "Zero"

        val prefix = if (amount.signum() < 0) "Minus " else ""
        var remaining = whole
        val parts = mutableListOf<String>()

        // Indian scale: crore (10^7), lakh (10^5), thousand, hundred, then <100.
        for ((divisor, name) in listOf(
            java.math.BigInteger.valueOf(10_000_000L) to "Crore",
            java.math.BigInteger.valueOf(100_000L) to "Lakh",
            java.math.BigInteger.valueOf(1_000L) to "Thousand",
            java.math.BigInteger.valueOf(100L) to "Hundred",
        )) {
            val count = remaining / divisor
            if (count.signum() > 0) {
                parts += "${underHundred(count.toInt().takeIf { it < 100 } ?: 0).ifEmpty { count.toString() }} $name"
                remaining %= divisor
            }
        }
        if (remaining.signum() > 0) parts += underHundred(remaining.toInt())
        return prefix + parts.joinToString(" ").trim()
    }

    private fun underHundred(n: Int): String = when {
        n == 0 -> ""
        n < 20 -> ONES[n]
        else -> (TENS[n / 10] + if (n % 10 != 0) " ${ONES[n % 10]}" else "")
    }

    /**
     * "₹1,76,875", and "−₹53,400" when negative.
     *
     * The sign goes OUTSIDE the symbol. "₹-53,400" reads as a currency called
     * "₹-" for a moment before it resolves, and a figure people are reading to
     * find out whether they are ahead or behind should not need resolving. The
     * minus is U+2212, which aligns with digits; a hyphen does not.
     */
    fun rupees(amount: BigDecimal): String =
        if (amount.signum() < 0) "\u2212₹${group(amount.abs())}" else "₹${group(amount)}"
}
