package tech.bhrigu.almira.guidance

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * "Contact us" in Settings (X-42, docs/03 §1.6).
 *
 * How a family reaches the people running a deployment is a fact about those
 * people, not about the code, so it is configuration — like the grievance
 * contact (PrivacyProperties). Unset is the default and says so: the card then
 * reads "not set up yet" rather than showing an address nobody reads.
 *
 * No provider is involved. A WhatsApp channel is a wa.me link and an email
 * channel a mailto: link, opened by the person's own phone; the server sends
 * nothing anywhere and learns nothing about who tapped it.
 */
@ConfigurationProperties(prefix = "almira.support")
data class SupportProperties(
    /** "whatsapp", "email", or blank for none. */
    val channel: String = "",
    /** A phone number in international form for WhatsApp, or an email address. */
    val address: String = "",
    /** One stated reply time for every plan, e.g. "within one working day". */
    val replyTime: String = "",
) {
    init {
        require(channel.isBlank() || channel in CHANNELS) {
            "almira.support.channel is '$channel'; it must be one of ${CHANNELS.joinToString()} or blank"
        }
        if (channel == "whatsapp") {
            require(address.matches(Regex("""\+[1-9]\d{7,14}"""))) {
                "almira.support.address must be a phone number like +919876543210 for WhatsApp"
            }
        }
        if (channel == "email") {
            require(address.matches(Regex("""[^\s@<>"]{1,64}@[^\s@<>"]{1,190}\.[A-Za-z]{2,}"""))) {
                "almira.support.address must be an email address for the email channel"
            }
        }
        require(replyTime.length <= 80) { "almira.support.reply-time must be 80 characters or fewer" }
    }

    val configured: Boolean get() = channel.isNotBlank() && address.isNotBlank()

    companion object {
        val CHANNELS = setOf("whatsapp", "email")
    }
}

data class SupportContactResponse(
    val configured: Boolean,
    /** "whatsapp" or "email" when configured; null otherwise. */
    val channel: String?,
    /** The link the client opens. Built here so no client has to assemble it from parts. */
    val link: String?,
    /** What is shown beside the link: the number or address. */
    val display: String?,
    /** The stated reply time, or null when none is configured. */
    val replyTime: String?,
)

@RestController
class SupportContactController(private val properties: SupportProperties) {

    @GetMapping("/api/v1/support/contact")
    fun supportContact(): SupportContactResponse = contact()

    /**
     * The same, before anyone is signed in, for the sign-in code step's "Didn't
     * arrive in two minutes? Contact us" (docs/13 §5). Under /auth/otp, which is
     * open, so the settings endpoint keeps its sign-in requirement. Configuration
     * only, the same answer for everyone: it names the deployment's support
     * channel and nothing about whoever asks or the address they typed.
     */
    @GetMapping("/api/v1/auth/otp/contact")
    fun signInContact(): SupportContactResponse = contact()

    private fun contact(): SupportContactResponse {
        if (!properties.configured) return SupportContactResponse(false, null, null, null, null)
        val link = when (properties.channel) {
            "whatsapp" -> "https://wa.me/${properties.address.removePrefix("+")}"
            else -> "mailto:${properties.address}"
        }
        return SupportContactResponse(
            configured = true,
            channel = properties.channel,
            link = link,
            display = properties.address,
            replyTime = properties.replyTime.ifBlank { null },
        )
    }
}
