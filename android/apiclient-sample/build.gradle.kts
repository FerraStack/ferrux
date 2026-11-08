// SPDX-License-Identifier: Apache-2.0
//
// apiclient-sample: a tiny client of the Ferrux API (docs/API.md) used by CI's
// cross-app test (scripts/ci-api.sh). It binds Ferrux like Ferride would, renders a
// shell from the PTY fd Ferrux passes with the shared terminal-view, and its
// instrumentation tests exercise spawn/pipes/PTY/resize/kill/files/keep-alive.
// It is signed with the same debug key as the Ferrux debug build; CI re-signs copies
// with throwaway keys to check that other signers are denied.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.ferrux.apiclient.sample"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "dev.ferrux.apiclient.sample"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":api"))
    implementation(project(":terminal-view"))

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
