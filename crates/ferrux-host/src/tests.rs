// SPDX-License-Identifier: Apache-2.0
use std::io::{Read, Write};
use std::os::unix::fs::symlink;
use std::time::Instant;

use super::files::{FileType, OpenMode};
use super::*;

/// A temporary Ferrux layout (`files/home` exists), removed on drop.
struct TempLayout {
    root: PathBuf,
    layout: Layout,
}

impl TempLayout {
    fn new(name: &str) -> Self {
        let root = std::env::temp_dir().join(format!(
            "ferrux-host-{name}-{}-{:?}",
            std::process::id(),
            std::thread::current().id()
        ));
        let _ = fs_remove(&root);
        let layout = Layout::new(root.join("files"));
        std::fs::create_dir_all(layout.home()).unwrap();
        Self { root, layout }
    }

    fn host(&self) -> Host {
        Host::new(self.layout.clone(), vec![])
    }
}

fn fs_remove(p: &Path) -> std::io::Result<()> {
    std::fs::remove_dir_all(p)
}

impl Drop for TempLayout {
    fn drop(&mut self) {
        let _ = fs_remove(&self.root);
    }
}

fn sh(script: &str) -> SpawnRequest {
    SpawnRequest {
        argv: vec!["/bin/sh".into(), "-c".into(), script.into()],
        env: vec!["PATH=/usr/bin:/bin".into()],
        ..Default::default()
    }
}

fn read_all(fd: OwnedFd) -> String {
    let mut s = String::new();
    std::fs::File::from(fd).read_to_string(&mut s).unwrap();
    s
}

fn pipes(spawned: Spawned) -> (OwnedFd, OwnedFd, Option<OwnedFd>) {
    match spawned.io {
        SpawnedIo::Pipes {
            stdin,
            stdout,
            stderr,
        } => (stdin, stdout, stderr),
        SpawnedIo::Pty { .. } => panic!("expected pipes"),
    }
}

/// Waits for the exit of `handle`.
fn wait_exit(host: &Host, handle: Handle) -> ExitEvent {
    let start = Instant::now();
    loop {
        assert!(start.elapsed() < Duration::from_secs(10), "no exit");
        if let Some(e) = host
            .wait_exits(Duration::from_millis(100))
            .into_iter()
            .find(|e| e.handle == handle)
        {
            return e;
        }
    }
}

#[test]
fn pipes_echo_and_exit_code() {
    let t = TempLayout::new("echo");
    let host = t.host();
    let s = host.spawn(&sh("echo api-ok; exit 7"), 1).unwrap();
    let handle = s.handle;
    assert!(s.pid > 0);
    let (stdin, stdout, stderr) = pipes(s);
    drop(stdin);
    assert_eq!(read_all(stdout), "api-ok\n");
    assert_eq!(read_all(stderr.unwrap()), "");
    let exit = wait_exit(&host, handle);
    assert_eq!(exit.owner, 1);
    assert_eq!(exit.code_and_signal(), (7, 0));
    assert_eq!(host.live_count(), 0);
    // The handle stays until closed.
    assert_eq!(host.owner_of(handle), Some(1));
    host.close(handle).unwrap();
    assert_eq!(host.owner_of(handle), None);
    assert_eq!(
        host.close(handle).unwrap_err().code,
        ErrorCode::NoSuchHandle
    );
}

#[test]
fn stdin_round_trip_and_merged_stderr() {
    let t = TempLayout::new("cat");
    let host = t.host();
    let mut req = sh("cat; echo err >&2");
    req.merge_stderr = true;
    let (stdin, stdout, stderr) = pipes(host.spawn(&req, 1).unwrap());
    assert!(stderr.is_none());
    let mut stdin = std::fs::File::from(stdin);
    stdin.write_all(b"Content-Length: 2\r\n\r\n{}").unwrap();
    drop(stdin);
    assert_eq!(read_all(stdout), "Content-Length: 2\r\n\r\n{}err\n");
}

#[test]
fn env_merge_cwd_and_path_lookup() {
    let t = TempLayout::new("env");
    let host = t.host();
    std::fs::create_dir(t.layout.home().join("proj")).unwrap();
    let req = SpawnRequest {
        // Looked up in PATH; argv[0] is passed through unchanged.
        argv: vec![
            "sh".into(),
            "-c".into(),
            "echo \"$0|$FOO|$TERM|$(pwd)|$HOME\"".into(),
        ],
        env: vec!["PATH=/usr/bin:/bin".into(), "FOO=a=b".into()],
        cwd: Some("proj".into()),
        ..Default::default()
    };
    let (_, stdout, _) = pipes(host.spawn(&req, 1).unwrap());
    let home = std::fs::canonicalize(t.layout.home()).unwrap();
    assert_eq!(
        read_all(stdout),
        format!(
            "sh|a=b|xterm-256color|{}|{}\n",
            home.join("proj").display(),
            t.layout.home().display()
        )
    );

    let mut clear = sh("echo \"[$FERRUX_PREFIX]\"");
    clear.clear_env = true;
    let (_, stdout, _) = pipes(host.spawn(&clear, 1).unwrap());
    assert_eq!(read_all(stdout), "[]\n");
}

#[test]
fn spawn_errors() {
    let t = TempLayout::new("errors");
    let host = t.host();
    let err = |req: SpawnRequest| host.spawn(&req, 1).unwrap_err().code;

    assert_eq!(err(SpawnRequest::default()), ErrorCode::InvalidArgument);
    let mut bad_env = sh("true");
    bad_env.env.push("NOEQUALS".into());
    assert_eq!(err(bad_env), ErrorCode::InvalidArgument);
    // Not bootstrapped: a missing name means the bootstrap is required.
    let mut missing = sh("true");
    missing.argv[0] = "definitely-not-a-program".into();
    assert_eq!(err(missing), ErrorCode::BootstrapRequired);
    let mut missing_path = sh("true");
    missing_path.argv[0] = "/nonexistent/prog".into();
    assert_eq!(err(missing_path), ErrorCode::NotFound);
    let mut outside = sh("true");
    outside.cwd = Some("/".into());
    assert_eq!(err(outside), ErrorCode::OutsideHome);
    // Exists but isn't executable: exec fails in the child.
    std::fs::write(t.layout.home().join("data"), "x").unwrap();
    let mut noexec = sh("true");
    noexec.argv[0] = t.layout.home().join("data").display().to_string();
    assert_eq!(err(noexec), ErrorCode::SpawnFailed);
}

#[test]
fn pty_master_is_handed_out_and_resizable() {
    let t = TempLayout::new("pty");
    let host = t.host();
    let mut req = sh("stty size; read line; echo got:$line; stty size");
    req.pty = Some(WinSize::new(20, 70));
    let s = host.spawn(&req, 9).unwrap();
    let handle = s.handle;
    let SpawnedIo::Pty { master } = s.io else {
        panic!("expected a pty")
    };
    let master = PtyMaster::from_fd(master);
    let mut seen = String::new();
    let mut buf = [0u8; 256];
    while !seen.contains("20 70") {
        let n = (&master).read(&mut buf).unwrap();
        seen.push_str(&String::from_utf8_lossy(&buf[..n]));
    }
    host.resize(handle, 11, 44).unwrap();
    (&master).write_all(b"hi\n").unwrap();
    while !seen.contains("11 44") {
        match (&master).read(&mut buf) {
            Ok(n) if n > 0 => seen.push_str(&String::from_utf8_lossy(&buf[..n])),
            _ => break,
        }
    }
    assert!(seen.contains("got:hi"), "{seen:?}");
    assert!(seen.contains("11 44"), "{seen:?}");
    assert_eq!(wait_exit(&host, handle).code_and_signal(), (0, 0));
}

#[test]
fn resize_of_pipes_is_unsupported() {
    let t = TempLayout::new("resize");
    let host = t.host();
    let s = host.spawn(&sh("true"), 1).unwrap();
    assert_eq!(
        host.resize(s.handle, 1, 1).unwrap_err().code,
        ErrorCode::Unsupported
    );
    assert_eq!(
        host.resize(999, 1, 1).unwrap_err().code,
        ErrorCode::NoSuchHandle
    );
}

#[test]
fn kill_and_signal_reach_the_group() {
    let t = TempLayout::new("kill");
    let host = t.host();
    // The grandchild keeps stdout open; killing the group must close it.
    let s = host.spawn(&sh("sleep 30 & wait"), 1).unwrap();
    let handle = s.handle;
    let (_stdin, stdout, _) = pipes(s);
    host.kill(handle).unwrap();
    assert_eq!(wait_exit(&host, handle).code_and_signal(), (-1, 9));
    assert_eq!(read_all(stdout), "");

    let s = host.spawn(&sh("sleep 30"), 1).unwrap();
    host.signal(s.handle, libc::SIGTERM).unwrap();
    assert_eq!(
        wait_exit(&host, s.handle).code_and_signal(),
        (-1, libc::SIGTERM)
    );
    assert_eq!(
        host.signal(s.handle, 0).unwrap_err().code,
        ErrorCode::InvalidArgument
    );
}

#[test]
fn close_owner_hangs_up_only_that_owner() {
    let t = TempLayout::new("owner");
    let host = t.host();
    let a = host.spawn(&sh("sleep 30"), 1).unwrap();
    let b = host.spawn(&sh("sleep 30"), 2).unwrap();
    assert_eq!(host.live_count(), 2);
    assert_eq!(host.close_owner(1), 1);
    assert_eq!(host.owner_of(a.handle), None);
    // Closed handles report no exit; wait for the reap via live_count.
    let start = Instant::now();
    while host.live_count() != 1 {
        assert!(start.elapsed() < Duration::from_secs(10), "not hung up");
        std::thread::sleep(Duration::from_millis(20));
    }
    assert_eq!(host.owner_of(b.handle), Some(2));
    host.kill(b.handle).unwrap();
    wait_exit(&host, b.handle);
}

#[test]
fn per_owner_limit() {
    let t = TempLayout::new("limit");
    let host = t.host().with_owner_limit(2);
    let a = host.spawn(&sh("sleep 30"), 1).unwrap();
    let _b = host.spawn(&sh("sleep 30"), 1).unwrap();
    assert_eq!(
        host.spawn(&sh("true"), 1).unwrap_err().code,
        ErrorCode::Limit
    );
    // Other owners are unaffected.
    host.spawn(&sh("true"), 2).unwrap();
    host.kill(a.handle).unwrap();
    wait_exit(&host, a.handle);
    host.spawn(&sh("true"), 1).unwrap();
    host.close_owner(1);
}

// --- files ---

fn files(t: &TempLayout) -> files::Files {
    t.host().files()
}

#[test]
fn files_write_read_stat_list() {
    let t = TempLayout::new("files");
    let f = files(&t);
    let fd = f.open("notes/a.txt", OpenMode::Write).unwrap_err();
    assert_eq!(fd.code, ErrorCode::NotFound);
    f.mkdirs("notes/sub").unwrap();
    std::fs::File::from(f.open("notes/a.txt", OpenMode::Write).unwrap())
        .write_all(b"hello")
        .unwrap();
    std::fs::File::from(f.open("notes/a.txt", OpenMode::Append).unwrap())
        .write_all(b" world")
        .unwrap();
    let mut s = String::new();
    std::fs::File::from(f.open("notes/a.txt", OpenMode::Read).unwrap())
        .read_to_string(&mut s)
        .unwrap();
    assert_eq!(s, "hello world");

    let abs = t.layout.home().join("notes/a.txt");
    let st = f.stat(abs.to_str().unwrap()).unwrap();
    assert_eq!(st.file_type, FileType::File);
    assert_eq!(st.size, 11);
    assert_eq!(st.mode, 0o600);
    assert_eq!(st.name, "a.txt");

    symlink("a.txt", t.layout.home().join("notes/link")).unwrap();
    let names: Vec<_> = f
        .list("notes", 0, 100)
        .unwrap()
        .into_iter()
        .map(|s| (s.name, s.file_type))
        .collect();
    assert_eq!(
        names,
        vec![
            ("a.txt".into(), FileType::File),
            ("link".into(), FileType::Symlink),
            ("sub".into(), FileType::Dir),
        ]
    );
    let page: Vec<_> = f
        .list("notes", 1, 1)
        .unwrap()
        .into_iter()
        .map(|s| s.name)
        .collect();
    assert_eq!(page, vec!["link".to_string()]);
    assert_eq!(
        f.stat("notes/link").unwrap().symlink_target,
        Some("a.txt".into())
    );
    // Opening an inside symlink follows it.
    let mut s = String::new();
    std::fs::File::from(f.open("notes/link", OpenMode::Read).unwrap())
        .read_to_string(&mut s)
        .unwrap();
    assert_eq!(s, "hello world");

    f.rename("notes/a.txt", "notes/b.txt").unwrap();
    assert_eq!(f.stat("notes/a.txt").unwrap_err().code, ErrorCode::NotFound);
    f.delete("notes/b.txt", false).unwrap();
    assert_eq!(
        f.delete("notes", false).unwrap_err().code,
        ErrorCode::Io,
        "non-empty dir needs recursive"
    );
    f.delete("notes", true).unwrap();
    assert_eq!(f.list("", 0, 10).unwrap(), vec![]);
    assert_eq!(
        f.open("", OpenMode::Read).unwrap_err().code,
        ErrorCode::InvalidArgument
    );
}

#[test]
fn files_are_confined_to_home() {
    let t = TempLayout::new("confine");
    let f = files(&t);
    let secret = t.layout.files_dir().join("secret");
    std::fs::write(&secret, "s").unwrap();
    let home = t.layout.home();
    symlink(&secret, home.join("escape")).unwrap();
    symlink(t.layout.files_dir(), home.join("updir")).unwrap();

    fn code<T: std::fmt::Debug>(r: Result<T>) -> ErrorCode {
        r.unwrap_err().code
    }
    assert_eq!(code(f.stat("../secret")), ErrorCode::OutsideHome);
    assert_eq!(
        code(f.stat(secret.to_str().unwrap())),
        ErrorCode::OutsideHome
    );
    assert_eq!(
        code(f.open("escape", OpenMode::Read)),
        ErrorCode::OutsideHome
    );
    assert_eq!(
        code(f.open("updir/secret", OpenMode::Read)),
        ErrorCode::OutsideHome
    );
    assert_eq!(code(f.list("updir", 0, 10)), ErrorCode::OutsideHome);
    assert_eq!(code(f.list("..", 0, 10)), ErrorCode::OutsideHome);
    assert_eq!(code(f.mkdirs("updir/new")), ErrorCode::OutsideHome);
    assert_eq!(code(f.mkdirs("../new")), ErrorCode::OutsideHome);
    assert_eq!(code(f.rename("escape", "../moved")), ErrorCode::OutsideHome);
    assert_eq!(code(f.delete("", true)), ErrorCode::InvalidArgument);
    assert_eq!(code(f.delete(".", true)), ErrorCode::InvalidArgument);
    // The symlink itself is inside and can be inspected and removed.
    assert_eq!(f.stat("escape").unwrap().file_type, FileType::Symlink);
    f.delete("escape", false).unwrap();
    assert!(secret.exists());
    assert_eq!(
        code(f.open("/etc/passwd", OpenMode::Read)),
        ErrorCode::OutsideHome
    );
}
