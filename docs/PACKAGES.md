# Packages, bootstrap and `pkg`

Milestone M3. How Ferrux gets a Linux userland into
`/data/data/dev.ferrux/files/usr`, and the decisions behind it. Background:
[RESEARCH.md](RESEARCH.md) §1.3–1.5 (Termux bootstrap, termux-exec,
termux-packages), §2.1 (W^X) and §3 (package name, prefix, repositories).

## Overview

```
FerraStack/ferrux-packages  (fork of termux-play-store/termux-packages)
  CI: build ferrux/packages.txt for aarch64 + x86_64 in the termux-packages Docker image
   ├─ release repo-aarch64, repo-x86_64   signed flat apt repositories (pkg install …)
   └─ release bootstrap-YYYY.MM.DD-rN     bootstrap-<arch>.zip + SHA256SUMS

Ferrux app (this repo)
  ferrux-env::bootstrap::PINNED   release tag + SHA-256 per arch
  first run: setup screen → download (Kotlin) → verify + extract (Rust) → $PREFIX
  session:   /system/bin/linker64 $PREFIX/bin/bash -l, LD_PRELOAD=libtermux-exec.so
```

## Decisions

### Package source: fork of the Google Play Termux packages

[FerraStack/ferrux-packages](https://github.com/FerraStack/ferrux-packages) is a
fork of `termux-play-store/termux-packages`, not of `termux/termux-packages`:

- It already carries the patches for running everything through
  `/system/bin/linker64` (pgrep/pkill, LLVM, Go, …) and its termux-exec
  (`termux-play-store/termux-exec`, Apache-2.0) does linker exec for every
  program under the app data directory. Upstream Termux targets SDK 28 and
  does not need them.
- It builds for API level 30, which is our `minSdk`.
- Licences are the same as upstream: build infrastructure Apache-2.0, each
  recipe under its package's licence. It merges `termux/termux-packages`
  regularly, so we track upstream through it.

The fork changes only what the prefix and repository require
(`scripts/properties.sh`: `TERMUX_APP__PACKAGE_NAME=dev.ferrux`; `repo.json`;
apt sources; keyring) plus Ferrux CI under `ferrux/` and
`.github/workflows/ferrux-*.yml`. Its `FERRUX.md` lists every difference.

### Repository hosting: GitHub Releases, one flat repo per architecture

- **Free and sustainable**: no bandwidth cap, 2 GiB per file, no 1 GB site
  limit (GitHub Pages has one, plus a soft 100 GB/month bandwidth limit).
- Release assets cannot live in directories, so each architecture is a *flat*
  apt repository (`InRelease`, `Packages`, debs side by side) in the release
  `repo-<arch>`. apt supports this natively:

  ```
  Types: deb
  URIs: https://github.com/FerraStack/ferrux-packages/releases/download/repo-aarch64/
  Suites: /
  Signed-By: /data/data/dev.ferrux/files/usr/etc/apt/trusted.gpg.d/ferrux-packages.gpg
  ```

  This file ships in the `apt` package as `etc/apt/sources.list.d/ferrux.sources`.
- Only the newest version of each package is kept. Updating a release is not
  atomic; uploads go debs → `Packages` → `InRelease`, so a client can at worst
  see a hash mismatch for a few seconds and must retry `pkg update`.
- If we outgrow Releases (or want a domain such as `packages.ferrux.dev`), the
  same files move to object storage. That needs an account and a domain: a
  founder decision, not needed for M3.

### Signing key

A dedicated RSA 4096 key, `Ferrux Packages (github.com/FerraStack/ferrux-packages)`,
fingerprint `3D33 FD41 BDB1 1FA5 5B5D  A331 AB13 AF01 501B 3FB0`, expires
2031-11. The public key is committed in ferrux-packages
(`ferrux/ferrux-packages.asc`, `packages/termux-keyring/ferrux-packages.gpg`)
and installed by `termux-keyring`. The private key exists **only** as the
Actions secret `FERRUX_APT_SIGNING_KEY` of ferrux-packages; it was generated in
a throwaway GnuPG home that was deleted afterwards. Rotation: add the new key
to `termux-keyring`, publish, wait for users to upgrade, then switch the
secret.

### Bootstrap: downloaded on first run, pinned by SHA-256

Bundling the bootstrap (Termux's approach) costs ~25 MB per ABI in the APK and
couples every bootstrap update to an app release anyway. Downloading keeps the
APK small and lets a fresh install pick up the pinned archive from GitHub.

- The pin (`BOOTSTRAP_RELEASE` + per-arch SHA-256) lives in
  `crates/ferrux-env/src/bootstrap.rs`; the app refuses any other archive. A new
  bootstrap therefore needs a one-line PR here, which is intended.
- The download only starts after the user taps **Download and install**
  (RESEARCH.md §2.2: no downloading code without an explicit user action).
  **Use Android's shell for now** skips setup for this run; the session is
  `/system/bin/sh` and setup is offered again on the next start.
- Offline first run is not supported yet. If that matters (F-Droid, air-gapped
  devices), a `bundled` build flavour can ship the same zip as an APK asset and
  feed it to the same installer.

### Installation (`ferrux-env::bootstrap::install`)

1. SHA-256 of the downloaded zip must equal the pin.
2. Wipe and recreate `files/usr-staging`; extract every entry, rejecting any
   path that would escape it (zip-slip); keep the owner permission bits stored
   in the zip and force `0700` in `bin/`, `libexec/` and `lib/apt/{apt-helper,methods}`.
3. Recreate the symlinks from `SYMLINKS.txt` (`target←link`, U+2190), with the
   same escape check on the link path.
4. `syncfs`, then move any old prefix aside, `rename(usr-staging, usr)`, delete
   the old prefix. A crash leaves either no prefix or a complete one.

The download itself is Kotlin (`BootstrapInstaller`, Android's HTTP stack with
the system proxy and CA settings); the archive is deleted after every attempt.

### Exec and environment

- Programs in the app data directory cannot be `execve()`d at targetSdk ≥ 29
  (RESEARCH.md §2.1). The session shell starts as
  `/system/bin/linker64 $PREFIX/bin/bash -l` (`ExecMode::Linker`, chosen for
  any program under `files/`), and `LD_PRELOAD=$PREFIX/lib/libtermux-exec.so`
  makes every later `exec` in the session do the same.
- Login shell: `$PREFIX/bin/bash -l` (falls back to `$PREFIX/bin/sh -l`, then
  `/system/bin/sh` when there is no bootstrap). `-l` is passed as an argument
  because the linker replaces `argv[0]`.
- Environment: `HOME`, `PREFIX`, `TMPDIR=$PREFIX/tmp`, `PATH=$PREFIX/bin:/system/bin:/system/xbin`,
  `LANG=en_US.UTF-8`, `TERM=xterm-256color`, `COLORTERM=truecolor`, `SHELL`,
  `LD_PRELOAD`, `TERMUX_EXEC__PROC_SELF_EXE` (the program the linker runs),
  Android's own variables (`ANDROID_ROOT`, `BOOTCLASSPATH`, …) and `FERRUX_*`.
- Termux compatibility: we export `TERMUX__PREFIX`, `TERMUX__ROOTFS`,
  `TERMUX__HOME` and `TERMUX_APP__PACKAGE_NAME` because packages built from
  termux-packages read them. We do **not** export `TERMUX_VERSION`: tools use
  it to detect the Termux app and its APIs (`termux-am`, Termux:API), which
  Ferrux does not provide.

## Building and publishing (ferrux-packages CI)

Nothing is built on developer machines. `.github/workflows/ferrux-packages.yml`
runs on pushes that touch recipes or build scripts:

1. Three chained **build segments**, each a matrix over aarch64 and x86_64.
   A segment restores the published debs (`ferrux/fetch-repo.sh`), marks them
   as built, builds the missing packages of `ferrux/packages.txt` and their
   dependencies one `build-package.sh` call at a time (`ferrux/build.sh`), and
   publishes (`ferrux/publish-repo.sh`). It stops starting new packages after
   5 h 15 min, so a cold build gets ~16 h across segments and later runs only
   rebuild what changed. A cold build of the current set takes about 1.5 h.
   Every package is built from an **empty prefix** with only its declared
   dependencies extracted, as in upstream's fresh-container CI: otherwise
   configure scripts pick up leftovers of earlier builds (the first repo had a
   `dpkg-deb` linked to `libzstd` without depending on `zstd`).
   `publish-repo.sh` refuses debs whose control file is not valid deb822.
2. **Bootstrap**: `scripts/generate-bootstraps.sh --flat-repository …`
   assembles `bootstrap-<arch>.zip` from the *published* repository, so the
   bootstrap and `pkg` always agree.
3. **Publish bootstrap**: if the bootstrap's package versions changed, a new
   `bootstrap-YYYY.MM.DD-rN` release with both zips, `SHA256SUMS` and
   `manifest.txt`. Then bump the pin here.

The initial set: the bootstrap (apt, bash, coreutils, curl, dash, findutils,
gawk, grep, procps, psmisc, sed, tar, termux-exec, termux-tools, util-linux,
nano, …) plus ca-certificates, openssl, less, git, python, vim and openssh,
128 source packages per architecture with dependencies. Add packages to
`ferrux/packages.txt`.

## Testing

The emulator job (`scripts/ci-screenshots.sh`) starts from a fresh install on
API 35 and API 30: it taps **Download and install**, waits for the bootstrap,
and asserts in the terminal grid that `$PREFIX` is
`/data/data/dev.ferrux/files/usr`, that the session shell is
`$PREFIX/bin/bash`, and `bash --version`. On API 35 it then runs
`pkg update && pkg install -y python git` and checks that
`python -c 'print(6*7)'` prints `42` and `git --version` runs.

## Known gaps

- Packages' `postinst` scripts in the bootstrap are not run (the same as
  Termux's Play build); none in the current bootstrap is required.
- GPL source mirroring: sources come from the URLs in each `build.sh`; we
  should mirror the exact tarballs next to the repository before a public
  release (RESEARCH.md §3.3).
- `motd`, `termux-info` and other termux-tools texts still say Termux.
- Multi-user and work profiles are unsupported (the prefix is fixed to user 0).
