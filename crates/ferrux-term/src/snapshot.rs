// SPDX-License-Identifier: Apache-2.0
//! Renderer-facing copy of the visible screen.
//!
//! A [`Snapshot`] is plain data: every color is resolved to RGB, reverse video
//! is already applied, and wide characters are marked. Renderers (the Kotlin
//! `TerminalView`, a future wgpu renderer, Ferride) need no knowledge of the
//! VT engine behind it.

use crate::color::Rgb;

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
}

impl Modes {
    /// FFI bits: 0 app cursor, 1 bracketed paste, 2 alt screen, 3 mouse.
    pub const fn bits(self) -> u32 {
        self.app_cursor as u32
            | (self.bracketed_paste as u32) << 1
            | (self.alt_screen as u32) << 2
            | (self.mouse_reporting as u32) << 3
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
    pub default_fg: Rgb,
    pub default_bg: Rgb,
    pub modes: Modes,
}

impl Snapshot {
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
        let text: String = self
            .row(row)
            .iter()
            .filter(|c| !c.flags.contains(CellFlags::WIDE_SPACER))
            .map(|c| c.ch)
            .collect();
        text.trim_end().to_owned()
    }

    /// All rows joined by `\n`, with trailing blank rows removed.
    pub fn text(&self) -> String {
        let mut lines: Vec<String> = (0..self.rows).map(|r| self.row_text(r)).collect();
        while lines.last().is_some_and(String::is_empty) {
            lines.pop();
        }
        lines.join("\n")
    }

    /// Number of `i32`s [`Snapshot::encode_ints`] writes.
    pub fn encoded_len(&self) -> usize {
        Self::HEADER_LEN + self.cells.len() * Self::INTS_PER_CELL
    }

    /// Length of the header in the integer encoding.
    pub const HEADER_LEN: usize = 8;
    /// Integers per cell in the integer encoding.
    pub const INTS_PER_CELL: usize = 4;

    /// Writes the compact integer encoding used across JNI.
    ///
    /// Header: `rows, cols, cursor_row, cursor_col, cursor_shape,
    /// display_offset, default_bg_argb, mode_bits`, followed by
    /// `codepoint, fg_argb, bg_argb, flags` per cell (row-major). Blank cells
    /// have codepoint 0. Returns `None` if `out` is shorter than
    /// [`Snapshot::encoded_len`].
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
        ];
        let (head, body) = out.split_at_mut(Self::HEADER_LEN);
        for (dst, src) in head.iter_mut().zip(header) {
            *dst = src as i32;
        }
        for (dst, cell) in body.chunks_exact_mut(Self::INTS_PER_CELL).zip(&self.cells) {
            let cp = if cell.ch == ' ' { 0 } else { cell.ch as u32 };
            dst[0] = cp as i32;
            dst[1] = cell.fg.to_argb() as i32;
            dst[2] = cell.bg.to_argb() as i32;
            dst[3] = cell.flags.bits() as i32;
        }
        Some(len)
    }
}
