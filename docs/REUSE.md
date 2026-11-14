# Reusing the Ferrux terminal in other apps

Ferride and other FerraStack apps embed Ferrux's terminal: the VT engine, the
PTY and session layer, and the Kotlin `terminal-view`. They do **not** want
Ferrux's own Linux environment, meaning the `dev.ferrux` prefix, the shell and
env defaults, the bootstrap, or the name of Ferrux's native library. This
document describes the seam between the two (issue #6).

## Layers

```
┌─ any host app ──────────────────────────────────────────────────────────────┐
│ terminal-view (Kotlin)  TerminalView, TerminalBackend, NativeSessionBackend │
│                         TerminalNative: terminal natives + load(library)    │
└──────────────────────────────┬──────────────────────────────────────────────┘
                               │ natives bound with RegisterNatives
┌──────────────────────────────▼──────────────────────────────────────────────┐
│ one native library per process, whose JNI_OnLoad calls                      │
│   ferrux_jni_core::register_terminal_natives(env, TERMINAL_CLASS)           │
│   (+ the host's own natives)                                                │
├─────────────────────────────────────────────────────────────────────────────┤
│ crates/ferrux-jni-core  (rlib) process-wide SessionManager, terminal        │
│                         natives, attachPty, spawn_session, natives_check    │
│ crates/ferrux-session / ferrux-term / ferrux-pty                            │
└─────────────────────────────────────────────────────────────────────────────┘
  Ferrux only: crates/ferrux-env (prefix, layout, env, bootstrap) and the app
  natives in crates/ferrux-android → libferrux.so
```

| Host | Native library in the APK | Calls | Own natives |
|---|---|---|---|
| Ferrux app | `libferrux.so` (`ferrux-android`); excludes `libferruxterm.so` | `TerminalNative.load("ferrux")` (via `FerruxAppNative.loadError`) | `dev.ferrux.app.FerruxAppNative`: `init`, `defaultShell`, `defaultShellCommand`, `defaultEnv`, `createSession` |
| Ferride | its own cdylib linking `ferrux-jni-core`; excludes `libferruxterm.so` | `TerminalNative.load("<its lib>")` | whatever it needs |
| Kotlin-only client (e.g. the API v1 `:apiclient-sample`) | `libferruxterm.so` from the `terminal-view` AAR | `TerminalNative.load()` (default) | none |

**Exactly one library per process** may contain `ferrux-jni-core`. Each copy
has its own `SessionManager`, and session ids from one mean nothing to the
other. That's why Ferrux and Ferride exclude `libferruxterm.so`:

```kotlin
android { packaging { jniLibs { excludes += "**/libferruxterm.so" } } }
```

CI fails if `libferruxterm.so` ends up in Ferrux's APK.

## Rust

### `crates/ferrux-jni-core` (rlib)

Depends on `ferrux-term`, `ferrux-pty`, `ferrux-session`, `jni`, `rustix` and
`log`, and **never on `ferrux-env`**.

- `core`: the platform-neutral logic, unit-tested on Linux:
  - `manager()`
  - sessions, input, `snapshot`, selection, scroll gestures and mouse
  - `screen_text`, `await_change`, `poll_events`
  - `spawn_session(&SpawnConfig)`: the host builds the whole config
    (program, `arg0`, env, cwd, `ExecMode`). This crate never decides the
    environment or exec policy.
  - `attach_pty(fd, rows, cols)`: adopts a PTY master fd, e.g. one returned
    by Ferrux API v1 `spawn` in PTY mode (docs/API.md §6.3), as a session
    without a local child. It takes ownership of the fd, and
    `destroySession` closes it.
- `TERMINAL_NATIVES`: the JNI table. Each entry is a `jni::native_method!`,
  so its JNI signature is derived from the Rust function's types at compile
  time. There are no `#[no_mangle] Java_…` exports, which would hard-code one
  class name and clash between libraries.
- `register_terminal_natives(env, class)`: binds the table to `class`,
  normally `TERMINAL_CLASS` (`dev/ferrux/terminal/TerminalNative`). A host
  that doesn't use `terminal-view` can pass its own class.
- `on_load(vm, tag, register)`: the body of a host's `JNI_OnLoad`. It sets up
  logcat logging and runs the registrations. On failure it returns `JNI_ERR`,
  so `System.loadLibrary` throws `UnsatisfiedLinkError` and
  `TerminalNative.loadError` reports it. Without this, the first native call
  would crash.
- Helpers for host natives: `string_array`, `new_string_array`,
  `throw_io`, `register_natives`.
- `natives_check` (for tests): parses Kotlin `external fun` declarations into
  `(name, JNI signature)` and reads the same pairs from a `NativeMethod`
  table.

A Ferride-style host:

```rust
#[unsafe(no_mangle)]
pub unsafe extern "system" fn JNI_OnLoad(vm: *mut jni::sys::JavaVM, _: *mut c_void) -> jint {
    unsafe {
        ferrux_jni_core::on_load(vm, "ferride", |env| {
            ferrux_jni_core::register_terminal_natives(env, ferrux_jni_core::TERMINAL_CLASS)?;
            ferrux_jni_core::register_natives(env, MY_CLASS, MY_NATIVES)
        })
    }
}
```

### `crates/ferrux-term-jni` → `libferruxterm.so`

This is the whole crate: a `JNI_OnLoad` that registers the terminal natives.
It is built by `scripts/build-native.sh` and by the `terminal-view` Gradle task
`cargoNdkBuild` into `android/terminal-view/src/main/jniLibs`, and ships inside
the `terminal-view` AAR.

### `crates/ferrux-android` → `libferrux.so`

This crate holds only the Ferrux-specific natives on
`dev/ferrux/app/FerruxAppNative`: `init`, `defaultShell`, `defaultShellCommand`,
`defaultEnv` and `createSession`. They use `ferrux-env`, deriving `ExecMode`
via `Layout::exec_mode_for` and accepting an explicit `arg0`. Its `JNI_OnLoad`
registers the terminal table and the app table.

## Kotlin

- `dev.ferrux.terminal.TerminalNative` (formerly `FerruxNative`), in
  `terminal-view`, declares the terminal natives plus `attachPty`.
  - `load(library = "ferruxterm")` loads the library once per process. The
    first call wins, and it returns the load error or null.
  - `loadError` calls `load()`, so a host with its own library must call
    `load("<name>")` before anything touches `TerminalNative`.
- `dev.ferrux.app.FerruxAppNative`, in the app, declares the Ferrux natives.
  Its `loadError` loads `libferrux.so` through `TerminalNative.load("ferrux")`.
- `NativeSessionBackend(sessionId)` works for spawned and attached sessions
  alike.
- R8: `terminal-view`'s `consumer-rules.pro` keeps `TerminalNative`'s natives.
  The app keeps `FerruxAppNative`'s.

## Publishing

Two AARs are published to **GitHub Packages** (Maven,
`https://maven.pkg.github.com/FerraStack/ferrux`), group `dev.ferrux`:

| Artifact | Contents |
|---|---|
| `dev.ferrux:terminal-view` | the widget's classes, the sources jar, and `jni/<abi>/libferruxterm.so` for `arm64-v8a` and `x86_64` |
| `dev.ferrux:ferrux-client` | the Ferrux API AIDL stubs and the Kotlin client ([API.md](API.md) §9) |

- **Workflow:** [`.github/workflows/publish.yml`](../.github/workflows/publish.yml)
  builds both `.so` files with cargo-ndk (`scripts/build-native.sh`), then runs
  `:terminal-view:publish :api:publish`.
- **Version:** one Gradle property, `ferrux.publishVersion` in
  `android/gradle.properties` (e.g. `0.1.0-SNAPSHOT`).
  - A push to `main` publishes that snapshot.
  - A tag `v0.1.0` publishes `0.1.0`.
- **CI:** every PR runs `publishToMavenLocal` for both and checks the AARs'
  contents.
- **Maven Central:** can come later. It needs a founder-owned Sonatype account
  and a signing key.

### Consuming the packages

GitHub Packages requires authentication even for public packages.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            name = "FerruxPackages"
            url = uri("https://maven.pkg.github.com/FerraStack/ferrux")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
// build.gradle.kts
implementation("dev.ferrux:terminal-view:0.1.0-SNAPSHOT")
implementation("dev.ferrux:ferrux-client:0.1.0-SNAPSHOT")
```

- **CI in another repository (e.g. Ferride):**
  - Give the job `permissions: packages: read` and pass `GITHUB_TOKEN` to Gradle.
  - Each package's settings on GitHub must grant that repository read access:
    Package settings → *Manage Actions access*. Only an org admin can do this.
- **Local development:**
  - Create a personal access token (classic) with `read:packages`.
  - Put `gpr.user=<github login>` and `gpr.key=<token>` in
    `~/.gradle/gradle.properties`, never in the repository.
- **Without credentials:** build from a Ferrux checkout. Use
  `./gradlew :terminal-view:publishToMavenLocal :api:publishToMavenLocal` and
  `mavenLocal()`, or an `includeBuild` of `ferrux/android`.

## Tests

- **Rust (Linux):**
  - `ferrux-jni-core` has the core round trip (spawn, snapshot, selection,
    mouse, events), the `attach_pty` round trip (a raw fd handed over, output,
    typing, hang-up on destroy), and a check that a bad fd is rejected.
  - Registration tests compare `TerminalNative.kt` with `TERMINAL_NATIVES`,
    and `FerruxAppNative.kt` with `APP_NATIVES`, by name and JNI signature. A
    native added on only one side fails `cargo test` instead of
    `System.loadLibrary` on a device.
- **Emulator (existing CI job):** if registration fails, the library doesn't
  load and the launch checks fail. The screenshots must not change.

## Adding a native

1. Add the `external fun` to `TerminalNative.kt` (or `FerruxAppNative.kt`).
2. Add the logic to `ferrux-jni-core/src/core.rs` (or `ferrux-android`).
3. Add an adapter in `natives` and a `native_method!` entry to the table.
   `cargo test` checks that the two sides agree.
