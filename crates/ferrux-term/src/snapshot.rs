// SPDX-License-Identifier: Apache-2.0
//! Renderer-facing copy of the visible screen.
//!
//! A [`Snapshot`] is plain data: every color is resolved to RGB, reverse video
//! is already applied, and wide characters are marked. Renderers (the Kotlin
//! `TerminalView`, a future wgpu renderer, Ferride) need no knowledge of the
//! VT engine behind it.

use crate::color::Rgb;
use crate::selection::SelectionSpan;

/// Per-cell attribute bits. The values are part of the FFI contract with the
/// Kotlin `SnapshotLayout` object.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub struct CellFlags(u32);

impl CellFlags {
    pub const BOLD: Self = Self(1 << 0);
    pub const ITALIC: Self = Self(1 << 1);
    pub const UNDERLINE: Self = Self(1 << 2);
    pub const STRIKEOUT: Self = Self(1 << 3);
    pub const DIM: Self = Self(1 << 4);
    /// The glyph occupies this cell and the next one.
    pub const WIDE: Self = Self(1 << 5);
    /// Right half of a wide glyph; draw nothing.
    pub const WIDE_SPACER: Self = Self(1 << 6);
    pub const HIDDEN: Self = Self(1 << 7);
    pub const DOUBLE_UNDERLINE: Self = Self(1 << 8);
    pub const UNDERCURL: Self = Self(1 << 9);
    /// Zero-width characters (combining marks, ZWJ, variation selectors)
    /// follow the base character; see [`Snapshot::combining`].
    pub const COMBINING: Self = Self(1 << 10);
    /// Set on the last cell of a row whose text continues on the next row
    /// (soft wrap), so copy/accessibility can rejoin long lines.
    pub const WRAPPED: Self = Self(1 << 11);
    /// Part of the selection; `bg` is already the selection color.
    pub const SELECTED: Self = Self(1 << 12);
    /// Part of an OSC 8 hyperlink; see [`Snapshot::links`].
    pub const HYPERLINK: Self = Self(1 << 13);

    pub const fn empty() -> Self {
        Self(0)
    }

    pub const fn bits(self) -> u32 {
        self.0
    }

    pub const fn contains(self, other: Self) -> bool {
        self.0 & other.0 == other.0
    }

    pub fn insert(&mut self, other: Self) {
        self.0 |= other.0;
    }
}

/// One rendered cell.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Cell {
    /// The character, or `' '` for a blank cell.
    pub ch: char,
    pub fg: Rgb,
    pub bg: Rgb,
    pub flags: CellFlags,
}

impl Default for Cell {
    fn default() -> Self {
        Self {
            ch: ' ',
            fg: Rgb::default(),
            bg: Rgb::default(),
            flags: CellFlags::empty(),
        }
    }
}

/// How the cursor is drawn.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum CursorShape {
    Hidden,
    #[default]
    Block,
    HollowBlock,
    Underline,
    Bar,
}

impl CursorShape {
    /// FFI code: 0 hidden, 1 block, 2 underline, 3 bar; `0x100` marks a
    /// hollow block (`0x101`).
    pub const fn code(self) -> u32 {
        match self {
            CursorShape::Hidden => 0,
            CursorShape::Block => 1,
            CursorShape::Underline => 2,
            CursorShape::Bar => 3,
            CursorShape::HollowBlock => 0x101,
        }
    }
}

/// Cursor position (viewport coordinates) and shape.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Cursor {
    pub row: usize,
    pub col: usize,
    pub shape: CursorShape,
    pub color: Rgb,
}

/// Terminal modes relevant to the UI layer.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Modes {
    pub app_cursor: bool,
    pub bracketed_paste: bool,
    pub alt_screen: bool,
    pub mouse_reporting: bool,
    /// DECSET 1007: on the alternate screen, scrolling sends arrow keys.
    pub alternate_scroll: bool,
}

impl Modes {
    /// FFI bits: 0 app cursor, 1 bracketed paste, 2 alt screen, 3 mouse,
    /// 4 alternate scroll.
    pub const fn bits(self) -> u32 {
        self.app_cursor as u32
            | (self.bracketed_paste as u32) << 1
            | (self.alt_screen as u32) << 2
            | (self.mouse_reporting as u32) << 3
            | (self.alternate_scroll as u32) << 4
    }
}

/// The visible screen.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Snapshot {
    pub rows: usize,
    pub cols: usize,
    /// `rows * cols` cells, row-major.
    pub cells: Vec<Cell>,
    pub cursor: Cursor,
    /// How many lines the view is scrolled back into history (0 = live).
    pub display_offset: usize,
    /// Lines of scrollback history above the screen (0 on the alternate
    /// screen), for scrollbars.
    pub history: usize,
    pub default_fg: Rgb,
    pub default_bg: Rgb,
    pub modes: Modes,
    /// Zero-width characters attached to cells flagged
    /// [`CellFlags::COMBINING`], sorted by cell index.
    pub combining: Vec<Combining>,
    /// The selection, in viewport coordinates. Its ends may lie outside the
    /// viewport when the selected text is scrolled out of view.
    pub selection: Option<SelectionSpan>,
    /// OSC 8 hyperlinks on screen, sorted by cell index. Their cells are
    /// flagged [`CellFlags::HYPERLINK`].
    pub links: Vec<Link>,
}

/// A run of consecutive cells (row-major, possibly across rows) that belong
/// to the same OSC 8 hyperlink.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Link {
    /// First cell, a row-major index into [`Snapshot::cells`].
    pub start: usize,
    /// One past the last cell.
    pub end: usize,
    pub uri: String,
}

/// Zero-width characters that render together with a cell's base character.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Combining {
    /// Row-major index into [`Snapshot::cells`].
    pub cell: usize,
    pub chars: Vec<char>,
}

impl Snapshot {
    /// The zero-width characters attached to a cell, if any.
    pub fn combining_at(&self, row: usize, col: usize) -> &[char] {
        let index = row * self.cols + col;
        if !self.cells[index].flags.contains(CellFlags::COMBINING) {
            return &[];
        }
        self.combining
            .binary_search_by_key(&index, |c| c.cell)
            .map(|i| self.combining[i].chars.as_slice())
            .unwrap_or(&[])
    }

    /// The hyperlink covering the cell at `(row, col)`, if any.
    pub fn link_at(&self, row: usize, col: usize) -> Option<&Link> {
        let index = row * self.cols + col;
        if !self.cells.get(index)?.flags.contains(CellFlags::HYPERLINK) {
            return None;
        }
        let i = self.links.partition_point(|l| l.end <= index);
        self.links.get(i).filter(|l| l.start <= index)
    }

    /// The cell at `(row, col)`.
    pub fn cell(&self, row: usize, col: usize) -> &Cell {
        &self.cells[row * self.cols + col]
    }

    /// The cells of one row.
    pub fn row(&self, row: usize) -> &[Cell] {
        &self.cells[row * self.cols..(row + 1) * self.cols]
    }

    /// The text of one row, without wide-char spacers or trailing blanks.
    pub fn row_text(&self, row: usize) -> String {
        let mut text = String::with_capacity(self.cols);
        for (col, cell) in self.row(row).iter().enumerate() {
            if cell.flags.contains(CellFlags::WIDE_SPACER) {
                continue;
            }
            text.push(cell.ch);
            text.extend(self.combining_at(row, col));
        }
        text.trim_end().to_owned()
    }

    /// Whether `row` soft-wraps into the next row.
    pub fn row_wraps(&self, row: usize) -> bool {
        self.cols > 0
            && self
                .cell(row, self.cols - 1)
                .flags
                .contains(CellFlags::WRAPPED)
    }

    /// The visible text: rows joined by `\n`, except that soft-wrapped rows
    /// are joined directly; trailing blank lines removed.
    pub fn text(&self) -> String {
        let mut out = String::new();
        for row in 0..self.rows {
            if self.row_wraps(row) {
                // Keep trailing spaces: they are part of the wrapped line.
                for (col, cell) in self.row(row).iter().enumerate() {
                    if !cell.flags.contains(CellFlags::WIDE_SPACER) {
                        out.push(cell.ch);
                        out.extend(self.combining_at(row, col));
                    }
                }
            } else {
                out.push_str(&self.row_text(row));
                out.push('\n');
            }
        }
        let trimmed = out.trim_end_matches(['\n', ' ']).len();
        out.truncate(trimmed);
        out
    }

    /// Number of `i32`s [`Snapshot::encode_ints`] writes.
    pub fn encoded_len(&self) -> usize {
        let combining: usize = self.combining.iter().map(|c| 2 + c.chars.len()).sum();
        let links: usize = self.links.iter().map(|l| 3 + l.uri.chars().count()).sum();
        Self::HEADER_LEN
            + self.cells.len() * Self::INTS_PER_CELL
            + 1
            + combining
            + Self::SELECTION_LEN
            + 1
            + links
    }

    /// Number of `i32`s in the selection record of the integer encoding.
    pub const SELECTION_LEN: usize = 5;

    /// Length of the header in the integer encoding.
    pub const HEADER_LEN: usize = 10;
    /// Integers per cell in the integer encoding.
    pub const INTS_PER_CELL: usize = 4;

    /// Writes the compact integer encoding used across JNI.
    ///
    /// Header: `rows, cols, cursor_row, cursor_col, cursor_shape,
    /// display_offset, default_bg_argb, mode_bits, history, default_fg_argb`,
    /// followed by
    /// `codepoint, fg_argb, bg_argb, flags` per cell (row-major). Blank cells
    /// have codepoint 0. Then three variable-length sections:
    ///
    /// 1. Combining table: `count`, followed by `count` entries of
    ///    `cell_index, n, codepoint × n`.
    /// 2. Selection: `kind, start_row, start_col, end_row, end_col` where kind
    ///    is 0 (none), 1 (running text) or 2 (block); rows are viewport rows
    ///    and may be negative or `>= rows` (scrolled out of view); both ends
    ///    are inclusive.
    /// 3. Hyperlinks: `count`, followed by `count` entries of
    ///    `start_cell, end_cell, n, codepoint × n` (the URI; `end_cell` is
    ///    exclusive).
    ///
    /// Returns `None` if `out` is shorter than [`Snapshot::encoded_len`].
    pub fn encode_ints(&self, out: &mut [i32]) -> Option<usize> {
        let len = self.encoded_len();
        let out = out.get_mut(..len)?;
        let header = [
            self.rows as u32,
            self.cols as u32,
            self.cursor.row as u32,
            self.cursor.col as u32,
            self.cursor.shape.code(),
            self.display_offset as u32,
            self.default_bg.to_argb(),
            self.modes.bits(),
            self.history as u32,
            self.default_fg.to_argb(),
        ];
        let (head, body) = out.split_at_mut(Self::HEADER_LEN);
        for (dst, src) in head.iter_mut().zip(header) {
            *dst = src as i32;
        }
        let (cells_out, tail) = body.split_at_mut(self.cells.len() * Self::INTS_PER_CELL);
        let (chunks, _) = cells_out.as_chunks_mut::<{ Self::INTS_PER_CELL }>();
        for (dst, cell) in chunks.iter_mut().zip(&self.cells) {
            let cp = if cell.ch == ' ' { 0 } else { cell.ch as u32 };
            dst[0] = cp as i32;
            dst[1] = cell.fg.to_argb() as i32;
            dst[2] = cell.bg.to_argb() as i32;
            dst[3] = cell.flags.bits() as i32;
        }
        let mut i = 0;
        let mut push = |v: u32| {
            tail[i] = v as i32;
            i += 1;
        };
        push(self.combining.len() as u32);
        for c in &self.combining {
            push(c.cell as u32);
            push(c.chars.len() as u32);
            for &ch in &c.chars {
                push(ch as u32);
            }
        }
        match self.selection {
            Some(sel) => {
                push(if sel.block { 2 } else { 1 });
                push(sel.start.row as u32);
                push(sel.start.col as u32);
                push(sel.end.row as u32);
                push(sel.end.col as u32);
            }
            None => (0..Self::SELECTION_LEN).for_each(|_| push(0)),
        }
        push(self.links.len() as u32);
        for link in &self.links {
            push(link.start as u32);
            push(link.end as u32);
            push(link.uri.chars().count() as u32);
            for ch in link.uri.chars() {
                push(ch as u32);
            }
        }
        debug_assert_eq!(i, tail.len());
        Some(len)
    }
}
