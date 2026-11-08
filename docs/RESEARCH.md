# Ferrux — Technical Research

*Status: research input for architecture decisions. Researched 2026-11-07.*
*Scope: Termux internals, Android platform restrictions, the Rust-on-Android ecosystem, architecture options, and an MVP plan for Ferrux and its embedding in Ferride.*

Facts were checked against primary sources: cloned source code, AOSP sepolicy, the crates.io and GitHub APIs, and developer.android.com. Anything inferred or unverified says so in the text. Version numbers and dates are as of 2026-11-07.

---

## TL;DR

1. **Rust is a good fit for the core, not for the whole app.** Use Rust for terminal emulation, PTY/process management, sessions, rendering and the IPC/session daemon. That code is portable, testable on desktop, and shared with Ferride. Kotlin is a better fit for Android integration: the soft keyboard, accessibility, lifecycle, services, notifications, intents and storage. Today a pure-Rust NativeActivity/GameActivity app **cannot** get reliable soft-keyboard support (CJK composition, swipe typing, autocorrect). winit, egui and android-activity all still have open issues on this. Every Rust UI toolkit with working Android text input (Slint, Makepad, android-view) ships its own Java `View` + `InputConnection` to get it.
2. **Rust does not solve Termux's hard problems.** Those are the W^X exec ban, building and hosting about 2,200 packages for a hard-coded prefix, Play policy, and the phantom process killer. They make up most of the long-term work. Plan and staff for them explicitly.
3. **Recommended: Option B.** A thin Kotlin shell hosts a `SurfaceView`, an `InputConnection`, a foreground service and a Binder/intent API. It sits on a Rust core (`libferrux.so`, JNI via `jni` 0.22) that owns emulation, PTYs, sessions and GPU rendering into the `ANativeWindow`.
4. **Exec strategy:** target the current SDK (36/37) and run binaries through `/system/bin/linker64` using `termux-exec` (Apache-2.0) as an `LD_PRELOAD` shim. Ship Ferrux's own helpers (proot loader, etc.) as `lib*.so` in `nativeLibraryDir`. The Google Play build of Termux has used this design since 2024 and now targets SDK 37. That is Termux's Play build, not upstream Termux, which is still stuck on targetSdk 28.
5. **Package name / prefix:** **`dev.ferrux`**, giving prefix `/data/data/dev.ferrux/files/usr`. Ferrux **cannot reuse Termux's binary repos**, because the prefix is compiled into every binary. Fork termux-packages (preferably the Play fork, which already carries the linker-exec patches), rebuild with `TERMUX_APP__PACKAGE_NAME=dev.ferrux`, and host an apt repo signed with Ferrux's own key. Start with a curated subset.
6. **Ferride integration:** Ferride runs as a different Android UID and can never exec Ferrux's files directly. Ferrux should expose a signature-protected bound service. Ferrux spawns processes in its own sandbox and hands Ferride the PTY master or pipe file descriptors over Binder. Ferride then renders with the same `ferrux-term` crate.

---

## Table of contents

1. [Termux architecture](#1-termux-architecture)
2. [Android restrictions](#2-android-restrictions)
3. [Package name, prefix and package repositories](#3-package-name-prefix-and-package-repositories)
4. [Rust ecosystem, layer by layer](#4-rust-ecosystem-layer-by-layer)
5. [Existing projects / prior art](#5-existing-projects--prior-art)
6. [Does Rust make sense? (honest assessment)](#6-does-rust-make-sense-honest-assessment)
7. [Architecture options compared](#7-architecture-options-compared)
8. [Ferride integration (embedding & IPC)](#8-ferride-integration-embedding--ipc)
9. [Feature list: MVP vs later](#9-feature-list-mvp-vs-later)
10. [Recommendation](#10-recommendation)
11. [Proposed architecture](#11-proposed-architecture)
12. [MVP milestones](#12-mvp-milestones)
13. [Risks](#13-risks)
14. [Open questions](#14-open-questions)
15. [Sources](#15-sources)

---

## 1. Termux architecture

Read from `termux/termux-app` master (commit `8629e63`, 2026-09-26).

### 1.1 Modules

| Module | What it is | Size (Java LOC) | License |
|---|---|---|---|
| `terminal-emulator` | VT state machine (`TerminalEmulator.java`, 3.5k LOC), buffer and rows, `KeyHandler`, `WcWidth`, recent sixel/iTerm image support (`TerminalSixel`, `TerminalBitmap`, `ITermImage`). The JNI file `termux.c` does `/dev/ptmx` + `fork` + `execvp`. | ~6k | Derived from jackpal's Android-Terminal-Emulator (Apache-2.0). The repo LICENSE calls the module an "Apache 2.0 exception", but there is no per-module LICENSE file, so Termux's own changes are **ambiguous**. Treat them as GPLv3 unless confirmed. |
| `terminal-view` | `TerminalView` (an Android `View`: Canvas renderer, `InputConnection`, gestures, text-selection handles) | ~2.5k | Same as above |
| `termux-shared` | Constants, settings, shell/exec utilities, file utilities, logging, the `RUN_COMMAND` plumbing | large | **MIT by default**, but `com/termux/shared/termux/*` is **GPLv3-only** (except `TermuxConstants.java` and `TermuxPropertyConstants.java`, which are MIT). `file/filesystem/*` is GPLv2 + Classpath; `StreamGobbler` is Apache-2.0. |
| `app` | Activities, `TermuxService`, `TermuxInstaller` (bootstrap), `RunCommandService`, storage setup, extra-keys, settings, `DocumentsProvider` | ~34k total across app and shared | **GPLv3-only** |

Termux app build configuration:
- `minSdkVersion=21`, `targetSdkVersion=28`, `compileSdkVersion=36`, NDK 29.
- Latest stable release v0.118.3 (2025-05-22). There is a v0.119.0-beta line.

**Licensing implication for Ferrux (Apache-2.0):**
- Do **not** copy code from `app` or the GPL parts of `termux-shared`. Read it to understand behavior, then reimplement.
- MIT parts may be reused with attribution, but there is little reason to: Ferrux's core will be Rust.

### 1.2 PTY spawning (`terminal-emulator/src/main/jni/termux.c`)

`create_subprocess()` does the following:
1. `open("/dev/ptmx", O_RDWR|O_CLOEXEC)`, then `grantpt`, `unlockpt`, `ptsname_r`.
2. Sets `IUTF8` and clears `IXON|IXOFF`, so Ctrl-S doesn't freeze the terminal. Sets `TIOCSWINSZ`, including pixel size.
3. `fork()`. In the child:
   - Unblock all signals. ART blocks some, and the blocked mask is inherited across exec.
   - `setsid()`, then `open(pts)`. Opening the pts implicitly makes it the controlling TTY.
   - `dup2` the pts to fds 0, 1 and 2.
   - Close every fd listed in `/proc/self/fd`.
   - `clearenv` + `putenv`, `chdir`, `execvp`.
4. The parent reaps the child with `waitpid` from a Java thread.

Pitfalls to avoid in Ferrux:
- The child calls functions that are not async-signal-safe (`opendir`, `asprintf`, `putenv`) after `fork()` in a heavily multithreaded ART process. This mostly works because bionic and Scudo register `pthread_atfork` handlers for malloc, but it is fragile.
- A Rust reimplementation should prepare everything (argv/envp arrays, fd lists) **before** `fork`, and call only raw syscalls in the child. Alternatively, use `posix_spawn` (bionic API 28+, see §4.2).

### 1.3 Bootstrap install flow

**Building the bootstrap:**
- `termux-packages/scripts/generate-bootstraps.sh` downloads `.deb`s from a repo.
- `scripts/build-bootstraps.sh` builds them from source instead; this is intended for forks.
- The packages are unpacked into a prefix tree and zipped.
- **Symlinks are not stored in the zip.** They are listed in `SYMLINKS.txt` as `target←linkpath`, separated by U+2190.
- Bootstrap contents: apt, bash, coreutils, curl, dash, diffutils, findutils, gawk, grep, gzip, less, procps, psmisc, sed, tar, termux-core, termux-exec, termux-keyring, termux-tools, util-linux, xz-utils, nano, unzip, and a few more.
- Current size per architecture: about 31.5 MB for aarch64 (`bootstrap-2026.11.01-r1+apt.android-7`) and 23.4 MB for the Play build.

**Embedding in the APK:**
- Gradle downloads `bootstrap-<arch>.zip` from termux-packages GitHub releases, pinned by SHA-256.
- `app/src/main/cpp/termux-bootstrap-zip.S` uses `.incbin` to put the zip into `.rodata` of **`libtermux-bootstrap.so`**.
- A JNI function `getZip()` returns it as a `byte[]`.
- Shipping it as a native library means the Play Store splits it per ABI automatically.

**First run** (`TermuxInstaller`):
1. Refuse to run if not the primary user.
2. Delete any leftover staging dir.
3. Extract the zip into `$PREFIX`'s staging dir, with a zip-slip check.
4. `chmod 0700` everything in `bin/`, `libexec/` and similar.
5. Create the symlinks from `SYMLINKS.txt`.
6. Atomically `rename(staging, $PREFIX)`.
7. Separately, `termux-setup-storage` creates `~/storage/{shared,dcim,downloads,…}` symlinks.

Ferrux can implement the same flow in Rust (`ferrux-env`): stream-extract, stage, rename atomically. A `.tar.zst` with real symlinks would be smaller and simpler than zip + `SYMLINKS.txt`. Keep the `.incbin`-in-a-`.so` trick, or load the archive from APK assets.

### 1.4 termux-exec

`termux-exec` (now `termux/termux-exec-package`, v2.5.0, 2026-07-01, **Apache-2.0**, docs MIT) is an `LD_PRELOAD` library that hooks the `exec*()` family to:
- rewrite `/bin/sh`, `/usr/bin/env` and similar shebang interpreters into `$PREFIX`;
- parse shebangs itself, which also lifts the kernel's 128/256-byte shebang limit to 340;
- in **system-linker-exec mode** (`TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE=disable|enable|force`), turn `execve(path)` into `execve("/system/bin/linker64", [linker64, path, …])`. This is the W^X workaround (§2.1).

It exports `TERMUX_EXEC__PROC_SELF_EXE` so that patched programs can find their real path. Because it is Apache-2.0, **Ferrux can reuse it directly**, built for the Ferrux prefix.

### 1.5 termux-packages build system

**Scale:**
- `packages/`: about **2,235** packages, plus `x11-packages/` (975) and `root-packages/` (72), counted today.
- Each package has a `build.sh` recipe and patches. Builds run in a Docker image with the NDK toolchain, cross-compiling against bionic.
- The current package API level is 24 (variant `apt-android-7`).

**Licensing:** recipes and patches carry the license of the package they build. Build infrastructure outside the package dirs is Apache-2.0.

**Where the prefix comes from:**
- `scripts/properties.sh` defines `TERMUX_APP__PACKAGE_NAME="com.termux"`, then `TERMUX_APP__DATA_DIR=/data/data/$TERMUX_APP__PACKAGE_NAME`, `TERMUX__ROOTFS=$DATA_DIR/files`, and `TERMUX__PREFIX=$ROOTFS/usr`.
- `TERMUX_REPO__*` variables pin what the **official repo** was built for, which is `com.termux`.

**Why the prefix is hard-coded.** On Android, the rootfs is not at `/`; it sits inside the app's private data directory. Every binary therefore embeds absolute paths:
- **ELF `DT_RUNPATH`**: the toolchain adds `-Wl,-rpath=$PREFIX/lib -Wl,--enable-new-dtags`.
- **`--prefix=` strings** compiled into binaries: config search paths, `sysconfdir`, plugin dirs, Python/Perl/Ruby library paths.
- **Shebangs** (`#!/data/data/com.termux/files/usr/bin/sh`), pkg-config `.pc` files, libtool `.la` files, apt and dpkg configuration.
- Relocation by patching binaries is impossible in general: strings have fixed lengths and some are compressed or hashed. There is no `/usr` that apps can bind-mount, and no mount namespaces for untrusted apps; that is why proot exists.

**Path-length limits** (properties.sh and the "Termux file system layout" wiki):
- `sun_path` for Unix sockets is 108 bytes, so `TERMUX__ROOTFS` must be at most 85 characters and `$PREFIX/tmp` at most 94.
- Shebangs are limited to 128 bytes (kernel < 5.1) or 256 bytes.
- The **app data dir** is allowed at most 69 characters.
- The wiki says: *"Ideally package name should be `<= 21` characters and max `33`. If package name has not yet been chosen, then it would be best to keep it to `<= 10` characters."*

**Multi-user:** `/data/data/<pkg>` resolves only for user 0. Secondary users and work profiles use `/data/user/<id>/<pkg>`, so a hard-coded prefix breaks there. Termux refuses to run outside the primary user. Termux's long-term fix ("dynamic variables", NLnet-funded, 2024) is not done.

### 1.6 Plugins and IPC in Termux

- **`RUN_COMMAND` intent:** `com.termux.RUN_COMMAND` targets `RunCommandService`. It is protected by the *dangerous* permission `com.termux.permission.RUN_COMMAND` and the user must also set `allow-external-apps=true` in `termux.properties`. It runs a command in the background or in a new terminal session, and can return results through a `PendingIntent`.
- **Termux:API, Boot, Widget, Styling, Float, Tasker** were separate APKs sharing a UID via `sharedUserId`. The Play build merged them into the main app, because `sharedUserId` is deprecated and was flagged in Play review.
- **`TermuxDocumentsProvider`** exposes `$HOME` to the Storage Access Framework.
- **`termux-am-socket` / `termux-am`** let shell scripts send intents through the app.

---

## 2. Android restrictions

### 2.1 W^X: no `execve()` of app-writable files (targetSdk ≥ 29)

Mechanism, verified in AOSP `system/sepolicy` main:

**The domain an app runs in depends on its targetSdk** (`seapp_contexts`):

| targetSdk | SELinux domain |
|---|---|
| ≥ 34 | `untrusted_app` |
| 32–33 | `untrusted_app_32` |
| 30–31 | `untrusted_app_30` |
| 29 | `untrusted_app_29` |
| 26–28 | `untrusted_app_27` |
| ≤ 25 | `untrusted_app_25` |

**The rule.** `app_neverallows.te` contains:
```
neverallow { all_untrusted_apps -untrusted_app_25 -untrusted_app_27 -runas_app }
           { app_data_file privapp_data_file }:file execute_no_trans;
```
- In plain terms: apps targeting **29 or higher cannot `execve()` anything in their own data dir**.
- Because it is a `neverallow`, CTS enforces it and OEMs cannot relax it. It applies on Android 10 through 17.

**What is still allowed** (`untrusted_app_all.te`), which is what makes the workarounds possible:
- `app_data_file:file { r_file_perms execute }`: you may `mmap(PROT_EXEC)` and `dlopen` your own files. Only *exec* is banned.
- `system_linker_exec:file execute_no_trans`: you may exec `/system/bin/linker64`. Google allowed this for Chrome's Crashpad.
- `apk_data_file` is `rx`: files extracted from the APK to `nativeLibraryDir` are executable.

**Other notes:**
- External storage (`/storage/emulated/0`) is mounted `noexec`.
- `memfd` + `fexecve` is believed to be denied. Memfds are `appdomain_tmpfs`, which is not granted `execute_no_trans`; Zdroid reports the same. *Not verified on a device by us.*
- **Android 17** (targetSdk 37) adds "Safer native DCL": files loaded with `System.load()` must be read-only. That does not affect linker-exec, but it shows the direction of travel.

**Workarounds compared:**

| Approach | How | Pros | Cons |
|---|---|---|---|
| **targetSdk 28** (Termux F-Droid/GitHub) | Stay in the `untrusted_app_27` domain, which may exec | Everything works, including static binaries and raw `execve` | **Not allowed on Google Play** (needs 36 since 2026-08-31). Android shows an "old app" warning. Every new platform behavior (FGS types, notification permission, …) is missed. Android 14+ refuses to install below 24, and the floor may rise again. |
| **`/system/bin/linker64 <file>`** (termux-exec system-linker-exec, Termux Play build) | `LD_PRELOAD` shim rewrites exec calls to go through the system linker | Works on targetSdk 37 today. Packages can stay in the writable prefix, so apt works normally. | Static binaries fail (Go and Zig-musl tools need care). `/proc/self/exe` and `comm` show `linker64`, so pgrep, Go and LLVM need patches (already done in the Play fork). Raw `syscall(SYS_execve)` bypasses the shim. `LD_PRELOAD` must survive every entry point (sshd, cron…). Only Android 10+. |
| **Executables as `lib*.so` in `nativeLibraryDir`** | Name binaries `libfoo.so`, set `useLegacyPackaging=true`, Android extracts them as executable `apk_data_file` | The only approach that is unambiguously Play-policy-compliant | Read-only and only updatable by updating the APK. This doesn't scale to apt; Termux's experimental "APKLF" (packages as APKs) is still months away per agnostic-apollo (termux-app#2155, 2026-08). **Good for Ferrux's own helpers** (proot loader, static tools). |
| **proot** | ptrace-based syscall translation; its loader is a native lib or is run via the linker | Can run unmodified glibc distros (Debian, Arch, Alpine) with any paths | ptrace overhead on syscall-heavy work (git, compilers) and on some devices. Not "native". |
| **Custom ELF loader + seccomp path translation** (Zdroid's `libzdload.so`) | User-space loader maps binaries itself; SIGSYS handler rewrites paths | Runs an unmodified Ubuntu rootfs at targetSdk 35 | Novel, complex and GPL. High maintenance. Worth watching. |
| **AVF / Android "Linux Terminal"** | Debian VM under pKVM | A real Linux VM | Needs signature/privileged permissions (`MANAGE_VIRTUAL_MACHINE`), so third-party apps can't use it (*permission level partially verified*) |

### 2.2 Google Play policy

**Target API:**
- Since **2026-08-31**, new apps and updates must target **API 36**. An extension to 2026-11-01 was available and has now expired.
- **16 KB page size:** apps targeting 35+ must support it. Updates without it will be **rejected from 2027-02-01**. NDK r28+ aligns to 16 KB by default; for Rust `.so`s, verify with `readelf -l` (LOAD alignment `0x4000`). This also applies to every binary in the bootstrap and packages.

**Device and Network Abuse policy:**
> "an app may not download executable code (such as dex, JAR, .so files) from a source other than Google Play. This restriction does not apply to code that runs in a virtual machine or an interpreter…"

A package manager technically conflicts with this. In practice, Termux (Play), UserLAnd and Acode are all on Play and download user-requested binaries or rootfs images. The Play Termux README says reviewers objected to `sharedUserId` and permissions, not to package downloads. **Treat this as tolerated, not guaranteed.** Ferrux should never download code without an explicit user action, and must not ship "self-update" logic outside Play.

**Foreground services:** use **`specialUse`**, with a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` explanation that Play reviews. This is what both Play Termux and AOSP's Linux Terminal do. Do **not** use `dataSync`: on targetSdk 35+ it is limited to 6 h per 24 h and can't start from `BOOT_COMPLETED`.

**Android 17 / targetSdk 37:** `ACCESS_LOCAL_NETWORK` is now a runtime permission, which matters for ssh/http to LAN hosts.

### 2.3 Sideloading, F-Droid and developer verification

- **Android developer verification:** enforcement started **2026-09-30** in Brazil, Indonesia, Singapore and Thailand, and goes **global in 2027**.
  - Unverified apps need the "advanced flow": Developer options, a one-time 24 h wait, then biometric confirmation. ADB installs still work.
  - **Implication:** register the FerraStack developer identity and the `dev.ferrux` package name early. Do this even for GitHub and F-Droid distribution, because a verified signer avoids the scary flow.
- **F-Droid:**
  - Requires building from source with FLOSS tools.
  - Downloading executables needs an explicit anti-feature disclosure, which Termux has.
  - F-Droid normally signs with its own key, which breaks upgrades between channels. Use **reproducible builds** so F-Droid can publish the developer-signed APK.
  - F-Droid calls developer verification an existential threat, and its future status is uncertain.

### 2.4 Phantom process killer (Android 12+)

- `PhantomProcessList` kills child processes of apps once there are more than **32 across all apps combined**. It also kills processes it considers to use excessive CPU while the app is in the background.
- Symptom: `[Process completed (signal 9)]`. Builds such as `make -j8` or language servers are typical victims.
- **No in-app fix exists.** Mitigations:
  - Keep a foreground service running while sessions exist; that reduces CPU-based kills but not the 32 limit.
  - Detect the setting and show guidance:
    - Android 12L/13+: `adb shell settings put global settings_enable_monitor_phantom_procs false`.
    - Android 14+: Developer options → **"Disable child process restrictions"**. This reverts if Developer options is turned off; the adb setting persists.
  - Document it prominently. Ferride (LSPs, builds) will hit it.

### 2.5 Other platform facts relevant to design

- **Storage:**
  - `MANAGE_EXTERNAL_STORAGE` ("All files access") is restricted on Play to approved use cases. Terminals/file managers have historically been approved, but Play may require a declaration.
  - Fallbacks: SAF + `DocumentsProvider`, plus `~/storage` symlinks after the permission is granted.
- **Notifications:** `POST_NOTIFICATIONS` is a runtime permission on 13+. The FGS notification needs it to be visible, though the service still runs without it.
- **Battery:** `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` plus wakelock toggles, as Termux does.
- **`sharedUserId`:** deprecated (API 29) and cannot be changed after release. **Do not use it.**

---

## 3. Package name, prefix and package repositories

### 3.1 Can Ferrux reuse Termux's repos?

**No, not for a native prefix.** Every binary in `packages-cf.termux.dev` is linked for `/data/data/com.termux/files/usr`.
- Using `com.termux` as Ferrux's package name would conflict with Termux. The two apps couldn't be installed together, and users would be confused. Ferrux would also inherit Termux's targetSdk-28 assumptions; the official repo is not built for linker-exec, while the Play fork's repo uses a different signing key.
- The termux-packages wiki is explicit: *"If forking to compile packages for a different app package name, then packages hosted by Termux package repositories cannot be used… All packages will need to be compiled… Packages cannot be mixed."*

**Possible interim bridge (development only):**
- Run official Termux packages under **proot** with `-b $FERRUX_PREFIX:/data/data/com.termux/files/usr`. This works without rebuilding, at proot's performance cost.
- Using Termux's donation-funded mirrors from another app without permission is not appropriate for a release.
- **Don't build the product on this.**

### 3.2 Prior art for forks with their own prefix

| Project | Package name / prefix | Repo |
|---|---|---|
| **Code on the Go** (AndroidIDE successor) | `com.itsaky.androidide` | Own termux-packages wrapper (`appdevforall/terminal-packages`), own repo `packages.appdevforall.org`, own GPG key. **targetSdk 28**, by design: "a hard requirement, not tech debt". |
| AndroidIDE (archived 2024-10) | `com.itsaky.androidide` | Same approach |
| **Termux Play build** (termux-play-store) | `com.termux` (same name, different signing key) | Own repo `termux.net`; fork of termux-packages with linker-exec patches. targetSdk 37, minSdk 30. |
| Nix-on-Droid | `com.termux.nix` | Termux fork; Nix runs under proot |
| ZeroTermux | `com.termux` | Uses Termux repos |
| UserLAnd, Andronix, AnLinux, proot-distro, ReTerminal, Acode | n/a | Avoid the prefix problem entirely with proot + glibc distro rootfs images |

So building a full own-prefix repo has been done (AndroidIDE / Code on the Go) and is viable. It costs CI time, storage and maintainer effort.

### 3.3 Decision

**Package name `dev.ferrux` → prefix `/data/data/dev.ferrux/files/usr`.**
- **Ten characters**, exactly what termux-packages recommends for a new fork. The prefix has the same length as `com.termux`'s, so no path-length surprises.
- It is permanent once published on Play, so register the matching domain. `ferrux.org` was unregistered as of 2026-11-07; I could not check `.dev` WHOIS from here. If `ferrux.dev` isn't obtainable, `org.ferrux` is the fallback. **Do not use `io.github.ferrastack.ferrux`**: at 27 characters it exceeds the recommended 21 and eats into the socket-path budget.
- Ferride should be `dev.ferride` (11 characters), in case it ever hosts a prefix of its own.
- **Keep Termux's internal layout** (`files/usr`, `files/home`, `$PREFIX/tmp`) so termux-packages recipes work unchanged.

**Package repository plan:**
- Fork **`termux-play-store/termux-packages`**, the Play fork, which already carries the system-linker-exec patches. Rebase regularly onto `termux/termux-packages`.
- Rebuild with `TERMUX_APP__PACKAGE_NAME=dev.ferrux` and `TERMUX_REPO_*` updated, sign with a Ferrux GPG key, and host on Cloudflare R2, GitHub Releases or Hetzner.
- Start with a **curated set**: the bootstrap plus roughly 150–300 high-demand packages (python, nodejs, git, openssh, clang, rust, golang, make, cmake, vim, neovim, tmux, ripgrep, fd, jq, LSP servers for Ferride…). Grow on demand.
- **Ship arm64-v8a first.** Add x86_64 for emulators and ChromeOS. armeabi-v7a is later or never.
- **GPL compliance:** when distributing GPL binaries in the bootstrap and repo, mirror the corresponding source tarballs and patches next to the repo.

**Runtime environment variables:**
- Export `PREFIX`, `HOME`, `TMPDIR`, `LD_PRELOAD=$PREFIX/lib/libtermux-exec*.so`.
- Also export the `TERMUX__*` / `TERMUX_EXEC__*` variables that termux-exec and termux-tools read; they are part of the "ABI" of packages built from termux-packages.
- Add Ferrux-specific `FERRUX_*` variables for its own tools. **Decide deliberately** whether to export `TERMUX_VERSION`: some tools use it to detect Termux and adjust their behavior.

---

## 4. Rust ecosystem, layer by layer

Versions are from crates.io as of 2026-11-07.

### 4.1 Terminal emulation

| Crate | Version | License | Notes |
|---|---|---|---|
| **`alacritty_terminal`** | 0.26.0 (2026-04) | Apache-2.0 | Headless `Term`/grid used by Zed, Lapce and **Zdroid on Android**. Scrollback, reflow, selection (simple/semantic/block), OSC 8 hyperlinks, regex search, vi mode, kitty keyboard protocol. **No sixel or kitty graphics.** Its `tty` module installs a process-global SIGCHLD handler; **don't use it inside an ART process**, use only the grid. Maintainers won't upstream large Android-specific changes (alacritty#7121). |
| `vte` | 0.15.0 | MIT/Apache | Parser only. Use it if you write your own grid. |
| `termwiz` / `wezterm-term` | 0.23.3 / git-only | MIT | Most complete feature set (sixel, kitty, iTerm2 images). `wezterm-term` isn't published, has a heavy dependency tree, and WezTerm's last tagged release was in 2024. |
| `vt100` | 0.18.1 (2026-10) | MIT | Simple; no reflow or hyperlinks |
| `avt` (asciinema) | 0.20.0 (2026-11) | Apache-2.0 | `no_std`, reflow, optional scrollback. Lean. Android untested. |
| `rio-vt` / `rio-backend` | 0.5.29 (2026-11) | MIT | Rio's core, now split out; image protocols behind a feature. Freya's `freya-terminal` uses it. |
| **`libghostty-vt`** (Zig, C ABI) | tag `vt-1.0.0` (2026-11-06) | MIT | Stable C ABI as of yesterday. Render state, selection, search, kitty graphics, key/mouse encoders. Documents Android builds. Rust wrappers `libghostty-vt` 0.2 are pre-1.0 and **need Zig at build time**. |

**Choice:** wrap **`alacritty_terminal`** behind Ferrux's own `ferrux-term` API. It is proven, has a matching license, and runs on Android in Zdroid. Keep the API engine-agnostic so the engine could later be swapped for **libghostty-vt**: more modern, with kitty graphics, but it adds a Zig toolchain. Re-evaluate in 2027. Writing a new VT engine is not an MVP task.

### 4.2 PTY and process spawning

**Crate options:**

| Crate / API | Notes |
|---|---|
| `rustix` 1.1.6 `pty` | `openpt`, `grantpt`, `unlockpt`, `ptsname`. Uses the libc backend on Android. |
| `nix` 0.31 `openpty`/`forkpty` | bionic has these from API 23 |
| `portable-pty` 0.9 | Uses `Command::pre_exec`, which allocates after fork (`close_random_fds` builds a `Vec`). Avoid it in-process on Android. |
| `posix_spawn` | bionic API 28+. Has `POSIX_SPAWN_SETSID`. bionic applies attributes (setsid) **before** file actions, so opening the pts in a file action makes it the controlling TTY. `addchdir_np` needs API 34, so on older versions `chdir` has to be done some other way: a tiny exec helper, or `sh -c 'cd … && exec …'`. |

**Recommendation:** write a small `ferrux-pty`:
- `openpt` via rustix; termios `IUTF8`, no `IXON`; `TIOCSWINSZ`.
- **Prepare everything before forking:** CStrings, envp, the fd list.
- The child calls only raw syscalls: `sigprocmask` unblock-all, `setsid`, open pts, `dup2`, `close_range` / loop, `chdir`, `execve`.
- In linker mode, exec `/system/bin/linker64` with the target path as `argv[1]`. termux-exec handles the children of children.
- Reap children on a dedicated waiter thread with `waitpid(pid)` per child. **Never** install a process-wide SIGCHLD handler or call `waitpid(-1)`: ART and other libraries own other children.
- Run fds through Android's `fdsan`-friendly ownership (`OwnedFd`).

### 4.3 Rendering

| Option | Notes |
|---|---|
| **`wgpu` 30.0.1** | Vulkan + GLES backends on Android. Works in production-like use (Zdroid: gpui on wgpu/Vulkan). Open Android driver bugs: Mali #10186, Adreno #10621, GLES debug-group crash #8937. Surface must be recreated on window loss. Adds a few MB per ABI (*to measure*). |
| `glow` 0.18 + EGL (`khronos-egl`/`glutin`) | Smallest, universal GLES 3. Alacritty's renderer is GL, but its font stack (`crossfont`) has no Android backend. |
| `skia-safe` 0.157 | Prebuilt for Android, but several MB plus ICU, and there's an open "bad text performance on Android" issue. Overkill for a cell grid. |
| `softbuffer` + `tiny-skia` | `ANativeWindow_lock` CPU blit. Viable for a terminal (damage-tracked rows); good fallback for broken GPU drivers. |
| Kotlin `Canvas` (what Termux does) | Simplest and proven, but rendering then isn't reusable from Rust/Ferride. A JNI snapshot copy is needed every frame. |

**Fonts and shaping:**
- **`swash`** 0.2.10 for rasterisation (used by Rio and cosmic-text). `cosmic-text` 0.19 + `glyphon` 0.12 if you want shaping/layout with wgpu.
- **`harfrust`** for shaping: `rustybuzz` is archived in favour of HarfRust.
- A terminal needs mostly per-cell rasterisation, a glyph atlas, ligatures (optional) and fallback fonts. Load fallbacks from **`/system/fonts`** for CJK and emoji (Noto Color Emoji is CBDT/COLRv1; swash handles colour bitmaps) and Powerline/Nerd glyphs. Bundle one monospace font (e.g. JetBrains Mono, OFL).
- Disable `fontconfig` features on Android.

**Choice:** a glyph-atlas, instanced-quad renderer in **`ferrux-render`** on **wgpu**, with an API that draws *into a caller-supplied render pass/texture*. Ferride can then composite terminals inside its own GPU UI. Decide in M0 whether to also keep a **software fallback** (softbuffer) for problem drivers.

### 4.4 Android glue

| Tool | State |
|---|---|
| `jni` **0.22.4** (2026-03) | Big redesign: `Env`, `Global<T>`, `native_method!`, `bind_java_type!`, `#[jni_mangle]`. The right choice for hot-path JNI (input events, surface callbacks). |
| `uniffi` 0.32 | Kotlin bindings via **JNA**, with per-call overhead. Fine for coarse APIs (session CRUD, settings, Ferride control); wrong for per-keystroke or per-frame calls. MPL-2.0 (file-level copyleft; fine as a dependency). |
| `ndk` 0.9.0 | `ANativeWindow`, `ALooper`, `AChoreographer`. Last release 2024 but works. |
| `android-activity` 0.6.1 | NativeActivity/GameActivity glue. **Not needed** in Option B: the Activity is Kotlin. |
| **`cargo-ndk` 4.1.2** | The de-facto standard; drive it from Gradle (or an `xtask`). |
| `xbuild`, `cargo-apk` | Unmaintained or legacy. Avoid. |

### 4.5 Soft keyboard (IME), selection, clipboard, accessibility: the decisive layer

Real IME support needs a focused Android **`View`** returning an **`InputConnection`** from `onCreateInputConnection`. That interface carries composing text, `commitText`, `deleteSurroundingText`, `setComposingRegion`, `CursorAnchorInfo` (for candidate-window placement) and key events.

**State in pure-Rust stacks (2026-11):**
- **winit** (0.30.13; 0.31.0-beta.4):
  - Android IME requests only toggle the keyboard. `set_ime_cursor_area` and `set_ime_purpose` are no-ops, and *no IME events are delivered*.
  - PR #4451 ("basic IME support for Android", GameActivity-only) has been open since 2025-12; testers report that preedit never commits.
  - With NativeActivity the keyboard doesn't even show for some testers.
- **android-activity**: NativeActivity "is not expected to ever get full InputConnection/IME support" (maintainer, #44). GameActivity's `GameTextInput` provides one edit buffer with selection and compose region. That is usable, but it has open crash bugs (#252, #254), and **GameActivity 4.x must be packaged with Gradle anyway**.
- **egui/eframe** 0.37: soft keyboard doesn't open on Android (#5590).
- **iced**: no official Android support.
- **Freya**: has `freya-android`; keyboard handling is immature (#2494).
- **Slint** 1.18 is the most complete. It works by **injecting its own Java** (`SlintAndroidJavaHelper.java`): a custom `View` with a `BaseInputConnection`, `CursorAnchorInfo`, native selection handles, an `ActionMode` copy/paste menu and `ClipboardManager`. License note: its royalty-free license forbids redistributing an app that *exposes Slint APIs*, which is relevant to an embeddable component.
- **Makepad**: also ships its own Java `InputConnection` plus a TalkBack provider.
- **rust-mobile/android-view** (Linebender folks, WIP, paused since 2025-07): "implement an Android View in Rust", aiming for framework-faithful text input. This is the right model and validates Option B.
- **Accessibility:** **AccessKit** 0.25 / `accesskit_android` 0.9 is a real Android adapter, including an `InjectingAdapter` for any `View`. For a terminal, a custom `AccessibilityNodeProvider` (per-line nodes, cursor announcements) in Kotlin fed from Rust is simpler and more controllable.

**Lessons from Termux's `TerminalView`:**
- Default `inputType = TYPE_NULL`, so most IMEs send raw key events. This breaks Japanese/CJK input (termux#137).
- Optional "enforce char-based input" (`VISIBLE_PASSWORD | NO_SUGGESTIONS`), needed for Samsung keyboards.
- `IME_FLAG_NO_FULLSCREEN`.
- Composition is sent only on `commitText`/`finishComposingText`.
- **Ferrux can do better.** Use a proper `BaseInputConnection` with a shadow editable, render the **preedit inline at the cursor** (done in Rust), and report `CursorAnchorInfo` so candidate windows follow the cursor. Keep a per-keyboard compatibility toggle.

**Conclusion:** the IME layer must be Kotlin/Java whichever Rust UI stack is used. Every successful Rust toolkit on Android already reached the same conclusion.

### 4.6 Rust UI frameworks (for settings and chrome)

| Framework | Version | License | Android maturity |
|---|---|---|---|
| Slint | 1.18.1 | GPL-3 / royalty-free / commercial | Best Android text input of the Rust toolkits |
| Makepad | 1.0.0 (git active) | MIT/Apache | Own build tool, API churn |
| Freya | 0.5.0 | MIT | Skia, terminal widget exists, immature keyboard |
| egui | 0.37 | MIT/Apache | No soft keyboard |
| iced | 0.14 | MIT | No official Android |
| Dioxus | 0.7.10 | MIT/Apache | Mobile = WebView; native (Blitz) experimental |
| Tauri v2 | 2.12.5 | MIT/Apache | Production mobile via WebView; a terminal would be xterm.js |
| Xilem/Masonry | 0.4 | Apache-2.0 | Experimental |

A terminal's main surface is custom-drawn anyway. A Rust UI framework would only render the settings screens, drawers and dialogs. **Jetpack Compose is the better tool there**: native look, accessibility, IME and predictive back for free, and a huge contributor pool.

---

## 5. Existing projects / prior art

**Rust (or partly Rust) terminals on Android:**
- **Zdroid** (`Dylanmurzello/zed-android-port`, 254★, active, GPL-3.0 app / MIT engine crates):
  - Zed's gpui on wgpu/Vulkan, Zed's alacritty-based terminal, a bundled Ubuntu rootfs via a custom loader + seccomp path translation, a foreground service. Moved from targetSdk 28 to 35 on 2026-10-28.
  - **Supports hardware keyboards only.** Soft keyboard/IME is explicitly deferred, which confirms the IME difficulty.
  - Its ~70 "workarounds" docs are the best Android pitfall catalogue available. Read them.
- **Rin** (`pavelc4/Rin`, MIT, small): Kotlin/Compose UI + Rust via JNI + a Termux-repo-compatible package tool. Same split as Option B.
- No WezTerm or Rio Android ports exist. An Alacritty Android port was shown in a screenshot (alacritty#7121) but never released.

**Non-Rust terminals:**
- **Termux**: F-Droid/GitHub at targetSdk 28; Play build at 37 via linker-exec.
- **ZeroTermux** and **ReTerminal**: ReTerminal is MIT, Compose on top of Termux's `TerminalView`, Alpine via proot.
- **Acode**: Cordova + xterm.js + proot.
- **NeoTerm**: dormant.
- **UserLAnd**: targetSdk 37, proot binaries shipped as `.so`, downloads rootfs images.
- **Android's own "Linux Terminal"**: an AVF Debian VM; the UI is a WebView (ttyd/xterm.js). Not usable by third parties.
- **Code on the Go**: an IDE with a Termux-based environment and its own prefix and repo. **The closest analogue to Ferride+Ferrux.**

---

## 6. Does Rust make sense? (honest assessment)

| Layer | Fit | Reasoning |
|---|---|---|
| VT parser and grid | **Excellent** | Parses untrusted byte streams, so memory safety matters. Performance-sensitive (`cat` of large files, TUI redraws). Mature crates. Testable on desktop CI. **Shared with Ferride.** |
| PTY / process / session daemon | **Good** | Small, systems-level, needs careful `unsafe`. Rust is fine; C would be too. Sharing it with Ferride and the IPC server is the win. |
| GPU renderer | **Good, with risk** | wgpu + swash is a solid stack, shared with Ferride, but exposed to Android GPU driver quirks. A Kotlin Canvas renderer is simpler and proven (Termux), but not shareable. |
| Bootstrap installer, env, pkg front-end | **Good** | Plain systems code; a Rust `ferrux` CLI can replace shell glue later |
| Android UI: Activity, IME, a11y, selection UI, clipboard, notifications, FGS, intents, SAF, permissions, settings | **Poor** | Every one of these is a Java framework API. Pure-Rust glue for IME and a11y is immature (§4.5). Kotlin/Compose is less code, less risk, and has more contributors. |
| `LD_PRELOAD` exec shim | **Neutral** | Reuse `termux-exec` (C, Apache-2.0). Rewriting it in Rust gains nothing right now. |
| Package recipes / build system | **N/A** | Bash + C patches in termux-packages. Thousands of recipes; fork, don't rewrite. |

**Where Rust pays off over "just fork Termux":**
1. One terminal engine and renderer shared between Ferrux and Ferride. This is the key strategic reason.
2. A desktop dev loop (`cargo run` on Linux) for the whole core.
3. Safety and performance in the parser.
4. Apache-2.0 freedom. You cannot fork GPLv3 Termux into an Apache project, so a rewrite of the app layer was needed anyway.

**Where it doesn't:** the problems that dominate a Termux-class project. Rust does nothing for W^X, packaging, Play policy, OEM process killing, IME quirks or keyboard compatibility.

Conversely, a **pure-Rust** app would cost the most in exactly the places where users notice: typing on a phone.

---

## 7. Architecture options compared

**(A) Pure Rust app.** NativeActivity/GameActivity via android-activity, wgpu renderer, UI in Rust (egui/Slint/custom).

**(B) Thin Kotlin shell + Rust core.**
- Kotlin: Activity, `TerminalSurfaceView` (SurfaceView + `InputConnection` + `AccessibilityNodeProvider` + gestures), extra-keys row, Compose settings, `FerruxService` (FGS), `RunCommandService`, a Binder API, `DocumentsProvider`.
- Rust (`libferrux.so`): emulation, PTY, sessions, renderer into `ANativeWindow`, bootstrap, IPC logic.

**(B′) B, but with Kotlin Canvas rendering** (Termux-style) of a Rust-owned grid snapshot.

**(C) Fork termux-app (Java) and swap in Rust pieces.** Blocked: GPLv3 vs Ferrux's Apache-2.0, and no real Rust gain.

**(D) WebView + xterm.js** (Tauri v2 / Acode / AVF Terminal style), with a Rust backend.

| Criterion | A: pure Rust | **B: Kotlin shell + Rust core** | B′: Canvas render | D: WebView/xterm.js |
|---|---|---|---|---|
| IME (CJK, swipe, autocorrect) | ✗ Broken today; you'd write Java anyway | ✓ Native `InputConnection` | ✓ | ~ WebView IME is OK but quirky in terminals |
| Selection / clipboard / ActionMode | Must hand-roll in Java | ✓ Native | ✓ | ✓ (xterm.js) |
| Accessibility (TalkBack) | AccessKit (young) | ✓ `AccessibilityNodeProvider` | ✓ | ~ |
| Services, notifications, intents, SAF | JNI everywhere | ✓ Natural | ✓ | Plugins |
| Rendering performance | ✓ GPU | ✓ GPU | ~ CPU Canvas (fine in practice) | ~ JS/DOM/WebGL |
| Reuse in Ferride | ✓ | ✓ (crates + optional Kotlin view AAR) | ~ renderer not reusable from Rust | ✗ |
| APK size | Small | Small + Kotlin/Compose (~2–4 MB) | Smallest | WebView is system-provided; JS bundle |
| Maintenance risk | High (ecosystem churn) | Medium | Low | Medium |
| Contributor pool | Rust + Android-internals experts (rare) | Rust **or** Android devs can contribute in their layer | Same | Web devs |
| Desktop dev loop | ✓ | ✓ for the core | Partial | ✓ |

**Verdict: B.** Keep B′ as an explicit fallback: if the M0 renderer spike shows driver problems, the Kotlin side can render `ferrux-term` snapshots with Canvas without touching the rest of the architecture.

---

## 8. Ferride integration (embedding & IPC)

**Constraint:** Ferride (`dev.ferride`) is a separate app with its own UID. It **cannot read or exec** files in `/data/data/dev.ferrux`, which is mode 0700 and in a different SELinux category. `sharedUserId` is deprecated and must not be used.

| Model | Description | Verdict |
|---|---|---|
| **1. Library-only** | Ferride links `ferrux-*` crates and installs its *own* bootstrap/prefix (`/data/data/dev.ferride/files/usr`) | Doubles the package-repo burden (a second prefix). Only sensible if Ferride ships standalone without Ferrux. |
| **2. Service + fd passing** ✅ | Ferrux exports a bound service (AIDL), protected by a **signature-level permission** (same FerraStack key, so granted automatically). A dangerous-level permission, opt-in like Termux's `allow-external-apps`, covers third-party apps. Ferride calls `openSession(argv, env, cwd, rows, cols)`; Ferrux spawns the process **in its own sandbox** and returns the **PTY master as a `ParcelFileDescriptor`**. Ferride runs `ferrux-term` + `ferrux-render` locally on that fd. `exec(argv) → stdin/stdout/stderr pipes + exit code` serves LSPs, builds and git. | **Recommended.** One prefix, one repo. Processes live in Ferrux and are protected by its foreground service. Zero-copy terminal I/O, and Ferride gets native-feeling terminals. |
| 3. RUN_COMMAND intent | Fire-and-forget, Termux-compatible style; results via `PendingIntent` | Implement it as well, for automation (Tasker etc.), but it's too coarse for an IDE |

The v1 API that implements this model is specified in [API.md](API.md).

**Details to design and verify for model 2:**
- **fd passing:** passing pipe fds across apps via Binder is routine (`ParcelFileDescriptor.createPipe`). Passing a **`/dev/ptmx` master fd** across apps *should* work, but SELinux/MLS rules for cross-app `fd use` on `ptmx_device` **must be verified in an M0 spike**. The fallback is a socketpair/pipe proxy owned by Ferrux, plus a resize RPC.
- **Files:** Ferride needs to edit projects living in Ferrux's `$HOME`. Options:
  - a `DocumentsProvider` (SAF) for user-visible access;
  - a Binder file API (open → `ParcelFileDescriptor`, list, stat, watch via inotify, rename);
  - projects in shared storage. That one is slow (FUSE), `noexec`, and needs All-files access.
  - **Recommended:** the Binder file API, plus a `DocumentsProvider` for other apps.
- **Lifecycle:** a session's processes belong to Ferrux. Ferrux's FGS must stay up while Ferride has live sessions. Phantom-process limits apply to the combined total.
- **Versioning:** the AIDL interface is versioned. Ferride checks the Ferrux version and offers "install/update Ferrux" if needed.
- **Optional:** publish the Kotlin `TerminalSurfaceView` as an AAR (`ferrux-terminal-view`), so that Ferride, if it has any Kotlin shell, gets IME/a11y for free.

---

## 9. Feature list: MVP vs later

### MVP (v0.1, "usable daily driver for a developer")
- Bootstrap install (arm64 + x86_64), `apt`/`pkg` from the **Ferrux repo**, linker-exec at targetSdk 36+.
- A terminal with good IME: Gboard, Samsung, SwiftKey, CJK composition shown inline; a hardware keyboard with full modifier support.
- **Extra keys row**: ESC, TAB, CTRL, ALT, arrows, `-`, `/`, `|`, HOME, END, PGUP, PGDN. Sticky modifiers; configurable via a properties/TOML file.
- **Multiple sessions** with a drawer: create, rename, switch, kill. Sessions survive the Activity being destroyed (FGS + notification with session count, "exit all" and wakelock actions).
- Scrollback, pinch-to-zoom font size, long-press selection with native handles and an ActionMode copy/paste, paste with bracketed paste, URL detection and opening.
- Colours and themes (a couple built in), a bundled monospace font plus system fallbacks, bell (vibrate/beep).
- `ferrux-setup-storage` (shared storage symlinks after the permission is granted).
- `ferrux-open` / `xdg-open` / `termux-open-url` equivalents and clipboard get/set commands (via a socket to the app).
- **Ferride API v1:** signature-protected bound service with `openSession`, `exec`, a basic file API, version query.
- `RUN_COMMAND`-style intent (opt-in for third parties).
- Phantom-process detection and guidance screen; battery-optimisation prompt.
- Crash and log export.

### Later
- `DocumentsProvider`; Ferrux:API equivalents: notifications, battery, camera, location, SMS-less, sensors, dialogs, share. Build them in the main app (no `sharedUserId`), exposed through a CLI + socket.
- Boot scripts (`~/.ferrux/boot`, requires `RECEIVE_BOOT_COMPLETED`), home-screen widgets and shortcuts, Tasker plugin.
- Image protocols (sixel, kitty graphics), hyperlinks UI, search in scrollback, split panes, tmux-style detach/attach from the IPC API.
- **proot-distro-style glibc distros** (Debian, Arch, Alpine) as an alternative environment: the proot loader shipped as `lib*.so`.
- X11/Wayland GUI apps (Termux:X11-like), PulseAudio.
- Multi-window, DeX/desktop mode, ChromeOS, foldables; mouse and trackpad.
- armeabi-v7a; a full package-repo breadth comparable to Termux; mirrors.
- Accessibility polish: line-by-line navigation, output announcements.
- Possible engine swap to libghostty-vt; possible software-render fallback.
- A Rust `ferrux` CLI replacing shell glue (`pkg`, `ferrux-change-repo`, …).

---

## 10. Recommendation

1. **Use Option B:** a Kotlin shell + Rust core. Rust owns emulation, PTY, sessions, rendering, bootstrap, IPC logic and CLI tools. Kotlin owns everything that is an Android framework API, starting with the `InputConnection`.
2. **Use `alacritty_terminal` (grid only)** behind an engine-agnostic `ferrux-term` API, with a hand-written `ferrux-pty` (no global SIGCHLD, async-signal-safe child or `posix_spawn`).
3. **Renderer:** wgpu + swash glyph atlas, drawing into a caller-provided target so Ferride can reuse it. Gate this on the M0 device spike, with Kotlin Canvas (B′) as a fallback.
4. **App config:** targetSdk 36 (37 when stable for Play), **minSdk 30**, matching the Play Termux fork whose package patches you will build on. Linker-exec via `termux-exec`. Own helper binaries go in `nativeLibraryDir`. FGS type `specialUse`. 16 KB-aligned everything.
5. **Package name `dev.ferrux`**, prefix `/data/data/dev.ferrux/files/usr`, Termux-compatible layout. Own apt repo built from a fork of `termux-play-store/termux-packages`, with its own GPG key and a curated subset first. Register the domain and the Android developer identity now.
6. **Ferride talks to Ferrux through a signature-protected Binder service with fd passing.** Ferride shares the Rust crates, not the prefix.
7. **Licensing:** stay Apache-2.0 for Ferrux code and copy nothing from GPL Termux code. Reuse `termux-exec` (Apache-2.0) and termux-packages infrastructure (Apache-2.0). Package recipes keep their own licenses in the separate packages repo. Mirror GPL sources for the repo.

---

## 11. Proposed architecture

```
ferrux/                                   (this repo, Apache-2.0)
├── Cargo.toml                            workspace
├── crates/
│   ├── ferrux-term/      VT engine facade: wraps alacritty_terminal::Term.
│   │                     Snapshot/damage API, key/mouse/paste → bytes encoder,
│   │                     selection, search, OSC handling (title, cwd, OSC 7/8/52/133).
│   │                     No Android deps; used by Ferride too.
│   ├── ferrux-pty/       openpt + spawn (posix_spawn or async-signal-safe fork),
│   │                     resize, per-child waiter, exec modes {direct, linker64}.
│   ├── ferrux-session/   Session manager: session registry, I/O event loop
│   │                     (mio/polling), scrollback limits, title/bell events,
│   │                     "remote session" over a passed-in fd (Ferride side).
│   ├── ferrux-render/    wgpu glyph-atlas renderer (swash raster, harfrust optional
│   │                     for ligatures), font discovery incl. /system/fonts fallback,
│   │                     cursor/selection/preedit drawing. API: render(&Snapshot, target).
│   ├── ferrux-env/       Prefix/layout constants, env construction (PREFIX, HOME,
│   │                     TMPDIR, LD_PRELOAD, TERMUX__* compat, FERRUX_*), bootstrap
│   │                     extraction (stage → atomic rename), first-run & upgrades.
│   ├── ferrux-ipc/       Protocol types for app↔CLI socket ($PREFIX/../ferrux/app.sock)
│   │                     and the Ferride API (mirrors the AIDL), versioning.
│   ├── ferrux-android/   cdylib → libferrux.so. jni 0.22 native methods: surface
│   │                     lifecycle (ANativeWindow), input/IME events, session control,
│   │                     selection/a11y text queries. Thin; logic lives in crates above.
│   ├── ferrux-cli/       `ferrux` multi-call binary installed into the prefix
│   │                     (open, clipboard, notify, setup-storage…) — talks to app.sock.
│   └── ferrux-desktop/   dev harness: winit + ferrux-render + ferrux-session on Linux/macOS.
├── android/                              Gradle project (Kotlin)
│   ├── app/              MainActivity, session drawer, extra-keys, Compose settings,
│   │                     FerruxService (FGS specialUse), RunCommandService,
│   │                     FerruxApiService (AIDL, signature permission),
│   │                     DocumentsProvider (later), BootReceiver (later).
│   ├── terminal-view/    TerminalSurfaceView: SurfaceView + InputConnection +
│   │                     CursorAnchorInfo + gestures + selection ActionMode +
│   │                     AccessibilityNodeProvider. Published as AAR for Ferride.
│   └── api/              AIDL + Kotlin client lib (`ferrux-client`) for Ferride / 3rd parties.
├── xtask/                build orchestration: cargo-ndk per ABI → jniLibs, bootstrap fetch
│                         + checksum, 16 KB alignment check, APK/AAB size report.
└── docs/

FerraStack/ferrux-packages                (separate repo; fork of termux-play-store/termux-packages)
   scripts/properties.sh: TERMUX_APP__PACKAGE_NAME=dev.ferrux, TERMUX_REPO_* → ferrux
   CI: build bootstrap + curated packages (aarch64, x86_64), sign Release, publish apt repo
```

**Threading model (Android):**
- **Kotlin UI thread**: input and IME callbacks → `native_method!` → lock-free queue into the session.
- **Rust I/O thread** (one for all sessions, poll-based): reads PTYs, feeds `ferrux-term`, marks damage, wakes the renderer.
- **Rust render thread** per visible surface, paced by `AChoreographer`.
- **Waiter threads**, or one thread with a `pidfd` poll if available, for child exit.

---

## 12. MVP milestones

| # | Milestone | Content | Exit criteria |
|---|---|---|---|
| **M0** | Spikes (2–3 weeks) | (1) **Exec:** a targetSdk-36 APK, minSdk 30, runs a hand-built `dev.ferrux` bootstrap (`build-bootstraps.sh` from the Play fork) via linker64 + termux-exec; `apt update` works. (2) **Render:** wgpu vs glow vs softbuffer on Adreno, Mali and the emulator: cold start, frame time, APK delta. (3) **IME:** Kotlin `InputConnection` prototype tested with Gboard, Samsung, SwiftKey, a CJK IME and swipe typing. (4) **Cross-app PTY fd** passing via Binder between two test apps. | Written go/no-go per spike; renderer decision |
| **M1** | Core on desktop | `ferrux-term`, `ferrux-pty`, `ferrux-session`, `ferrux-render`, `ferrux-desktop` run bash/vim/htop on Linux. vttest and esctest baselines. Fuzzing the parser via the facade. | `vim`, `tmux`, `htop` correct; CI green |
| **M2** | Android shell | Gradle project; `TerminalSurfaceView` + JNI; `/system/bin/sh` session; IME + extra keys; FGS; lifecycle (rotate, background, surface loss) | Daily usable with system shell on 3 devices |
| **M3** | Packages | `ferrux-packages` CI builds bootstrap and an initial ~150 packages (aarch64, x86_64), signed repo, `ferrux-env` installer, `pkg` working, 16 KB alignment verified | Fresh install → `pkg install python git openssh nodejs` works |
| **M4** | UX completeness | Sessions drawer, selection/copy/paste, URL open, scrollback, zoom, themes, bell, settings (Compose), `ferrux-cli` basics, phantom-process guidance | Feature list in §9 MVP minus the API |
| **M5** | Ferride API v1 | AIDL service + signature permission, `openSession` (fd), `exec` (pipes), file API, `ferrux-client` lib, RUN_COMMAND intent | Ferride prototype opens a terminal and runs an LSP through Ferrux |
| **M6** | Release | Reproducible build; GitHub releases; F-Droid metadata; Play listing (FGS `specialUse` declaration, data safety, All-files-access declaration if used); developer-verification registration | v0.1.0 published |

---

## 13. Risks

1. **Package infrastructure is the long pole.** Building and keeping current even a few hundred packages for a custom prefix takes ongoing CI compute (hours per full rebuild), storage, a signing-key process, and a maintainer who rebases onto termux-packages weekly. Mitigation: a curated set, automation, and close tracking of the Play fork.
2. **Linker-exec edge cases.** Static binaries (Go with `CGO_ENABLED=0`, Zig/musl), `/proc/self/exe` users, and raw `execve` callers (some language runtimes) break. Ferride's language toolchains (Go, Rust, Node prebuilt binaries, Python wheels with static helpers) hit these. Mitigation: the Play fork's patches, proot fallback for problem binaries, and documentation.
3. **Policy risk.** Google could enforce the "no downloaded executable code" clause against terminals, or close the linker64 path in a future SELinux release. Android 17's read-only `System.load` rule shows active tightening. Mitigation: keep F-Droid/GitHub channels, follow termux-app#2155 / APKLF, and keep the proot path ready.
4. **Developer verification (2027 global)** makes sideloaded distribution harder. Mitigation: register early, ship on Play.
5. **IME compatibility** is endless OEM whack-a-mole (Samsung, Xiaomi, SwiftKey). Mitigation: a per-IME compatibility mode setting, as Termux has, and a device test matrix.
6. **GPU driver bugs** on Mali and old Adreno with wgpu. Mitigation: the M0 spike, plus a software or Canvas fallback.
7. **Phantom process killer** hurts IDE workloads (LSP + build + watchers > 32 processes). Not fixable in-app; needs good UX guidance.
8. **Cross-app PTY fd passing** might be blocked by SELinux MLS. Mitigation: the pipe/socket proxy fallback, verified in M0.
9. **Multi-user / work profile** is unsupported with a fixed prefix, same as Termux. Document it.
10. **Scope creep toward a "Termux clone".** Termux has ~10 years of accumulated edge-case handling. Keep the MVP tight.

---

## 14. Open questions

- Is `ferrux.dev` obtainable? Is the Android package name final (`dev.ferrux`) before the first Play upload?
- Does Ferride ship standalone (needing its own prefix, Model 1) or always require Ferrux installed (Model 2)? This report assumes Model 2.
- Should packages build on termux-play-store's fork (linker-exec patches, minSdk 30) or upstream termux-packages plus our own patches? The recommendation is the Play fork; confirm its license and patch provenance and contact the maintainers.
- Is a targetSdk-28 "unrestricted" flavour (direct exec) worth offering on GitHub/F-Droid? Same package name, so same repo, different signing key, like Termux's Play vs F-Droid builds.
- Export `TERMUX_VERSION` / `TERMUX__*` for compatibility, or not?
- Should Ferride's own UI stack share `ferrux-render`'s wgpu device, or receive frames as textures?

---

## 15. Sources

**Termux**
- termux-app source (cloned, commit 8629e63, 2026-09-26): https://github.com/termux/termux-app — `LICENSE.md`, `termux-shared/LICENSE.md`, `gradle.properties`, `app/build.gradle`, `app/src/main/cpp/termux-bootstrap-zip.S`, `terminal-emulator/src/main/jni/termux.c`, `terminal-view/.../TerminalView.java`, `app/.../TermuxInstaller.java`
- W^X issues: https://github.com/termux/termux-app/issues/2155 · https://github.com/termux/termux-app/issues/1072 · https://issuetracker.google.com/issues/128554619
- termux-exec-package (cloned): https://github.com/termux/termux-exec-package — `site/pages/en/projects/docs/technical/index.md`
- termux-packages: https://github.com/termux/termux-packages — `scripts/properties.sh`, `scripts/generate-bootstraps.sh`, `scripts/build-bootstraps.sh`, `LICENSE.md`
- Wiki: https://github.com/termux/termux-packages/wiki/Termux-file-system-layout · https://github.com/termux/termux-packages/wiki/Building-packages · https://github.com/termux/termux-packages/wiki/Termux-and-Android-10
- Termux Play build: https://github.com/termux-play-store · https://github.com/termux-play-store/termux-apps · https://github.com/termux-play-store/termux-packages · https://github.com/termux-play-store/termux-exec
- agnostic-apollo Android docs: https://github.com/agnostic-apollo/Android-Docs (`app-data-file-execute-restrictions.md`, `phantom-cached-and-empty-processes.md`)

**Android platform and policy**
- AOSP sepolicy: https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/ (`seapp_contexts`, `app_neverallows.te`, `untrusted_app_all.te`)
- bionic: https://android.googlesource.com/platform/bionic/+/refs/heads/main/docs/status.md · https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/bionic/spawn.cpp
- Target SDK: https://developer.android.com/google/play/requirements/target-sdk
- 16 KB pages: https://developer.android.com/guide/practices/page-sizes
- Android 17 changes: https://developer.android.com/about/versions/17/behavior-changes-17
- FGS types and timeouts: https://developer.android.com/develop/background-work/services/fgs/service-types · https://developer.android.com/develop/background-work/services/fgs/timeout
- Play Device & Network Abuse: https://support.google.com/googleplay/android-developer/answer/9888379
- Developer verification: https://developer.android.com/developer-verification · https://android-developers.googleblog.com/2026/03/android-developer-verification-rolling-out-to-all-developers.html · https://9to5google.com/2026/08/18/google-gradually-rolling-out-androids-advanced-sideloading-ahead-of-developer-verification/
- F-Droid inclusion policy: https://f-droid.org/docs/Inclusion_Policy/
- Min targetSdk matrix: https://bayton.org/android/android-minimum-targetsdk-matrix/
- AVF Terminal: https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/main/android/TerminalApp/

**Rust ecosystem**
- crates.io API (versions/licenses as of 2026-11-07): alacritty_terminal, vte, termwiz, vt100, avt, rio-backend, android-activity, ndk, jni, uniffi, wgpu, glow, skia-safe, swash, cosmic-text, fontdue, ab_glyph, rustix, nix, portable-pty, cargo-ndk, xbuild, cargo-apk, winit, slint, egui, makepad-widgets, dioxus, iced
- https://github.com/alacritty/alacritty (issue #7121, `alacritty_terminal/CHANGELOG.md`)
- https://github.com/wezterm/wezterm · https://github.com/doy/vt100-rust · https://github.com/asciinema/avt · https://github.com/raphamorim/rio
- https://libghostty.tip.ghostty.org/ · https://github.com/ghostty-org/ghostty · https://github.com/Uzaaft/libghostty-rs
- https://github.com/wezterm/wezterm/blob/main/pty/src/unix.rs · https://github.com/bytecodealliance/rustix
- https://github.com/gfx-rs/wgpu/issues?q=android · https://github.com/rust-skia/rust-skia · https://github.com/rust-windowing/softbuffer · https://github.com/harfbuzz/harfrust · https://github.com/pop-os/cosmic-text · https://github.com/grovesNL/glyphon
- https://github.com/rust-mobile/android-activity (CHANGELOG, issue #44, PR #255) · https://github.com/jni-rs/jni-rs · https://github.com/mozilla/uniffi-rs · https://github.com/bbqsrc/cargo-ndk · https://github.com/rust-mobile/xbuild
- IME state: https://github.com/rust-windowing/winit/pull/4451 · https://github.com/rust-windowing/winit/issues/1823 · https://github.com/rust-windowing/winit/issues/2305 · https://github.com/emilk/egui/issues/5590 · https://github.com/marc2332/freya/issues/2494
- Slint Android Java helper: https://github.com/slint-ui/slint/blob/master/internal/backends/android-activity/java/SlintAndroidJavaHelper.java · Slint license: https://github.com/slint-ui/slint/blob/master/LICENSE.md
- Makepad Android Java: https://github.com/makepad/makepad/tree/main/tools/cargo_makepad/src/android/java/dev/makepad/android
- https://github.com/rust-mobile/android-view · https://github.com/AccessKit/accesskit/tree/main/adapters/android
- Blitz on mobile: https://github.com/DioxusLabs/blitz/issues/377

**Prior art**
- Zdroid: https://github.com/Dylanmurzello/zed-android-port (see `crates/gpui_android/docs/workarounds`)
- Rin: https://github.com/pavelc4/Rin
- Code on the Go: https://github.com/appdevforall/CodeOnTheGo · https://github.com/appdevforall/terminal-packages
- AndroidIDE terminal-packages: https://github.com/AndroidIDEOfficial/terminal-packages
- UserLAnd: https://github.com/CypherpunkArmory/UserLAnd · Acode: https://github.com/Acode-Foundation/Acode · ReTerminal: https://github.com/RohitKushvaha01/ReTerminal · ZeroTermux: https://github.com/hanxinhao000/ZeroTermux · Nix-on-Droid: https://github.com/nix-community/nix-on-droid-app · proot-distro: https://github.com/termux/proot-distro · NeoTerm: https://github.com/neoterm/neoterm
