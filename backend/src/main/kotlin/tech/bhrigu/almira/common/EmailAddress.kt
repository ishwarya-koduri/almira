package tech.bhrigu.almira.common

import java.util.Locale

/**
 * The one spelling of an email address that sign-in, the allowlist and the
 * users table all agree on.
 *
 * Trimmed and lower-cased, and nothing more. In particular dots and `+tags`
 * are NOT stripped, even though Gmail ignores both:
 *
 *  - it is a Gmail rule, not an email rule. At most other providers
 *    `a.b@example.com` and `ab@example.com` are two different mailboxes, owned
 *    by two different people, and folding them together would sign one of them
 *    into the other's account;
 *  - the allowlist is compared against exactly what the operator typed. If
 *    `+tags` were stripped, being allowlisted as `asha+alpha@gmail.com` would
 *    quietly also let in `asha@gmail.com`, which nobody listed.
 *
 * The cost is that `asha+1@gmail.com` and `asha@gmail.com` can be two accounts.
 * During the alpha that needs an operator to allowlist both, which is a choice
 * rather than an accident.
 *
 * Lower-casing is not strictly correct either — the part before the `@` is
 * case-sensitive in the RFC — but no mainstream provider treats it that way,
 * `users.email` is `citext` already, and two accounts differing only in case
 * would be a support problem with no upside.
 */
object EmailAddress {

    /** RFC 5321's limit on a path, which is the useful bound in practice. */
    private const val MAX_LENGTH = 254

    /**
     * Deliberately loose: one `@`, something either side, a dot in the domain,
     * no whitespace. The only real test of an address is whether a code sent to
     * it arrives, and a strict pattern mostly refuses addresses that work.
     */
    private val PLAUSIBLE = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s.]+$")

    /** The canonical form, or null when it is not an address. Never throws. */
    fun canonicalOrNull(raw: String): String? {
        val candidate = raw.trim().lowercase(Locale.ROOT)
        return candidate.takeIf { it.length <= MAX_LENGTH && PLAUSIBLE.matches(it) }
    }

    fun normalize(raw: String): String = canonicalOrNull(raw)
        ?: throw ApiException.badRequest(
            "email_invalid",
            "That doesn't look like an email address we can send a code to.",
        )

    /** For logs and audit trails: the domain and one letter, never the address. */
    fun mask(address: String): String {
        val at = address.lastIndexOf('@')
        if (at <= 0) return "****"
        return "${address.first()}····${address.substring(at)}"
    }
}
