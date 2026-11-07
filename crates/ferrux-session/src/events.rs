// SPDX-License-Identifier: Apache-2.0
//! A bounded, coalescing per-session event queue.
//!
//! A program can produce UI events much faster than a UI drains them (a
//! `printf '\a'` loop while the app is in the background), so the queue
//! never holds more than one of each "latest state wins" event:
//!
//! * `Bell`: one pending bell stands for any number of rings.
//! * `Title`: only the newest title is kept.
//! * `ClipboardStore`: only the newest copy request is kept (the clipboard
//!   would end up holding it anyway).
//!
//! `Exited` and `Failed` are never coalesced or dropped. As a backstop the
//! queue is capped at [`EVENT_LIMIT`]: when full, the oldest droppable event
//! makes room, and if every queued event is terminal, a new droppable event is
//! discarded instead.
//!
//! Screen updates are not events at all: they bump the manager's change
//! counter, which coalesces by construction.

use std::collections::VecDeque;

use crate::SessionEvent;

/// Upper bound on queued events per session.
pub(crate) const EVENT_LIMIT: usize = 64;

#[derive(Debug)]
pub(crate) struct EventQueue {
    queue: VecDeque<SessionEvent>,
    limit: usize,
}

impl Default for EventQueue {
    fn default() -> Self {
        Self::with_limit(EVENT_LIMIT)
    }
}

impl SessionEvent {
    /// Whether the event may be dropped when the queue is full: everything
    /// except the session's terminal events.
    fn droppable(&self) -> bool {
        !matches!(self, SessionEvent::Exited(_) | SessionEvent::Failed(_))
    }
}

impl EventQueue {
    pub(crate) fn with_limit(limit: usize) -> Self {
        Self {
            queue: VecDeque::new(),
            limit: limit.max(1),
        }
    }

    pub(crate) fn push(&mut self, ev: SessionEvent) {
        match &ev {
            SessionEvent::Bell if self.queue.contains(&SessionEvent::Bell) => return,
            SessionEvent::Title(_) => self.queue.retain(|e| !matches!(e, SessionEvent::Title(_))),
            SessionEvent::ClipboardStore(_) => self
                .queue
                .retain(|e| !matches!(e, SessionEvent::ClipboardStore(_))),
            _ => {}
        }
        if self.queue.len() >= self.limit {
            match self.queue.iter().position(SessionEvent::droppable) {
                Some(oldest) => {
                    self.queue.remove(oldest);
                }
                None if ev.droppable() => return,
                // Terminal events are always kept, even over the limit.
                None => {}
            }
        }
        self.queue.push_back(ev);
    }

    pub(crate) fn drain(&mut self) -> Vec<SessionEvent> {
        self.queue.drain(..).collect()
    }

    #[cfg(test)]
    pub(crate) fn len(&self) -> usize {
        self.queue.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ferrux_pty::ExitStatus;

    fn title(t: &str) -> SessionEvent {
        SessionEvent::Title(Some(t.into()))
    }

    #[test]
    fn bells_coalesce() {
        let mut q = EventQueue::default();
        for _ in 0..10_000 {
            q.push(SessionEvent::Bell);
        }
        assert_eq!(q.drain(), [SessionEvent::Bell]);
        q.push(SessionEvent::Bell);
        assert_eq!(
            q.drain(),
            [SessionEvent::Bell],
            "a drained bell rings again"
        );
    }

    #[test]
    fn latest_title_and_clipboard_win() {
        let mut q = EventQueue::default();
        q.push(title("a"));
        q.push(SessionEvent::ClipboardStore("one".into()));
        q.push(SessionEvent::Bell);
        q.push(title("b"));
        q.push(SessionEvent::Title(None));
        q.push(SessionEvent::ClipboardStore("two".into()));
        assert_eq!(
            q.drain(),
            [
                SessionEvent::Bell,
                SessionEvent::Title(None),
                SessionEvent::ClipboardStore("two".into()),
            ]
        );
    }

    #[test]
    fn cap_drops_oldest_droppable_and_keeps_terminal_events() {
        let mut q = EventQueue::with_limit(2);
        q.push(SessionEvent::Bell);
        q.push(SessionEvent::Exited(ExitStatus::Exited(0)));
        q.push(title("t")); // full: the bell goes
        assert_eq!(q.len(), 2);
        q.push(SessionEvent::Failed("boom".into())); // full: the title goes
        q.push(SessionEvent::Bell); // full of terminal events: dropped
        q.push(SessionEvent::Failed("again".into())); // terminal: kept anyway
        assert_eq!(
            q.drain(),
            [
                SessionEvent::Exited(ExitStatus::Exited(0)),
                SessionEvent::Failed("boom".into()),
                SessionEvent::Failed("again".into()),
            ]
        );
    }
}
