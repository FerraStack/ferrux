// SPDX-License-Identifier: Apache-2.0
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Single source of truth for the package name: android/gradle.properties.
val ferruxApplicationId: String = providers.gradleProperty("ferrux.applicationId").get()

android {
    // `namespace` is the Kotlin/R package of the code and stays fixed;
    // `applicationId` (the installed package, which defines the prefix) is configurable.
    namespace = "dev.ferrux.app"
    // Compose 2026.09 requires compileSdk 37; targetSdk (runtime behavior) stays 36.
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = ferruxApplicationId
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            // libferrux.so already contains the terminal natives (ferrux-jni-core);
            // terminal-view's standalone copy would be a second session manager.
            excludes += "**/libferruxterm.so"
        }
    }

    testOptions {
        // android.util.Log & co. return defaults in plain JVM tests instead of throwing.
        unitTests.isReturnDefaultValues = true
        // Robolectric (Compose UI tests) needs the merged resources and manifest.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":terminal-view"))
    implementation(project(":api"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    // Compose UI tests run on Robolectric, without an emulator.
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// ---------------------------------------------------------------------------
// Native library: build libferrux.so (crates/ferrux-android) with cargo-ndk.
//
// Output goes to src/main/jniLibs/<abi>/libferrux.so (gitignored), which AGP
// packages automatically. Skip with -Pferrux.skipCargo=true.
// Requires: rustup targets aarch64-linux-android + x86_64-linux-android, cargo-ndk.
// ---------------------------------------------------------------------------
val skipCargo = providers.gradleProperty("ferrux.skipCargo").map(String::toBoolean).getOrElse(false)
val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs").asFile
val repoRoot = rootProject.layout.projectDirectory.dir("..").asFile
val ndkDirectory = androidComponents.sdkComponents.ndkDirectory
val cargoBuildJobs = providers.environmentVariable("CARGO_BUILD_JOBS").orElse("6")

val cargoNdkBuild = tasks.register<Exec>("cargoNdkBuild") {
    group = "build"
    description = "Builds libferrux.so for arm64-v8a and x86_64 with cargo-ndk."
    workingDir = repoRoot
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-t", "x86_64",
        "--platform", "30",
        "-o", jniLibsDir.absolutePath,
        "build", "--release", "-p", "ferrux-android",
    )
    // Resolved when the task is configured, i.e. only if it is part of the build.
    environment("ANDROID_NDK_HOME", ndkDirectory.get().asFile.absolutePath)
    environment("CARGO_BUILD_JOBS", cargoBuildJobs.get())
    // cargo does its own up-to-date checking; always let it decide.
    outputs.upToDateWhen { false }
}

if (!skipCargo) {
    tasks.named("preBuild") { dependsOn(cargoNdkBuild) }
}

// ---------------------------------------------------------------------------
// Ferrux API signers (docs/API.md §3.3): the SHA-256 digests of signing certificates
// whose apps get `<applicationId>.permission.API` through `knownSigner`, as the
// generated resource @array/ferrux_api_known_signers. Sources:
//   android/api-signers.properties    name=digest, the FerraStack release keys
//   -Pferrux.api.knownSigners=d1,d2   extra digests (local builds, CI)
// Apps signed with the same key as this Ferrux build need no entry (signature).
// ---------------------------------------------------------------------------
abstract class KnownSignersTask : DefaultTask() {
    @get:Input
    abstract val digests: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val normalized = digests.get().map { it.trim().replace(":", "").lowercase() }.filter { it.isNotEmpty() }
        normalized.forEach {
            require(Regex("[0-9a-f]{64}").matches(it)) { "not a SHA-256 certificate digest: $it" }
        }
        val values = outputDir.get().asFile.resolve("values")
        values.mkdirs()
        val items = normalized.distinct().joinToString("") { "\n        <item>$it</item>" }
        values.resolve("ferrux_api_known_signers.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
            |<!-- Generated by :app:generateKnownSigners from android/api-signers.properties
            |     and -Pferrux.api.knownSigners. Do not edit. -->
            |<resources>
            |    <string-array name="ferrux_api_known_signers" translatable="false">$items
            |    </string-array>
            |</resources>
            |""".trimMargin(),
        )
    }
}

val signersFile = rootProject.layout.projectDirectory.file("api-signers.properties")
val knownSignerDigests: Provider<List<String>> = providers.fileContents(signersFile).asText
    .map { text ->
        text.lines().map { it.substringBefore('#').trim() }.filter { '=' in it }.map { it.substringAfter('=') }
    }
    .orElse(emptyList())
    .zip(providers.gradleProperty("ferrux.api.knownSigners").orElse("")) { fromFile, extra ->
        fromFile + extra.split(',')
    }

val generateKnownSigners = tasks.register<KnownSignersTask>("generateKnownSigners") {
    digests.set(knownSignerDigests)
    outputDir.set(layout.buildDirectory.dir("generated/res/knownSigners"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(generateKnownSigners, KnownSignersTask::outputDir)
    }
}
