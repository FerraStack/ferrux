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

### Look and feel

The app opens straight into a shell, with the terminal edge to edge. The extra-keys
row sits above the soft keyboard. A single tap on CTRL or ALT applies it to the next
key; a double tap locks it. The arrow and page keys repeat while held. To open the
session drawer, swipe from the left edge or tap ☰.

Selecting text works like in a text field:

- Long-press a word to select it, then keep dragging to extend the selection word by word.
  URLs and `path/file.rs:10:5` count as one word.
- Double-tap selects a word and triple-tap selects the line, following soft wraps.
- Drag either handle to adjust the selection. Dragging past the top or bottom edge scrolls.
- The floating toolbar offers Copy, Open link, Paste, Select all and Share. Open link
  appears for OSC 8 hyperlinks and for a selected URL, and it opens only `http(s)`,
  `mailto` and similar schemes.
- A copied line never includes its trailing newline, so pasting it into a shell doesn't
  run it.
- Long-press blank space to Paste or Select all. On a hardware keyboard, Ctrl+Shift+C
  copies and Ctrl+Shift+V pastes.
- Tap or press Back to end the selection.

OSC 8 hyperlinks get a faint underline.

Scrolling:

- Swipe to scroll through the scrollback (10 000 lines). A fling keeps going after you
  lift your finger, and touching the screen stops it.
- A thin scrollbar shows where you are. A round ↓ button in the corner returns to the
  live screen, and so does typing.
- In full-screen programs on the alternate screen (`less`, `vim`, `htop`), a swipe
  sends Up/Down arrow keys (DECSET 1007).
- If the program turned on mouse reporting (DECSET 1000/1002/1003, in X10, UTF-8 or SGR
  encoding), a swipe sends wheel events and a tap is a left click.
- Mouse wheels and trackpads scroll 3 lines per notch.

Pinch to zoom. On a hardware keyboard, Ctrl+Alt+= and Ctrl+Alt+- also zoom. Ferrux saves the font
size in sp, so it follows the system font scale. The terminal keeps its state while you
switch apps.

The bundled font is [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono) 2.304
under the SIL Open Font License 1.1, in
[`android/terminal-view/licenses/JetBrainsMono-OFL.txt`](android/terminal-view/licenses/JetBrainsMono-OFL.txt).
Ligatures are turned off so every glyph stays in its own cell.

The default palette is **Ferrux Night**:

| Index | Colour  | Normal    | Bright    |
|-------|---------|-----------|-----------|
| 0 / 8 | black   | `#1C2029` | `#4E5666` |
| 1 / 9 | red     | `#F0717A` | `#FF8E96` |
| 2 / 10| green   | `#9BD68A` | `#B6EBA3` |
| 3 / 11| yellow  | `#EBC47A` | `#F7D99B` |
| 4 / 12| blue    | `#6CB4F5` | `#92CBFF` |
| 5 / 13| magenta | `#C79BF2` | `#DDB8FF` |
| 6 / 14| cyan    | `#5FD1C9` | `#8CE6DF` |
| 7 / 15| white   | `#C9D1DC` | `#F2F5F9` |

- Foreground is `#D6DCE5`, background `#0E1116`, cursor `#F29E4C` and selection `#264166`.
- Entries 16–255 are the standard xterm colour cube and grayscale ramp.
- Source: `crates/ferrux-term/src/color.rs`.

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
  and the JVM unit tests, and uploads the APK.
- **emulator** (pull requests and manual runs only) runs on two emulators:
  - **API 35 (Pixel 6):** launch, `echo`, `ls` and a colour test, the soft keyboard,
    two sessions in the drawer, CTRL, and text selection: long press, double and
    triple tap, handle drag, Copy, Select all, Paste and OSC 8 hyperlinks; scrollback
    drag, fling, scrollbar and jump button; arrow keys on the alternate screen; mouse
    wheel and click reporting.
  - **API 30:** launch and `echo` only.

  Each step is checked against the real terminal grid, read through a debug-only
  `DebugReceiver`, and against the UI tree via uiautomator. Logcat is scanned for
  Java and native crashes, and any failed check fails the job. The API 35 screenshots
  are pushed to the `ci-screenshots` branch and posted in one PR comment, which is
  updated on every run. Only the last 3 runs are kept, and the
  `screenshots-cleanup` workflow deletes them once the PR closes.

## Roadmap

See [docs/RESEARCH.md §12](docs/RESEARCH.md#12-mvp-milestones). Next up:

- **M3:** bootstrap and packages via linker64 + termux-exec.
- **M4:** selection, copy/paste, URLs, settings.
- **M5:** the Ferride API.

## License

[Apache-2.0](LICENSE). Ferrux contains no code from termux-app, which is GPLv3.
The approach of running binaries through `/system/bin/linker64` follows
[termux-exec](https://github.com/termux/termux-exec), which is Apache-2.0.
