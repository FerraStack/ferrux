// SPDX-License-Identifier: Apache-2.0
//
// terminal-view: a plain-Views terminal widget (Canvas renderer + IME InputConnection).
// Deliberately has no Compose or AndroidX dependency so other FerraStack apps
// (e.g. Ferride) can embed it without pulling in Ferrux's UI stack.
//
// Published as one AAR, dev.ferrux:terminal-view, together with the terminal-only
// native library libferruxterm.so (crates/ferrux-term-jni). Apps with their own
// library linking ferrux-jni-core (Ferrux, Ferride) exclude libferruxterm.so from
// their APK and call TerminalNative.load("<their lib>"). See docs/REUSE.md.
plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
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

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // Plain JVM tests for the pure-Kotlin parts (layout decoding, tap counting, links).
    testImplementation(libs.junit)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "dev.ferrux"
            artifactId = "terminal-view"
            version = providers.gradleProperty("ferrux.publishVersion").getOrElse("0.0.1-SNAPSHOT")
            afterEvaluate { from(components["release"]) }
            pom {
                name = "Ferrux terminal-view"
                description = "Terminal widget and terminal-only native library from Ferrux"
                url = "https://github.com/FerraStack/ferrux"
                licenses {
                    license {
                        name = "Apache-2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0"
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Native library: build libferruxterm.so (crates/ferrux-term-jni) with cargo-ndk
// into src/main/jniLibs (gitignored). Same switch as the app: -Pferrux.skipCargo=true.
// ---------------------------------------------------------------------------
val skipCargo = providers.gradleProperty("ferrux.skipCargo").map(String::toBoolean).getOrElse(false)
val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs").asFile
val repoRoot = rootProject.layout.projectDirectory.dir("..").asFile
val ndkDirectory = androidComponents.sdkComponents.ndkDirectory
val cargoBuildJobs = providers.environmentVariable("CARGO_BUILD_JOBS").orElse("6")

val cargoNdkBuild = tasks.register<Exec>("cargoNdkBuild") {
    group = "build"
    description = "Builds libferruxterm.so for arm64-v8a and x86_64 with cargo-ndk."
    workingDir = repoRoot
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-t", "x86_64",
        "--platform", "30",
        "-o", jniLibsDir.absolutePath,
        "build", "--release", "-p", "ferrux-term-jni",
    )
    environment("ANDROID_NDK_HOME", ndkDirectory.get().asFile.absolutePath)
    environment("CARGO_BUILD_JOBS", cargoBuildJobs.get())
    outputs.upToDateWhen { false }
}

if (!skipCargo) {
    tasks.named("preBuild") { dependsOn(cargoNdkBuild) }
}
