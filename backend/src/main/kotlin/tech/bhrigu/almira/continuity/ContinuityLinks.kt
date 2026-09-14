package tech.bhrigu.almira.continuity

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.security.JwtService
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

/**
 * Where a one-tap link points (docs/27 §2).
 *
 * A sweep has no request to read a host from, so the public address of the web
 * client is configuration. Empty by default: then no link is made at all, and
 * the message says to open Almira instead, where the same button is one tap
 * away behind a sign-in. Nothing is sent anywhere either way — which channels
 * carry the message is the delivery settings' business (docs/13).
 */
@ConfigurationProperties(prefix = "almira.continuity")
data class ContinuityProperties(
    /** e.g. `https://almira.example.in`. `http` only for localhost. */
    val linkBaseUrl: String = "",
) {
    init {
        val url = linkBaseUrl.trim()
        require(
            url.isEmpty() || url.startsWith("https://") ||
                url.startsWith("http://localhost") || url.startsWith("http://127.0.0.1"),
        ) {
            "almira.continuity.link-base-url must be an https address (http only for localhost), got '$url'"
        }
    }
}

/**
 * The one-tap links: "I'm here" for an owner who has gone quiet, and "Yes, I can
 * still be reached" for a trusted contact.
 *
 * Each is 256 random bits, and only its SHA-256 is stored, the way a guest link
 * is (V20): a database dump holds nothing that can be tapped. It is single-use
 * and lasts thirty days, which is the gap between one reminder and the next, so
 * a link is never live after the step it belonged to. Spending one reveals
 * nothing — the page says thank you, and that is all.
 *
 * "Signed" in the plan's sense: the server can recognise a link it made and
 * nobody can forge one. A random token checked against a stored hash gives
 * that without a signing key that could leak.
 */
@Component
class ContinuityLinks(
    private val jwt: JwtService,
    private val properties: ContinuityProperties,
) {
    private val random = SecureRandom()

    fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun hash(token: String): String = jwt.hash(token)

    /**
     * The address the message carries, or null when none is configured. The token
     * rides in the fragment, which a browser never sends to a server, so it is in
     * no access log on the way to the page.
     */
    fun url(token: String): String? =
        properties.linkBaseUrl.trim().trimEnd('/').takeIf { it.isNotEmpty() }?.let { "$it/#/here/$token" }

    /** A token is 43 URL-safe characters. Anything else is not one of ours. */
    fun looksLikeToken(token: String): Boolean = TOKEN.matches(token)

    companion object {
        val LIFETIME: Duration = Duration.ofDays(30)
        private val TOKEN = Regex("^[A-Za-z0-9_-]{43}$")
    }
}

data class RedeemLinkBody(
    @field:NotBlank @field:Size(max = 100)
    val token: String,
)

/** What a tap tells the person who tapped. Nothing about anyone's records. */
data class RedeemedLink(
    /** `check_in` | `reachable` */
    val purpose: String,
    val message: String,
)

@Service
class ContinuityLinkService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val links: ContinuityLinks,
) {

    /**
     * Nobody is signed in here: the token is the authority. The database spends
     * it and records what it means in one definer function, keyed by the hash
     * alone, so this request can reach nothing else (V95).
     */
    @Transactional
    fun redeem(token: String): RedeemedLink {
        val trimmed = token.trim()
        if (!links.looksLikeToken(trimmed)) throw unusable()
        val purpose = jdbc.queryForObject(
            "select app.redeem_continuity_link(:hash)",
            mapOf("hash" to links.hash(trimmed)),
            String::class.java,
        ) ?: throw unusable()
        return when (purpose) {
            "check_in" -> RedeemedLink(purpose, CHECKED_IN)
            else -> RedeemedLink(purpose, REACHABLE)
        }
    }

    // Unknown, used and expired all read the same, on purpose.
    private fun unusable() = ApiException.notFound(
        "That link has already been used or has expired. If you meant to say you're here, open Almira — " +
            "signing in says it too.",
    )

    private companion object {
        const val CHECKED_IN = "Thank you. We've noted that you're here. Nothing else has changed."
        const val REACHABLE = "Thank you. We've noted that you can still be reached. Nothing else has changed."
    }
}

/**
 * The only unauthenticated write in the product besides signing in: spending a
 * one-tap link. POST with the token in the body, never in the path, so it is in
 * no access log — and a mail scanner that fetches the link only loads a page.
 */
@RestController
@RequestMapping("/api/v1/continuity-links")
class ContinuityLinkController(private val service: ContinuityLinkService) {

    @PostMapping("/redeem")
    fun redeemContinuityLink(@RequestBody @Valid body: RedeemLinkBody): RedeemedLink = service.redeem(body.token)
}
