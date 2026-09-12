package tech.bhrigu.almira.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin
import platform.UIKit.UIDevice

actual fun createPlatformHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(Darwin) {
        configure()
    }

actual fun deviceName(): String = UIDevice.currentDevice.name

actual fun currentTimeMillis(): Long =
    (platform.Foundation.NSDate().timeIntervalSince1970 * 1000).toLong()
