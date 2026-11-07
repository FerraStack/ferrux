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
