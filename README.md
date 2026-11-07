# Ferrux

Ferrux is a terminal emulator and Linux environment for Android, in the spirit of
[Termux](https://termux.dev), built as a **Rust core with a thin Kotlin shell**.
It is part of [FerraStack](https://github.com/FerraStack). Ferride, the FerraStack
code editor, reuses Ferrux's terminal crates and will talk to Ferrux over a Binder
service.

> **Status: early development (milestones M1 + part of M2).** The app runs Android's
> `/system/bin/sh` in real pseudo-terminals with IME and extra-keys input. There is
> no package manager yet: the bootstrap and `pkg`/`apt` arrive in M3. See
> [docs/RESEARCH.md](docs/RESEARCH.md) for the design and roadmap.

## Architecture

```
┌──────────────────────────── Android app (Kotlin) ─────────────────────────────┐
│ app/            MainActivity (Compose drawer, title bar, extra-keys row)      │
│                 FerruxService (foreground service, specialUse) owning         │
│                 SessionManager (event thread → titles, bell, exit, redraw)    │
│ terminal-view/  TerminalView: Canvas renderer, InputConnection (IME),         │
│                 hardware keys, pinch-zoom, scrollback. No Compose deps.       │
│                 FerruxNative: the JNI surface                                 │
└───────────────────────────────────────┬───────────────────────────────────────┘
                                        │ JNI (snapshot as int[], key codes, events)
┌───────────────────────────────────────▼───────────────────────────────────────┐
│ libferrux.so   crates/ferrux-android  thin jni 0.22 adapter                   │
├───────────────────────────────────────────────────────────────────────────────┤
│ crates/ferrux-session  session registry, one poll(2) I/O thread, per-pid      │
│                        waiter threads, input queues, change notifications     │
│ crates/ferrux-term     VT engine facade over alacritty_terminal: feed bytes,  │
│                        snapshot/damage, key + paste encoding, OSC events      │
│ crates/ferrux-pty      openpt + fork/exec (async-signal-safe child), resize,  │
│                        exec modes Direct | Linker (/system/bin/linker64)      │
│ crates/ferrux-env      package/prefix constants, layout, session env,         │
│                        bootstrap installer (stub until M3)                    │
└───────────────────────────────────────────────────────────────────────────────┘
```

`ferrux-term`, `ferrux-pty` and `ferrux-session` have no Android dependencies and
are tested on Linux. The renderer is a Kotlin `Canvas` for now; the wgpu renderer
(`ferrux-render`) is an M0 spike.

### Package name and prefix

The application id is **`dev.ferrux`**, so the prefix is
`/data/data/dev.ferrux/files/usr`. It is defined in two places that must agree:

- `crates/ferrux-env/src/lib.rs`: the `app_package!()` macro and `APP_PACKAGE`
- `android/gradle.properties`: `ferrux.applicationId`

## Building

### Rust core (Linux or macOS)

```sh
cargo fmt --all --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
```

The tests spawn real `/bin/sh` processes through a pty and check the terminal grid.

### Android APK

Requirements:

- JDK 21. Gradle 9.9 also starts on newer JDKs, but CI uses 21.
- Android SDK with `platforms;android-37.0` (compileSdk 37; targetSdk is 36), `build-tools;36.0.0` and
  `ndk;30.0.16248370`.
- Rust targets and cargo-ndk:
  `rustup target add aarch64-linux-android x86_64-linux-android` and
  `cargo install cargo-ndk`.

```sh
echo "sdk.dir=$HOME/Android/Sdk" > android/local.properties
cd android
./gradlew assembleDebug        # runs cargo-ndk first (task :app:cargoNdkBuild)
# APK: android/app/build/outputs/apk/debug/app-debug.apk
```

To build the native library separately, run `scripts/build-native.sh`. Then pass
`-Pferrux.skipCargo=true` to Gradle; CI does it this way.

### CI

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) has three jobs:

- **rust**: fmt, clippy and tests.
- **android**: builds with cargo-ndk, checks 16 KB ELF alignment, runs `assembleDebug`
  and uploads the APK.
- **screenshots** (pull requests and manual runs only): boots an API 35 emulator,
  types a few commands and uploads PNG screenshots and logcat. On pull requests it
  also pushes the PNGs to the `ci-screenshots` branch and posts them in a PR comment,
  which is updated on every run.

## Roadmap

See [docs/RESEARCH.md §12](docs/RESEARCH.md#12-mvp-milestones). Next up:

- **M3:** bootstrap and packages via linker64 + termux-exec.
- **M4:** selection, copy/paste, URLs, settings.
- **M5:** the Ferride API.

## License

[Apache-2.0](LICENSE). Ferrux contains no code from termux-app, which is GPLv3.
The approach of running binaries through `/system/bin/linker64` follows
[termux-exec](https://github.com/termux/termux-exec), which is Apache-2.0.
