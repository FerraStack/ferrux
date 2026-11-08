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
pub mod mouse;
pub mod selection;
pub mod snapshot;
pub mod urls;

use std::sync::{Arc, Mutex};
use std::time::Instant;

use alacritty_terminal::event::{Event as EngineEvent, EventListener, WindowSize};
use alacritty_terminal::grid::{Dimensions, Scroll};
use alacritty_terminal::index::{Column, Line, Point, Side};
use alacritty_terminal::selection::{Selection, SelectionType};
use alacritty_terminal::term::cell::{Flags, Hyperlink};
use alacritty_terminal::term::{self, Term, TermDamage, TermMode};
use alacritty_terminal::vte::ansi::{self, Color, CursorShape as EngineCursorShape, NamedColor};
use alacritty_terminal::vte::{self, Params};

pub use color::{Palette, Rgb};
pub use keys::{Key, KeyMode, Modifiers};
pub use mouse::{MouseAction, MouseButton, MouseMode};
pub use selection::{SelectionKind, SelectionSpan, ViewPoint};
pub use snapshot::{
    Cell, CellFlags, Combining, Cursor, CursorShape, Link, LinkKind, Modes, Snapshot,
};

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
    /// Characters that end a word for word selection (double tap, long
    /// press). See [`WORD_SEPARATORS`].
    pub word_separators: String,
}

/// Default word separators: like alacritty's, but without `:`, so a long
/// press selects a whole URL or `src/main.rs:10:5` rather than a fragment.
pub const WORD_SEPARATORS: &str = ",│`|\"' ()[]{}<>\t";

impl Default for TermConfig {
    fn default() -> Self {
        Self {
            scrollback: 10_000,
            palette: Palette::default(),
            bold_is_bright: true,
            word_separators: WORD_SEPARATORS.to_owned(),
        }
    }
}

/// Largest OSC 52 clipboard write passed on to the UI (decoded UTF-8 bytes).
pub const MAX_CLIPBOARD_BYTES: usize = 1 << 20;

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
    /// History lines (the newest ones) that a taller screen may pull back into
    /// view; see [`Terminal::resize`].
    pullable: usize,
    /// Spots screen clears in the output, alongside the engine's parser.
    clears: vte::Parser,
    clear_seen: ClearDetector,
}

/// Records whether the output cleared the screen: ED 2/3 (`CSI 2 J`, as sent
/// by `clear`; `CSI 3 J`) or RIS (`ESC c`).
#[derive(Debug, Default)]
struct ClearDetector(bool);

impl vte::Perform for ClearDetector {
    fn csi_dispatch(&mut self, params: &Params, _: &[u8], _: bool, action: char) {
        // The engine only reads the first parameter.
        if action == 'J' && matches!(params.iter().next(), Some([2 | 3, ..])) {
            self.0 = true;
        }
    }

    fn esc_dispatch(&mut self, intermediates: &[u8], _: bool, byte: u8) {
        if byte == b'c' && intermediates.is_empty() {
            self.0 = true;
        }
    }
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
            semantic_escape_chars: config.word_separators.clone(),
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
            pullable: 0,
            clears: vte::Parser::new(),
            clear_seen: ClearDetector::default(),
        }
    }

    pub fn size(&self) -> TermSize {
        self.size
    }

    /// Parses output from the program.
    pub fn feed(&mut self, bytes: &[u8]) {
        let history = self.history_size();
        self.clears.advance(&mut self.clear_seen, bytes);
        self.parser.advance(&mut self.term, bytes);
        self.flush_sync_if_expired();
        self.collect_engine_events();
        let cleared = std::mem::take(&mut self.clear_seen.0);
        self.track_history(history, cleared);
    }

    /// Updates [`Terminal::pullable`] after output: lines that scrolled into
    /// the history may come back on a taller screen, but nothing from before a
    /// clear. (A clear in the middle of a chunk also drops what scrolled after
    /// it; that only leaves blank rows at the bottom on the next grow.)
    fn track_history(&mut self, before: usize, cleared: bool) {
        // The alternate screen has no history; the primary one's is unchanged.
        if self.term.mode().contains(TermMode::ALT_SCREEN) {
            return;
        }
        let now = self.history_size();
        if cleared {
            self.pullable = 0;
        } else {
            self.pullable += now.saturating_sub(before);
        }
        self.pullable = self.pullable.min(now);
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
    ///
    /// Like xterm, a shorter screen pushes lines off the top into the history
    /// (as far as needed to keep the cursor visible) and a taller one pulls
    /// them back, so opening and closing the soft keyboard leaves the screen as
    /// it was. Unlike the engine on its own, it never pulls back lines from
    /// before a screen clear: `clear` with the keyboard up, then hiding the
    /// keyboard, keeps the screen clear (#30).
    pub fn resize(&mut self, size: TermSize) {
        let size = size.clamped();
        self.size = size;
        let alt = self.term.mode().contains(TermMode::ALT_SCREEN);
        let history = self.history_size();
        self.term.resize(EngineSize(size));
        if alt {
            return;
        }
        let now = self.history_size();
        if now >= history {
            self.pullable += now - history;
            return;
        }
        let pulled = history - now;
        let excess = pulled.saturating_sub(self.pullable);
        self.pullable -= pulled - excess;
        if excess > 0 {
            // Put the lines the engine pulled from beyond the limit back: they
            // are the top rows; the cursor moved down by as many.
            let rows = self.term.screen_lines();
            let grid = self.term.grid_mut();
            grid.scroll_up(&(Line(0)..Line(rows as i32)), excess);
            grid.cursor.point.line = Line((grid.cursor.point.line.0 - excess as i32).max(0));
            grid.saved_cursor.point.line =
                Line((grid.saved_cursor.point.line.0 - excess as i32).max(0));
        }
    }

    /// History lines a taller screen may pull back into view (for tests).
    #[cfg(test)]
    fn pullable(&self) -> usize {
        self.pullable
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

    /// Lines of scrollback history above the screen.
    pub fn history_size(&self) -> usize {
        self.term.grid().history_size()
    }

    /// Handles a scroll gesture (touch drag, fling or mouse wheel) of
    /// `lines` at a viewport cell; positive `lines` go towards older output.
    ///
    /// * Mouse reporting on: returns one wheel event per line for the program.
    /// * Alternate screen with DECSET 1007 (the default): returns Up/Down
    ///   arrow keys, so `less`, `vim` or `htop` scroll themselves.
    /// * Otherwise scrolls the view through history and returns nothing.
    ///
    /// The caller writes the returned bytes to the program.
    pub fn scroll_gesture(&mut self, lines: i32, row: usize, col: usize) -> Vec<u8> {
        if lines == 0 {
            return Vec::new();
        }
        let count = lines.unsigned_abs() as usize;
        let modes = self.modes();
        if modes.mouse_reporting {
            let button = if lines > 0 {
                MouseButton::WheelUp
            } else {
                MouseButton::WheelDown
            };
            let event = self.encode_mouse(button, MouseAction::Press, row, col, Modifiers::NONE);
            return event.repeat(count);
        }
        if modes.alt_screen {
            if !modes.alternate_scroll {
                return Vec::new();
            }
            let key = if lines > 0 { Key::Up } else { Key::Down };
            return self.encode_key(key, Modifiers::NONE).repeat(count);
        }
        self.scroll(lines);
        Vec::new()
    }

    /// The mouse events the program asked for, and their encoding.
    pub fn mouse_mode(&self) -> MouseMode {
        let m = *self.term.mode();
        MouseMode {
            click: m.contains(TermMode::MOUSE_REPORT_CLICK),
            drag: m.contains(TermMode::MOUSE_DRAG),
            motion: m.contains(TermMode::MOUSE_MOTION),
            sgr: m.contains(TermMode::SGR_MOUSE),
            utf8: m.contains(TermMode::UTF8_MOUSE),
        }
    }

    /// Bytes for a mouse event at a viewport cell, or nothing when the
    /// program doesn't want it (see [`mouse::encode_mouse`]).
    pub fn encode_mouse(
        &self,
        button: MouseButton,
        action: MouseAction,
        row: usize,
        col: usize,
        mods: Modifiers,
    ) -> Vec<u8> {
        let col = col.min(self.size.cols.saturating_sub(1) as usize);
        let row = row.min(self.size.rows.saturating_sub(1) as usize);
        mouse::encode_mouse(button, action, col, row, mods, self.mouse_mode())
    }

    // ------------------------------------------------------------ selection

    /// Converts a viewport point to a grid point, clamped to the buffer.
    fn grid_point(&self, at: ViewPoint) -> Point {
        let offset = self.term.grid().display_offset() as i32;
        let line = Line(at.row.saturating_sub(offset))
            .max(self.term.topmost_line())
            .min(self.term.bottommost_line());
        let column = Column(at.col.min(self.term.columns().saturating_sub(1)));
        Point::new(line, column)
    }

    fn view_point(&self, point: Point) -> ViewPoint {
        let offset = self.term.grid().display_offset() as i32;
        ViewPoint::new(point.line.0 + offset, point.column.0)
    }

    /// Starts a selection at a viewport cell. A [`SelectionKind::Word`] or
    /// [`SelectionKind::Line`] selection immediately covers the word or line
    /// there; the others cover that one cell.
    pub fn start_selection(&mut self, kind: SelectionKind, at: ViewPoint) {
        let ty = match kind {
            SelectionKind::Simple => SelectionType::Simple,
            SelectionKind::Word => SelectionType::Semantic,
            SelectionKind::Line => SelectionType::Lines,
            SelectionKind::Block => SelectionType::Block,
        };
        let point = self.grid_point(at);
        let mut selection = Selection::new(ty, point, Side::Left);
        selection.update(point, Side::Right);
        self.term.selection = Some(selection);
    }

    /// Moves the free end of the selection (the anchor stays where the
    /// selection started). Word and line selections keep expanding to whole
    /// words or lines. Both end cells are included.
    pub fn update_selection(&mut self, to: ViewPoint) {
        let point = self.grid_point(to);
        if let Some(selection) = self.term.selection.as_mut() {
            selection.update(point, Side::Right);
            selection.include_all();
        }
    }

    /// Replaces the selection with the cells from `start` to `end`, both
    /// inclusive and in either order (dragging a selection handle).
    pub fn set_selection(&mut self, start: ViewPoint, end: ViewPoint, block: bool) {
        let ty = if block {
            SelectionType::Block
        } else {
            SelectionType::Simple
        };
        let mut selection = Selection::new(ty, self.grid_point(start), Side::Left);
        selection.update(self.grid_point(end), Side::Right);
        selection.include_all();
        self.term.selection = Some(selection);
    }

    /// Selects everything: the scrollback history and the screen, down to the
    /// last line with content (or the cursor line), not the blank rows below.
    pub fn select_all(&mut self) {
        let start = Point::new(self.term.topmost_line(), Column(0));
        let grid = self.term.grid();
        let cursor_line = grid.cursor.point.line;
        let mut last = self.term.bottommost_line();
        while last > cursor_line && grid[last].is_clear() {
            last -= 1;
        }
        let end = Point::new(last, self.term.last_column());
        let mut selection = Selection::new(SelectionType::Simple, start, Side::Left);
        selection.update(end, Side::Right);
        self.term.selection = Some(selection);
    }

    pub fn clear_selection(&mut self) {
        self.term.selection = None;
    }

    /// The selected cells in viewport coordinates, if anything is selected.
    /// The selection disappears by itself when the program overwrites it.
    pub fn selection(&self) -> Option<SelectionSpan> {
        let range = self.term.selection.as_ref()?.to_range(&self.term)?;
        Some(SelectionSpan {
            start: self.view_point(range.start),
            end: self.view_point(range.end),
            block: range.is_block,
        })
    }

    /// The selected text, soft-wrapped lines rejoined. Trailing whitespace
    /// and newlines are dropped, so pasting a copied line into a shell never
    /// runs it by accident. `None` when nothing (or only blanks) is selected.
    pub fn selection_text(&self) -> Option<String> {
        let text = self.term.selection_to_string()?;
        let trimmed = text.trim_end();
        (!trimmed.is_empty()).then(|| trimmed.to_owned())
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
            alternate_scroll: m.contains(TermMode::ALTERNATE_SCROLL),
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
        snap.history = self.term.grid().history_size();
        snap.default_fg = default_fg;
        snap.default_bg = default_bg;
        snap.modes = self.modes();
        snap.cells.clear();
        snap.combining.clear();
        snap.links.clear();
        snap.selection = self.selection();
        let selection = content.selection;
        let mut last_link: Option<Hyperlink> = None;
        snap.cells.resize(
            rows * cols,
            Cell {
                fg: default_fg,
                bg: default_bg,
                ..Cell::default()
            },
        );

        // display_iter walks the grid in row-major order, so `combining`
        // ends up sorted by cell index.
        for indexed in content.display_iter {
            let Some(point) = term::point_to_viewport(content.display_offset, indexed.point) else {
                continue;
            };
            if point.line >= rows || point.column.0 >= cols {
                continue;
            }
            let src = indexed.cell;
            let index = point.line * cols + point.column.0;
            let mut cell = self.convert_cell(src);
            if let Some(chars) = src.zerowidth().filter(|z| !z.is_empty() && cell.ch != ' ') {
                cell.flags.insert(CellFlags::COMBINING);
                snap.combining.push(Combining {
                    cell: index,
                    chars: chars.to_vec(),
                });
            }
            if selection.is_some_and(|s| s.contains(indexed.point)) {
                cell.flags.insert(CellFlags::SELECTED);
                cell.bg = palette.selection;
                // Select both halves of a wide character.
                if cell.flags.contains(CellFlags::WIDE_SPACER)
                    && point.column.0 > 0
                    && let Some(lead) = snap.cells.get_mut(index - 1)
                    && lead.flags.contains(CellFlags::WIDE)
                {
                    lead.flags.insert(CellFlags::SELECTED);
                    lead.bg = palette.selection;
                }
            } else if index > 0
                && cell.flags.contains(CellFlags::WIDE_SPACER)
                && snap.cells[index - 1].flags.contains(CellFlags::SELECTED)
            {
                cell.flags.insert(CellFlags::SELECTED);
                cell.bg = palette.selection;
            }
            if let Some(link) = src.hyperlink() {
                cell.flags.insert(CellFlags::HYPERLINK);
                match snap.links.last_mut() {
                    Some(prev) if prev.end == index && last_link.as_ref() == Some(&link) => {
                        prev.end = index + 1;
                    }
                    _ => {
                        snap.links.push(Link {
                            start: index,
                            end: index + 1,
                            kind: LinkKind::Hyperlink,
                            uri: link.uri().to_owned(),
                        });
                        last_link = Some(link);
                    }
                }
            }
            snap.cells[index] = cell;
        }
        detect_urls(snap);

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
            (Flags::WRAPLINE, CellFlags::WRAPPED),
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
                // A program flooding the clipboard is dropped, not truncated: a
                // partial copy would be silently wrong.
                EngineEvent::ClipboardStore(_, text) if text.len() > MAX_CLIPBOARD_BYTES => {
                    log::warn!(
                        "OSC 52: ignoring a {} byte clipboard write (limit {MAX_CLIPBOARD_BYTES})",
                        text.len()
                    );
                }
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

/// Adds the URLs found in the snapshot's text to its links, flagging their
/// cells with [`CellFlags::URL`]. Lines are scanned whole, across soft wraps;
/// text that is already an OSC 8 hyperlink is skipped.
fn detect_urls(snap: &mut Snapshot) {
    let (rows, cols) = (snap.rows, snap.cols);
    if rows == 0 || cols == 0 {
        return;
    }
    let explicit = snap.links.len();
    let mut chars = Vec::with_capacity(cols);
    let mut cells = Vec::with_capacity(cols);
    let mut row = 0;
    while row < rows {
        // One logical line: this row and the rows it soft-wraps into.
        chars.clear();
        cells.clear();
        loop {
            for col in 0..cols {
                let index = row * cols + col;
                let cell = &snap.cells[index];
                if !cell.flags.contains(CellFlags::WIDE_SPACER) {
                    chars.push(cell.ch);
                    cells.push(index);
                }
            }
            let wraps = snap.row_wraps(row);
            row += 1;
            if !wraps || row >= rows {
                break;
            }
        }
        // Cheap pre-check: every URL prefix has a ':' or starts with "www.".
        if !chars.iter().any(|&c| c == ':' || c == '.') {
            continue;
        }
        for url in urls::find_urls(&chars) {
            let first = cells[url.range.start];
            let last = cells[url.range.end - 1];
            let end = last
                + if snap.cells[last].flags.contains(CellFlags::WIDE) {
                    2
                } else {
                    1
                };
            if snap.cells[first..end]
                .iter()
                .any(|c| c.flags.contains(CellFlags::HYPERLINK))
            {
                continue;
            }
            for cell in &mut snap.cells[first..end] {
                cell.flags.insert(CellFlags::URL);
            }
            snap.links.push(Link {
                start: first,
                end,
                kind: LinkKind::Url,
                uri: url.uri,
            });
        }
    }
    if snap.links.len() > explicit {
        snap.links.sort_by_key(|l| l.start);
    }
}

#[cfg(test)]
mod tests;
