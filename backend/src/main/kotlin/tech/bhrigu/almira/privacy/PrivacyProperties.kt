package tech.bhrigu.almira.privacy

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Who answers a complaint, and how quickly (docs/23 "Your data rights").
 *
 * The DPDP Act (s.8(10), s.13) and Rules 9 and 14(3) ask for a published
 * contact for questions about personal data and a published period within which
 * a grievance is answered, of at most ninety days. Both are facts about the
 * people running a deployment, not about the code, so they are configuration.
 *
 * Unset is allowed and says so: every rights reply then reports
 * `configured: false`, and the page says the contact has not been named yet,
 * rather than inventing a name or an address nobody reads.
 *
 * A period outside 1..90 refuses to start. A server that promised a reply in
 * 120 days would be publishing a breach of Rule 14(3) on every screen; the
 * database refuses such a date too (V45).
 */
@ConfigurationProperties(prefix = "almira.privacy")
data class PrivacyProperties(
    val grievance: Grievance = Grievance(),
) {
    data class Grievance(
        /** A named person or role, e.g. "Grievance Officer, Almira". */
        val name: String = "",
        val email: String = "",
        val responseDays: Int = 30,
    ) {
        init {
            require(responseDays in 1..MAX_RESPONSE_DAYS) {
                "almira.privacy.grievance.response-days is $responseDays; it must be between 1 and " +
                    "$MAX_RESPONSE_DAYS (DPDP Rules 2025, Rule 14(3))"
            }
        }

        val configured: Boolean get() = name.isNotBlank() && email.isNotBlank()
    }

    companion object {
        const val MAX_RESPONSE_DAYS = 90
    }
}
