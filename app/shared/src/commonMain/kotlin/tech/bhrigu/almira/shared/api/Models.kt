package tech.bhrigu.almira.shared.api

import kotlinx.serialization.Serializable

/**
 * The slice of the v1 contract this app speaks so far.
 *
 * Hand-written rather than generated, and deliberately partial: every field
 * here is one a screen actually uses. `ignoreUnknownKeys` means the server can
 * keep adding fields — which v1 explicitly allows — without any of this
 * needing to change.
 *
 * Names and nullability come from `docs/api/openapi-v1.json`. A field that is
 * optional there is nullable here; a field that is required there is not. That
 * correspondence is the whole value of a frozen contract, so it is worth
 * keeping exact.
 */

// --- auth -------------------------------------------------------------------

@Serializable
data class OtpRequestBody(val phone: String)

@Serializable
data class OtpChallenge(
    val requestId: String,
    val expiresInSeconds: Int,
    val resendAfterSeconds: Int,
    /** Development only — absent from every other environment. */
    val developmentCode: String? = null,
    /** `phone` or `email`: where the code went. Added after v1 froze; absent from older servers. */
    val channel: String? = null,
)

@Serializable
data class EmailOtpRequestBody(val email: String)

/**
 * How the email for a sign-in request went (GET /auth/otp/email/delivery/{requestId}).
 * Added after v1 froze. `status` is `sending`, `sent`, `delayed` or `failed`.
 */
@Serializable
data class OtpDeliveryStatus(
    val requestId: String,
    val status: String,
    val failure: String? = null,
    val message: String? = null,
    val resendAfterSeconds: Int? = null,
)

@Serializable
data class EmailOtpVerifyBody(
    val email: String,
    val code: String,
    val requestId: String? = null,
    val deviceName: String? = null,
)

/** `GET /auth/otp/channels`: which sign-in endpoints this server answers. */
@Serializable
data class SignInChannelsResponse(val channels: List<String> = emptyList())

@Serializable
data class OtpVerifyBody(
    val phone: String,
    val code: String,
    val requestId: String? = null,
    val deviceName: String? = null,
)

@Serializable
data class LoginResponse(
    val accessToken: String,
    val refreshToken: String,
    /** int64 in the contract, so Long here even though it will fit in an Int. */
    val expiresInSeconds: Long,
    val tokenType: String,
    val isNewUser: Boolean,
    val user: Me,
)

/**
 * What `/auth/refresh` answers with — and **not** a [LoginResponse].
 *
 * Signing in tells you who you are; refreshing does not, because you already
 * know. So there is no `user` and no `isNewUser` here, and decoding a refresh
 * into [LoginResponse] fails on the missing fields. It failed silently for two
 * stages, because nothing refreshed until an access token had actually expired
 * on a device — and then surfaced as "couldn't reach Almira", which was a lie
 * about a server that had answered perfectly.
 */
@Serializable
data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
    val tokenType: String,
)

@Serializable
data class RefreshBody(val refreshToken: String)

@Serializable
data class Me(
    val id: String,
    val phone: String? = null,
    val email: String? = null,
    val fullName: String? = null,
    val defaultVisibility: String,
    val currency: String,
    val locale: String,
)

// --- households -------------------------------------------------------------

@Serializable
data class Household(
    val id: String,
    val name: String,
    val baseCurrency: String,
    val defaultVisibility: String,
    val myRole: String,
    val myMemberId: String? = null,
    val memberCount: Int,
    val version: Int,
)

// --- the dashboard ----------------------------------------------------------

/**
 * How much of the total is a real valuation and how much is still what someone
 * paid. `unknown` is the "No value yet" count on the dashboard — a holding
 * carrying no figure at all, which is a gap worth naming rather than a zero
 * worth hiding.
 */
@Serializable
data class ValueConfidence(
    val valued: Int = 0,
    val atCost: Int = 0,
    val fromCustomField: Int = 0,
    val unknown: Int = 0,
)

@Serializable
data class Breakdown(
    val key: String,
    val label: String,
    val color: String? = null,
    val value: Double = 0.0,
    val valueFormatted: String,
    val percentage: Double,
    val count: Int,
)

/**
 * Amounts arrive twice: as a number, and pre-formatted by the server.
 *
 * **Prefer the formatted string.** It is computed once on the server so a
 * phone, a PDF and the web client cannot disagree, and the Indian grouping it
 * uses — ₹1,76,875 — is not what any platform's default number formatter
 * produces (docs/api/README.md).
 */
@Serializable
data class Dashboard(
    val scope: String,
    val scopeLabel: String,
    val netWorth: Double,
    val netWorthFormatted: String,
    val netWorthInWords: String,
    val totalAssets: Double,
    val totalAssetsFormatted: String,
    val totalLiabilities: Double,
    val totalLiabilitiesFormatted: String,
    val currency: String,
    val holdingCount: Int,
    val liabilityCount: Int,
    val valueConfidence: ValueConfidence = ValueConfidence(),
    val byCategory: List<Breakdown> = emptyList(),
    val byMember: List<Breakdown> = emptyList(),
    val disclaimer: String,
)

// --- scores: completeness and handover readiness ---------------------------

/**
 * A score as a screen may show it: a percentage the records earned, or the
 * sentence saying why there is none. Never both missing, and never a number
 * the server did not stand behind (docs/18 §6, known-issues 19, docs/22 §1).
 *
 * This is the only way to get at either score. The raw `score` fields below are
 * private on purpose: a brand-new household's completeness arrives as
 * `score: 0, scoreEarned: false`, and a screen that read `score` directly
 * would tell a new user "0%" — which reads as a failure they have not had.
 * `scripts/check-spec.py` fails if client code reads a score any other way.
 */
sealed interface ScoreDisplay {
    /** An earned number, already written as "72%". */
    data class Percent(val text: String) : ScoreDisplay

    /** No number; show this sentence where the number would be. */
    data class NoScore(val sentence: String) : ScoreDisplay
}

@Serializable
data class CompletenessCheck(
    val code: String,
    val label: String,
    val fix: String,
    val done: Int,
    val outstanding: Int,
    val investmentIds: List<String> = emptyList(),
    val weight: Int,
)

/**
 * `GET /households/{id}/reports/completeness`. No screen shows it yet; the
 * model exists now so that the first one cannot forget [scoreEarned].
 *
 * [scoreEarned] is required here, as it is in the contract, with no default:
 * a response without it fails to decode rather than being guessed at.
 */
@Serializable
data class Completeness(
    /** 0 and meaningless when [scoreEarned] is false. Read it through [display]. */
    private val score: Int,
    val scoreEarned: Boolean,
    /** Present only when [scoreEarned] is false. */
    val scoreExplanation: String? = null,
    val recordCount: Int,
    val scoreLabel: String,
    val checks: List<CompletenessCheck> = emptyList(),
    val nextStep: String? = null,
    val note: String,
) {
    fun display(): ScoreDisplay =
        if (scoreEarned) {
            ScoreDisplay.Percent("$score%")
        } else {
            ScoreDisplay.NoScore(scoreExplanation ?: NOTHING_TO_SCORE)
        }

    companion object {
        /** The server's own sentence, for a response that left it out. */
        const val NOTHING_TO_SCORE =
            "Nothing is recorded that you can see yet, so there is nothing to score."
    }
}

@Serializable
data class ReadinessCheck(
    val code: String,
    val label: String,
    val done: Int,
    val applicable: Int,
    /** Null when nothing counted applies to this check; then show no bar. */
    val percent: Int? = null,
)

@Serializable
data class ReadinessGap(
    val check: String,
    val reason: String,
    val recordType: String? = null,
    val recordId: String? = null,
    val title: String? = null,
    val fix: String,
)

@Serializable
data class LeftOutRecord(
    val recordType: String,
    val recordId: String,
    val title: String,
)

/**
 * `GET /households/{id}/continuity/readiness`. No screen shows it yet.
 *
 * `score` is left out of the JSON when the data has not earned a number, so it
 * is nullable, private, and read through [display].
 */
@Serializable
data class HandoverReadiness(
    private val score: Int? = null,
    /** Always a sentence: how the number was made, or why there is none. */
    val scoreExplanation: String,
    /** True only when there is a score, nothing is missing, and nothing is left out. */
    val complete: Boolean,
    val recordCount: Int,
    val leftOutCount: Int,
    val leftOut: List<LeftOutRecord> = emptyList(),
    val checks: List<ReadinessCheck> = emptyList(),
    val gaps: List<ReadinessGap> = emptyList(),
    val caveats: List<String> = emptyList(),
) {
    fun display(): ScoreDisplay =
        score?.let { ScoreDisplay.Percent("$it%") } ?: ScoreDisplay.NoScore(scoreExplanation)
}

// --- health, for the connection check ---------------------------------------

@Serializable
data class ServerHealth(
    val status: String,
    val database: String,
    val dbRole: String,
    val rlsEnforced: Boolean,
    val environment: String,
)
