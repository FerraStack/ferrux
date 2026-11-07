// SPDX-License-Identifier: Apache-2.0
//! Text selection in viewport coordinates.
//!
//! Renderers and touch code think in *viewport* rows (row 0 is the top of
//! what is on screen right now). The engine keeps the selection in grid
//! lines, so it stays on the same text while the view scrolls through
//! history. These types convert between the two and are part of the FFI
//! contract (see [`SelectionKind::code`] and [`crate::Snapshot::encode_ints`]).

/// How a selection grows from where it started.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum SelectionKind {
    /// Exactly the cells between the two points.
    Simple,
    /// Whole words (double tap, long press): expands to word boundaries.
    Word,
    /// Whole lines (triple tap), following soft wraps.
    Line,
    /// A rectangle.
    Block,
}

impl SelectionKind {
    /// FFI code: 0 simple, 1 word, 2 line, 3 block.
    pub const fn code(self) -> u32 {
        match self {
            SelectionKind::Simple => 0,
            SelectionKind::Word => 1,
            SelectionKind::Line => 2,
            SelectionKind::Block => 3,
        }
    }

    /// Inverse of [`SelectionKind::code`].
    pub const fn from_code(code: u32) -> Option<Self> {
        Some(match code {
            0 => SelectionKind::Simple,
            1 => SelectionKind::Word,
            2 => SelectionKind::Line,
            3 => SelectionKind::Block,
            _ => return None,
        })
    }
}

/// A point in viewport coordinates. `row` may be negative (above the
/// viewport, in history) or `>= rows` (below it) when the selected text is
/// scrolled out of view.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct ViewPoint {
    pub row: i32,
    pub col: usize,
}

impl ViewPoint {
    pub const fn new(row: i32, col: usize) -> Self {
        Self { row, col }
    }
}

/// The selected cells, both ends inclusive, `start <= end`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct SelectionSpan {
    pub start: ViewPoint,
    pub end: ViewPoint,
    /// A rectangle from `start` to `end` rather than running text.
    pub block: bool,
}

impl SelectionSpan {
    /// Whether the cell at `point` is selected.
    pub fn contains(&self, point: ViewPoint) -> bool {
        if point.row < self.start.row || point.row > self.end.row {
            return false;
        }
        if self.block {
            return (self.start.col..=self.end.col).contains(&point.col);
        }
        (point.row != self.start.row || point.col >= self.start.col)
            && (point.row != self.end.row || point.col <= self.end.col)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn kind_codes_round_trip() {
        for kind in [
            SelectionKind::Simple,
            SelectionKind::Word,
            SelectionKind::Line,
            SelectionKind::Block,
        ] {
            assert_eq!(SelectionKind::from_code(kind.code()), Some(kind));
        }
        assert_eq!(SelectionKind::from_code(4), None);
    }

    #[test]
    fn span_contains_running_text_and_blocks() {
        let span = SelectionSpan {
            start: ViewPoint::new(1, 5),
            end: ViewPoint::new(3, 2),
            block: false,
        };
        assert!(!span.contains(ViewPoint::new(1, 4)));
        assert!(span.contains(ViewPoint::new(1, 5)));
        assert!(span.contains(ViewPoint::new(2, 0)));
        assert!(span.contains(ViewPoint::new(2, 79)));
        assert!(span.contains(ViewPoint::new(3, 2)));
        assert!(!span.contains(ViewPoint::new(3, 3)));
        assert!(!span.contains(ViewPoint::new(0, 6)));

        let block = SelectionSpan {
            block: true,
            ..span
        };
        // A block from column 5 to 2 is normalised by the engine; here the
        // columns are taken as given, so nothing is inside.
        assert!(!block.contains(ViewPoint::new(2, 3)));
        let block = SelectionSpan {
            start: ViewPoint::new(1, 2),
            end: ViewPoint::new(3, 5),
            block: true,
        };
        assert!(block.contains(ViewPoint::new(2, 3)));
        assert!(!block.contains(ViewPoint::new(2, 6)));
    }
}
