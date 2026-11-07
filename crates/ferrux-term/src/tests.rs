// SPDX-License-Identifier: Apache-2.0
use super::*;

fn term(rows: u16, cols: u16) -> Terminal {
    Terminal::new(TermSize::new(rows, cols), TermConfig::default())
}

#[test]
fn plain_text_and_newlines() {
    let mut t = term(5, 20);
    t.feed(b"hello\r\nworld");
    let s = t.snapshot();
    assert_eq!(s.rows, 5);
    assert_eq!(s.cols, 20);
    assert_eq!(s.row_text(0), "hello");
    assert_eq!(s.row_text(1), "world");
    assert_eq!((s.cursor.row, s.cursor.col), (1, 5));
    assert_eq!(s.text(), "hello\nworld");
}

#[test]
fn utf8_split_across_feeds() {
    let mut t = term(3, 10);
    let bytes = "héllo".as_bytes();
    t.feed(&bytes[..2]);
    t.feed(&bytes[2..]);
    assert_eq!(t.snapshot().row_text(0), "héllo");
}

#[test]
fn wide_chars_take_two_cells() {
    let mut t = term(3, 10);
    t.feed("a漢b".as_bytes());
    let s = t.snapshot();
    assert_eq!(s.cell(0, 1).ch, '漢');
    assert!(s.cell(0, 1).flags.contains(CellFlags::WIDE));
    assert!(s.cell(0, 2).flags.contains(CellFlags::WIDE_SPACER));
    assert_eq!(s.cell(0, 3).ch, 'b');
    assert_eq!(s.row_text(0), "a漢b");
}

#[test]
fn sgr_colors_and_attributes() {
    let p = Palette::default();
    let mut t = term(3, 20);
    // red, bold green (bright), 256-color, truecolor bg, inverse.
    t.feed(b"\x1b[31mR\x1b[1;32mG\x1b[0;38;5;196mX\x1b[48;2;1;2;3mT\x1b[0;7mI\x1b[0m");
    let s = t.snapshot();
    assert_eq!(s.cell(0, 0).fg, p.indexed[1]);
    assert_eq!(s.cell(0, 1).fg, p.indexed[10]);
    assert!(s.cell(0, 1).flags.contains(CellFlags::BOLD));
    assert_eq!(s.cell(0, 2).fg, p.indexed[196]);
    assert_eq!(s.cell(0, 3).bg, Rgb::new(1, 2, 3));
    assert_eq!(s.cell(0, 4).fg, p.background);
    assert_eq!(s.cell(0, 4).bg, p.foreground);
    assert_eq!(s.cell(0, 5).bg, p.background);
}

#[test]
fn underline_italic_strike_dim() {
    let mut t = term(2, 10);
    t.feed(b"\x1b[4mu\x1b[0;3mi\x1b[0;9ms\x1b[0;2md\x1b[0;4:3mc");
    let s = t.snapshot();
    assert!(s.cell(0, 0).flags.contains(CellFlags::UNDERLINE));
    assert!(s.cell(0, 1).flags.contains(CellFlags::ITALIC));
    assert!(s.cell(0, 2).flags.contains(CellFlags::STRIKEOUT));
    assert!(s.cell(0, 3).flags.contains(CellFlags::DIM));
    assert_eq!(s.cell(0, 3).fg, Palette::default().foreground.dim());
    assert!(s.cell(0, 4).flags.contains(CellFlags::UNDERCURL));
}

#[test]
fn cursor_movement_and_erase() {
    let mut t = term(5, 10);
    t.feed(b"abcdef\x1b[3D\x1b[K\x1b[3;4HX");
    let s = t.snapshot();
    assert_eq!(s.row_text(0), "abc");
    assert_eq!(s.row_text(2), "   X");
    assert_eq!((s.cursor.row, s.cursor.col), (2, 4));
}

#[test]
fn cursor_shape_and_visibility() {
    let mut t = term(2, 10);
    assert_eq!(t.snapshot().cursor.shape, CursorShape::Block);
    t.feed(b"\x1b[6 q");
    assert_eq!(t.snapshot().cursor.shape, CursorShape::Bar);
    t.feed(b"\x1b[?25l");
    assert_eq!(t.snapshot().cursor.shape, CursorShape::Hidden);
}

#[test]
fn title_and_bell_events() {
    let mut t = term(2, 10);
    t.feed(b"\x1b]0;my title\x07\x07");
    assert_eq!(
        t.drain_events(),
        vec![TermEvent::Title(Some("my title".into())), TermEvent::Bell]
    );
    assert_eq!(t.title().as_deref(), Some("my title"));
    assert!(t.drain_events().is_empty());
}

#[test]
fn osc52_copy_event() {
    let mut t = term(2, 10);
    // "hi" in base64.
    t.feed(b"\x1b]52;c;aGk=\x07");
    assert_eq!(
        t.drain_events(),
        vec![TermEvent::ClipboardStore("hi".into())]
    );
}

#[test]
fn device_status_report_produces_response() {
    let mut t = term(5, 10);
    t.feed(b"ab\x1b[6n");
    assert_eq!(t.take_responses(), b"\x1b[1;3R");
    assert!(t.take_responses().is_empty());
}

#[test]
fn modes_follow_decset() {
    let mut t = term(3, 10);
    assert_eq!(t.modes(), Modes::default());
    t.feed(b"\x1b[?1h\x1b[?2004h\x1b[?1049h\x1b[?1000h");
    let m = t.modes();
    assert!(m.app_cursor && m.bracketed_paste && m.alt_screen && m.mouse_reporting);
    assert_eq!(m.bits(), 0b1111);
    assert_eq!(t.encode_key(Key::Up, Modifiers::NONE), b"\x1bOA");
    assert_eq!(t.encode_paste("x"), b"\x1b[200~x\x1b[201~");
}

#[test]
fn resize_reflows_and_clamps() {
    let mut t = term(3, 10);
    t.feed(b"0123456789abc");
    assert_eq!(t.snapshot().row_text(0), "0123456789");
    assert_eq!(t.snapshot().row_text(1), "abc");
    t.resize(TermSize::new(3, 20));
    let s = t.snapshot();
    assert_eq!((s.rows, s.cols), (3, 20));
    assert_eq!(s.row_text(0), "0123456789abc");
    t.resize(TermSize::new(0, 0));
    assert_eq!(t.size(), TermSize::new(1, 2));
}

#[test]
fn scrollback_and_scroll_to_bottom() {
    let mut t = term(3, 10);
    for i in 0..10 {
        t.feed(format!("line{i}\r\n").as_bytes());
    }
    assert_eq!(t.snapshot().row_text(0), "line8");
    t.scroll(2);
    let s = t.snapshot();
    assert_eq!(s.display_offset, 2);
    assert_eq!(s.row_text(0), "line6");
    // The live cursor (bottom row) is now below the viewport.
    assert_eq!(s.cursor.shape, CursorShape::Hidden);
    t.scroll_to_bottom();
    assert_eq!(t.display_offset(), 0);
}

#[test]
fn damage_is_tracked() {
    let mut t = term(4, 10);
    assert_eq!(t.take_damage(), Damage::Full);
    t.feed(b"\x1b[3;1Hx");
    match t.take_damage() {
        Damage::Rows(rows) => assert!(rows.contains(&2), "{rows:?}"),
        Damage::Full => panic!("expected partial damage"),
    }
}

#[test]
fn synchronized_update_is_buffered_until_end() {
    let mut t = term(2, 10);
    t.feed(b"\x1b[?2026hhidden");
    assert_eq!(t.snapshot().row_text(0), "");
    assert!(t.sync_deadline().is_some());
    t.feed(b"\x1b[?2026l");
    assert_eq!(t.snapshot().row_text(0), "hidden");
}

#[test]
fn int_encoding_layout() {
    let mut t = term(2, 3);
    t.feed(b"\x1b[31mA");
    let s = t.snapshot();
    let mut out = vec![0i32; s.encoded_len()];
    assert_eq!(s.encode_ints(&mut out[..5]), None);
    assert_eq!(s.encode_ints(&mut out), Some(8 + 6 * 4 + 1));
    assert_eq!(out[8 + 6 * 4], 0, "empty combining table");
    assert_eq!(&out[..6], &[2, 3, 0, 1, 1, 0]);
    assert_eq!(out[6] as u32, Palette::default().background.to_argb());
    assert_eq!(out[8], 'A' as i32);
    assert_eq!(out[9] as u32, Palette::default().indexed[1].to_argb());
    assert_eq!(out[12], 0, "blank cell encodes as codepoint 0");
}

#[test]
fn combining_marks_are_kept() {
    let mut t = term(3, 20);
    // Decomposed é, emoji + VS16, Hangul L+V jamo, emoji ZWJ sequence.
    t.feed("e\u{301}x ❤\u{FE0F} \u{1100}\u{1161} 👨\u{200D}👩".as_bytes());
    let s = t.snapshot();
    assert!(s.cell(0, 0).flags.contains(CellFlags::COMBINING));
    assert_eq!(s.combining_at(0, 0), &['\u{301}']);
    assert!(!s.cell(0, 1).flags.contains(CellFlags::COMBINING));
    assert_eq!(s.combining_at(0, 3), &['\u{FE0F}']);
    let text = s.row_text(0);
    assert!(text.starts_with("e\u{301}x ❤\u{FE0F} "), "{text:?}");
    assert!(text.contains("\u{1100}\u{1161}"), "{text:?}");
    assert!(text.contains("👨\u{200D}"), "{text:?}");

    // Integer encoding carries the side table.
    let mut out = vec![0i32; s.encoded_len()];
    s.encode_ints(&mut out).unwrap();
    let table = &out[8 + 3 * 20 * 4..];
    assert_eq!(table[0] as usize, s.combining.len());
    assert_eq!(&table[1..4], &[0, 1, 0x301]);
}
