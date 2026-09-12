package tech.bhrigu.almira.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIDevice

actual fun createPlatformHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(Darwin) {
        configure()
    }

actual fun deviceName(): String = UIDevice.currentDevice.name

/**
 * `timeIntervalSince1970` arrives from an Objective-C category, which
 * Kotlin/Native exposes as an extension rather than a member — so it has to be
 * imported by name. Fully qualifying `NSDate` is not enough, and the compiler
 * says only "unresolved reference".
 */
actual fun currentTimeMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000).toLong()
