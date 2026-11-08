# Ferrux API v1

**Status:** design, milestone M5. Nothing here is implemented yet; the
implementation lands in follow-up PRs and this document is updated with them.
**Audience:** Ferrux contributors, and FerraStack apps (first of all
[Ferride](https://github.com/FerraStack/ferride)) that run programs in Ferrux.
**Background:** [RESEARCH.md §8](RESEARCH.md#8-ferride-integration-embedding--ipc)
and Ferride's `docs/RESEARCH.md` §5 and §10 ("Ferrux integration contract").

## 1. Summary

Ferrux exports one bound service, **`FerruxApiService`**, with an AIDL
interface **`IFerruxApi`**. A client app binds it and can:

- read Ferrux's version, paths and bootstrap state (`getInfo`);
- **spawn** a program inside Ferrux's sandbox, either with **pipes**
  (stdin/stdout/stderr, for language servers and build tools) or with a
  **PTY** (for terminals), and get the file descriptors back as
  `ParcelFileDescriptor`s;
- resize, signal, kill and close what it spawned, and get an **exit callback**;
- **attach** to a terminal session that is also shown in the Ferrux app;
- **stat, list, open, create, rename and delete files** under `$HOME`;
- hold a **keep-alive** token so Ferrux stays in the foreground while the
  client works in the background.

Binder carries control calls only. Bytes flow through the file descriptors, so
they never touch Binder's 1 MB transaction buffer.

The service is protected by a **signature-level custom permission** that is
also granted to apps signed with a known FerraStack key
(`signature|knownSigner`). Only FerraStack apps can bind it.

```
┌──────────── client app (e.g. Ferride) ─────────────┐        ┌──────────────── Ferrux app ─────────────────┐
│ ferrux-client (Kotlin)                             │ Binder │ FerruxApiService  (IFerruxApi.Stub)          │
│   FerruxConnection ── spawn/resize/signal/files ───┼────────┼─▶ permission + caller checks                 │
│   FerruxProcess  ◀── onExit (oneway callback) ─────┼────────┼── per-client registry, linkToDeath           │
│                                                    │        │      │ JNI                                   │
│ Rust (ferride-host) reads/writes the raw fds       │  fds   │ libferrux.so: ferrux-pty (fork/exec, pipes,  │
│   PTY master │ socket │ stdin/stdout/stderr pipes ◀┼════════┼═ pty), ferrux-session (shared sessions),     │
│                                                    │        │ ferrux-env (PATH, env, exec mode, $HOME)     │
└────────────────────────────────────────────────────┘        └──────────────────────────────────────────────┘
```

## 2. Names and discovery

| What | Value (official build) | Notes |
|---|---|---|
| Ferrux package | `dev.ferrux` | `ferrux.applicationId` in `android/gradle.properties` |
| Bind action | `dev.ferrux.api.action.BIND` | clients bind with `Intent(action).setPackage(ferruxPackage)` |
| Service | `dev.ferrux.app.api.FerruxApiService` | exported, `android:permission` set |
| Permission | `dev.ferrux.permission.API` | declared as `${applicationId}.permission.API`, so forks with another id don't collide |
| Version meta-data | `dev.ferrux.api.VERSION` on the `<service>` | integer; lets clients check the API version **without binding** |
| AIDL package | `dev.ferrux.api` | shipped in the `ferrux-client` artifact (§9) |

A client needs, in its manifest (the `ferrux-client` library merges these in):

```xml
<uses-permission android:name="dev.ferrux.permission.API" />
<queries>
    <package android:name="dev.ferrux" />
</queries>
```

`<queries>` is required for package visibility (Android 11+); without it the
client can't see that Ferrux is installed.

## 3. Security

### 3.1 Threat model

**What a granted client can do.** `spawn` runs arbitrary programs as Ferrux's
Linux user. A granted client therefore has **full access to everything Ferrux
can access**: `$HOME`, `$PREFIX`, Ferrux's private files, its network access
and anything the user stored there. There is no finer-grained capability in
v1, and the file API's path confinement (§6.5) is not a security boundary
against a client that can also spawn `sh`. Granting the API is equivalent to
"this app may act as Ferrux".

That is why only FerraStack-signed apps get it.

**Assets.** The user's code, credentials and keys in `$HOME` (SSH keys, git
credentials, tokens), the installed packages, and the ability to run code.

**Adversaries considered.**

| Adversary | Mitigation |
|---|---|
| Any other app on the device | Can't bind: the service requires `dev.ferrux.permission.API`, which is `signature|knownSigner`. The system denies `bindService` with a `SecurityException`. |
| An app that obtains the `IFerruxApi` binder object from a granted app (a confused or compromised client passing it on) | Every method re-checks `checkCallingPermission(API)` on the **calling** UID (Binder.getCallingUid can't be forged), so a leaked binder is useless to an ungranted app. |
| A granted client crashing or confusing Ferrux itself, e.g. by passing Ferrux's own `IFerruxApi` binder back as its `IProcessCallback` or keep-alive token | Callback and token binders that are local to Ferrux's process are rejected (`SecurityException`). Exceptions while delivering `onExit` are caught per delivery, so a client can't take down Ferrux and everyone's sessions. |
| A granted client misusing another client's processes | Process handles are owned by the client UID that created them; other UIDs get `ERR_NO_SUCH_HANDLE`. Shared Ferrux sessions (§6.4) are visible to every granted client by design. |
| A repackaged FerraStack app (same package name, different key) | Its signer is neither Ferrux's nor in `knownCerts`, so it isn't granted. |
| Processes running inside Ferrux | Out of scope: they already run as Ferrux's UID. |
| Malicious FerraStack release, compromised signing key | Out of scope; key handling is a release-process matter (§3.3). |

**What the API does not protect against.** A granted client is trusted. A bug
in Ferride that lets a web page or an extension run commands through it would
run them in Ferrux; Ferride must treat spawn like `exec` in its own process.

### 3.2 The permission

```xml
<permission
    android:name="${applicationId}.permission.API"
    android:label="@string/api_permission_label"
    android:description="@string/api_permission_description"
    android:protectionLevel="signature|knownSigner"
    android:knownCerts="@array/ferrux_api_known_signers" />

<service
    android:name=".api.FerruxApiService"
    android:exported="true"
    android:permission="${applicationId}.permission.API">
    <intent-filter>
        <action android:name="dev.ferrux.api.action.BIND" />
    </intent-filter>
    <meta-data android:name="dev.ferrux.api.VERSION" android:value="1" />
</service>
```

- **`signature`**: any app signed with the same key as this Ferrux build is
  granted automatically. This covers "all FerraStack apps signed with one
  key", and CI, where Ferrux and the test client are built on one runner with
  one debug key.
- **`knownSigner` + `knownCerts`** (Android 12, API 31+): apps signed with any
  key whose SHA-256 certificate digest is listed are granted too. This is what
  lets a Ferride signed with a *different* FerraStack key use Ferrux (for
  example Ferrux from F-Droid with F-Droid's key and Ferride from GitHub
  releases), without `sharedUserId` and without sharing one key across stores.
  The system also accepts any key in the requesting app's signing lineage, so
  key rotation keeps working.
- **API 30** (Ferrux's `minSdk`) doesn't know `knownSigner`, so only the
  `signature` part applies there: same key or nothing.
- The service declares `android:permission`, so the system checks the
  permission at bind time; each call checks it again (§3.1).
- **No dangerous-level fallback in v1.** Ferride's research sketches an
  opt-in, user-approved permission for third-party apps (like Termux's
  `allow-external-apps`). It's deferred: v1 is FerraStack-only.

**Install order.** A permission is defined by the app that declares it. If the
client is installed **before** Ferrux, the permission doesn't exist at that
moment. Current Android versions re-evaluate signature grants for installed
apps when the defining package arrives, and the CI test (§10) installs the
client first on purpose to keep that verified on API 30 and 35. If a device
ever gets this wrong, reinstalling or updating the client fixes it, and the
client library reports `NotPermitted` (§8) rather than failing obscurely.
Clients **must not** declare the permission themselves: a second definition by
a differently signed app makes the second install fail
(`INSTALL_FAILED_DUPLICATE_PERMISSION`).

### 3.3 Signing keys and `knownCerts`

The digest list is the **SHA-256 of the DER-encoded signing certificate**,
written as 64 lowercase hex characters without colons. Digests are public;
the private keys never enter this repository or CI logs.

It is assembled at build time into a generated resource
`@array/ferrux_api_known_signers` from:

1. `android/api-signers.properties` (committed): one `name=digest` line per
   FerraStack release key, e.g. `ferride-github=<digest>`. **Empty until the
   founder decides the release keys** (see §12).
2. The Gradle property `ferrux.api.knownSigners` (comma-separated digests),
   for local builds and CI.

How to get a digest:

```sh
# From a keystore
keytool -list -v -keystore release.jks -alias ferride | grep 'SHA256:' \
  | sed 's/.*SHA256: //; s/://g' | tr 'A-F' 'a-f'
# From a signed APK
apksigner verify --print-certs app-release.apk | grep 'SHA-256 digest'
```

**CI** never needs a secret: both APKs are built in one job and signed with
that runner's Android debug key, so the `signature` part grants access. To
exercise the `knownSigner` path too, the CI job generates a throwaway
keystore, passes its digest in `-Pferrux.api.knownSigners`, re-signs a copy of
the test client with it and expects access on API 35 (and a denial on API 30);
a third throwaway key that isn't listed must always be denied (§10).

**Local development:** Android Studio signs every app with
`~/.android/debug.keystore`, so Ferrux and Ferride built on the same machine
already share a key.

## 4. Versioning

- `IFerruxApi.API_VERSION` is the version the service implements (`1`).
  Clients call `getApiVersion()` first, or read the `dev.ferrux.api.VERSION`
  meta-data before binding.
- **Methods have explicit transaction codes** (`= 1`, `= 2`, …). A code is
  never reused or renumbered; new methods get new codes and bump the version.
  An old client against a new Ferrux works unchanged. A new client against an
  old Ferrux must check the version before calling a newer method; calling an
  unknown code fails with an exception, and `ferrux-client` turns that into
  `FerruxException.Unsupported`.
- **Parcelables are structured AIDL parcelables.** Fields are only ever
  appended. The generated code writes a size header, so a reader skips fields
  it doesn't know and leaves missing ones at their defaults.
- **Constants and error codes** are only added, never changed.
- Removing something requires a major version: a new interface
  (`IFerruxApi2`) bound with a new action, with the old one kept for at least
  one release.

## 5. Interface (AIDL)

The files live in `android/api/src/main/aidl/dev/ferrux/api/` (§9).

```aidl
// IFerruxApi.aidl
package dev.ferrux.api;

import dev.ferrux.api.AttachResult;
import dev.ferrux.api.FerruxInfo;
import dev.ferrux.api.FileStat;
import dev.ferrux.api.IProcessCallback;
import dev.ferrux.api.SessionInfo;
import dev.ferrux.api.SpawnRequest;
import dev.ferrux.api.SpawnResult;

interface IFerruxApi {
    const int API_VERSION = 1;

    // Error codes, thrown as IllegalStateException("E<code>: message") (§7).
    const int ERR_INVALID_ARGUMENT = 1;
    const int ERR_NOT_FOUND = 2;
    const int ERR_NO_SUCH_HANDLE = 3;
    const int ERR_OUTSIDE_HOME = 4;
    const int ERR_BOOTSTRAP_REQUIRED = 5;
    const int ERR_SPAWN_FAILED = 6;
    const int ERR_IO = 7;
    const int ERR_LIMIT = 8;
    const int ERR_KEEPALIVE_DENIED = 9;
    const int ERR_UNSUPPORTED = 10;

    int getApiVersion() = 1;
    FerruxInfo getInfo() = 2;

    // Processes
    SpawnResult spawn(in SpawnRequest request, IProcessCallback callback) = 3;
    void resize(long handle, int rows, int cols) = 4;
    void signal(long handle, int signo) = 5;
    void kill(long handle) = 6;
    void close(long handle) = 7;

    // Sessions shared with the Ferrux app
    SessionInfo[] listSessions() = 8;
    AttachResult attachSession(long sessionId, int rows, int cols, IProcessCallback callback) = 9;

    // Files under $HOME
    FileStat stat(String path) = 10;
    FileStat[] list(String path, int offset, int limit) = 11;
    ParcelFileDescriptor openFile(String path, String mode) = 12;
    void mkdirs(String path) = 13;
    void rename(String from, String to) = 14;
    void delete(String path, boolean recursive) = 15;

    // Lifecycle
    void acquireKeepAlive(IBinder token, String reason) = 16;
    void releaseKeepAlive(IBinder token) = 17;

    // Sets permission bits (mode & 07777) under $HOME (§6.5).
    void chmod(String path, int mode) = 18;
}
```

```aidl
// IProcessCallback.aidl: implemented by the client. Also the lifetime anchor
// of everything spawned with it (§6.6).
package dev.ferrux.api;

oneway interface IProcessCallback {
    // exitCode is 0..255 for a normal exit, -1 if killed by a signal.
    // signal is the terminating signal, or 0.
    void onExit(long handle, int exitCode, int signal) = 1;
}
```

```aidl
// SpawnRequest.aidl
package dev.ferrux.api;

parcelable SpawnRequest {
    const int MODE_PIPES = 0;
    const int MODE_PTY = 1;

    // argv[0] is the program: an absolute path, or a name looked up in the
    // resulting PATH ($PREFIX/bin:/system/bin:/system/xbin by default).
    String[] argv;
    // KEY=VALUE entries, applied over Ferrux's session environment
    // (HOME, PREFIX, TMPDIR, PATH, TERM, LANG, LD_PRELOAD, ...).
    String[] env;
    // Start from an empty environment instead of Ferrux's.
    boolean clearEnv = false;
    // Absolute or $HOME-relative; null or empty = $HOME.
    @nullable String cwd;
    int mode = MODE_PIPES;
    // Pipes mode: send stderr into the stdout pipe (no separate stderr fd).
    boolean mergeStderr = false;
    // PTY mode: initial size.
    int rows = 24;
    int cols = 80;
    // PTY mode: create a Ferrux session (shown in the Ferrux app, survives the
    // client) and attach to it, instead of handing out the PTY master.
    boolean shared = false;
    // Shared sessions: title shown in Ferrux's drawer.
    @nullable String title;
}
```

```aidl
// SpawnResult.aidl
package dev.ferrux.api;

parcelable SpawnResult {
    long handle;          // for resize/signal/kill/close; > 0
    int pid;              // in Ferrux's pid namespace (diagnostics only)
    long sessionId;       // shared sessions only, else 0
    // MODE_PIPES: stdin (write end), stdout and stderr (read ends);
    //             stderr is null when mergeStderr.
    @nullable ParcelFileDescriptor stdin;
    @nullable ParcelFileDescriptor stdout;
    @nullable ParcelFileDescriptor stderr;
    // MODE_PTY: the PTY master, or for shared sessions a connected
    // SOCK_STREAM socket carrying the same byte stream (§6.3, §6.4).
    @nullable ParcelFileDescriptor pty;
    boolean ptyIsSocket;
}
```

```aidl
// FerruxInfo.aidl
package dev.ferrux.api;

parcelable FerruxInfo {
    const int BOOTSTRAP_NOT_INSTALLED = 0;
    const int BOOTSTRAP_INSTALLING = 1;
    const int BOOTSTRAP_READY = 2;
    const int BOOTSTRAP_FAILED = 3;

    int apiVersion;
    String versionName;      // Ferrux app version
    long versionCode;
    String packageName;
    String prefix;           // $PREFIX
    String home;             // $HOME
    String tmpDir;           // $TMPDIR
    int bootstrapState;
    String[] capabilities;   // "pipes", "pty", "pty-master-fd", "attach",
                             // "files", "keepalive"; future: "packages", "services"
    int androidApi;          // Build.VERSION.SDK_INT
    String abi;              // e.g. "arm64-v8a"
    int liveProcesses;       // processes spawned through the API, all clients
    int clientProcessLimit;  // max live processes per client app (UID) (§6.7)
}
```

```aidl
// SessionInfo.aidl
package dev.ferrux.api;

parcelable SessionInfo {
    long sessionId;
    @nullable String title;
    int pid;
    int rows;
    int cols;
    boolean exited;
    long createdAtMillis;    // System.currentTimeMillis()
}

// AttachResult.aidl
parcelable AttachResult {
    long handle;
    ParcelFileDescriptor socket;   // SOCK_STREAM, raw terminal bytes both ways
}

// FileStat.aidl
parcelable FileStat {
    const int TYPE_FILE = 0;
    const int TYPE_DIR = 1;
    const int TYPE_SYMLINK = 2;
    const int TYPE_OTHER = 3;

    String path;          // absolute
    String name;
    int type;
    long size;
    long modifiedMillis;
    int mode;             // permission bits (st_mode & 07777)
    @nullable String symlinkTarget;
}
```

## 6. Semantics

### 6.1 `getInfo`

Cheap, never fails for a granted caller. `bootstrapState` comes from
`ferrux-env` (`Layout::is_bootstrapped` today, the installer's state once the
bootstrap lands in M3). A client should show "Open Ferrux to finish setup"
(launch intent for `dev.ferrux`) while it isn't `BOOTSTRAP_READY` and the
program it needs lives in `$PREFIX`. Programs in `/system/bin` work in every
state.

### 6.2 `spawn`, pipes mode

For language servers, build tools and git: anything that speaks over stdio.

1. **Environment**: Ferrux's session environment (`ferrux_env::build_env`),
   then the request's `env` entries on top (same key = replaced); with
   `clearEnv`, only the request's entries.
2. **Program lookup**: like `execvp`, an `argv[0]` without `/` is looked up in
   the resulting `PATH` (one with `/` is a path, relative to `cwd`), and
   `argv[0]` is passed to the program unchanged. Not found → `ERR_NOT_FOUND`,
   or `ERR_BOOTSTRAP_REQUIRED` when the bootstrap isn't installed (the name
   might come from a package).
3. **Exec mode**: decided by `ferrux-env` from the program's location (files
   under the app data dir run through `/system/bin/linker64` on targetSdk ≥ 29;
   see issue #5). The API never takes an exec mode from the client.
4. **cwd**: must resolve inside `$HOME` (same rules as §6.5), default `$HOME`.
5. The child runs as a new session and process-group leader (`setsid`), so
   `signal`/`kill` reach its whole job tree. All other fds are closed in the
   child, as in `ferrux-pty`.
6. The result holds three pipe ends (two when `mergeStderr`). The client owns
   them; Ferrux keeps no copy. Closing `stdin` gives the program EOF. When the
   program exits, its pipes close and the client reads EOF after the last
   byte.
7. Fork/exec errors (bad path, `chdir` failure, exec format error) come back
   synchronously as `ERR_SPAWN_FAILED` with the errno text, not as an exit
   code 127, thanks to `ferrux-pty`'s close-on-exec error pipe.

### 6.3 `spawn`, PTY mode

For terminals rendered by the client.

- **Not shared** (default): Ferrux opens a PTY, spawns the program on its slave
  side (controlling terminal, `setsid`; pass `-sh`-style `argv[0]` yourself
  for a login shell), and returns the **PTY master fd itself** in `pty`
  (`ptyIsSocket = false`). The client reads and writes it directly, with no
  copy through Ferrux, and can run its own `ferrux-term` on it
  (`PtyMaster::from_fd` + `SessionManager::attach`). Ferrux keeps a duplicate
  of the master only to serve `resize` and to hang up on `close`.
- **Shared** (`shared = true`): see §6.4. The result is a socket
  (`ptyIsSocket = true`) and a `sessionId`.
- `resize(handle, rows, cols)` sets the window size (`TIOCSWINSZ`, which
  sends `SIGWINCH`). A client that holds the master can also do the ioctl
  itself; calling `resize` keeps Ferrux's view consistent and works for
  sockets too.

**Can a PTY master cross apps?** The fd is a `/dev/ptmx` open file (SELinux
type `ptmx_device`). Android's policy allows `untrusted_app` to read, write
and `ioctl` `ptmx_device` (every terminal app opens it) and allows `fd use`
between app domains, which is what passing any fd over Binder relies on. That
is the analysis; the CI test (§10) makes it a fact on API 30 and 35
emulators, which run the same AOSP policy as devices. Some OEM policies could
still differ. The capability string `pty-master-fd` in `getInfo` says that
Ferrux hands out masters. If a device turns out to deny it, Ferrux drops that
capability and serves PTY spawns as shared sessions with a socket, which needs
no client change beyond honouring `ptyIsSocket`.

### 6.4 Shared sessions and `attachSession`

A **shared session** is an ordinary Ferrux terminal session: it lives in
Ferrux's `SessionManager`, appears in the Ferrux drawer, and keeps running
when the client goes away. Sessions the user opened in Ferrux are shared
sessions too. This is how "Open in Ferrux" and "show my Ferrux shell in
Ferride" work.

- `listSessions()` returns all of them.
- `attachSession(id, rows, cols, callback)` returns a **connected
  `SOCK_STREAM` socket**. Ferrux keeps reading the PTY master for its own
  terminal model and also copies every output byte to each attached socket;
  bytes the client writes to the socket go to the PTY input. Two readers on
  one master would steal output from each other, which is why attach never
  hands out the master.
- On attach, Ferrux first writes a **screen replay**: clear screen, the
  current visible screen with its colours and attributes, then the cursor
  position and modes (cursor visibility, bracketed paste, application keys).
  This comes from `ferrux-term`'s snapshot. Scrollback isn't replayed in v1.
- **Size:** a PTY has one size. `attachSession` and `resize` set it, last
  writer wins (like tmux's `latest` policy); the Ferrux UI re-applies its own
  size when the session becomes visible there again.
- **Backpressure:** each attachment has a bounded buffer (1 MB). If the
  client doesn't read and the buffer fills, Ferrux closes that socket instead
  of stalling the session; the client sees EOF and may re-attach (and gets a
  fresh replay).
- `close(handle)` on an attachment **detaches** it; the session keeps running.
  To end the session, send `signal(handle, SIGHUP)` or let the user close it
  in Ferrux.
- `onExit` fires on the attachment's callback when the session's program
  exits.

### 6.5 Files

Paths are absolute or relative to `$HOME`. Every path must resolve **inside
`$HOME`** after following symlinks; anything else is `ERR_OUTSIDE_HOME`.

- Resolution: canonicalise the parent directory (`realpath`) and check that it
  is `$HOME` or below it; the final component is handled with `O_NOFOLLOW`
  where it matters, so `stat`/`list`/`delete` act on a symlink itself and
  `openFile` refuses to follow a final symlink that points outside.
- This is a **correctness and least-surprise rule, not a security boundary**
  (§3.1): a granted client could run `cat` on any file anyway. It keeps
  editors from wandering into Ferrux's private state by accident, and lets a
  future files-only grant reuse the same code.
- `openFile(path, mode)`: `mode` as in `ParcelFileDescriptor.parseMode`
  (`"r"`, `"w"`, `"wt"`, `"wa"`, `"rw"`, `"rwt"`).
  - Reading and writing go through the fd, so file size isn't limited by
    Binder.
  - Only regular files can be opened. FIFOs, sockets and devices are refused,
    and the open itself never blocks.
  - New files get mode 0600.
- `chmod(path, mode)` sets permission bits; a final symlink is followed only
  inside `$HOME`.
- `ferrux-client`'s `writeAtomically(path, data)` writes a temporary file next
  to the real target, gives it the target's permission bits, then calls
  `rename`.
  - A script keeps its `+x`.
  - If `path` is a symlink, the file it points to is replaced and the link
    stays a link. Each step is still confined to `$HOME`.
- A symlink that points at `$HOME` is an ordinary entry: it can be stat'ed,
  renamed and deleted like any other link.
- `list(path, offset, limit)` pages through a directory (sorted by name;
  `limit` ≤ 1000) so a huge directory never exceeds the Binder buffer.
- `delete(path, recursive)` refuses to delete `$HOME` itself.
- Not in v1: file watching (clients run `inotifywait` through `spawn` if
  needed, or wait for v2), and a `DocumentsProvider` for apps outside
  FerraStack (separate milestone; RESEARCH.md §8).

### 6.6 Lifetime and cleanup

Everything a client spawns or attaches is tied to the `IProcessCallback`
binder passed with it (it is required, never null). Ferrux calls
`linkToDeath` on it.

| Event | What Ferrux does |
|---|---|
| `close(handle)` on a spawned process | `SIGHUP` to the process group (and the PTY's foreground group), `SIGKILL` 3 s later if still alive, closes its fds, forgets the handle. Same as closing a Ferrux tab. This also applies after the program itself exited: background children it left in its process group (`sleep 30 &`) are hung up too. |
| `close(handle)` on an attachment | Detaches; the shared session keeps running. |
| Client process dies or unbinds (callback binder dies) | `close` on every handle that callback owns. Shared sessions survive. |
| Program exits | Reaped by a per-pid waiter thread (never `waitpid(-1)`); `onExit` is sent; the handle stays valid for `close` until the client closes it or dies. |
| Ferrux's process dies (low memory, user force-stop, update) | Android kills the whole app process group, so all spawned programs die too. Clients see EOF on every fd and `onServiceDisconnected`/`binderDied`. Nothing survives a Ferrux restart in v1. |
| `kill(handle)` | `SIGKILL` to the process group. |
| `signal(handle, signo)` | That signal to the process group (to the PTY's foreground group for PTYs: `SIGINT` reaches the running command, not just the shell). |

Signals are sent only while the child is known to be unreaped (the same
reaped-lock scheme as `ferrux-session`), so a recycled pid is never signalled.

### 6.7 Keep-alive and process limits

**Keep-alive.** While a client is in the foreground and bound with
`BIND_AUTO_CREATE`, Ferrux's process inherits its priority and won't be
killed. When the client goes to the background, Ferrux needs its own
foreground service:

- `acquireKeepAlive(token, reason)` registers `token` (any binder the client
  owns; Ferrux links to its death) and moves `FerruxService` into the
  foreground (`specialUse`, the existing terminal notification, which then
  also says e.g. "Ferride: 3 processes").
- `releaseKeepAlive(token)`, or the token's process dying, releases it. Ferrux
  leaves the foreground when no keep-alive tokens, no live API processes and
  no Ferrux sessions remain.
- Ferrux also enters the foreground on its own whenever API processes are
  running, so `acquireKeepAlive` is really "stay up even while idle" (e.g. a
  workspace is open but no server is running yet).
- **Background start restrictions (Android 12+):** an app in the background
  may not start a foreground service. Call `acquireKeepAlive` (and the first
  `spawn`) **while the client is visible**; Ferrux then starts its FGS during
  that call. If the system refuses anyway, the call fails with
  `ERR_KEEPALIVE_DENIED` and the client should ask the user to open Ferrux once
  (an activity start is always allowed). CI asserts on API 35 that the FGS
  starts during the call.

**Phantom processes.** Android 12+ treats processes forked by apps as
"phantom processes". The system kills them when **more than 32 exist across
all apps** (`max_phantom_processes`), and kills phantom processes that use a
lot of CPU while their app is in the background. Ferrux's FGS doesn't exempt
them from the count. Every shell, language server and compiler spawned through
the API counts, together with the user's own Ferrux sessions. Guidance:

- Start language servers lazily and stop idle ones; reuse one server per
  workspace and language.
- Prefer one long-lived process over many short ones (`git` subprocess storms
  add up).
- Ferrux caps each client **app** (UID) at **16 live processes**
  (`clientProcessLimit` in `getInfo`; `ERR_LIMIT` beyond that), so one client
  can't consume the whole budget.
  - Opening more connections doesn't raise the cap.
  - A handle counts while its program or anything left in its process group
    runs.
  - Every process in a group still counts towards Android's 32.
- A killed phantom process looks like a normal `SIGKILL` exit (`onExit` with
  `signal = 9`). Clients should report "killed by Android (process limit)" and
  offer a restart, rather than looping.
- Users can lift the limit: Developer options → "Disable child process
  restrictions" (Android 14+), or over adb
  `settings put global settings_enable_monitor_phantom_procs false`
  (Android 12L+) or
  `device_config put activity_manager max_phantom_processes 2147483647`
  (Android 12). Ferrux's docs explain this; clients link to them.

### 6.8 Threading

Binder calls run on Ferrux's Binder thread pool. No call blocks for long:
spawn is a fork/exec, file calls are single syscalls (or a directory page),
and there is **no blocking `wait`**: exit is reported through the `oneway`
`onExit`. Clients may call from any thread but shouldn't call from the main
thread (spawn can take some milliseconds); `ferrux-client` does its calls on
`Dispatchers.IO`.

## 7. Errors

| Condition | Delivered as |
|---|---|
| Caller lacks the permission | `SecurityException` |
| Error codes in `IFerruxApi` (`ERR_*`) | `IllegalStateException` with the message `E<code>: <text>`, e.g. `E4: /etc/hosts: outside the home directory` |
| Missing or malformed arguments (null path, null callback) | `IllegalArgumentException` / `NullPointerException` |
| Method unknown to this Ferrux (newer client) | `RemoteException`/`UnsupportedOperationException`, mapped by `ferrux-client` |
| Ferrux died during a call | `DeadObjectException` |

Binder only carries a fixed set of exception types across processes.
`ServiceSpecificException`, the usual carrier of service error codes, is not
part of the app SDK, so neither side could use it. The `E<code>: ` prefix is
the contract instead: `dev.ferrux.api.ApiErrors` builds and parses it, and
`ferrux-client` maps all of these to a sealed `FerruxException`
(`FerruxException.Api(code, text)` for the `ERR_*` codes).

## 8. When Ferrux isn't installed (or can't be used)

`FerruxClient.status(context)` answers without binding:

| Status | Detected by | What the client should show |
|---|---|---|
| `NotInstalled` | `PackageManager.getPackageInfo("dev.ferrux")` fails | "Ferride runs programs in Ferrux" + install links (F-Droid / GitHub / Play, whichever the client's flavour uses). The editor itself keeps working; terminals and language servers are disabled. |
| `Outdated(installed, required)` | service meta-data `dev.ferrux.api.VERSION` < required | "Update Ferrux". |
| `NotPermitted` | `checkPermission(API, myPackage)` denied | "This Ferride and Ferrux are signed by different publishers" (e.g. a store build with a self-built one), with a pointer to installing both from the same source. |
| `Disabled` | Ferrux app disabled by the user | "Enable Ferrux in Settings". |
| `Ready` | all of the above pass | — |

After binding, `bootstrapState` (§6.1) adds "Open Ferrux to finish setup".
Ferride's `HostStatus` maps one-to-one: `NotInstalled` → `NotInstalled`,
`NotPermitted` → `NotPaired`, `Outdated`/`Disabled`/bootstrap missing →
`Unavailable { reason }`, `Ready` → `Ready`.

## 9. Client library: `ferrux-client`

A Gradle module **`android/api`** holds the AIDL files and a small Kotlin
library. The Ferrux app uses the same module for the service stub, so there is
one copy of the interface.

```kotlin
object FerruxClient {
    fun status(context: Context, minApi: Int = 1): FerruxStatus
    /** Binds and returns once connected; throws FerruxException. */
    suspend fun connect(context: Context, minApi: Int = 1): FerruxConnection
}

class FerruxConnection : Closeable {             // close() = unbind
    val info: FerruxInfo
    val state: StateFlow<ConnectionState>          // Connected / Disconnected
    suspend fun spawn(spec: ProcessSpec): FerruxProcess
    suspend fun exec(argv: List<String>, cwd: String? = null): ExecResult  // collect output + exit
    suspend fun sessions(): List<SessionInfo>
    suspend fun attach(sessionId: Long, rows: Int, cols: Int): FerruxProcess
    val files: FerruxFiles                          // stat/list/read/write/writeAtomically/…
    suspend fun keepAlive(reason: String): Closeable
}

class FerruxProcess : Closeable {                  // close() = IFerruxApi.close
    val handle: Long
    val pid: Int
    val stdin: OutputStream?; val stdout: InputStream?; val stderr: InputStream?
    val pty: ParcelFileDescriptor?; val ptyIsSocket: Boolean
    val exit: Deferred<ExitStatus>                 // completes from onExit
    fun output(): Flow<ByteArray>                  // stdout (or pty) chunks, cold
    suspend fun resize(rows: Int, cols: Int)
    suspend fun signal(signo: Int)
    suspend fun kill()
    /** For Rust (ferride-host): hand over raw fds; the caller must close them. */
    fun detachFds(): RawFds
}
```

**Distribution: GitHub Packages (Maven).** `ferrux-client` is published next
to `terminal-view`, group `dev.ferrux`, by `.github/workflows/publish.yml`:

- **Build:** the workflow builds the native libraries with cargo-ndk and runs
  `:terminal-view:publish :api:publish`.
- **Version:** one Gradle property, `ferrux.publishVersion` in
  `android/gradle.properties`.
  - Every push to `main` publishes it as a `-SNAPSHOT`.
  - A tag `v<version>` publishes `<version>`.
- **Consumers** add the repository and the coordinates:

```kotlin
// settings.gradle.kts
maven {
    url = uri("https://maven.pkg.github.com/FerraStack/ferrux")
    credentials {
        username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
        password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
    }
}
// build.gradle.kts
implementation("dev.ferrux:ferrux-client:0.1.0-SNAPSHOT")
```

GitHub Packages requires authentication even for public packages:

- **Ferride's CI:** uses its `GITHUB_TOKEN` with `permissions: packages: read`.
  Each package must also grant the ferride repository read access (Package
  settings → *Manage Actions access*, an org-admin setting; see §12).
- **Local developers:** use a personal access token (classic) with
  `read:packages`, as `gpr.user`/`gpr.key` in `~/.gradle/gradle.properties`.
- **Without a token:** `./gradlew :api:publishToMavenLocal` from a Ferrux
  checkout plus `mavenLocal()`, or an `includeBuild`, gives the same
  coordinates.

CI runs `publishToMavenLocal` on every PR and checks the AAR. Maven Central can
come later; it needs a founder-owned Sonatype account and a signing key.
[REUSE.md](REUSE.md#publishing) covers both artifacts.

The library has no native code and no dependency on `terminal-view`; it pulls
in only `kotlinx-coroutines-android`. Rendering a terminal from the PTY fd is
the client's job:

- **Ferride:** runs `ferrux-term` from Rust.
- **Kotlin clients:** can embed `dev.ferrux:terminal-view` and hand the fd to
  `TerminalNative.attachPty` (docs/REUSE.md).

## 10. Testing

**Rust (Linux, `cargo test`):** pipes spawn (stdin → stdout round trip,
separate and merged stderr, exit codes and signals), PATH lookup, env merge,
path confinement (`..`, absolute paths outside, symlink escapes), attach
fan-out with screen replay and the backpressure cut-off, and cleanup when an
owner goes away.

**Emulator (CI, API 35 and API 30):** a test client APK, module
`:apiclient-sample` (package `dev.ferrux.apiclient.sample`), built in the
same job as Ferrux and signed with the same debug key. It is installed
**before** Ferrux (install-order check, §3.2). Driven through instrumentation
and `adb`, it must:

1. bind and read `getInfo`;
2. spawn `/system/bin/sh -c 'echo api-ok'` in pipes mode → stdout is
   `api-ok\n`, exit code 0;
3. spawn an interactive PTY shell, write `echo hi`, read `hi` back from the
   master fd;
4. resize and check `stty size` in the PTY;
5. kill and receive `onExit` with `signal = 9`;
6. attach to a Ferrux session and see its screen replay;
7. confine files: write and read `$HOME/api-test.txt`, get
   `ERR_OUTSIDE_HOME` for `/data/data/dev.ferrux/shared_prefs`;
8. acquire a keep-alive and see Ferrux's service in the foreground
   (`dumpsys activity services`); release it after the client dies;
9. **denial:** the same client re-signed with a throwaway key that isn't
   listed gets `SecurityException` on bind; re-signed with a throwaway key
   that *is* listed via `-Pferrux.api.knownSigners`, it binds on API 35 and is
   denied on API 30;
10. screenshot the sample client rendering a live shell from the passed PTY
    fd with the shared `terminal-view`.

## 11. Relationship to Ferride's contract sketch

Ferride's `docs/RESEARCH.md` §10.2 sketched `IFerruxHost`. v1 is a subset
with these deliberate differences:

| Sketch | v1 | Why |
|---|---|---|
| `IFerruxHost`, action `org.ferrastack.ferrux.HOST` | `IFerruxApi`, action `dev.ferrux.api.action.BIND` | Ferrux's real package is `dev.ferrux`. |
| PTY spawn returns a socketpair; Ferrux keeps the master | Master fd by default; socket for shared sessions and as the fallback (§6.3) | Zero-copy for the common case; the socket path exists anyway for attach. |
| `ensurePackages`, `startService`/`stopService`, `connect(endpoint)` | Not in v1 | Need the M3 package manager and a supervisor. Planned for v2 with new transaction codes. |
| `documentUriFor`, `openTerminalIntent` | Not in v1 | Arrive with the `DocumentsProvider` and the "Open in Ferrux" UI. |
| Dangerous-level permission for third parties | Not in v1 | FerraStack-only first (§3.2). |
| `acquireKeepAlive` returns a token | Client passes its own token binder | Ferrux needs a client-owned binder to detect the client's death. |
| — | File API (`stat`/`list`/`openFile`/…) | Ferride's M2 needs to list and edit files in Ferrux's `$HOME`. |

## 12. Decisions that need the founder

1. **Release signing certificates** for Ferride (and later other FerraStack
   apps) per channel: GitHub releases, F-Droid (F-Droid signs with its own key
   unless the app uses reproducible builds with the developer's signature),
   and Play (Play App Signing key). Their SHA-256 digests go into
   `android/api-signers.properties`. Until then only same-key builds can use
   the API.
2. **A shared FerraStack CI debug key** (an org-level secret) if Ferride's CI
   should test against a Ferrux debug APK built in another repository. Not
   needed for Ferrux's own CI (§3.3).
3. **Third-party access** (opt-in dangerous permission with an approval UI):
   whether and when.
4. **Package access for Ferride's CI:** on GitHub, grant the `ferride`
   repository read access to the `dev.ferrux.ferrux-client` and
   `dev.ferrux.terminal-view` packages (*Manage Actions access*). This can only
   be done once the packages exist, i.e. after the first publish from `main`.
5. **Maven Central** (optional, later): a Sonatype account and a signing key
   owned by the founder.

## 13. Implementation plan

1. This document.
2. `crates/ferrux-host` (platform-neutral: pipes spawn in `ferrux-pty`,
   process registry with owners and cleanup, path confinement), JNI in
   `ferrux-android`, `FerruxApiService` in the app, module `android/api` with
   AIDL + `ferrux-client`, published to GitHub Packages. Shared sessions and
   `listSessions`/`attachSession` answer `ERR_UNSUPPORTED` (and `getInfo`
   doesn't list `attach`) until step 3b.
3. `:apiclient-sample` and the emulator test of §10, with screenshots.
3b. Shared sessions: attach fan-out with screen replay in `ferrux-session`.
4. Spawning `$PREFIX` programs through the exec mode and bootstrap state from
   `ferrux-env` once those land (issue #5 and the M3 bootstrap).
5. An issue in FerraStack/ferride describing `FerruxHost` on top of
   `ferrux-client`.
