// SPDX-License-Identifier: Apache-2.0
//! Platform-neutral logic behind the JNI functions, kept separate so it can
//! be unit-tested on the desktop without a JVM.

use std::cell::RefCell;
use std::sync::{Arc, OnceLock};
use std::time::Duration;

use ferrux_env::Layout;
use ferrux_pty::{ExecMode, SpawnConfig, SpawnError, WinSize};
use ferrux_session::{Session, SessionEvent, SessionId, SessionManager};
use ferrux_term::{Key, Modifiers, Snapshot, TermConfig, TermSize};

static MANAGER: OnceLock<SessionManager> = OnceLock::new();
static LAYOUT: OnceLock<Layout> = OnceLock::new();

/// Process-wide session manager (started lazily).
pub fn manager() -> &'static SessionManager {
    MANAGER.get_or_init(|| SessionManager::new().expect("cannot start ferrux I/O thread"))
}

/// Sets the layout from the app's real files dir. First call wins.
pub fn init(files_dir: &str) {
    // Android reports /data/user/0/<pkg>/files; /data/data/<pkg> is the same
    // directory and is the path packages are compiled for, so prefer it.
    let canonical = |p: &str| std::fs::canonicalize(p).ok();
    let layout = LAYOUT.get_or_init(|| {
        if canonical(files_dir).is_some()
            && canonical(files_dir) == canonical(ferrux_env::FILES_DIR)
        {
            Layout::default()
        } else {
            Layout::new(files_dir)
        }
    });
    if !layout.matches_compiled_prefix() {
        log::warn!(
            "files dir {} does not match the compiled prefix {}; packages built for {} will not work",
            layout.files_dir().display(),
            ferrux_env::PREFIX,
            ferrux_env::APP_PACKAGE
        );
    }
    if let Err(e) = ferrux_env::bootstrap::prepare_dirs(layout) {
        log::error!("cannot create home/tmp dirs: {e}");
    }
}

pub fn layout() -> &'static Layout {
    LAYOUT.get_or_init(Layout::default)
}

/// Path of the default shell.
pub fn default_shell() -> String {
    ferrux_env::default_shell(layout())
        .program
        .to_string_lossy()
        .into_owned()
}

/// The default session environment as `KEY=VALUE` strings.
pub fn default_env() -> Vec<String> {
    ferrux_env::build_env(layout(), std::env::vars())
        .into_iter()
        .map(|(k, v)| format!("{k}={v}"))
        .collect()
}

/// Creates a session; `env` entries are `KEY=VALUE`.
pub fn create_session(
    program: &str,
    args: Vec<String>,
    env: Vec<String>,
    cwd: &str,
    rows: u16,
    cols: u16,
) -> Result<SessionId, SpawnError> {
    let shell = ferrux_env::default_shell(layout());
    let mut config = SpawnConfig::new(program)
        .args(args)
        .size(WinSize::new(rows.max(1), cols.max(1)));
    // A login-style argv[0] only for the default system shell.
    if shell.program.as_os_str() == program {
        config.arg0 = shell.arg0.map(Into::into);
        if shell.needs_linker {
            config.exec_mode = ExecMode::system_linker();
        }
    }
    config.env = env
        .into_iter()
        .filter_map(|kv| kv.split_once('=').map(|(k, v)| (k.into(), v.into())))
        .collect();
    if !cwd.is_empty() {
        config.cwd = Some(cwd.into());
    }
    let session = manager().spawn(&config, TermConfig::default())?;
    log::info!(
        "session {} started: {program} (pid {:?})",
        session.id(),
        session.pid()
    );
    Ok(session.id())
}

pub fn session(id: i64) -> Option<Arc<Session>> {
    u64::try_from(id).ok().and_then(|id| manager().get(id))
}

pub fn destroy_session(id: i64) {
    if let Ok(id) = u64::try_from(id) {
        manager().close(id);
    }
}

pub fn send_key(id: i64, key: i32, mods: i32) -> bool {
    let (Some(s), Some(key)) = (session(id), Key::from_code(key as u32)) else {
        return false;
    };
    s.send_key(key, Modifiers::from_bits_truncate(mods as u32));
    true
}

pub fn resize(id: i64, rows: i32, cols: i32) {
    if let Some(s) = session(id) {
        let clamp = |v: i32| v.clamp(1, u16::MAX as i32) as u16;
        s.resize(TermSize::new(clamp(rows), clamp(cols)));
    }
}

thread_local! {
    /// Reused per calling thread (the UI thread) to avoid per-frame
    /// allocations.
    static SNAPSHOT: RefCell<(Snapshot, Vec<i32>)> = RefCell::new((Snapshot::default(), Vec::new()));
}

/// Encodes the session's screen and hands the ints to `sink`, which must copy
/// them into `out` if it is big enough. Returns the JNI result: ints written,
/// `-(needed)` if `capacity` is too small, or 0 for an unknown session.
pub fn snapshot(id: i64, capacity: usize, sink: impl FnOnce(&[i32])) -> i32 {
    let Some(s) = session(id) else { return 0 };
    SNAPSHOT.with_borrow_mut(|(snap, ints)| {
        s.snapshot_into(snap);
        let len = snap.encoded_len();
        if len > capacity {
            return -(len as i32);
        }
        ints.resize(len, 0);
        snap.encode_ints(ints);
        sink(ints);
        len as i32
    })
}

pub fn await_change(last: i64, timeout_ms: i32) -> i64 {
    let timeout = Duration::from_millis(timeout_ms.max(0) as u64);
    manager().wait_change(last as u64, timeout) as i64
}

/// Drains events as `kind + payload` strings (see `FerruxNative.pollEvents`).
pub fn poll_events(id: i64) -> Option<Vec<String>> {
    let events = session(id)?.drain_events();
    Some(events.into_iter().map(encode_event).collect())
}

fn encode_event(event: SessionEvent) -> String {
    match event {
        SessionEvent::Title(t) => format!("T{}", t.unwrap_or_default()),
        SessionEvent::Bell => "B".into(),
        SessionEvent::ClipboardStore(text) => format!("C{text}"),
        SessionEvent::Exited(status) => format!("X{}", status.code()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Instant;

    #[test]
    fn session_round_trip_through_core_api() {
        let id = create_session(
            "/bin/sh",
            vec![
                "-c".into(),
                "printf '\\033]0;t\\007hi'; read _; exit 4".into(),
            ],
            vec!["PATH=/usr/bin:/bin".into(), "BROKEN".into()],
            "/",
            5,
            20,
        )
        .unwrap() as i64;

        let start = Instant::now();
        let mut seq = 0;
        let mut ints = Vec::new();
        loop {
            assert!(start.elapsed() < Duration::from_secs(10), "no output");
            assert_eq!(snapshot(id, 3, |_| unreachable!()), -(8 + 5 * 20 * 4));
            let n = snapshot(id, 1000, |data| ints = data.to_vec());
            assert_eq!(n, 408);
            if ints[8] == 'h' as i32 {
                break;
            }
            seq = await_change(seq, 100);
        }
        assert_eq!(&ints[..2], &[5, 20]);
        resize(id, 6, 30);
        assert_eq!(snapshot(id, 1000, |d| ints = d.to_vec()), 8 + 6 * 30 * 4);
        assert!(send_key(id, Key::Enter.code() as i32, 0));
        assert!(!send_key(id, 999, 0));

        let mut events = Vec::new();
        while !events.iter().any(|e: &String| e.starts_with('X')) {
            assert!(
                start.elapsed() < Duration::from_secs(10),
                "no exit: {events:?}"
            );
            seq = await_change(seq, 100);
            events.extend(poll_events(id).unwrap());
        }
        assert_eq!(events, vec!["Tt".to_string(), "X4".to_string()]);
        destroy_session(id);
        assert!(poll_events(id).is_none());
        assert_eq!(snapshot(id, 1000, |_| {}), 0);
    }

    #[test]
    fn unknown_and_invalid_ids() {
        assert!(session(-1).is_none());
        assert!(poll_events(123_456).is_none());
        destroy_session(-5);
        resize(123_456, 1, 1);
    }

    #[test]
    fn env_and_shell_defaults() {
        let env = default_env();
        assert!(env.iter().any(|kv| kv.starts_with("HOME=")));
        assert!(env.iter().any(|kv| kv == "TERM=xterm-256color"));
        assert!(!default_shell().is_empty());
    }
}
