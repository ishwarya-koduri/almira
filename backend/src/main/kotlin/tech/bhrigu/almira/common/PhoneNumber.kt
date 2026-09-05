package tech.bhrigu.almira.common

/**
 * Normalises what people actually type into one canonical E.164 string, so the
 * same person is never two accounts. "98765 43210", "+91 98765-43210" and
 * "09876543210" are all one number.
 */
object PhoneNumber {

    private const val DEFAULT_COUNTRY_CODE = "91" // India — the launch market.
    private val E164 = Regex("^\\+[1-9][0-9]{7,14}$")

    fun normalize(raw: String): String {
        val digits = raw.filter { it.isDigit() || it == '+' }
        val cleaned = when {
            digits.startsWith("+") -> digits
            digits.startsWith("00") -> "+" + digits.drop(2)
            // A bare 10-digit number is assumed local to the default country.
            digits.length == 10 -> "+$DEFAULT_COUNTRY_CODE$digits"
            // "0" prefix is the Indian STD convention, not part of the number.
            digits.length == 11 && digits.startsWith("0") ->
                "+$DEFAULT_COUNTRY_CODE${digits.drop(1)}"
            digits.length == 12 && digits.startsWith(DEFAULT_COUNTRY_CODE) -> "+$digits"
            else -> "+$digits"
        }
        if (!E164.matches(cleaned)) {
            throw ApiException.badRequest(
                "phone_invalid",
                "That doesn't look like a phone number we can text.",
            )
        }
        return cleaned
    }

    /** For logs and audit trails — never log a full phone number. */
    fun mask(e164: String): String =
        if (e164.length <= 4) "****" else "${e164.take(3)}····${e164.takeLast(4)}"
}
