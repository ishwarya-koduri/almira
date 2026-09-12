plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }

    // Declared now so the project structure is real. Nothing compiles for iOS
    // until an iOS task is invoked, which needs Xcode — see docs/19.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            // Static: one fewer moving part in the Xcode project, and the
            // framework is embedded rather than shipped alongside.
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.auth)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.animation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
        }
        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            // One engine per target, chosen by the platform source set. This is
            // the only place either platform appears in the networking stack.
            implementation(libs.ktor.client.okhttp)
            // The prompt and the Keystore work both live in androidMain, so
            // the dependency does too — commonMain never sees it.
            // `api`, not `implementation`: PlatformHost takes a FragmentActivity,
            // so the type is part of this module's public surface.
            api(libs.androidx.biometric)
            api(libs.androidx.fragment)
            implementation(libs.play.services.auth.api.phone)
            implementation(libs.androidx.core.ktx)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        androidUnitTest.dependencies {
            implementation(kotlin("test"))
        }
        androidInstrumentedTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.androidx.test.junit)
            implementation(libs.androidx.test.runner)
        }
    }
}

// The generated accessor lands in our own package rather than a derived one,
// so `Res.font.inter_regular` reads the same from either platform module.
compose.resources {
    publicResClass = true
    packageOfResClass = "tech.bhrigu.almira.shared.resources"
    generateResClass = always
}

android {
    namespace = "tech.bhrigu.almira.shared"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        // The passphrase vectors have to run against Android's crypto provider,
        // not the JDK's, so they exist as an on-device test as well.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
