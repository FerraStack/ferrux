// SPDX-License-Identifier: Apache-2.0
//
// api: the Ferrux API v1 AIDL interface (docs/API.md) plus `ferrux-client`, a small
// Kotlin library with coroutine wrappers for client apps such as Ferride. The Ferrux
// app uses the same module for the service stub, so there is one copy of the interface.
//
// Published as `dev.ferrux:ferrux-client` to GitHub Packages (.github/workflows/publish.yml);
// the version is `ferrux.publishVersion` in android/gradle.properties.
plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "dev.ferrux.api"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    testOptions {
        // Robolectric: real Binder/Parcel/RemoteException for the client tests.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    api(libs.kotlinx.coroutines.android)

    // Plain JVM tests for the pure-Kotlin parts (cancellation ownership, path logic).
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}


publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "dev.ferrux"
            artifactId = "ferrux-client"
            version = providers.gradleProperty("ferrux.publishVersion").get()
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("ferrux-client")
                description.set("Client library for the Ferrux API (run programs in Ferrux from FerraStack apps)")
                url.set("https://github.com/FerraStack/ferrux")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
            }
        }
    }
    repositories {
        // GitHub Packages; .github/workflows/publish.yml runs `publish` on main and v* tags.
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/FerraStack/ferrux")
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
            }
        }
    }
}
