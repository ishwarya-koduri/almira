package tech.bhrigu.almira.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

/**
 * The one place either platform appears in the networking stack.
 *
 * Ktor needs an engine, and the engine is the only part of this that cannot be
 * shared: OkHttp on Android, Darwin's NSURLSession on iOS. Everything else —
 * the JSON, the auth, the error handling, the endpoints — is written once in
 * `commonMain`. Adding iOS later is adding an `actual`, not a second client.
 */
expect fun createPlatformHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient

/** Named on the sign-in screen and in the session list, so it must read well. */
expect fun deviceName(): String

/**
 * Wall-clock milliseconds since the epoch.
 *
 * Here rather than from `kotlin.time.Clock` because that is still experimental
 * on this compiler, and rather than from kotlinx-datetime because one function
 * is not worth a dependency that has to be version-matched to the Kotlin this
 * project is pinned to.
 */
expect fun currentTimeMillis(): Long
