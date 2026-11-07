# Reusing the Ferrux terminal in other apps

Ferride and other FerraStack apps embed Ferrux's terminal: the VT engine, the
PTY and session layer, and the Kotlin `terminal-view`. They do **not** want
Ferrux's own Linux environment, meaning the `dev.ferrux` prefix, the shell and
env defaults, and the bootstrap. This document describes the seam between the
two (issue #6).

## Layers

```
┌─ any host app ─────────────────────────────────────────────────────────────┐
│ terminal-view (Kotlin)   TerminalView, TerminalBackend, FerruxNative       │
│                          (terminal-only natives, no System.loadLibrary     │
│                          of a fixed name)                                  │
└──────────────────────────────┬─────────────────────────────────────────────┘
                               │ natives bound with RegisterNatives
┌──────────────────────────────▼─────────────────────────────────────────────┐
│ host .so (cdylib, one per app)                                             │
│   JNI_OnLoad → ferrux_jni_core::register_terminal_natives(env, class)      │
│              → + the host's own natives (spawn, env, …)                    │
├────────────────────────────────────────────────────────────────────────────┤
│ crates/ferrux-jni-core  (rlib) process-wide SessionManager, terminal JNI   │
│                         functions, attach-fd, registration table           │
│ crates/ferrux-session / ferrux-term / ferrux-pty                           │
└────────────────────────────────────────────────────────────────────────────┘
  Ferrux only: crates/ferrux-env (prefix, layout, env, bootstrap) and the
  app natives in crates/ferrux-android → libferrux.so
```

| Host | Native library | Terminal natives | Host natives |
|---|---|---|---|
| Ferrux app | `libferrux.so` (`ferrux-android`) | `ferrux-jni-core` | `init`, `defaultShell`, `defaultEnv`, `createSession` (uses `ferrux-env`) on `dev.ferrux.app.FerruxAppNative` |
| Ferride | its own cdylib | `ferrux-jni-core`, registered in Ferride's `JNI_OnLoad` | whatever Ferride needs (`ferride-host`, …) |
| Kotlin-only client (e.g. the API v1 `:apiclient-sample`) | a prebuilt terminal-only `.so` (see open question 2) | `ferrux-jni-core` | none |

## Rust: `ferrux-jni-core`

A new rlib crate. It depends on `ferrux-term`, `ferrux-pty`,
`ferrux-session`, `jni` and `log`, and **never on `ferrux-env`**. It contains:

- `manager()`: the process-wide `SessionManager`. There is exactly one per
  process, so a process must load only one library that contains this crate.
  Otherwise the session ids from one manager mean nothing to the other.
- The terminal functions that live in `ferrux-android` today, moved unchanged:
  - `destroySession`, `write`, `sendText`, `sendKey`, `paste`
  - `resize`, `scroll`, `snapshot`
  - selection: `startSelection`, `updateSelection`, `setSelection`,
    `selectAll`, `clearSelection`, `selectionText`
  - `screenText`, `awaitChange`, `pollEvents`
  - the event encoding
- **New** `attachPty(fd: Int, rows: Int, cols: Int): Long`. It takes ownership
  of a PTY master fd (from `ParcelFileDescriptor.detachFd()`, e.g. one
  received from Ferrux API v1 `spawn` in PTY mode) and wraps
  `SessionManager::attach`. The session has no local child. `destroySession`
  closes the fd, and the owner of the process (Ferrux) handles hang-up and
  exit.
- `spawn_session(&SpawnConfig) -> Result<SessionId, SpawnError>`: a plain
  Rust helper for hosts. The host builds the whole `SpawnConfig` (program,
  `arg0`, env, cwd, `ExecMode`), so the env and exec policy are **injected by
  the host**. This crate never decides them.
- `TERMINAL_CLASS = "dev/ferrux/terminal/FerruxNative"` and
  `register_terminal_natives(env, class)`. The table is built with
  `jni::native_method!`, so every JNI signature is derived from the Rust
  function's types at compile time. The class is a parameter, so a host can
  bind the same functions to a different Kotlin class if it doesn't use
  `terminal-view`.

None of the functions carry `#[no_mangle]` `Java_…` exports. Exports would bake
in one class name, and two libraries linking the crate would clash. Each host
registers the natives explicitly in its `JNI_OnLoad`.

`ferrux-android` (`libferrux.so`) keeps only the Ferrux-specific parts:
`init`, `defaultShell`, `defaultEnv` and `createSession`, with their
`ferrux-env` and `ExecMode` logic **moved verbatim and unchanged**, since that
logic belongs to issue #5. `createSession` builds its `SpawnConfig` as it does
today and then calls `ferrux_jni_core::spawn_session`. Its `JNI_OnLoad`
registers the terminal natives on `dev/ferrux/terminal/FerruxNative` and the
app natives on `dev/ferrux/app/FerruxAppNative`. If either registration
fails, it returns `JNI_ERR`, so `System.loadLibrary` throws and the existing
`loadError` path shows the failure, instead of the app crashing later on the
first call.

## Kotlin

- `terminal-view`'s `FerruxNative` keeps its name. Other lanes are editing it,
  and renaming it to `TerminalNative` can be a follow-up. It keeps only the
  terminal externals listed above, plus `attachPty`. Library loading becomes
  host-configurable:

  ```kotlin
  FerruxNative.load("ferride")   // before first use; Ferrux uses the default "ferrux"
  FerruxNative.loadError          // as today; triggers load("ferrux") if nobody called load
  ```

- The app natives move to `dev.ferrux.app.FerruxAppNative` in the `app`
  module: `init`, `defaultShell`, `defaultEnv` and `createSession`. The only
  call sites are `FerruxApplication` and `SessionManager`, which each need a
  one-line change.
- `NativeSessionBackend` is unchanged. A Ferride session created with
  `attachPty` uses the same backend, because session ids come from the shared
  manager.

## Tests

- **Rust (Linux):**
  - The `core` tests move to `ferrux-jni-core`.
  - A new `attach_pty` round trip: spawn with `ferrux-pty`, hand the raw fd
    over, see output, type input, then `destroySession` closes the fd.
  - **A registration test:** it parses `FerruxNative.kt` and
    `FerruxAppNative.kt` (`external fun` name plus the JNI signature mapped
    from the Kotlin types) and asserts that each set equals the corresponding
    Rust `NativeMethod` table. A native added on one side only fails
    `cargo test`, not the emulator.
- **Emulator (existing CI job):** if registration fails, the library doesn't
  load and the launch screenshot check fails. The existing screenshots must
  not change.

## Open questions for review

1. The `FerruxNative` name stays for now, to limit conflicts with the
   selection, scrolling and lifecycle PRs. Should it be renamed to
   `TerminalNative` in a follow-up?
2. Kotlin-only clients need a terminal-only `.so`. Proposed: a tiny cdylib
   crate `ferrux-term-jni` (`libferruxterm.so`), which is just a `JNI_OnLoad`
   calling `register_terminal_natives`. It would be built by the API lane when
   `:apiclient-sample` needs it, not shipped inside the `terminal-view` AAR.
   Shipping it in the AAR would load a second `SessionManager` next to
   `libferrux.so` in the Ferrux app. Should it land in this PR or with the API
   lane?
3. Pending other PRs: whichever of this and the scrolling PR (#22) merges
   second moves the new natives into `ferrux-jni-core` and adds them to the
   registration table. The registration test points out any native that was
   missed.
