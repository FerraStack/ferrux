// SPDX-License-Identifier: Apache-2.0
//! End-to-end tests: real `/bin/sh` processes through a pty into the
//! terminal, driven by the manager's I/O thread.

use super::*;

const TIMEOUT: Duration = Duration::from_secs(10);

fn sh(script: &str) -> SpawnConfig {
    SpawnConfig::new("/bin/sh")
        .arg("-c")
        .arg(script)
        .env("PATH", "/usr/bin:/bin")
        .env("TERM", "xterm-256color")
        .size(WinSize::new(10, 40))
}

/// Waits (via the change counter) until `pred` holds for the session.
fn wait_for(mgr: &SessionManager, session: &Session, what: &str, pred: impl Fn(&Session) -> bool) {
    let start = Instant::now();
    let mut seq = mgr.change_seq();
    while !pred(session) {
        assert!(
            start.elapsed() < TIMEOUT,
            "timed out waiting for {what}; screen:\n{}",
            session.snapshot().text()
        );
        seq = mgr.wait_change(seq, Duration::from_millis(200));
    }
}

fn screen_contains(text: &str) -> impl Fn(&Session) -> bool + '_ {
    move |s| s.snapshot().text().contains(text)
}

#[test]
fn output_reaches_the_grid_and_exit_is_reported() {
    let mgr = SessionManager::new().unwrap();
    let s = mgr
        .spawn(
            &sh("printf 'hello\\r\\n\\033[1;31mred\\033[0m'; exit 7"),
            TermConfig::default(),
        )
        .unwrap();
    wait_for(&mgr, &s, "exit", |s| s.exit_status().is_some());
    wait_for(&mgr, &s, "output", screen_contains("red"));
    let snap = s.snapshot();
    assert_eq!(snap.row_text(0), "hello");
    assert_eq!(snap.row_text(1), "red");
    assert!(snap.cell(1, 0).flags.contains(ferrux_term::CellFlags::BOLD));
    assert_eq!(s.exit_status(), Some(ExitStatus::Exited(7)));
    assert!(
        s.drain_events()
            .contains(&SessionEvent::Exited(ExitStatus::Exited(7)))
    );
    assert!(s.pid().is_some());
}

#[test]
fn interactive_input_and_keys() {
    let mgr = SessionManager::new().unwrap();
    let s = mgr
        .spawn(
            &sh("read a; printf '[%s]\\r\\n' \"$a\"; read b; printf '{%s}' \"$b\""),
            TermConfig::default(),
        )
        .unwrap();
    s.send_text("first", Modifiers::NONE);
    s.send_key(Key::Enter, Modifiers::NONE);
    wait_for(&mgr, &s, "first echo", screen_contains("[first]"));
    // Backspace is handled by the tty line discipline (VERASE = DEL).
    s.send_text("secX", Modifiers::NONE);
    s.send_key(Key::Backspace, Modifiers::NONE);
    s.send_text("ond", Modifiers::NONE);
    s.send_key(Key::Enter, Modifiers::NONE);
    wait_for(&mgr, &s, "second echo", screen_contains("{second}"));
    wait_for(&mgr, &s, "exit", |s| {
        s.exit_status() == Some(ExitStatus::Exited(0))
    });
}

#[test]
fn ctrl_c_interrupts_foreground_job() {
    let mgr = SessionManager::new().unwrap();
    let s = mgr
        .spawn(&sh("printf ready; exec sleep 30"), TermConfig::default())
        .unwrap();
    wait_for(&mgr, &s, "ready", screen_contains("ready"));
    s.send_text("c", Modifiers::CTRL);
    wait_for(&mgr, &s, "SIGINT exit", |s| {
        s.exit_status() == Some(ExitStatus::Signaled(2))
    });
}

#[test]
fn resize_updates_pty_and_grid() {
    let mgr = SessionManager::new().unwrap();
    let s = mgr
        .spawn(
            &sh("stty size; read _; stty size; read _"),
            TermConfig::default(),
        )
        .unwrap();
    wait_for(&mgr, &s, "initial size", screen_contains("10 40"));
    s.resize(TermSize::new(20, 60));
    assert_eq!(s.snapshot().cols, 60);
    assert_eq!(s.snapshot().rows, 20);
    s.send_key(Key::Enter, Modifiers::NONE);
    wait_for(&mgr, &s, "new size", screen_contains("20 60"));
    s.send_key(Key::Enter, Modifiers::NONE);
    wait_for(&mgr, &s, "exit", |s| s.exit_status().is_some());
}

#[test]
fn terminal_responses_are_written_back() {
    // The program asks for the cursor position (DSR 6); the terminal must
    // answer through the pty, and the program prints what it received.
    let script = "stty raw -echo; printf 'ab\\033[6n'; r=$(dd bs=1 count=6 2>/dev/null | od -An -c | tr -d ' \\n'); stty sane; printf '\\r\\n%s' \"$r\"";
    let mgr = SessionManager::new().unwrap();
    let s = mgr.spawn(&sh(script), TermConfig::default()).unwrap();
    wait_for(&mgr, &s, "DSR reply", screen_contains("033[1;3R"));
}

#[test]
fn title_bell_and_clipboard_events() {
    let mgr = SessionManager::new().unwrap();
    let s = mgr
        .spawn(
            &sh("printf '\\033]2;my title\\007\\007\\033]52;c;aGk=\\007done'"),
            TermConfig::default(),
        )
        .unwrap();
    wait_for(&mgr, &s, "done", screen_contains("done"));
    let events = s.drain_events();
    assert!(
        events.contains(&SessionEvent::Title(Some("my title".into()))),
        "{events:?}"
    );
    assert!(events.contains(&SessionEvent::Bell), "{events:?}");
    assert!(
        events.contains(&SessionEvent::ClipboardStore("hi".into())),
        "{events:?}"
    );
    assert_eq!(s.title().as_deref(), Some("my title"));
}

#[test]
fn bracketed_paste_reaches_program() {
    let mgr = SessionManager::new().unwrap();
    let script = "stty raw -echo; printf '\\033[?2004h'; r=$(dd bs=1 count=15 2>/dev/null | od -An -c | tr -d ' \\n'); stty sane; printf '\\r\\n%s' \"$r\"";
    let s = mgr.spawn(&sh(script), TermConfig::default()).unwrap();
    wait_for(&mgr, &s, "bracketed paste mode", |s| {
        s.snapshot().modes.bracketed_paste
    });
    s.paste("xyz");
    wait_for(
        &mgr,
        &s,
        "pasted bytes",
        screen_contains("033[200~xyz033[201~"),
    );
}

#[test]
fn large_write_does_not_block_and_arrives_intact() {
    let mgr = SessionManager::new().unwrap();
    let s = mgr
        .spawn(&sh("stty raw -echo; printf ready; n=$(head -c 262144 | wc -c); stty sane; printf 'got %s' $n"), TermConfig::default())
        .unwrap();
    // Raw mode must be active before writing, or the canonical line buffer
    // (4 KiB) would truncate the input.
    wait_for(&mgr, &s, "raw mode", screen_contains("ready"));
    let data = vec![b'x'; 256 * 1024];
    let start = Instant::now();
    s.write(&data);
    assert!(
        start.elapsed() < Duration::from_secs(1),
        "write must only enqueue"
    );
    wait_for(&mgr, &s, "byte count", screen_contains("got 262144"));
}

#[test]
fn close_hangs_up_and_unregisters() {
    let mgr = SessionManager::new().unwrap();
    let s = mgr
        .spawn(&sh("printf up; sleep 30"), TermConfig::default())
        .unwrap();
    wait_for(&mgr, &s, "up", screen_contains("up"));
    let id = s.id();
    assert_eq!(mgr.ids(), vec![id]);
    assert!(mgr.close(id));
    assert!(!mgr.close(id));
    assert!(mgr.get(id).is_none());
    wait_for(&mgr, &s, "SIGHUP exit", |s| s.exit_status().is_some());
    assert_eq!(s.exit_status(), Some(ExitStatus::Signaled(1)));
}

#[test]
fn many_sessions_share_one_io_thread() {
    let mgr = SessionManager::new().unwrap();
    let sessions: Vec<_> = (0..8)
        .map(|i| {
            mgr.spawn(
                &sh(&format!("printf 'session-{i}'; sleep 1")),
                TermConfig::default(),
            )
            .unwrap()
        })
        .collect();
    for (i, s) in sessions.iter().enumerate() {
        wait_for(&mgr, s, "output", screen_contains(&format!("session-{i}")));
    }
    let ids = mgr.ids();
    assert_eq!(ids.len(), 8);
    assert!(ids.windows(2).all(|w| w[0] < w[1]));
}

#[test]
fn attach_existing_master() {
    // Simulates Ferride receiving a pty master fd from the Ferrux service.
    let (master, child) = ferrux_pty::spawn(&sh("printf attached; read _")).unwrap();
    let mgr = SessionManager::new().unwrap();
    let s = mgr
        .attach(master, TermSize::new(10, 40), TermConfig::default())
        .unwrap();
    assert_eq!(s.pid(), None);
    wait_for(&mgr, &s, "output", screen_contains("attached"));
    s.send_key(Key::Enter, Modifiers::NONE);
    assert_eq!(child.wait().unwrap(), ExitStatus::Exited(0));
}

#[test]
fn wait_change_times_out() {
    let mgr = SessionManager::new().unwrap();
    let seq = mgr.change_seq();
    let start = Instant::now();
    assert_eq!(mgr.wait_change(seq, Duration::from_millis(50)), seq);
    assert!(start.elapsed() >= Duration::from_millis(40));
}

#[test]
fn spawn_errors_propagate() {
    let mgr = SessionManager::new().unwrap();
    let err = mgr
        .spawn(&SpawnConfig::new("/nonexistent"), TermConfig::default())
        .unwrap_err();
    assert!(matches!(err, SpawnError::Child { .. }), "{err:?}");
    assert!(mgr.ids().is_empty());
}
