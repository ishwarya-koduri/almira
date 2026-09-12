package tech.bhrigu.almira.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * The client for Almira's frozen v1 API.
 *
 * Written against `docs/api/openapi-v1.json`, which is additive-only for the
 * life of v1 — so `ignoreUnknownKeys` is not laziness here, it is the thing
 * that lets the server add fields without shipping a new app.
 *
 * Three behaviours are load-bearing and each is here rather than at a call site:
 *
 * 1. **One refresh at a time.** Refresh tokens are single-use: presenting one
 *    that has already been rotated is treated as theft and revokes the whole
 *    session. Several screens failing at once and each starting its own refresh
 *    would look exactly like that and would sign the user out. Ktor's `Auth`
 *    plugin serialises `refreshTokens` for us, which is precisely the guarantee
 *    docs/api/README.md asks for.
 * 2. **Errors arrive as the server's own envelope**, so `code` can be branched
 *    on and `message` can be shown as written.
 * 3. **A 404 is "not visible to you" as often as "does not exist"** — never
 *    render it as a permission error, because saying "you don't have access to
 *    this holding" would confirm that the holding exists.
 */
class AlmiraApi(
    private val baseUrl: String,
    private val tokens: TokenStore,
    /** Called when a refresh fails for good: the session is gone, sign in again. */
    private val onSessionLost: suspend () -> Unit = {},
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        explicitNulls = false
    }

    private val client: HttpClient = createPlatformHttpClient {
        expectSuccess = false

        install(ContentNegotiation) { json(json) }

        install(Auth) {
            bearer {
                loadTokens {
                    val access = tokens.accessToken() ?: return@loadTokens null
                    BearerTokens(access, tokens.refreshToken() ?: "")
                }
                refreshTokens {
                    val refresh = tokens.refreshToken() ?: return@refreshTokens null
                    val response = client.post("$baseUrl/api/v1/auth/refresh") {
                        contentType(ContentType.Application.Json)
                        setBody(RefreshBody(refresh))
                        markAsRefreshTokenRequest()
                    }
                    if (!response.status.isSuccess()) {
                        // A rotated-away or revoked token. Nothing to retry.
                        tokens.clear()
                        onSessionLost()
                        return@refreshTokens null
                    }
                    val fresh: LoginResponse = response.body()
                    tokens.save(fresh.accessToken, fresh.refreshToken)
                    BearerTokens(fresh.accessToken, fresh.refreshToken)
                }
                // Only send the token where it belongs. Matching on the whole
                // URL rather than a path property keeps this independent of
                // which Ktor version exposes what on the builder.
                sendWithoutRequest { request ->
                    val url = request.url.buildString()
                    url.contains("/api/v1/") &&
                        !url.contains("/api/v1/auth/otp") &&
                        !url.contains("/api/v1/auth/refresh")
                }
            }
        }

        defaultRequest { contentType(ContentType.Application.Json) }
    }

    // --- the connection itself --------------------------------------------

    /**
     * Unauthenticated, and the first thing worth knowing: can this build reach
     * its server at all, and is that server enforcing row-level security.
     */
    suspend fun health(): ServerHealth = request { client.get("$baseUrl/health") }

    // --- sign in ------------------------------------------------------------

    suspend fun requestOtp(phone: String): OtpChallenge = request {
        client.post("$baseUrl/api/v1/auth/otp/request") { setBody(OtpRequestBody(phone)) }
    }

    suspend fun verifyOtp(phone: String, code: String, requestId: String?): LoginResponse {
        val login: LoginResponse = request {
            client.post("$baseUrl/api/v1/auth/otp/verify") {
                setBody(OtpVerifyBody(phone, code, requestId, deviceName()))
            }
        }
        tokens.save(login.accessToken, login.refreshToken)
        return login
    }

    suspend fun signOut() {
        runCatching { client.post("$baseUrl/api/v1/auth/logout") }
        tokens.clear()
    }

    // --- the thin slice -----------------------------------------------------

    suspend fun me(): Me = request { client.get("$baseUrl/api/v1/me") }

    suspend fun households(): List<Household> = request { client.get("$baseUrl/api/v1/households") }

    /**
     * Each viewer's own totals. Two members of one household see different
     * figures and **both are correct** — never label one "the household total"
     * in a way that implies the other is incomplete.
     */
    suspend fun dashboard(
        householdId: String,
        scope: String = "household",
        memberId: String? = null,
    ): Dashboard = request {
        val member = memberId?.let { "&member=$it" } ?: ""
        client.get("$baseUrl/api/v1/households/$householdId/dashboard?scope=$scope$member")
    }

    // --- capture ------------------------------------------------------------

    /**
     * Categories, their types, and each type's field schema — the definition
     * the capture form is generated from. One call, because the picker needs
     * every category and the form needs the schema of whichever type is picked.
     */
    suspend fun taxonomy(householdId: String): List<TaxonomyCategory> = request {
        client.get("$baseUrl/api/v1/households/$householdId/taxonomy")
    }

    suspend fun members(householdId: String): List<Member> = request {
        client.get("$baseUrl/api/v1/households/$householdId/members")
    }

    suspend fun institutions(householdId: String): List<Institution> = request {
        client.get("$baseUrl/api/v1/households/$householdId/institutions")
    }

    suspend fun createInvestment(
        householdId: String,
        body: CreateInvestmentBody,
    ): CreateInvestmentResponse = request {
        client.post("$baseUrl/api/v1/households/$householdId/investments") { setBody(body) }
    }

    // --- plumbing -----------------------------------------------------------

    private suspend inline fun <reified T> request(block: () -> HttpResponse): T {
        val response = try {
            block()
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            throw ApiException.offline(failure)
        }

        if (response.status.isSuccess()) return response.body()

        // The server's own envelope, when it sent one. A proxy or a crash can
        // return something else entirely, and that must not become a parse
        // error the user sees instead of the real problem.
        val envelope = runCatching { response.body<ApiErrorEnvelope>() }.getOrNull()
        throw ApiException(
            status = response.status.value,
            code = envelope?.error?.code ?: fallbackCode(response.status),
            message = envelope?.error?.message ?: fallbackMessage(response.status),
            details = envelope?.error?.details ?: emptyMap(),
        )
    }

    private fun fallbackCode(status: HttpStatusCode) = when (status) {
        HttpStatusCode.Unauthorized -> "unauthorized"
        HttpStatusCode.NotFound -> "not_found"
        else -> "unknown"
    }

    private fun fallbackMessage(status: HttpStatusCode) = when {
        status == HttpStatusCode.Unauthorized -> "Please sign in again."
        status == HttpStatusCode.NotFound -> "We couldn't find that."
        status.value >= 500 -> "Almira is having trouble. Please try again in a moment."
        else -> "Something didn't work. Please try again."
    }
}
