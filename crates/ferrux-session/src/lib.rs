// SPDX-License-Identifier: Apache-2.0
//! `ferrux-session`: terminal sessions = a pty + a child process + a
//! [`Terminal`], driven by one background I/O thread.
//!
//! * [`SessionManager`] is the registry. It owns a single `poll(2)`-based
//!   I/O thread that reads every session's pty, feeds its terminal, and
//!   flushes queued input (so a large paste never blocks the UI thread).
//! * Each spawned child gets a small waiter thread that waits for *that* pid
//!   (never `waitpid(-1)`, never a `SIGCHLD` handler).
//! * UI layers either poll per-session events or block on
//!   [`SessionManager::wait_change`], a global change counter that bumps on
//!   any output, event or exit.
//!
//! No platform dependencies beyond POSIX; the Android JNI layer and the
//! desktop tests drive it the same way.

use std::collections::{HashMap, VecDeque};
use std::io::{self, Read, Write};
use std::os::fd::OwnedFd;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use ferrux_pty::{Child, ExitStatus, PtyMaster, SpawnConfig, SpawnError, WinSize};
use ferrux_term::{Key, Modifiers, Snapshot, TermConfig, TermEvent, TermSize, Terminal};
use rustix::event::{PollFd, PollFlags, Timespec};

pub use ferrux_pty;
pub use ferrux_term;

/// Session identifier; never reused within a manager. Always > 0.
pub type SessionId = u64;

/// Events for the UI layer.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SessionEvent {
    /// Title set by the program (`None` = reset to default).
    Title(Option<String>),
    Bell,
    /// OSC 52 copy request.
    ClipboardStore(String),
    /// The child process terminated.
    Exited(ExitStatus),
}

/// A running (or finished) session.
#[derive(Debug)]
pub struct Session {
    id: SessionId,
    master: PtyMaster,
    pid: Option<i32>,
    state: Mutex<State>,
    shared: Arc<Shared>,
}

#[derive(Debug)]
struct State {
    terminal: Terminal,
    events: VecDeque<SessionEvent>,
    /// Bytes waiting to be written to the pty.
    outbox: VecDeque<u8>,
    exit: Option<ExitStatus>,
    /// The pty reported EOF/EIO: no process holds the slave open any more.
    eof: bool,
    title: Option<String>,
}

/// Upper bound on queued input; beyond this, writes are dropped (a child that
/// never reads its input shouldn't grow our memory without limit).
const OUTBOX_LIMIT: usize = 8 << 20;

impl Session {
    pub fn id(&self) -> SessionId {
        self.id
    }

    /// The child's pid, or `None` for an attached session.
    pub fn pid(&self) -> Option<i32> {
        self.pid
    }

    fn lock(&self) -> MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Queues raw bytes for the program.
    pub fn write(&self, bytes: &[u8]) {
        if bytes.is_empty() {
            return;
        }
        let mut st = self.lock();
        if st.outbox.len() + bytes.len() > OUTBOX_LIMIT {
            log::warn!(
                "session {}: input queue full, dropping {} bytes",
                self.id,
                bytes.len()
            );
            return;
        }
        st.outbox.extend(bytes);
        // Typing returns the view to the live screen.
        st.terminal.scroll_to_bottom();
        drop(st);
        self.shared.wake_io();
    }

    /// Sends a non-text key, encoded for the current terminal mode.
    pub fn send_key(&self, key: Key, mods: Modifiers) {
        let bytes = self.lock().terminal.encode_key(key, mods);
        self.write(&bytes);
    }

    /// Sends typed text with modifiers (Ctrl/Alt applied per character).
    pub fn send_text(&self, text: &str, mods: Modifiers) {
        let bytes = self.lock().terminal.encode_text(text, mods);
        self.write(&bytes);
    }

    /// Pastes text, using bracketed paste when the program enabled it.
    pub fn paste(&self, text: &str) {
        let bytes = self.lock().terminal.encode_paste(text);
        self.write(&bytes);
    }

    /// Resizes the terminal grid and the pty (which signals `SIGWINCH`).
    pub fn resize(&self, size: TermSize) {
        let mut st = self.lock();
        if st.terminal.size() == size {
            return;
        }
        st.terminal.resize(size);
        let size = st.terminal.size();
        drop(st);
        let ws = WinSize {
            rows: size.rows,
            cols: size.cols,
            pixel_width: size.cols.saturating_mul(size.cell_width),
            pixel_height: size.rows.saturating_mul(size.cell_height),
        };
        if let Err(e) = self.master.resize(ws) {
            log::warn!("session {}: pty resize failed: {e}", self.id);
        }
        self.shared.changed();
    }

    /// Scrolls the view into history (positive = older).
    pub fn scroll(&self, lines: i32) {
        self.lock().terminal.scroll(lines);
        self.shared.changed();
    }

    /// Copies the visible screen.
    pub fn snapshot(&self) -> Snapshot {
        self.lock().terminal.snapshot()
    }

    /// Copies the visible screen into `snap`, reusing its allocation.
    pub fn snapshot_into(&self, snap: &mut Snapshot) {
        self.lock().terminal.snapshot_into(snap);
    }

    /// Runs `f` with the terminal locked (for selection, search, …).
    pub fn with_terminal<R>(&self, f: impl FnOnce(&mut Terminal) -> R) -> R {
        f(&mut self.lock().terminal)
    }

    /// Takes pending UI events.
    pub fn drain_events(&self) -> Vec<SessionEvent> {
        self.lock().events.drain(..).collect()
    }

    pub fn title(&self) -> Option<String> {
        self.lock().title.clone()
    }

    /// The child's exit status, once it terminated.
    pub fn exit_status(&self) -> Option<ExitStatus> {
        self.lock().exit
    }

    /// Hangs up the child's process group (like closing a terminal window).
    fn hang_up(&self) {
        let st = self.lock();
        // The waiter reaps under this lock, so the pid can't be reused while
        // we hold it and `exit` is still `None`.
        if let (Some(pid), None) = (self.pid, st.exit) {
            // The child is a session leader, so its pid is also its pgid.
            if let Some(pgid) = rustix::process::Pid::from_raw(pid) {
                let _ = rustix::process::kill_process_group(pgid, rustix::process::Signal::HUP);
            }
        }
    }

    /// Reads available output. Returns `false` once the pty hit EOF.
    fn pump_input(&self, buf: &mut [u8]) -> bool {
        let mut total = 0;
        loop {
            match (&self.master).read(buf) {
                Ok(0) => return self.mark_eof(),
                Ok(n) => {
                    let mut st = self.lock();
                    st.terminal.feed(&buf[..n]);
                    let responses = st.terminal.take_responses();
                    st.outbox.extend(responses);
                    for ev in st.terminal.drain_events() {
                        let ev = match ev {
                            TermEvent::Title(t) => {
                                st.title.clone_from(&t);
                                SessionEvent::Title(t)
                            }
                            TermEvent::Bell => SessionEvent::Bell,
                            TermEvent::ClipboardStore(s) => SessionEvent::ClipboardStore(s),
                        };
                        st.events.push_back(ev);
                    }
                    total += n;
                    // Yield to other sessions after a burst.
                    if total >= 1 << 20 {
                        return true;
                    }
                }
                Err(e) if e.kind() == io::ErrorKind::WouldBlock => return true,
                Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
                // EIO: every slave fd closed.
                Err(_) => return self.mark_eof(),
            }
        }
    }

    fn mark_eof(&self) -> bool {
        self.lock().eof = true;
        false
    }

    /// Writes queued input until the pty would block.
    fn flush_output(&self) {
        let mut st = self.lock();
        while !st.outbox.is_empty() {
            let (front, _) = st.outbox.as_slices();
            match (&self.master).write(front) {
                Ok(n) => {
                    st.outbox.drain(..n);
                }
                Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
                Err(e) if e.kind() == io::ErrorKind::WouldBlock => break,
                Err(_) => {
                    st.outbox.clear();
                    break;
                }
            }
        }
    }
}

/// State shared between the manager, sessions and threads.
#[derive(Debug)]
struct Shared {
    sessions: Mutex<HashMap<SessionId, Arc<Session>>>,
    next_id: AtomicU64,
    seq: Mutex<u64>,
    seq_cv: Condvar,
    wake_write: OwnedFd,
    shutdown: AtomicBool,
}

impl Shared {
    fn changed(&self) {
        let mut seq = self.seq.lock().unwrap_or_else(|e| e.into_inner());
        *seq += 1;
        self.seq_cv.notify_all();
    }

    fn wake_io(&self) {
        // A full pipe already means "wake up"; ignore EAGAIN.
        let _ = rustix::io::write(&self.wake_write, &[1]);
    }

    fn sessions(&self) -> MutexGuard<'_, HashMap<SessionId, Arc<Session>>> {
        self.sessions.lock().unwrap_or_else(|e| e.into_inner())
    }
}

/// The session registry and its I/O thread.
#[derive(Debug)]
pub struct SessionManager {
    shared: Arc<Shared>,
    io_thread: Option<JoinHandle<()>>,
}

impl SessionManager {
    /// Creates the manager and starts its I/O thread.
    pub fn new() -> io::Result<Self> {
        use rustix::pipe::{PipeFlags, pipe_with};
        let (wake_read, wake_write) = pipe_with(PipeFlags::CLOEXEC | PipeFlags::NONBLOCK)?;
        let shared = Arc::new(Shared {
            sessions: Mutex::new(HashMap::new()),
            next_id: AtomicU64::new(1),
            seq: Mutex::new(0),
            seq_cv: Condvar::new(),
            wake_write,
            shutdown: AtomicBool::new(false),
        });
        let io_shared = shared.clone();
        let io_thread = std::thread::Builder::new()
            .name("ferrux-io".into())
            .spawn(move || io_loop(&io_shared, wake_read))?;
        Ok(Self {
            shared,
            io_thread: Some(io_thread),
        })
    }

    /// Spawns a program in a new session. The terminal size is taken from
    /// `spawn.size`.
    pub fn spawn(&self, spawn: &SpawnConfig, term: TermConfig) -> Result<Arc<Session>, SpawnError> {
        let (master, child) = ferrux_pty::spawn(spawn)?;
        let size = TermSize::new(spawn.size.rows, spawn.size.cols);
        let session = self
            .register(master, Some(child.pid()), size, term)
            .map_err(SpawnError::Pty)?;
        let waiter_session = session.clone();
        let spawned = std::thread::Builder::new()
            .name(format!("ferrux-wait-{}", session.id))
            .spawn(move || wait_child(&waiter_session, &child));
        if let Err(e) = spawned {
            log::error!("session {}: cannot start waiter thread: {e}", session.id);
        }
        Ok(session)
    }

    /// Adopts an existing pty master without a local child (e.g. one passed
    /// over Binder from the Ferrux service to Ferride).
    pub fn attach(
        &self,
        master: PtyMaster,
        size: TermSize,
        term: TermConfig,
    ) -> io::Result<Arc<Session>> {
        self.register(master, None, size, term)
    }

    fn register(
        &self,
        master: PtyMaster,
        pid: Option<i32>,
        size: TermSize,
        term: TermConfig,
    ) -> io::Result<Arc<Session>> {
        master.set_nonblocking(true)?;
        let id = self.shared.next_id.fetch_add(1, Ordering::Relaxed);
        let session = Arc::new(Session {
            id,
            master,
            pid,
            state: Mutex::new(State {
                terminal: Terminal::new(size, term),
                events: VecDeque::new(),
                outbox: VecDeque::new(),
                exit: None,
                eof: false,
                title: None,
            }),
            shared: self.shared.clone(),
        });
        self.shared.sessions().insert(id, session.clone());
        self.shared.wake_io();
        self.shared.changed();
        Ok(session)
    }

    pub fn get(&self, id: SessionId) -> Option<Arc<Session>> {
        self.shared.sessions().get(&id).cloned()
    }

    /// Ids of all sessions, in creation order.
    pub fn ids(&self) -> Vec<SessionId> {
        let mut ids: Vec<_> = self.shared.sessions().keys().copied().collect();
        ids.sort_unstable();
        ids
    }

    /// Removes a session, hanging up its process group if still running.
    /// The pty closes once the last handle to the session is dropped.
    pub fn close(&self, id: SessionId) -> bool {
        let removed = self.shared.sessions().remove(&id);
        if let Some(session) = &removed {
            session.hang_up();
            self.shared.wake_io();
            self.shared.changed();
        }
        removed.is_some()
    }

    /// Current value of the global change counter.
    pub fn change_seq(&self) -> u64 {
        *self.shared.seq.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Blocks until the change counter differs from `last` or `timeout`
    /// elapses; returns the current value.
    pub fn wait_change(&self, last: u64, timeout: Duration) -> u64 {
        let guard = self.shared.seq.lock().unwrap_or_else(|e| e.into_inner());
        let (guard, _) = self
            .shared
            .seq_cv
            .wait_timeout_while(guard, timeout, |seq| *seq == last)
            .unwrap_or_else(|e| e.into_inner());
        *guard
    }
}

impl Drop for SessionManager {
    fn drop(&mut self) {
        for id in self.ids() {
            self.close(id);
        }
        self.shared.shutdown.store(true, Ordering::SeqCst);
        self.shared.wake_io();
        if let Some(t) = self.io_thread.take() {
            let _ = t.join();
        }
    }
}

/// Waits for the child without reaping, then reaps under the session lock.
fn wait_child(session: &Session, child: &Child) {
    if let Err(e) = child.wait_exited() {
        log::error!("session {}: waitid failed: {e}", session.id);
        return;
    }
    let mut st = session.lock();
    match child.try_wait() {
        Ok(Some(status)) => {
            st.exit = Some(status);
            st.events.push_back(SessionEvent::Exited(status));
        }
        Ok(None) => log::error!("session {}: child not reapable after waitid", session.id),
        Err(e) => log::error!("session {}: waitpid failed: {e}", session.id),
    }
    drop(st);
    session.shared.changed();
}

fn io_loop(shared: &Shared, wake_read: OwnedFd) {
    let mut buf = vec![0u8; 64 * 1024];
    while !shared.shutdown.load(Ordering::SeqCst) {
        let sessions: Vec<Arc<Session>> = shared.sessions().values().cloned().collect();

        let mut fds = Vec::with_capacity(sessions.len() + 1);
        fds.push(PollFd::new(&wake_read, PollFlags::IN));
        let mut deadline: Option<Instant> = None;
        for s in &sessions {
            let st = s.lock();
            let mut flags = PollFlags::empty();
            if !st.eof {
                flags |= PollFlags::IN;
            }
            if !st.outbox.is_empty() {
                flags |= PollFlags::OUT;
            }
            if let Some(d) = st.terminal.sync_deadline() {
                deadline = Some(deadline.map_or(d, |cur| cur.min(d)));
            }
            drop(st);
            fds.push(PollFd::new(&s.master, flags));
        }

        let timeout = deadline.map(|d| {
            let left = d.saturating_duration_since(Instant::now());
            Timespec {
                tv_sec: left.as_secs() as _,
                tv_nsec: left.subsec_nanos() as _,
            }
        });
        match rustix::event::poll(&mut fds, timeout.as_ref()) {
            Ok(_) => {}
            Err(rustix::io::Errno::INTR) => continue,
            Err(e) => {
                log::error!("poll failed: {e}");
                std::thread::sleep(Duration::from_millis(50));
                continue;
            }
        }

        if !fds[0].revents().is_empty() {
            let mut drain = [0u8; 64];
            while matches!(rustix::io::read(&wake_read, &mut drain), Ok(n) if n > 0) {}
        }

        let mut any_change = false;
        for (s, fd) in sessions.iter().zip(&fds[1..]) {
            let rev = fd.revents();
            if rev.intersects(PollFlags::OUT) {
                s.flush_output();
            }
            if rev.intersects(PollFlags::IN | PollFlags::HUP | PollFlags::ERR) {
                s.pump_input(&mut buf);
                // Output may have produced responses (DSR etc.).
                s.flush_output();
                any_change = true;
            }
        }
        if deadline.is_some_and(|d| d <= Instant::now()) {
            for s in &sessions {
                s.lock().terminal.flush_sync_if_expired();
            }
            any_change = true;
        }
        drop(fds);
        if any_change {
            shared.changed();
        }
    }
}

#[cfg(test)]
mod tests;
