package tech.bhrigu.almira.shared.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Every failure the API returns on purpose, in the shape it actually sends:
 * `{ "error": { "code", "message", "details" } }`.
 *
 * `code` is stable and safe to branch on. `message` is written for a person and
 * safe to show as-is — the server's voice is already the product's voice, so
 * re-writing it in the client would mean maintaining two sets of words that
 * drift apart.
 */
@Serializable
data class ApiErrorEnvelope(val error: ApiErrorBody)

@Serializable
data class ApiErrorBody(
    val code: String,
    val message: String,
    val details: Map<String, JsonElement> = emptyMap(),
    @SerialName("at") val at: String? = null,
)

/**
 * What the client throws. Carries the HTTP status as well as the envelope,
 * because a few decisions are made on the status alone — a 404 means "not
 * visible to you" as often as it means "does not exist", and the UI must say
 * "we couldn't find that" for both (docs/api/README.md).
 */
class ApiException(
    val status: Int,
    val code: String,
    override val message: String,
    val details: Map<String, JsonElement> = emptyMap(),
) : Exception(message) {

    /** Field-level messages for inline form errors, when the server sent them. */
    val fieldErrors: Map<String, String>
        get() = details["fields"]?.let { element ->
            runCatching {
                (element as kotlinx.serialization.json.JsonObject)
                    .mapValues { (_, v) -> (v as kotlinx.serialization.json.JsonPrimitive).content }
            }.getOrNull()
        } ?: emptyMap()

    val isUnauthorized: Boolean get() = status == 401
    val isNotFoundOrHidden: Boolean get() = status == 404

    companion object {
        /** A request that never reached the server at all. */
        fun offline(cause: Throwable) = ApiException(
            status = 0,
            code = "offline",
            message = "Couldn't reach Almira. Check your connection and try again.",
        ).also { it.initCause(cause) }
    }
}
