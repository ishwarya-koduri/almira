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
)

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

// --- health, for the connection check ---------------------------------------

@Serializable
data class ServerHealth(
    val status: String,
    val database: String,
    val dbRole: String,
    val rlsEnforced: Boolean,
    val environment: String,
)
