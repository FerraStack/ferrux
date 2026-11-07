// SPDX-License-Identifier: Apache-2.0
//! Platform-neutral logic behind the terminal JNI functions, kept separate so
//! it can be unit-tested on the desktop without a JVM.
//!
//! Session ids are those of the process-wide [`manager`]; a process must load
//! only one library containing this crate.

use std::cell::RefCell;
use std::sync::{Arc, OnceLock};
use std::time::Duration;

use std::io;
use std::os::fd::{FromRawFd, OwnedFd, RawFd};

use ferrux_pty::{PtyMaster, SpawnConfig, SpawnError};
use ferrux_session::{Session, SessionEvent, SessionId, SessionManager};
use ferrux_term::{
    Key, Modifiers, MouseAction, MouseButton, SelectionKind, Snapshot, TermConfig, TermSize,
    ViewPoint,
};

static MANAGER: OnceLock<SessionManager> = OnceLock::new();

/// Process-wide session manager (started lazily).
pub fn manager() -> &'static SessionManager {
    MANAGER.get_or_init(|| SessionManager::new().expect("cannot start ferrux I/O thread"))
}

/// Spawns a session from a fully prepared `config`.
///
/// The host decides the program, `argv[0]`, environment, working directory
/// and exec mode; this crate never does.
pub fn spawn_session(config: &SpawnConfig) -> Result<SessionId, SpawnError> {
    let session = manager().spawn(config, TermConfig::default())?;
    log::info!(
        "session {} started: {} (pid {:?})",
        session.id(),
        config.program.display(),
        session.pid()
    );
    Ok(session.id())
}

/// Adopts a pty master `fd` (e.g. one received over Binder) as a session
/// without a local child. Takes ownership of `fd`, also on failure.
pub fn attach_pty(fd: RawFd, rows: u16, cols: u16) -> io::Result<SessionId> {
    if fd < 0 {
        return Err(io::Error::from_raw_os_error(
            rustix::io::Errno::BADF.raw_os_error(),
        ));
    }
    // SAFETY: the caller hands over ownership of an open fd
    // (`ParcelFileDescriptor.detachFd()`); nothing else closes it.
    let fd = unsafe { OwnedFd::from_raw_fd(fd) };
    let size = TermSize::new(rows.max(1), cols.max(1));
    let session = manager().attach(PtyMaster::from_fd(fd), size, TermConfig::default())?;
    log::info!("session {} attached to a pty", session.id());
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

/// A scroll gesture at a viewport cell; see `Session::scroll_gesture`.
pub fn scroll_gesture(id: i64, lines: i32, row: i32, col: i32) {
    if let Some(s) = session(id) {
        s.scroll_gesture(lines, row.max(0) as usize, col.max(0) as usize);
    }
}

/// Reports a mouse event (codes: see `MouseButton::code` / `MouseAction::code`).
/// Returns whether the program wanted it.
pub fn mouse_event(id: i64, button: i32, action: i32, row: i32, col: i32, mods: i32) -> bool {
    let Some(s) = session(id) else { return false };
    let (Some(button), Some(action)) = (
        MouseButton::from_code(button as u32),
        MouseAction::from_code(action as u32),
    ) else {
        return false;
    };
    s.mouse(
        button,
        action,
        row.max(0) as usize,
        col.max(0) as usize,
        Modifiers::from_bits_truncate(mods as u32),
    )
}

fn view_point(row: i32, col: i32) -> ViewPoint {
    ViewPoint::new(row, col.max(0) as usize)
}

/// Starts a selection (`kind`: see [`SelectionKind::code`]). Returns false
/// for an unknown session or kind.
pub fn start_selection(id: i64, kind: i32, row: i32, col: i32) -> bool {
    let (Some(s), Some(kind)) = (session(id), SelectionKind::from_code(kind as u32)) else {
        return false;
    };
    s.with_terminal(|t| t.start_selection(kind, view_point(row, col)));
    true
}

pub fn update_selection(id: i64, row: i32, col: i32) {
    if let Some(s) = session(id) {
        s.with_terminal(|t| t.update_selection(view_point(row, col)));
    }
}

pub fn set_selection(id: i64, start: (i32, i32), end: (i32, i32), block: bool) {
    if let Some(s) = session(id) {
        let (start, end) = (view_point(start.0, start.1), view_point(end.0, end.1));
        s.with_terminal(|t| t.set_selection(start, end, block));
    }
}

pub fn select_all(id: i64) {
    if let Some(s) = session(id) {
        s.with_terminal(|t| t.select_all());
    }
}

pub fn clear_selection(id: i64) {
    if let Some(s) = session(id) {
        s.with_terminal(|t| t.clear_selection());
    }
}

/// The selected text, or `None` if nothing is selected.
pub fn selection_text(id: i64) -> Option<String> {
    session(id)?.with_terminal(|t| t.selection_text())
}

/// The visible screen as text (rows joined by `\n`), for accessibility and
/// debugging.
pub fn screen_text(id: i64) -> Option<String> {
    Some(session(id)?.snapshot().text())
}

pub fn await_change(last: i64, timeout_ms: i32) -> i64 {
    let timeout = Duration::from_millis(timeout_ms.max(0) as u64);
    manager().wait_change(last as u64, timeout) as i64
}

/// Drains events as `kind + payload` strings (see `TerminalNative.pollEvents`).
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
        SessionEvent::Failed(msg) => format!("E{msg}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::fd::IntoRawFd;
    use std::time::Instant;

    use ferrux_pty::WinSize;

    /// Ints before the first cell in the integer encoding.
    const H: usize = Snapshot::HEADER_LEN;

    #[test]
    fn session_round_trip_through_core_api() {
        let config = SpawnConfig::new("/bin/sh")
            .args(["-c", "printf '\\033]0;t\\007hi'; read _; exit 4"])
            .env("PATH", "/usr/bin:/bin")
            .cwd("/")
            .size(WinSize::new(5, 20));
        let id = spawn_session(&config).unwrap() as i64;

        let start = Instant::now();
        let mut seq = 0;
        let mut ints = Vec::new();
        loop {
            assert!(start.elapsed() < Duration::from_secs(10), "no output");
            assert_eq!(
                snapshot(id, 3, |_| unreachable!()),
                -((H + 5 * 20 * 4 + 1 + 6) as i32)
            );
            let n = snapshot(id, 1000, |data| ints = data.to_vec());
            assert_eq!(n, (H + 5 * 20 * 4 + 1 + 6) as i32);
            if ints[H] == 'h' as i32 {
                break;
            }
            seq = await_change(seq, 100);
        }
        assert_eq!(&ints[..2], &[5, 20]);
        assert_eq!(screen_text(id).as_deref(), Some("hi"));

        // Selection round trip: word, then explicit range, then cleared.
        assert!(start_selection(id, 1, 0, 1));
        assert!(!start_selection(id, 9, 0, 1), "unknown kind");
        assert_eq!(selection_text(id).as_deref(), Some("hi"));
        snapshot(id, 1000, |d| ints = d.to_vec());
        let sel = H + 5 * 20 * 4 + 1;
        assert_eq!(&ints[sel..sel + 5], &[1, 0, 0, 0, 1]);
        set_selection(id, (0, 1), (0, 1), false);
        assert_eq!(selection_text(id).as_deref(), Some("i"));
        update_selection(id, 0, -3);
        assert_eq!(selection_text(id).as_deref(), Some("hi"), "col clamps to 0");
        select_all(id);
        assert_eq!(selection_text(id).as_deref(), Some("hi"));
        clear_selection(id);
        assert_eq!(selection_text(id), None);

        resize(id, 6, 30);
        assert_eq!(
            snapshot(id, 1000, |d| ints = d.to_vec()),
            (H + 6 * 30 * 4 + 1 + 6) as i32
        );
        // No mouse reporting in a plain shell; scrolling an empty history is a no-op.
        assert!(!mouse_event(id, 0, 0, 0, 0, 0));
        assert!(!mouse_event(id, 9, 0, 0, 0, 0), "unknown button");
        scroll_gesture(id, 3, 0, 0);
        snapshot(id, 1000, |d| ints = d.to_vec());
        assert_eq!(ints[5], 0, "display offset");
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
        assert!(screen_text(id).is_none());
        assert!(selection_text(id).is_none());
        assert!(!start_selection(id, 0, 0, 0));
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
    fn attach_pty_round_trip() {
        // Ferride's case: a pty master fd owned by another process (here a
        // child we spawn ourselves) is handed over as a raw fd.
        let script = "printf attached; read line; printf '<%s>' \"$line\"; read _";
        let (master, child) = ferrux_pty::spawn(
            &SpawnConfig::new("/bin/sh")
                .args(["-c", script])
                .env("PATH", "/usr/bin:/bin"),
        )
        .unwrap();
        let fd = master.into_fd().into_raw_fd();
        let id = attach_pty(fd, 4, 30).unwrap() as i64;
        assert!(session(id).unwrap().pid().is_none());

        let wait_text = |text: &str| {
            let start = Instant::now();
            let mut seq = 0;
            while !screen_text(id).unwrap().contains(text) {
                assert!(start.elapsed() < Duration::from_secs(10), "no {text:?}");
                seq = await_change(seq, 100);
            }
        };
        wait_text("attached");
        session(id).unwrap().send_text("typed", Modifiers::NONE);
        assert!(send_key(id, Key::Enter.code() as i32, 0));
        wait_text("<typed>");

        // Destroying the session closes the master, which hangs up the shell.
        destroy_session(id);
        assert!(session(id).is_none());
        assert_eq!(
            child.wait().unwrap(),
            ferrux_pty::ExitStatus::Signaled(rustix::process::Signal::HUP.as_raw())
        );
    }

    #[test]
    fn attach_pty_rejects_bad_fd() {
        assert!(attach_pty(-1, 1, 1).is_err());
    }
}
