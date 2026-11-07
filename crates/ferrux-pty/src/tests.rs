// SPDX-License-Identifier: Apache-2.0
use super::*;

/// Reads until the slave side is closed by every process (EIO on Linux).
fn read_to_end(master: &PtyMaster) -> String {
    let mut out = Vec::new();
    let mut buf = [0u8; 4096];
    loop {
        match (&*master).read(&mut buf) {
            Ok(0) => break,
            Ok(n) => out.extend_from_slice(&buf[..n]),
            Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
            Err(_) => break, // EIO: slave closed
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn sh(script: &str) -> SpawnConfig {
    SpawnConfig::new("/bin/sh")
        .arg("-c")
        .arg(script)
        .env("PATH", "/usr/bin:/bin")
}

#[test]
fn runs_program_and_reports_exit_code() {
    let (master, child) = spawn(&sh("printf 'hello world'; exit 3")).unwrap();
    assert_eq!(read_to_end(&master), "hello world");
    assert_eq!(child.wait().unwrap(), ExitStatus::Exited(3));
}

#[test]
fn stdio_is_the_controlling_tty() {
    let (master, child) =
        spawn(&sh("test -t 0 && test -t 1 && test -t 2 && printf tty-ok")).unwrap();
    assert_eq!(read_to_end(&master), "tty-ok");
    assert_eq!(child.wait().unwrap().code(), 0);
}

#[test]
fn initial_size_and_resize() {
    let cfg = sh("stty size; read _; stty size").size(WinSize::new(33, 101));
    let (master, child) = spawn(&cfg).unwrap();
    assert_eq!(master.size().unwrap(), WinSize::new(33, 101));
    // Wait for the first report before resizing.
    let mut seen = Vec::new();
    let mut buf = [0u8; 256];
    while !String::from_utf8_lossy(&seen).contains("33 101") {
        let n = (&master).read(&mut buf).unwrap();
        seen.extend_from_slice(&buf[..n]);
    }
    master.resize(WinSize::new(10, 40)).unwrap();
    (&master).write_all(b"\n").unwrap();
    let rest = read_to_end(&master);
    assert!(rest.contains("10 40"), "{rest:?}");
    assert_eq!(child.wait().unwrap().code(), 0);
}

#[test]
fn environment_is_exact_and_cwd_applies() {
    let cfg = SpawnConfig::new("/bin/sh")
        .arg("-c")
        .arg("printf '%s|%s|%s' \"$FOO\" \"${HOME-unset}\" \"$(pwd)\"")
        .env("FOO", "bar baz")
        .env("PATH", "/usr/bin:/bin")
        .cwd("/tmp");
    let (master, child) = spawn(&cfg).unwrap();
    assert_eq!(read_to_end(&master), "bar baz|unset|/tmp");
    child.wait().unwrap();
}

#[test]
fn arg0_is_passed() {
    let cfg = SpawnConfig::new("/bin/sh")
        .arg0("custom-name")
        .arg("-c")
        .arg("printf %s \"$0\"");
    let (master, child) = spawn(&cfg).unwrap();
    assert_eq!(read_to_end(&master), "custom-name");
    child.wait().unwrap();
}

#[test]
fn utf8_line_discipline() {
    // IUTF8 is set and IXON cleared on the pty.
    let (master, child) = spawn(&sh("stty -a")).unwrap();
    let out = read_to_end(&master);
    assert!(out.contains(" iutf8"), "{out}");
    assert!(out.contains("-ixon"), "{out}");
    child.wait().unwrap();
}

#[test]
fn input_reaches_the_child() {
    let (master, child) = spawn(&sh("read line; printf '<%s>' \"$line\"")).unwrap();
    (&master).write_all(b"typed text\r").unwrap();
    let out = read_to_end(&master);
    assert!(out.ends_with("<typed text>"), "{out:?}");
    child.wait().unwrap();
}

#[test]
fn missing_program_is_an_error() {
    let err = spawn(&SpawnConfig::new("/nonexistent/ferrux-test")).unwrap_err();
    match err {
        SpawnError::Child {
            stage: ChildStage::Exec,
            error,
        } => {
            assert_eq!(error.kind(), io::ErrorKind::NotFound)
        }
        other => panic!("unexpected {other:?}"),
    }
}

#[test]
fn bad_cwd_is_an_error() {
    let err = spawn(&sh("true").cwd("/nonexistent/dir")).unwrap_err();
    assert!(
        matches!(
            err,
            SpawnError::Child {
                stage: ChildStage::Chdir,
                ..
            }
        ),
        "{err:?}"
    );
}

#[test]
fn nul_bytes_are_rejected() {
    assert!(matches!(
        spawn(&sh("true").arg("a\0b")),
        Err(SpawnError::Nul)
    ));
}

#[test]
fn signal_group_kills_child() {
    let (master, child) = spawn(&sh("printf ready; sleep 30")).unwrap();
    let mut buf = [0u8; 16];
    let n = (&master).read(&mut buf).unwrap();
    assert_eq!(&buf[..n], b"ready");
    assert_eq!(child.try_wait().unwrap(), None);
    child.signal_group(libc::SIGHUP).unwrap();
    assert_eq!(child.wait().unwrap(), ExitStatus::Signaled(libc::SIGHUP));
    assert_eq!(ExitStatus::Signaled(libc::SIGHUP).code(), 129);
}

#[test]
fn ignored_signals_are_reset_in_child() {
    // Simulate a parent (like ART) that ignores a signal; ignored
    // dispositions survive execve, so the child must reset them.
    // SAFETY: setting a signal disposition; restored below.
    let old = unsafe { libc::signal(libc::SIGINT, libc::SIG_IGN) };
    let result = spawn(&sh("trap - INT; kill -INT $$; sleep 5"));
    // SAFETY: restoring the previous disposition.
    unsafe { libc::signal(libc::SIGINT, old) };
    let (_master, child) = result.unwrap();
    assert_eq!(child.wait().unwrap(), ExitStatus::Signaled(libc::SIGINT));
}

/// Linux's ld.so can run programs like Android's linker64 does, which lets us
/// exercise the linker exec path on the desktop.
#[test]
fn linker_exec_mode() {
    let linker = ["/lib64/ld-linux-x86-64.so.2", "/lib/ld-linux-aarch64.so.1"]
        .into_iter()
        .find(|p| Path::new(p).exists());
    let Some(linker) = linker else {
        eprintln!("no ld.so found, skipping");
        return;
    };
    let cfg = sh("printf linked").exec_mode(ExecMode::Linker {
        linker: linker.into(),
    });
    let (master, child) = spawn(&cfg).unwrap();
    assert_eq!(read_to_end(&master), "linked");
    assert_eq!(child.wait().unwrap().code(), 0);
}

// --- Signals across fork() (issue #2) ---

use std::sync::atomic::{AtomicI32, Ordering};

/// Signal the next forked children raise against themselves before resetting
/// their signal state (0 = none). Read in the child: async-signal-safe.
static INJECT_SIGNAL: AtomicI32 = AtomicI32::new(0);
/// Write end of a pipe the test handler reports to.
static HANDLER_FD: AtomicI32 = AtomicI32::new(-1);

/// Called by `child_main` right after `fork()`, before signal dispositions are
/// reset: the window in which an inherited handler must not run.
pub(super) fn child_pre_reset_hook() {
    let sig = INJECT_SIGNAL.load(Ordering::Relaxed);
    if sig != 0 {
        // SAFETY: raw syscalls, async-signal-safe.
        unsafe {
            let pid = libc::syscall(libc::SYS_getpid);
            libc::syscall(libc::SYS_kill, pid, sig);
        }
    }
}

extern "C" fn report_handler(_sig: libc::c_int) {
    let fd = HANDLER_FD.load(Ordering::Relaxed);
    if fd >= 0 {
        // SAFETY: write(2) is async-signal-safe; the buffer is static.
        unsafe { libc::write(fd, b"!".as_ptr().cast(), 1) };
    }
}

fn thread_sigmask() -> libc::sigset_t {
    // SAFETY: querying the mask into a zeroed sigset_t.
    unsafe {
        let mut cur: libc::sigset_t = std::mem::zeroed();
        libc::pthread_sigmask(libc::SIG_SETMASK, std::ptr::null(), &mut cur);
        cur
    }
}

fn is_blocked(set: &libc::sigset_t, sig: i32) -> bool {
    // SAFETY: valid, initialised set.
    unsafe { libc::sigismember(set, sig) == 1 }
}

/// A signal delivered to the child between `fork()` and the reset of its
/// dispositions must not run the parent's handler in the child: the parent
/// blocks everything across fork, and once the child has reset to `SIG_DFL`
/// the pending signal gets its default action (ignore, for `SIGWINCH`).
#[test]
fn inherited_handler_never_runs_in_child() {
    let (rd, wr) = rustix::pipe::pipe_with(rustix::pipe::PipeFlags::CLOEXEC).unwrap();
    rustix::io::ioctl_fionbio(&rd, true).unwrap();
    HANDLER_FD.store(wr.as_raw_fd(), Ordering::SeqCst);
    // SAFETY: installing a handler that only does an async-signal-safe write.
    let old = unsafe {
        libc::signal(
            libc::SIGWINCH,
            report_handler as *const () as libc::sighandler_t,
        )
    };
    INJECT_SIGNAL.store(libc::SIGWINCH, Ordering::SeqCst);

    let result = spawn(&sh("printf spawned"));

    INJECT_SIGNAL.store(0, Ordering::SeqCst);
    // SAFETY: restoring the previous disposition.
    unsafe { libc::signal(libc::SIGWINCH, old) };
    let (master, child) = result.unwrap();
    assert_eq!(read_to_end(&master), "spawned");
    assert_eq!(child.wait().unwrap(), ExitStatus::Exited(0));
    HANDLER_FD.store(-1, Ordering::SeqCst);

    let mut buf = [0u8; 16];
    let ran = rustix::io::read(&rd, &mut buf).unwrap_or(0);
    assert_eq!(ran, 0, "parent's SIGWINCH handler ran in the forked child");
}

/// The parent's own mask is restored after spawn, and the exec'd child starts
/// with nothing blocked even if the spawning thread had signals blocked.
#[test]
fn signal_masks_after_spawn() {
    // Run on a fresh thread so the mask change cannot leak into other tests.
    std::thread::spawn(|| {
        // SAFETY: blocking SIGUSR2 in this thread only.
        unsafe {
            let mut set: libc::sigset_t = std::mem::zeroed();
            libc::sigemptyset(&mut set);
            libc::sigaddset(&mut set, libc::SIGUSR2);
            libc::pthread_sigmask(libc::SIG_BLOCK, &set, std::ptr::null_mut());
        }
        let (master, child) = spawn(&sh("grep '^SigBlk' /proc/self/status")).unwrap();

        let mask = thread_sigmask();
        assert!(is_blocked(&mask, libc::SIGUSR2));
        assert!(!is_blocked(&mask, libc::SIGUSR1));
        assert!(!is_blocked(&mask, libc::SIGTERM));

        let out = read_to_end(&master);
        assert!(out.contains("SigBlk:\t0000000000000000"), "{out:?}");
        assert_eq!(child.wait().unwrap().code(), 0);
    })
    .join()
    .unwrap();
}
