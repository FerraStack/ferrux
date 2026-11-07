// SPDX-License-Identifier: Apache-2.0
//
// terminal-view: a plain-Views terminal widget (Canvas renderer + IME InputConnection).
// Deliberately has no Compose or AndroidX dependency so other FerraStack apps
// (e.g. Ferride) can embed it without pulling in Ferrux's UI stack.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.ferrux.terminal"
    // Compose 2026.09 requires compileSdk 37; targetSdk (runtime behavior) stays 36.
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }
    ndkVersion = "30.0.16248370"

    defaultConfig {
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Plain JVM tests for the pure-Kotlin parts (layout decoding, tap counting, links).
    testImplementation(libs.junit)
}
