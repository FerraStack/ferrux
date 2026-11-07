// SPDX-License-Identifier: Apache-2.0
//! `ferrux-pty`: pseudo-terminals and process spawning for Ferrux.
//!
//! Designed to run inside an Android app process (ART), which constrains how
//! children may be created:
//!
//! * Everything the child needs (C strings, `argv`, `envp`, signal masks) is
//!   prepared **before** `fork()`; the child then calls only
//!   async-signal-safe libc functions and raw syscalls before `execve`.
//! * No process-global `SIGCHLD` handler and no `waitpid(-1)`: other code in
//!   the process (ART, libraries) owns other children. Each [`Child`] is
//!   reaped by pid.
//! * File descriptors are [`OwnedFd`]s, which plays well with Android's fdsan.
//!   The child closes inherited descriptors with raw syscalls, bypassing
//!   bionic's fdsan bookkeeping (which belongs to the parent anyway).
//!
//! Exec failures in the child (bad path, `chdir` failure, …) are reported
//! back to [`spawn`] through a close-on-exec pipe, so the caller gets a real
//! error instead of a process that exits with status 127.

use std::ffi::{CString, OsStr, OsString};
use std::io::{self, Read, Write};
use std::os::fd::{AsFd, AsRawFd, BorrowedFd, OwnedFd, RawFd};
use std::os::unix::ffi::OsStrExt;
use std::path::{Path, PathBuf};

use rustix::pty::OpenptFlags;
use rustix::termios::{self, InputModes, OptionalActions, Winsize};

/// How the program is executed.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub enum ExecMode {
    /// Plain `execve(program, argv, envp)`.
    #[default]
    Direct,
    /// `execve(linker, [linker, program, args…], envp)`.
    ///
    /// On Android with targetSdk ≥ 29, SELinux forbids executing files from
    /// the app's data directory, but the system dynamic linker may be
    /// executed and will load the target ELF itself. This is the strategy of
    /// termux-exec (Apache-2.0); grandchildren need an `LD_PRELOAD` shim to
    /// keep doing it. Static executables cannot be run this way.
    /// Implemented but **not yet exercised on a device** (milestone M3).
    Linker { linker: PathBuf },
}

impl ExecMode {
    /// The platform's system linker (`/system/bin/linker64` on 64-bit).
    pub fn system_linker() -> Self {
        let linker = if cfg!(target_pointer_width = "64") {
            "/system/bin/linker64"
        } else {
            "/system/bin/linker"
        };
        Self::Linker {
            linker: linker.into(),
        }
    }
}

/// Terminal window size.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct WinSize {
    pub rows: u16,
    pub cols: u16,
    pub pixel_width: u16,
    pub pixel_height: u16,
}

impl WinSize {
    pub const fn new(rows: u16, cols: u16) -> Self {
        Self {
            rows,
            cols,
            pixel_width: 0,
            pixel_height: 0,
        }
    }

    fn to_winsize(self) -> Winsize {
        Winsize {
            ws_row: self.rows,
            ws_col: self.cols,
            ws_xpixel: self.pixel_width,
            ws_ypixel: self.pixel_height,
        }
    }
}

impl Default for WinSize {
    fn default() -> Self {
        Self::new(24, 80)
    }
}

/// What to run and how.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SpawnConfig {
    /// Path of the executable (not searched in `PATH`).
    pub program: PathBuf,
    /// `argv[0]`; defaults to `program`. Use e.g. `-sh` for a login shell.
    pub arg0: Option<OsString>,
    /// `argv[1..]`.
    pub args: Vec<OsString>,
    /// The complete environment. Nothing is inherited from the parent.
    pub env: Vec<(OsString, OsString)>,
    /// Working directory; `None` keeps the parent's.
    pub cwd: Option<PathBuf>,
    pub size: WinSize,
    pub exec_mode: ExecMode,
}

impl SpawnConfig {
    pub fn new(program: impl Into<PathBuf>) -> Self {
        Self {
            program: program.into(),
            arg0: None,
            args: Vec::new(),
            env: Vec::new(),
            cwd: None,
            size: WinSize::default(),
            exec_mode: ExecMode::Direct,
        }
    }

    #[must_use]
    pub fn arg(mut self, arg: impl Into<OsString>) -> Self {
        self.args.push(arg.into());
        self
    }

    #[must_use]
    pub fn args<I, S>(mut self, args: I) -> Self
    where
        I: IntoIterator<Item = S>,
        S: Into<OsString>,
    {
        self.args.extend(args.into_iter().map(Into::into));
        self
    }

    #[must_use]
    pub fn env(mut self, key: impl Into<OsString>, value: impl Into<OsString>) -> Self {
        self.env.push((key.into(), value.into()));
        self
    }

    #[must_use]
    pub fn arg0(mut self, arg0: impl Into<OsString>) -> Self {
        self.arg0 = Some(arg0.into());
        self
    }

    #[must_use]
    pub fn cwd(mut self, dir: impl Into<PathBuf>) -> Self {
        self.cwd = Some(dir.into());
        self
    }

    #[must_use]
    pub fn size(mut self, size: WinSize) -> Self {
        self.size = size;
        self
    }

    #[must_use]
    pub fn exec_mode(mut self, mode: ExecMode) -> Self {
        self.exec_mode = mode;
        self
    }
}

/// The step in the child that failed.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ChildStage {
    Setsid,
    OpenPts,
    Chdir,
    Exec,
}

/// Errors from [`spawn`].
#[derive(Debug, thiserror::Error)]
pub enum SpawnError {
    #[error("argument, environment or path contains a NUL byte")]
    Nul,
    #[error("failed to set up pty: {0}")]
    Pty(#[source] io::Error),
    #[error("fork failed: {0}")]
    Fork(#[source] io::Error),
    #[error("child failed at {stage:?}: {error}")]
    Child { stage: ChildStage, error: io::Error },
}

/// The master side of a pseudo-terminal.
#[derive(Debug)]
pub struct PtyMaster {
    fd: OwnedFd,
}

impl PtyMaster {
    /// Updates the window size; the kernel sends `SIGWINCH` to the
    /// foreground process group.
    pub fn resize(&self, size: WinSize) -> io::Result<()> {
        termios::tcsetwinsize(&self.fd, size.to_winsize())?;
        Ok(())
    }

    pub fn size(&self) -> io::Result<WinSize> {
        let ws = termios::tcgetwinsize(&self.fd)?;
        Ok(WinSize {
            rows: ws.ws_row,
            cols: ws.ws_col,
            pixel_width: ws.ws_xpixel,
            pixel_height: ws.ws_ypixel,
        })
    }

    pub fn set_nonblocking(&self, nonblocking: bool) -> io::Result<()> {
        rustix::io::ioctl_fionbio(&self.fd, nonblocking)?;
        Ok(())
    }

    /// Wraps an existing master fd, e.g. one received over Binder
    /// (Ferride's "remote session").
    pub fn from_fd(fd: OwnedFd) -> Self {
        Self { fd }
    }

    pub fn into_fd(self) -> OwnedFd {
        self.fd
    }
}

impl AsFd for PtyMaster {
    fn as_fd(&self) -> BorrowedFd<'_> {
        self.fd.as_fd()
    }
}

impl AsRawFd for PtyMaster {
    fn as_raw_fd(&self) -> RawFd {
        self.fd.as_raw_fd()
    }
}

impl Read for &PtyMaster {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        Ok(rustix::io::read(&self.fd, buf)?)
    }
}

impl Write for &PtyMaster {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        Ok(rustix::io::write(&self.fd, buf)?)
    }
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

impl Read for PtyMaster {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        (&*self).read(buf)
    }
}

impl Write for PtyMaster {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        (&*self).write(buf)
    }
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

/// How a child terminated.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ExitStatus {
    Exited(i32),
    Signaled(i32),
}

impl ExitStatus {
    /// Shell-style code: the exit status, or 128 + signal number.
    pub fn code(self) -> i32 {
        match self {
            ExitStatus::Exited(c) => c,
            ExitStatus::Signaled(s) => 128 + s,
        }
    }

    fn from_raw(status: libc::c_int) -> Self {
        if libc::WIFSIGNALED(status) {
            ExitStatus::Signaled(libc::WTERMSIG(status))
        } else {
            ExitStatus::Exited(libc::WEXITSTATUS(status))
        }
    }
}

/// A spawned child process. Dropping it does not kill or reap it.
#[derive(Debug)]
pub struct Child {
    pid: libc::pid_t,
}

impl Child {
    pub fn pid(&self) -> i32 {
        self.pid
    }

    /// Blocks until the child exits and reaps it. Call this from exactly one
    /// thread (typically a dedicated waiter thread).
    pub fn wait(&self) -> io::Result<ExitStatus> {
        loop {
            let mut status = 0;
            // SAFETY: plain syscall wrapper with a valid out-pointer.
            let r = unsafe { libc::waitpid(self.pid, &mut status, 0) };
            if r == self.pid {
                return Ok(ExitStatus::from_raw(status));
            }
            let err = io::Error::last_os_error();
            if err.kind() != io::ErrorKind::Interrupted {
                return Err(err);
            }
        }
    }

    /// Blocks until the child has terminated **without reaping it**, so its
    /// pid stays reserved. Lets a waiter thread take a lock before reaping,
    /// which closes the race between reaping and [`Child::signal_group`].
    pub fn wait_exited(&self) -> io::Result<()> {
        loop {
            // SAFETY: zeroed siginfo_t is a valid out-parameter.
            let mut info: libc::siginfo_t = unsafe { std::mem::zeroed() };
            // SAFETY: plain syscall wrapper with a valid out-pointer.
            let r = unsafe {
                libc::waitid(
                    libc::P_PID,
                    self.pid as libc::id_t,
                    &mut info,
                    libc::WEXITED | libc::WNOWAIT,
                )
            };
            if r == 0 {
                return Ok(());
            }
            let err = io::Error::last_os_error();
            if err.kind() != io::ErrorKind::Interrupted {
                return Err(err);
            }
        }
    }

    /// Reaps the child if it already exited.
    pub fn try_wait(&self) -> io::Result<Option<ExitStatus>> {
        let mut status = 0;
        // SAFETY: plain syscall wrapper with a valid out-pointer.
        let r = unsafe { libc::waitpid(self.pid, &mut status, libc::WNOHANG) };
        match r {
            0 => Ok(None),
            r if r == self.pid => Ok(Some(ExitStatus::from_raw(status))),
            _ => Err(io::Error::last_os_error()),
        }
    }

    /// Sends `signal` to the child's process group (the child is a session
    /// and process-group leader, so this reaches its jobs too).
    ///
    /// Only call this while the child has not been reaped, otherwise the pid
    /// may have been reused.
    pub fn signal_group(&self, signal: i32) -> io::Result<()> {
        // SAFETY: plain syscall wrapper.
        if unsafe { libc::kill(-self.pid, signal) } == 0 {
            Ok(())
        } else {
            Err(io::Error::last_os_error())
        }
    }
}

/// Opens a pty, configures it and runs `config.program` on its slave side as
/// a session leader with the pty as controlling terminal.
pub fn spawn(config: &SpawnConfig) -> Result<(PtyMaster, Child), SpawnError> {
    let master = open_master(config.size).map_err(SpawnError::Pty)?;
    let pts = rustix::pty::ptsname(&master, Vec::new()).map_err(|e| SpawnError::Pty(e.into()))?;

    let prepared = Prepared::new(config, pts)?;
    let (err_read, err_write) = rustix::pipe::pipe_with(rustix::pipe::PipeFlags::CLOEXEC)
        .map_err(|e| SpawnError::Pty(e.into()))?;
    let max_fd = max_fd();

    // SAFETY: fork() in a multi-threaded process is sound as long as the child
    // only calls async-signal-safe functions until execve. `child_main` obeys
    // that: all allocation happened above.
    let pid = unsafe { libc::fork() };
    if pid < 0 {
        return Err(SpawnError::Fork(io::Error::last_os_error()));
    }
    if pid == 0 {
        // SAFETY: we are the freshly forked child.
        unsafe { child_main(&prepared, err_write.as_raw_fd(), max_fd) }
    }
    drop(err_write);

    let child = Child { pid };
    match read_child_error(&err_read) {
        None => Ok((PtyMaster { fd: master }, child)),
        Some((stage, errno)) => {
            let _ = child.wait();
            Err(SpawnError::Child {
                stage,
                error: io::Error::from_raw_os_error(errno),
            })
        }
    }
}

fn open_master(size: WinSize) -> io::Result<OwnedFd> {
    let master =
        rustix::pty::openpt(OpenptFlags::RDWR | OpenptFlags::NOCTTY | OpenptFlags::CLOEXEC)?;
    rustix::pty::grantpt(&master)?;
    rustix::pty::unlockpt(&master)?;
    // Settings on the master apply to the line discipline shared with the
    // slave. UTF-8 aware erase; no XON/XOFF so Ctrl+S/Ctrl+Q reach programs.
    let mut t = termios::tcgetattr(&master)?;
    t.input_modes.insert(InputModes::IUTF8);
    t.input_modes.remove(InputModes::IXON);
    termios::tcsetattr(&master, OptionalActions::Now, &t)?;
    termios::tcsetwinsize(&master, size.to_winsize())?;
    Ok(master)
}

/// The highest fd number the child might have inherited.
fn max_fd() -> libc::c_int {
    // SAFETY: plain libc call.
    let n = unsafe { libc::sysconf(libc::_SC_OPEN_MAX) };
    if n <= 0 {
        1024
    } else {
        n.min(65_536) as libc::c_int
    }
}

/// Everything the child needs, allocated before fork.
struct Prepared {
    exec_path: CString,
    pts: CString,
    cwd: Option<CString>,
    // Owned strings backing the pointer arrays below.
    _argv: Vec<CString>,
    _envp: Vec<CString>,
    argv_ptrs: Vec<*const libc::c_char>,
    envp_ptrs: Vec<*const libc::c_char>,
    default_action: libc::sigaction,
    empty_set: libc::sigset_t,
}

fn cstring(bytes: &[u8]) -> Result<CString, SpawnError> {
    CString::new(bytes).map_err(|_| SpawnError::Nul)
}

fn os(s: &OsStr) -> &[u8] {
    s.as_bytes()
}

fn path_bytes(p: &Path) -> &[u8] {
    p.as_os_str().as_bytes()
}

impl Prepared {
    fn new(config: &SpawnConfig, pts: CString) -> Result<Self, SpawnError> {
        let program = cstring(path_bytes(&config.program))?;
        let arg0 = match &config.arg0 {
            Some(a) => cstring(os(a))?,
            None => program.clone(),
        };
        let rest = config
            .args
            .iter()
            .map(|a| cstring(os(a)))
            .collect::<Result<Vec<_>, _>>()?;

        let (exec_path, argv) = match &config.exec_mode {
            ExecMode::Direct => (
                program,
                std::iter::once(arg0).chain(rest).collect::<Vec<_>>(),
            ),
            // The linker treats argv[1] as the program to load; the program
            // then sees its own path as argv[0], so a custom arg0 is lost.
            ExecMode::Linker { linker } => {
                let linker = cstring(path_bytes(linker))?;
                let argv = [linker.clone(), program].into_iter().chain(rest).collect();
                (linker, argv)
            }
        };

        let envp = config
            .env
            .iter()
            .map(|(k, v)| {
                let mut kv = os(k).to_vec();
                kv.push(b'=');
                kv.extend_from_slice(os(v));
                cstring(&kv)
            })
            .collect::<Result<Vec<_>, _>>()?;
        let cwd = config
            .cwd
            .as_deref()
            .map(|p| cstring(path_bytes(p)))
            .transpose()?;

        let null_terminated = |v: &[CString]| {
            v.iter()
                .map(|s| s.as_ptr())
                .chain(std::iter::once(std::ptr::null()))
                .collect::<Vec<_>>()
        };
        // SAFETY: zeroed sigaction/sigset_t are valid; we then set the fields.
        let (default_action, empty_set) = unsafe {
            let mut action: libc::sigaction = std::mem::zeroed();
            action.sa_sigaction = libc::SIG_DFL;
            let mut set: libc::sigset_t = std::mem::zeroed();
            libc::sigemptyset(&mut set);
            (action, set)
        };
        Ok(Self {
            exec_path,
            pts,
            cwd,
            argv_ptrs: null_terminated(&argv),
            envp_ptrs: null_terminated(&envp),
            _argv: argv,
            _envp: envp,
            default_action,
            empty_set,
        })
    }
}

/// Runs in the forked child. Async-signal-safe calls only; never returns.
///
/// # Safety
/// Must only be called in the child right after `fork()`.
unsafe fn child_main(p: &Prepared, err_fd: RawFd, max_fd: libc::c_int) -> ! {
    // SAFETY (whole body): only async-signal-safe libc calls on pointers that
    // were prepared before fork and stay valid in the child's copy of memory.
    unsafe {
        let fail = |stage: u8| -> ! {
            let errno = io::Error::last_os_error().raw_os_error().unwrap_or(0);
            let mut msg = [0u8; 5];
            msg[0] = stage;
            msg[1..].copy_from_slice(&errno.to_ne_bytes());
            libc::write(err_fd, msg.as_ptr().cast(), msg.len());
            libc::_exit(127);
        };

        // ART blocks and ignores some signals; ignored dispositions and the
        // mask survive execve, so reset both.
        for sig in 1..65 {
            if sig != libc::SIGKILL && sig != libc::SIGSTOP {
                libc::sigaction(sig, &p.default_action, std::ptr::null_mut());
            }
        }
        libc::sigprocmask(libc::SIG_SETMASK, &p.empty_set, std::ptr::null_mut());

        if libc::setsid() < 0 {
            fail(0);
        }
        // As a session leader without a controlling tty, opening the slave
        // makes it ours; TIOCSCTTY makes that explicit.
        let slave = libc::open(p.pts.as_ptr(), libc::O_RDWR);
        if slave < 0 {
            fail(1);
        }
        libc::ioctl(slave, libc::TIOCSCTTY as _, 0);
        for fd in 0..3 {
            if libc::dup2(slave, fd) < 0 {
                fail(1);
            }
        }

        // Close everything else except the error pipe (which is CLOEXEC).
        // Raw syscalls: bionic's close() would consult fdsan tags owned by
        // the parent's objects.
        let close_range = |lo: libc::c_int, hi: libc::c_int| {
            if lo > hi {
                return;
            }
            let r = libc::syscall(
                libc::SYS_close_range,
                lo as libc::c_uint,
                hi as libc::c_uint,
                0 as libc::c_uint,
            );
            if r != 0 {
                for fd in lo..=hi.min(max_fd) {
                    libc::syscall(libc::SYS_close, fd);
                }
            }
        };
        close_range(3, err_fd - 1);
        close_range(err_fd + 1, libc::c_int::MAX);

        if let Some(cwd) = &p.cwd
            && libc::chdir(cwd.as_ptr()) < 0
        {
            fail(2);
        }
        libc::execve(
            p.exec_path.as_ptr(),
            p.argv_ptrs.as_ptr(),
            p.envp_ptrs.as_ptr(),
        );
        fail(3)
    }
}

/// Reads the child's failure report; `None` means exec succeeded.
fn read_child_error(fd: &OwnedFd) -> Option<(ChildStage, i32)> {
    let mut buf = [0u8; 5];
    let mut got = 0;
    while got < buf.len() {
        match rustix::io::read(fd, &mut buf[got..]) {
            Ok(0) => break,
            Ok(n) => got += n,
            Err(rustix::io::Errno::INTR) => continue,
            Err(_) => break,
        }
    }
    if got < buf.len() {
        return None;
    }
    let stage = match buf[0] {
        0 => ChildStage::Setsid,
        1 => ChildStage::OpenPts,
        2 => ChildStage::Chdir,
        _ => ChildStage::Exec,
    };
    Some((stage, i32::from_ne_bytes([buf[1], buf[2], buf[3], buf[4]])))
}

#[cfg(test)]
mod tests;
