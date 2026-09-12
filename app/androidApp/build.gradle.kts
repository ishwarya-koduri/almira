plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    sourceSets {
        androidMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.runtime)
            implementation(compose.ui)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.fragment)
        }
    }
}

android {
    namespace = "tech.bhrigu.almira.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "tech.bhrigu.almira"
        // 26 is the floor for the things this app actually needs: the Keystore
        // work and the biometric prompt that arrive in a later stage.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // The API this build talks to. The emulator reaches the host's loopback
        // at 10.0.2.2, which is why this is a build setting rather than a
        // constant — a device on the same network needs the host's LAN address
        // instead, and neither is "localhost" from the app's point of view.
        buildConfigField(
            "String",
            "API_BASE_URL",
            "\"${project.findProperty("almira.apiBaseUrl") ?: "http://10.0.2.2:18080"}\"",
        )
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
