// SPDX-License-Identifier: Apache-2.0
//! `ferrux-term`: the terminal emulator core used by Ferrux and Ferride.
//!
//! This crate is a thin, engine-agnostic facade over a VT engine (currently
//! [`alacritty_terminal`]'s grid and parser; only the grid is used, never its
//! `tty` module, which installs a process-global `SIGCHLD` handler).
//!
//! * [`Terminal::feed`] parses bytes coming from the program.
//! * [`Terminal::snapshot`] returns a renderer-friendly copy of the screen.
//! * [`Terminal::take_responses`] returns bytes the terminal must send back to
//!   the program (device status reports, color queries, …).
//! * [`Terminal::drain_events`] returns UI events (title, bell, clipboard).
//! * [`keys`] encodes keyboard input and pastes.
//!
//! The crate has no platform dependencies and no I/O.

pub mod color;
pub mod keys;
pub mod snapshot;

use std::sync::{Arc, Mutex};
use std::time::Instant;

use alacritty_terminal::event::{Event as EngineEvent, EventListener, WindowSize};
use alacritty_terminal::grid::{Dimensions, Scroll};
use alacritty_terminal::term::cell::Flags;
use alacritty_terminal::term::{self, Term, TermDamage, TermMode};
use alacritty_terminal::vte::ansi::{self, Color, CursorShape as EngineCursorShape, NamedColor};

pub use color::{Palette, Rgb};
pub use keys::{Key, KeyMode, Modifiers};
pub use snapshot::{Cell, CellFlags, Cursor, CursorShape, Modes, Snapshot};

/// Terminal dimensions.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TermSize {
    pub rows: u16,
    pub cols: u16,
    /// Cell size in pixels, reported to programs that ask (CSI 14 t). 0 if
    /// unknown.
    pub cell_width: u16,
    pub cell_height: u16,
}

impl TermSize {
    pub const fn new(rows: u16, cols: u16) -> Self {
        Self {
            rows,
            cols,
            cell_width: 0,
            cell_height: 0,
        }
    }

    fn clamped(self) -> Self {
        Self {
            rows: self.rows.max(term::MIN_SCREEN_LINES as u16),
            cols: self.cols.max(term::MIN_COLUMNS as u16),
            ..self
        }
    }
}

impl Default for TermSize {
    fn default() -> Self {
        Self::new(24, 80)
    }
}

/// Adapter so the engine can read our size.
struct EngineSize(TermSize);

impl Dimensions for EngineSize {
    fn total_lines(&self) -> usize {
        self.screen_lines()
    }
    fn screen_lines(&self) -> usize {
        self.0.rows as usize
    }
    fn columns(&self) -> usize {
        self.0.cols as usize
    }
}

/// Terminal configuration.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TermConfig {
    /// Lines of scrollback history.
    pub scrollback: usize,
    pub palette: Palette,
    /// Render bold text in the bright variant of ANSI colors 0–7.
    pub bold_is_bright: bool,
}

impl Default for TermConfig {
    fn default() -> Self {
        Self {
            scrollback: 10_000,
            palette: Palette::default(),
            bold_is_bright: true,
        }
    }
}

/// Events for the UI layer.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TermEvent {
    /// Window title set by OSC 0/2; `None` resets to the default title.
    Title(Option<String>),
    /// BEL.
    Bell,
    /// OSC 52 copy request.
    ClipboardStore(String),
}

/// Lines changed since the last [`Terminal::take_damage`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Damage {
    /// Redraw everything.
    Full,
    /// Only these viewport rows changed (may be empty).
    Rows(Vec<usize>),
}

/// Collects engine events; the engine calls it synchronously while parsing.
#[derive(Clone, Default)]
struct Listener(Arc<Mutex<Vec<EngineEvent>>>);

impl EventListener for Listener {
    fn send_event(&self, event: EngineEvent) {
        match event {
            // High-frequency or irrelevant here.
            EngineEvent::MouseCursorDirty
            | EngineEvent::Wakeup
            | EngineEvent::CursorBlinkingChange => {}
            event => self.0.lock().unwrap_or_else(|e| e.into_inner()).push(event),
        }
    }
}

/// A terminal emulator instance: screen state plus parser.
pub struct Terminal {
    term: Term<Listener>,
    parser: ansi::Processor,
    listener: Listener,
    size: TermSize,
    config: TermConfig,
    events: Vec<TermEvent>,
    responses: Vec<u8>,
    title: Option<String>,
}

impl std::fmt::Debug for Terminal {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Terminal")
            .field("size", &self.size)
            .finish_non_exhaustive()
    }
}

impl Terminal {
    pub fn new(size: TermSize, config: TermConfig) -> Self {
        let size = size.clamped();
        let listener = Listener::default();
        let engine_config = term::Config {
            scrolling_history: config.scrollback,
            ..term::Config::default()
        };
        let term = Term::new(engine_config, &EngineSize(size), listener.clone());
        Self {
            term,
            parser: ansi::Processor::new(),
            listener,
            size,
            config,
            events: Vec::new(),
            responses: Vec::new(),
            title: None,
        }
    }

    pub fn size(&self) -> TermSize {
        self.size
    }

    /// Parses output from the program.
    pub fn feed(&mut self, bytes: &[u8]) {
        self.parser.advance(&mut self.term, bytes);
        self.flush_sync_if_expired();
        self.collect_engine_events();
    }

    /// When the program uses synchronized updates (DECSET 2026), output is
    /// buffered until the update ends or this deadline passes. The I/O loop
    /// should call [`Terminal::flush_sync_if_expired`] at that time.
    pub fn sync_deadline(&self) -> Option<Instant> {
        self.parser.sync_timeout().sync_timeout()
    }

    /// Applies a buffered synchronized update whose deadline has passed.
    pub fn flush_sync_if_expired(&mut self) {
        if self.sync_deadline().is_some_and(|t| t <= Instant::now()) {
            self.parser.stop_sync(&mut self.term);
            self.collect_engine_events();
        }
    }

    /// Changes the grid size, reflowing content. Does not touch the PTY; the
    /// caller resizes that too.
    pub fn resize(&mut self, size: TermSize) {
        let size = size.clamped();
        self.size = size;
        self.term.resize(EngineSize(size));
    }

    /// Scrolls the view into history by `lines` (positive = older output).
    pub fn scroll(&mut self, lines: i32) {
        self.term.scroll_display(Scroll::Delta(lines));
        self.collect_engine_events();
    }

    /// Returns the view to the live screen.
    pub fn scroll_to_bottom(&mut self) {
        if self.term.grid().display_offset() != 0 {
            self.term.scroll_display(Scroll::Bottom);
            self.collect_engine_events();
        }
    }

    pub fn display_offset(&self) -> usize {
        self.term.grid().display_offset()
    }

    /// Current title, if the program set one.
    pub fn title(&self) -> Option<String> {
        // The engine keeps its title private, so it is tracked from events.
        self.title.clone()
    }

    pub fn modes(&self) -> Modes {
        let m = *self.term.mode();
        Modes {
            app_cursor: m.contains(TermMode::APP_CURSOR),
            bracketed_paste: m.contains(TermMode::BRACKETED_PASTE),
            alt_screen: m.contains(TermMode::ALT_SCREEN),
            mouse_reporting: m.intersects(TermMode::MOUSE_MODE),
        }
    }

    /// Bytes to send for a non-text key in the current mode.
    pub fn encode_key(&self, key: Key, mods: Modifiers) -> Vec<u8> {
        keys::encode_key(
            key,
            mods,
            KeyMode {
                app_cursor: self.modes().app_cursor,
            },
        )
    }

    /// Bytes to send for typed text.
    pub fn encode_text(&self, text: &str, mods: Modifiers) -> Vec<u8> {
        keys::encode_text(text, mods)
    }

    /// Bytes to send for a paste, honouring bracketed-paste mode.
    pub fn encode_paste(&self, text: &str) -> Vec<u8> {
        keys::encode_paste(text, self.modes().bracketed_paste)
    }

    /// Takes the UI events produced since the last call.
    pub fn drain_events(&mut self) -> Vec<TermEvent> {
        std::mem::take(&mut self.events)
    }

    /// Takes bytes the terminal must write back to the program.
    pub fn take_responses(&mut self) -> Vec<u8> {
        std::mem::take(&mut self.responses)
    }

    /// Takes the set of rows changed since the last call.
    pub fn take_damage(&mut self) -> Damage {
        let damage = match self.term.damage() {
            TermDamage::Full => Damage::Full,
            TermDamage::Partial(lines) => Damage::Rows(lines.map(|l| l.line).collect()),
        };
        self.term.reset_damage();
        damage
    }

    /// Copies the visible screen.
    pub fn snapshot(&self) -> Snapshot {
        let mut snap = Snapshot::default();
        self.snapshot_into(&mut snap);
        snap
    }

    /// Copies the visible screen into `snap`, reusing its allocation.
    pub fn snapshot_into(&self, snap: &mut Snapshot) {
        let content = self.term.renderable_content();
        let rows = self.term.screen_lines();
        let cols = self.term.columns();
        let palette = &self.config.palette;
        let colors = content.colors;
        let lookup = |index: usize, fallback: Rgb| colors[index].map(Rgb::from).unwrap_or(fallback);
        let default_fg = lookup(NamedColor::Foreground as usize, palette.foreground);
        let default_bg = lookup(NamedColor::Background as usize, palette.background);

        snap.rows = rows;
        snap.cols = cols;
        snap.display_offset = content.display_offset;
        snap.default_fg = default_fg;
        snap.default_bg = default_bg;
        snap.modes = self.modes();
        snap.cells.clear();
        snap.cells.resize(
            rows * cols,
            Cell {
                fg: default_fg,
                bg: default_bg,
                ..Cell::default()
            },
        );

        for indexed in content.display_iter {
            let Some(point) = term::point_to_viewport(content.display_offset, indexed.point) else {
                continue;
            };
            if point.line >= rows || point.column.0 >= cols {
                continue;
            }
            let src = indexed.cell;
            snap.cells[point.line * cols + point.column.0] = self.convert_cell(src);
        }

        let cursor = content.cursor;
        let shape = match cursor.shape {
            EngineCursorShape::Block => CursorShape::Block,
            EngineCursorShape::Underline => CursorShape::Underline,
            EngineCursorShape::Beam => CursorShape::Bar,
            EngineCursorShape::HollowBlock => CursorShape::HollowBlock,
            EngineCursorShape::Hidden => CursorShape::Hidden,
        };
        let viewport = term::point_to_viewport(content.display_offset, cursor.point);
        snap.cursor = match viewport {
            Some(p) if p.line < rows && p.column.0 < cols => Cursor {
                row: p.line,
                col: p.column.0,
                shape,
                color: lookup(NamedColor::Cursor as usize, palette.cursor),
            },
            _ => Cursor {
                shape: CursorShape::Hidden,
                ..Cursor::default()
            },
        };
    }

    fn convert_cell(&self, src: &term::cell::Cell) -> Cell {
        let f = src.flags;
        let mut fg = self.resolve(src.fg, f, true);
        let mut bg = self.resolve(src.bg, f, false);
        if f.contains(Flags::INVERSE) {
            std::mem::swap(&mut fg, &mut bg);
        }
        let mut flags = CellFlags::empty();
        for (engine, ours) in [
            (Flags::BOLD, CellFlags::BOLD),
            (Flags::ITALIC, CellFlags::ITALIC),
            (Flags::STRIKEOUT, CellFlags::STRIKEOUT),
            (Flags::DIM, CellFlags::DIM),
            (Flags::WIDE_CHAR, CellFlags::WIDE),
            (Flags::HIDDEN, CellFlags::HIDDEN),
            (Flags::DOUBLE_UNDERLINE, CellFlags::DOUBLE_UNDERLINE),
            (Flags::UNDERCURL, CellFlags::UNDERCURL),
        ] {
            if f.contains(engine) {
                flags.insert(ours);
            }
        }
        if f.intersects(Flags::UNDERLINE | Flags::DOTTED_UNDERLINE | Flags::DASHED_UNDERLINE) {
            flags.insert(CellFlags::UNDERLINE);
        }
        let spacer = f.intersects(Flags::WIDE_CHAR_SPACER | Flags::LEADING_WIDE_CHAR_SPACER);
        if spacer {
            flags.insert(CellFlags::WIDE_SPACER);
        }
        let ch = if spacer || f.contains(Flags::HIDDEN) || src.c == '\0' {
            ' '
        } else {
            src.c
        };
        Cell { ch, fg, bg, flags }
    }

    /// Resolves an engine color to RGB, applying bold-is-bright and dim.
    fn resolve(&self, color: Color, flags: Flags, is_fg: bool) -> Rgb {
        let palette = &self.config.palette;
        let colors = self.term.colors();
        let indexed = |i: usize| colors[i].map(Rgb::from).unwrap_or(palette.indexed[i]);
        let brighten = is_fg && self.config.bold_is_bright && flags.contains(Flags::BOLD);
        let rgb = match color {
            Color::Spec(rgb) => rgb.into(),
            Color::Indexed(i) if brighten && i < 8 => indexed(i as usize + 8),
            Color::Indexed(i) => indexed(i as usize),
            Color::Named(n) => {
                let i = n as usize;
                match n {
                    _ if i < 8 && brighten => indexed(i + 8),
                    _ if i < 16 => indexed(i),
                    NamedColor::Foreground
                    | NamedColor::BrightForeground
                    | NamedColor::DimForeground => colors[NamedColor::Foreground]
                        .map(Rgb::from)
                        .unwrap_or(palette.foreground),
                    NamedColor::Background => colors[NamedColor::Background]
                        .map(Rgb::from)
                        .unwrap_or(palette.background),
                    NamedColor::Cursor => colors[NamedColor::Cursor]
                        .map(Rgb::from)
                        .unwrap_or(palette.cursor),
                    // Dim variants of the ANSI colors (259..267).
                    _ => {
                        let base = i.saturating_sub(NamedColor::DimBlack as usize).min(7);
                        return indexed(base).dim();
                    }
                }
            }
        };
        if is_fg && flags.contains(Flags::DIM) {
            rgb.dim()
        } else {
            rgb
        }
    }

    /// Translates queued engine events into responses and UI events.
    fn collect_engine_events(&mut self) {
        let queued =
            std::mem::take(&mut *self.listener.0.lock().unwrap_or_else(|e| e.into_inner()));
        for event in queued {
            match event {
                EngineEvent::PtyWrite(text) => self.responses.extend_from_slice(text.as_bytes()),
                EngineEvent::Title(title) => {
                    self.title = Some(title.clone());
                    self.events.push(TermEvent::Title(Some(title)));
                }
                EngineEvent::ResetTitle => {
                    self.title = None;
                    self.events.push(TermEvent::Title(None));
                }
                EngineEvent::Bell => self.events.push(TermEvent::Bell),
                EngineEvent::ClipboardStore(_, text) => {
                    self.events.push(TermEvent::ClipboardStore(text))
                }
                EngineEvent::ColorRequest(index, format) => {
                    let palette = &self.config.palette;
                    let fallback = match index {
                        0..=255 => palette.indexed[index],
                        256 => palette.foreground,
                        257 => palette.background,
                        _ => palette.cursor,
                    };
                    let rgb = self.term.colors()[index].map(Rgb::from).unwrap_or(fallback);
                    self.responses
                        .extend_from_slice(format(rgb.into()).as_bytes());
                }
                EngineEvent::TextAreaSizeRequest(format) => {
                    let s = self.size;
                    let window = WindowSize {
                        num_lines: s.rows,
                        num_cols: s.cols,
                        cell_width: s.cell_width,
                        cell_height: s.cell_height,
                    };
                    self.responses.extend_from_slice(format(window).as_bytes());
                }
                // OSC 52 paste is disabled by config (copy-only); child exit
                // is tracked by ferrux-pty, not the engine.
                _ => {}
            }
        }
    }
}

#[cfg(test)]
mod tests;
