// SPDX-License-Identifier: Apache-2.0
//! `ferrux-host`: the platform-neutral core of the Ferrux API v1
//! (`docs/API.md`), which lets other FerraStack apps run programs and
//! manage files inside Ferrux.
//!
//! * [`Host`] spawns programs for API clients, either with pipes or with a
//!   pty, and hands the parent's descriptors back to the caller (the Android
//!   service wraps them in `ParcelFileDescriptor`s). Every process belongs
//!   to an [`OwnerId`] (one per client callback binder) so everything a
//!   client started can be cleaned up when it goes away.
//! * Each child is reaped by its own waiter thread (never `waitpid(-1)`);
//!   exits are queued for [`Host::wait_exits`]. Signals are only sent while
//!   the child is known to be unreaped, so a recycled pid is never hit.
//! * [`files::Files`] implements the `$HOME`-confined file operations.
//!
//! No Android dependencies: the JNI layer in `ferrux-android` is a thin
//! adapter and everything here is tested on Linux.

pub mod files;

use std::collections::{HashMap, VecDeque};
use std::os::fd::OwnedFd;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::time::Duration;

use ferrux_env::Layout;
use ferrux_pty::{Child, ExecMode, ExitStatus, PtyMaster, SpawnConfig, SpawnError, WinSize};

pub use ferrux_pty;

/// Identifies a spawned process; never reused within a [`Host`]. Always > 0.
pub type Handle = u64;
/// Identifies a client connection (in practice one `IProcessCallback` binder).
pub type OwnerId = u64;
/// The Android UID of the client app; limits are per UID, not per connection.
pub type Uid = u32;

/// Error codes; the numbers are the `ERR_*` constants of `IFerruxApi`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum ErrorCode {
    InvalidArgument = 1,
    NotFound = 2,
    NoSuchHandle = 3,
    OutsideHome = 4,
    BootstrapRequired = 5,
    SpawnFailed = 6,
    Io = 7,
    Limit = 8,
    KeepAliveDenied = 9,
    Unsupported = 10,
}

/// An API error: a code from [`ErrorCode`] plus a human-readable message.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
#[error("{message}")]
pub struct HostError {
    pub code: ErrorCode,
    pub message: String,
}

impl HostError {
    pub fn new(code: ErrorCode, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }

    /// Maps an I/O error on `path` to the closest code.
    pub fn io(err: &std::io::Error, what: impl std::fmt::Display) -> Self {
        let code = match err.kind() {
            std::io::ErrorKind::NotFound => ErrorCode::NotFound,
            std::io::ErrorKind::InvalidInput => ErrorCode::InvalidArgument,
            _ => ErrorCode::Io,
        };
        Self::new(code, format!("{what}: {err}"))
    }
}

pub type Result<T, E = HostError> = std::result::Result<T, E>;

/// What to run; mirrors the AIDL `SpawnRequest`.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct SpawnRequest {
    /// `argv[0]` is the program: an absolute path or a name looked up in the
    /// resulting `PATH`. It is also passed to the program as `argv[0]`.
    pub argv: Vec<String>,
    /// `KEY=VALUE` entries applied over Ferrux's session environment.
    pub env: Vec<String>,
    /// Start from an empty environment instead of Ferrux's.
    pub clear_env: bool,
    /// Absolute or `$HOME`-relative; `None` or empty = `$HOME`.
    pub cwd: Option<String>,
    /// `Some(size)` = pty mode, `None` = pipes mode.
    pub pty: Option<WinSize>,
    /// Pipes mode: stderr goes to the stdout pipe.
    pub merge_stderr: bool,
}

/// The parent's descriptors of a spawned process.
#[derive(Debug)]
pub enum SpawnedIo {
    Pipes {
        stdin: OwnedFd,
        stdout: OwnedFd,
        stderr: Option<OwnedFd>,
    },
    /// The pty master. The host keeps its own duplicate for resize/hang-up.
    Pty { master: OwnedFd },
}

#[derive(Debug)]
pub struct Spawned {
    pub handle: Handle,
    pub pid: i32,
    pub io: SpawnedIo,
}

/// A process exit, as reported by [`Host::wait_exits`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ExitEvent {
    pub handle: Handle,
    pub owner: OwnerId,
    pub status: ExitStatus,
}

impl ExitEvent {
    /// `(exitCode, signal)` as in `IProcessCallback.onExit`: `(code, 0)` for a
    /// normal exit, `(-1, signo)` when killed by a signal.
    pub fn code_and_signal(&self) -> (i32, i32) {
        match self.status {
            ExitStatus::Exited(c) => (c, 0),
            ExitStatus::Signaled(s) => (-1, s),
        }
    }
}

/// How long closed processes get after `SIGHUP` before `SIGKILL`.
pub const CLOSE_GRACE: Duration = Duration::from_secs(3);
/// Default cap on live processes per client app (UID) (docs/API.md §6.7).
pub const DEFAULT_OWNER_LIMIT: usize = 16;

#[derive(Debug)]
struct Proc {
    owner: OwnerId,
    uid: Uid,
    pid: i32,
    /// Set (under the lock) when the child has been reaped.
    reaped: Mutex<bool>,
    /// Whether this is a pty process (even after `master` was dropped).
    is_pty: bool,
    /// Our duplicate of the pty master, for resize and the foreground job.
    /// Dropped once the program exited, so an exited handle the client never
    /// closes doesn't pin a pty (issue #39).
    master: Mutex<Option<PtyMaster>>,
}

impl Proc {
    fn reaped(&self) -> MutexGuard<'_, bool> {
        self.reaped.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Sends `signal` to each process group in `groups`.
    ///
    /// Also after the leader was reaped: background children (`sleep 30 &`)
    /// stay in its group, and a process group id can't be reused while any
    /// member is alive. Once the group is empty, `kill` fails with `ESRCH`.
    /// The lock orders this against reaping in the waiter thread.
    fn signal_groups(&self, groups: &[i32], signal: i32) {
        let _reaped = self.reaped();
        for &g in groups {
            // SAFETY: plain syscall wrapper; `g` > 0 is a process group of our
            // child's session.
            if g > 0 && unsafe { libc::kill(-g, signal) } != 0 {
                log::debug!("kill(-{g}, {signal}): {}", std::io::Error::last_os_error());
            }
        }
    }

    /// Whether the child or anything left in its process group is running.
    fn is_live(&self) -> bool {
        // SAFETY: plain syscall wrapper; signal 0 only checks existence.
        !*self.reaped() || unsafe { libc::kill(-self.pid, 0) } == 0
    }

    /// The child's own group, plus the pty's foreground group if different.
    fn groups(&self) -> Vec<i32> {
        let mut groups = vec![self.pid];
        if let Some(fg) = self.foreground_group()
            && fg != self.pid
        {
            groups.push(fg);
        }
        groups
    }

    fn master(&self) -> MutexGuard<'_, Option<PtyMaster>> {
        self.master.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn foreground_group(&self) -> Option<i32> {
        let master = self.master();
        rustix::termios::tcgetpgrp(master.as_ref()?)
            .ok()
            .map(|p| p.as_raw_nonzero().get())
    }

    /// Whether Ferrux still holds a pty master for this process.
    #[cfg(test)]
    fn holds_master(&self) -> bool {
        self.master().is_some()
    }
}

#[derive(Debug, Default)]
struct Inner {
    procs: Mutex<HashMap<Handle, Arc<Proc>>>,
    exits: Mutex<VecDeque<ExitEvent>>,
    exits_cv: Condvar,
}

impl Inner {
    fn procs(&self) -> MutexGuard<'_, HashMap<Handle, Arc<Proc>>> {
        self.procs.lock().unwrap_or_else(|e| e.into_inner())
    }
}

/// Processes spawned for API clients.
#[derive(Debug)]
pub struct Host {
    layout: Layout,
    host_env: Vec<(String, String)>,
    owner_limit: usize,
    next: AtomicU64,
    inner: Arc<Inner>,
    /// Serialises spawns so the per-UID limit check and the insert are atomic.
    spawn_lock: Mutex<()>,
}

impl Host {
    /// `host_env` is the app process's environment (only Android's
    /// pass-through variables are taken from it, see `ferrux_env::build_env`).
    pub fn new(layout: Layout, host_env: Vec<(String, String)>) -> Self {
        Self {
            layout,
            host_env,
            owner_limit: DEFAULT_OWNER_LIMIT,
            next: AtomicU64::new(1),
            inner: Arc::default(),
            spawn_lock: Mutex::new(()),
        }
    }

    #[must_use]
    pub fn with_owner_limit(mut self, limit: usize) -> Self {
        self.owner_limit = limit;
        self
    }

    pub fn layout(&self) -> &Layout {
        &self.layout
    }

    pub fn owner_limit(&self) -> usize {
        self.owner_limit
    }

    /// The `$HOME`-confined file API.
    pub fn files(&self) -> files::Files {
        files::Files::new(self.layout.home())
    }

    /// Number of spawned processes still running (including handles whose
    /// leader exited but left background children in its process group).
    pub fn live_count(&self) -> usize {
        self.inner.procs().values().filter(|p| p.is_live()).count()
    }

    /// Live processes of every connection of the client app `uid`.
    pub fn live_count_of_uid(&self, uid: Uid) -> usize {
        self.inner
            .procs()
            .values()
            .filter(|p| p.uid == uid && p.is_live())
            .count()
    }

    /// The owner of `handle`, if it exists.
    pub fn owner_of(&self, handle: Handle) -> Option<OwnerId> {
        self.inner.procs().get(&handle).map(|p| p.owner)
    }

    /// Spawns a program for connection `owner` of client app `uid` (docs/API.md
    /// §6.2, §6.3). At most [`Host::owner_limit`] live processes per `uid`,
    /// however many connections it opens.
    pub fn spawn(&self, req: &SpawnRequest, owner: OwnerId, uid: Uid) -> Result<Spawned> {
        let _serial = self.spawn_lock.lock().unwrap_or_else(|e| e.into_inner());
        if self.live_count_of_uid(uid) >= self.owner_limit {
            return Err(HostError::new(
                ErrorCode::Limit,
                format!(
                    "this app already runs {} processes in Ferrux (limit)",
                    self.owner_limit
                ),
            ));
        }
        let config = self.spawn_config(req)?;
        let (io, child, master) = match req.pty {
            Some(_) => {
                let (master, child) = ferrux_pty::spawn(&config).map_err(spawn_error)?;
                let ours = master
                    .try_clone()
                    .map_err(|e| HostError::io(&e, "dup pty master"));
                let ours = match ours {
                    Ok(m) => m,
                    Err(e) => {
                        let _ = child.signal_group(libc::SIGKILL);
                        let _ = child.wait();
                        return Err(e);
                    }
                };
                let io = SpawnedIo::Pty {
                    master: master.into_fd(),
                };
                (io, child, Some(ours))
            }
            None => {
                let (pipes, child) =
                    ferrux_pty::spawn_pipes(&config, req.merge_stderr).map_err(spawn_error)?;
                let io = SpawnedIo::Pipes {
                    stdin: pipes.stdin,
                    stdout: pipes.stdout,
                    stderr: pipes.stderr,
                };
                (io, child, None)
            }
        };

        let handle = self.next.fetch_add(1, Ordering::Relaxed);
        let pid = child.pid();
        let proc = Arc::new(Proc {
            owner,
            uid,
            pid,
            reaped: Mutex::new(false),
            is_pty: master.is_some(),
            master: Mutex::new(master),
        });
        self.inner.procs().insert(handle, proc.clone());
        let inner = self.inner.clone();
        let waiter = std::thread::Builder::new()
            .name(format!("ferrux-api-wait-{handle}"))
            .spawn(move || wait_child(handle, &child, &proc, &inner));
        if let Err(e) = waiter {
            // Without a waiter the child would never be reaped; don't run it.
            log::error!("api {handle}: cannot start waiter thread: {e}");
            if let Some(p) = self.inner.procs().remove(&handle) {
                p.signal_groups(&[pid], libc::SIGKILL);
            }
            // SAFETY: plain syscall wrapper; the child is ours and unreaped.
            unsafe { libc::waitpid(pid, std::ptr::null_mut(), 0) };
            return Err(HostError::new(
                ErrorCode::SpawnFailed,
                format!("cannot start waiter thread: {e}"),
            ));
        }
        log::info!(
            "api {handle}: started {:?} (pid {pid}, owner {owner})",
            req.argv
        );
        Ok(Spawned { handle, pid, io })
    }

    fn spawn_config(&self, req: &SpawnRequest) -> Result<SpawnConfig> {
        let Some(arg0) = req.argv.first().filter(|a| !a.is_empty()) else {
            return Err(HostError::new(ErrorCode::InvalidArgument, "argv is empty"));
        };
        let env = self.build_env(req)?;
        let cwd = match req.cwd.as_deref().filter(|c| !c.is_empty()) {
            Some(c) => self.files().resolve_dir(c)?,
            None => self.layout.home(),
        };
        let path_var = env
            .iter()
            .rev()
            .find(|(k, _)| k == "PATH")
            .map(|(_, v)| v.as_str())
            .unwrap_or("");
        let program = self.resolve_program(arg0, path_var, &cwd)?;
        let mut config = SpawnConfig::new(&program)
            .arg0(arg0.as_str())
            .args(req.argv[1..].iter().map(String::as_str))
            .cwd(cwd)
            .exec_mode(self.exec_mode_for(&program));
        config.env = env.into_iter().map(|(k, v)| (k.into(), v.into())).collect();
        if let Some(size) = req.pty {
            config.size = WinSize::new(size.rows.max(1), size.cols.max(1));
        }
        Ok(config)
    }

    /// Ferrux's session environment with the request's entries applied.
    fn build_env(&self, req: &SpawnRequest) -> Result<Vec<(String, String)>> {
        let mut env = if req.clear_env {
            Vec::new()
        } else {
            ferrux_env::build_env(&self.layout, self.host_env.iter().cloned())
        };
        for kv in &req.env {
            let Some((k, v)) = kv.split_once('=').filter(|(k, _)| !k.is_empty()) else {
                return Err(HostError::new(
                    ErrorCode::InvalidArgument,
                    format!("env entry is not KEY=VALUE: {kv:?}"),
                ));
            };
            env.retain(|(key, _)| key != k);
            env.push((k.to_owned(), v.to_owned()));
        }
        Ok(env)
    }

    /// Resolves `arg0` like `execvp`: a path if it contains `/` (relative to
    /// `cwd`), otherwise the first executable match in `path_var`.
    fn resolve_program(&self, arg0: &str, path_var: &str, cwd: &Path) -> Result<PathBuf> {
        if arg0.contains('/') {
            let p = cwd.join(arg0);
            return if p.is_file() {
                Ok(p)
            } else {
                Err(HostError::new(
                    ErrorCode::NotFound,
                    format!("{}: no such file", p.display()),
                ))
            };
        }
        let found = path_var
            .split(':')
            .filter(|d| !d.is_empty())
            .map(|d| Path::new(d).join(arg0))
            .find(|p| is_executable(p));
        found.ok_or_else(|| {
            let code = if self.layout.is_bootstrapped() {
                ErrorCode::NotFound
            } else {
                ErrorCode::BootstrapRequired
            };
            HostError::new(code, format!("{arg0}: not found in PATH={path_var}"))
        })
    }

    /// Programs inside the app's data directory must run through the system
    /// linker on Android (targetSdk >= 29).
    // TODO(#5): use ferrux-env's exec-mode API once it exists.
    fn exec_mode_for(&self, program: &Path) -> ExecMode {
        if cfg!(target_os = "android") && program.starts_with(self.layout.files_dir()) {
            ExecMode::system_linker()
        } else {
            ExecMode::Direct
        }
    }

    fn get(&self, handle: Handle) -> Result<Arc<Proc>> {
        self.inner.procs().get(&handle).cloned().ok_or_else(|| {
            HostError::new(ErrorCode::NoSuchHandle, format!("no such handle {handle}"))
        })
    }

    /// Sets the pty window size (sends `SIGWINCH`).
    pub fn resize(&self, handle: Handle, rows: u16, cols: u16) -> Result<()> {
        let proc = self.get(handle)?;
        if !proc.is_pty {
            return Err(HostError::new(
                ErrorCode::Unsupported,
                "not a pty process; cannot resize",
            ));
        }
        let master = proc.master();
        let master = master
            .as_ref()
            .ok_or_else(|| HostError::new(ErrorCode::InvalidArgument, "the process has exited"))?;
        master
            .resize(WinSize::new(rows.max(1), cols.max(1)))
            .map_err(|e| HostError::io(&e, "resize"))
    }

    /// Sends `signal` to the process group. For a pty, terminal signals
    /// (`SIGINT`, `SIGQUIT`, `SIGTSTP`, `SIGWINCH`) go to the foreground job
    /// only, like a keyboard would; others go to the shell and the job.
    pub fn signal(&self, handle: Handle, signal: i32) -> Result<()> {
        if !(1..=64).contains(&signal) {
            return Err(HostError::new(
                ErrorCode::InvalidArgument,
                format!("bad signal {signal}"),
            ));
        }
        let proc = self.get(handle)?;
        let terminal = [libc::SIGINT, libc::SIGQUIT, libc::SIGTSTP, libc::SIGWINCH];
        let groups = match proc.foreground_group() {
            Some(fg) if terminal.contains(&signal) => vec![fg],
            _ => proc.groups(),
        };
        proc.signal_groups(&groups, signal);
        Ok(())
    }

    /// `SIGKILL` to the process group (and the pty's foreground job).
    pub fn kill(&self, handle: Handle) -> Result<()> {
        let proc = self.get(handle)?;
        proc.signal_groups(&proc.groups(), libc::SIGKILL);
        Ok(())
    }

    /// Hangs up and forgets `handle`: `SIGHUP` now, `SIGKILL` after
    /// [`CLOSE_GRACE`] if still alive, and our pty duplicate is closed.
    pub fn close(&self, handle: Handle) -> Result<()> {
        let proc = self.inner.procs().remove(&handle).ok_or_else(|| {
            HostError::new(ErrorCode::NoSuchHandle, format!("no such handle {handle}"))
        })?;
        hang_up(handle, proc);
        Ok(())
    }

    /// Closes every handle of `owner` (client died or unbound). Returns how
    /// many there were.
    pub fn close_owner(&self, owner: OwnerId) -> usize {
        let procs: Vec<(Handle, Arc<Proc>)> = {
            let mut map = self.inner.procs();
            let handles: Vec<Handle> = map
                .iter()
                .filter(|(_, p)| p.owner == owner)
                .map(|(&h, _)| h)
                .collect();
            handles
                .into_iter()
                .filter_map(|h| map.remove(&h).map(|p| (h, p)))
                .collect()
        };
        let n = procs.len();
        for (handle, proc) in procs {
            hang_up(handle, proc);
        }
        n
    }

    /// Blocks until at least one exit is queued or `timeout` elapses, then
    /// takes all queued exits.
    pub fn wait_exits(&self, timeout: Duration) -> Vec<ExitEvent> {
        let guard = self.inner.exits.lock().unwrap_or_else(|e| e.into_inner());
        let (mut guard, _) = self
            .inner
            .exits_cv
            .wait_timeout_while(guard, timeout, |q| q.is_empty())
            .unwrap_or_else(|e| e.into_inner());
        guard.drain(..).collect()
    }
}

fn spawn_error(e: SpawnError) -> HostError {
    let code = match e {
        SpawnError::Nul => ErrorCode::InvalidArgument,
        _ => ErrorCode::SpawnFailed,
    };
    HostError::new(code, e.to_string())
}

fn is_executable(p: &Path) -> bool {
    use std::os::unix::fs::PermissionsExt;
    std::fs::metadata(p).is_ok_and(|m| m.is_file() && m.permissions().mode() & 0o111 != 0)
}

fn hang_up(handle: Handle, proc: Arc<Proc>) {
    if !proc.is_live() {
        return;
    }
    let groups = proc.groups();
    proc.signal_groups(&groups, libc::SIGHUP);
    let killer = std::thread::Builder::new()
        .name(format!("ferrux-api-kill-{handle}"))
        .spawn(move || {
            std::thread::sleep(CLOSE_GRACE);
            proc.signal_groups(&groups, libc::SIGKILL);
        });
    if let Err(e) = killer {
        log::warn!("api {handle}: cannot start kill timer: {e}");
    }
}

/// Waits without reaping, reaps under the `reaped` lock, then queues the
/// exit if the handle is still registered.
fn wait_child(handle: Handle, child: &Child, proc: &Proc, inner: &Inner) {
    if let Err(e) = child.wait_exited() {
        log::error!("api {handle}: waitid failed: {e}");
        return;
    }
    let status = {
        let mut reaped = proc.reaped();
        let status = child.try_wait();
        *reaped = true;
        status
    };
    // The terminal is the client's now; ours only served resize/signals.
    drop(proc.master().take());
    let status = match status {
        Ok(Some(s)) => s,
        Ok(None) => {
            log::error!("api {handle}: child not reapable after waitid");
            return;
        }
        Err(e) => {
            log::error!("api {handle}: waitpid failed: {e}");
            return;
        }
    };
    log::info!("api {handle}: exited with {status:?}");
    if !inner.procs().contains_key(&handle) {
        return;
    }
    let mut q = inner.exits.lock().unwrap_or_else(|e| e.into_inner());
    q.push_back(ExitEvent {
        handle,
        owner: proc.owner,
        status,
    });
    inner.exits_cv.notify_all();
}

#[cfg(test)]
mod tests;
