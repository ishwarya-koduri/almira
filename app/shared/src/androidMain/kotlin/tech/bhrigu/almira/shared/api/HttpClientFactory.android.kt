package tech.bhrigu.almira.shared.api

import android.os.Build
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp

actual fun createPlatformHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(OkHttp) {
        configure()
        engine {
            config { retryOnConnectionFailure(true) }
        }
    }

actual fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
