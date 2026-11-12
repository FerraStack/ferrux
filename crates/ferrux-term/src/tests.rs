// SPDX-License-Identifier: Apache-2.0
use super::*;

/// Ints before the first cell in the integer encoding.
const H: usize = Snapshot::HEADER_LEN;

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
fn themes_and_cursor_shape_change_at_runtime() {
    let mut t = term(2, 10);
    t.feed(b"\x1b]0;keep me\x07\x1b[31mR");
    t.drain_events();
    t.take_damage();
    let nord = Theme::by_id("nord").unwrap();
    t.set_palette(nord.palette());
    let s = t.snapshot();
    assert_eq!(s.default_bg, Rgb::from_u32(nord.background));
    assert_eq!(
        s.cell(0, 0).fg,
        Rgb::from_u32(nord.ansi[1]),
        "existing text recoloured"
    );
    assert_eq!(s.cursor_color, Rgb::from_u32(nord.cursor));
    assert_eq!(t.take_damage(), Damage::Full);
    assert!(t.drain_events().is_empty(), "no spurious title change");
    assert_eq!(t.title().as_deref(), Some("keep me"));

    t.set_cursor_shape(CursorShape::Bar);
    assert_eq!(t.snapshot().cursor.shape, CursorShape::Bar);
    // A program's choice (DECSCUSR 4 = steady underline) still wins.
    t.feed(b"\x1b[4 q");
    assert_eq!(t.snapshot().cursor.shape, CursorShape::Underline);
    // OSC 11 overrides the theme's background.
    t.feed(b"\x1b]11;rgb:01/02/03\x07");
    t.set_palette(Palette::default());
    assert_eq!(t.snapshot().default_bg, Rgb::new(1, 2, 3));

    let mut out = vec![0i32; t.snapshot().encoded_len()];
    t.snapshot().encode_ints(&mut out).unwrap();
    assert_eq!(out[10] as u32, Palette::default().cursor.to_argb());
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
fn oversized_osc52_writes_are_dropped() {
    let mut t = term(2, 10);
    // "AAAA" decodes to three NUL bytes: one group more than the limit allows.
    let groups = MAX_CLIPBOARD_BYTES / 3 + 1;
    let mut seq = b"\x1b]52;c;".to_vec();
    seq.extend(std::iter::repeat_n(b'A', groups * 4));
    seq.push(0x07);
    t.feed(&seq);
    assert!(t.drain_events().is_empty());
    // Exactly at the limit still works.
    let mut seq = b"\x1b]52;c;".to_vec();
    seq.extend(std::iter::repeat_n(b'A', (MAX_CLIPBOARD_BYTES / 3) * 4));
    seq.push(0x07);
    t.feed(&seq);
    assert!(
        matches!(&t.drain_events()[..], [TermEvent::ClipboardStore(s)] if s.len() <= MAX_CLIPBOARD_BYTES)
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
    // Alternate scroll (1007) is on by default, as in xterm's default config.
    assert_eq!(
        t.modes(),
        Modes {
            alternate_scroll: true,
            ..Modes::default()
        }
    );
    t.feed(b"\x1b[?1h\x1b[?2004h\x1b[?1049h\x1b[?1000h");
    let m = t.modes();
    assert!(m.app_cursor && m.bracketed_paste && m.alt_screen && m.mouse_reporting);
    assert_eq!(m.bits(), 0b11111);
    t.feed(b"\x1b[?1007l");
    assert!(!t.modes().alternate_scroll);
    t.feed(b"\x1b[?1007h");
    assert_eq!(t.encode_key(Key::Up, Modifiers::NONE), b"\x1bOA");
    assert_eq!(t.encode_paste("x"), b"\x1b[200~x\x1b[201~");
}

#[test]
fn resize_reflows_and_clamps() {
    let mut t = term(3, 10);
    t.feed(b"0123456789abc");
    assert_eq!(t.snapshot().row_text(0), "0123456789");
    assert_eq!(t.snapshot().row_text(1), "abc");
    assert!(t.snapshot().row_wraps(0));
    assert!(!t.snapshot().row_wraps(1));
    assert_eq!(
        t.snapshot().text(),
        "0123456789abc",
        "soft wrap is rejoined"
    );
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
fn scroll_gesture_scrolls_history_on_the_main_screen() {
    let mut t = term(3, 10);
    for i in 0..10 {
        t.feed(format!("line{i}\r\n").as_bytes());
    }
    assert_eq!(t.history_size(), 8);
    assert_eq!(t.snapshot().history, 8);
    assert!(t.scroll_gesture(3, 1, 1).is_empty());
    assert_eq!(t.display_offset(), 3);
    assert!(t.scroll_gesture(-1, 1, 1).is_empty());
    assert_eq!(t.display_offset(), 2);
    // Clamped to the history.
    t.scroll_gesture(100, 0, 0);
    assert_eq!(t.display_offset(), 8);
    assert!(t.scroll_gesture(0, 0, 0).is_empty());
}

#[test]
fn scroll_gesture_sends_arrows_on_the_alternate_screen() {
    let mut t = term(3, 10);
    t.feed(b"x\r\ny\r\nz\r\nw\r\n\x1b[?1049h");
    assert_eq!(
        t.snapshot().history,
        0,
        "the alternate screen has no history"
    );
    assert_eq!(t.scroll_gesture(2, 0, 0), b"\x1b[A\x1b[A");
    assert_eq!(t.scroll_gesture(-1, 0, 0), b"\x1b[B");
    assert_eq!(t.display_offset(), 0);
    // Application cursor mode changes the arrows.
    t.feed(b"\x1b[?1h");
    assert_eq!(t.scroll_gesture(1, 0, 0), b"\x1bOA");
    // Without alternate scroll the gesture does nothing.
    t.feed(b"\x1b[?1007l");
    assert!(t.scroll_gesture(1, 0, 0).is_empty());
}

#[test]
fn scroll_gesture_sends_wheel_events_with_mouse_reporting() {
    let mut t = term(5, 20);
    t.feed(b"\x1b[?1000h\x1b[?1006h");
    assert_eq!(t.scroll_gesture(2, 3, 4), b"\x1b[<64;5;4M\x1b[<64;5;4M");
    assert_eq!(t.scroll_gesture(-1, 0, 0), b"\x1b[<65;1;1M");
    // Taps: press and release, clamped to the grid.
    let press = t.encode_mouse(
        MouseButton::Left,
        MouseAction::Press,
        99,
        99,
        Modifiers::NONE,
    );
    assert_eq!(press, b"\x1b[<0;20;5M");
    t.feed(b"\x1b[?1000l");
    let off = t.encode_mouse(MouseButton::Left, MouseAction::Press, 0, 0, Modifiers::NONE);
    assert!(off.is_empty());
    assert_eq!(
        t.mouse_mode(),
        MouseMode {
            sgr: true,
            ..MouseMode::default()
        }
    );
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
    assert_eq!(s.encode_ints(&mut out), Some(H + 6 * 4 + 1 + 5 + 1));
    assert_eq!(out[H + 6 * 4], 0, "empty combining table");
    assert_eq!(
        &out[H + 6 * 4 + 1..],
        &[0, 0, 0, 0, 0, 0],
        "no selection, no links"
    );
    assert_eq!(&out[..6], &[2, 3, 0, 1, 1, 0]);
    assert_eq!(out[6] as u32, Palette::default().background.to_argb());
    assert_eq!(out[8], 0, "no history yet");
    assert_eq!(out[9] as u32, Palette::default().foreground.to_argb());
    assert_eq!(out[H], 'A' as i32);
    assert_eq!(out[H + 1] as u32, Palette::default().indexed[1].to_argb());
    assert_eq!(out[H + 4], 0, "blank cell encodes as codepoint 0");
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
    let table = &out[H + 3 * 20 * 4..];
    assert_eq!(table[0] as usize, s.combining.len());
    assert_eq!(&table[1..4], &[0, 1, 0x301]);
}

// ---------------------------------------------------------------- selection

fn vp(row: i32, col: usize) -> ViewPoint {
    ViewPoint::new(row, col)
}

fn selected_cols(s: &Snapshot, row: usize) -> Vec<usize> {
    (0..s.cols)
        .filter(|&c| s.cell(row, c).flags.contains(CellFlags::SELECTED))
        .collect()
}

#[test]
fn word_selection_covers_the_word_and_recolors_it() {
    let mut t = term(3, 20);
    t.feed(b"echo hello-world ok");
    t.start_selection(SelectionKind::Word, vp(0, 8));
    assert_eq!(t.selection_text().as_deref(), Some("hello-world"));
    let s = t.snapshot();
    assert_eq!(
        s.selection,
        Some(SelectionSpan {
            start: vp(0, 5),
            end: vp(0, 15),
            block: false
        })
    );
    assert_eq!(selected_cols(&s, 0), (5..=15).collect::<Vec<_>>());
    assert_eq!(s.cell(0, 5).bg, Palette::default().selection);
    assert_eq!(s.cell(0, 5).fg, Palette::default().foreground, "fg is kept");
    assert_eq!(s.cell(0, 4).bg, Palette::default().background);
}

#[test]
fn word_selection_keeps_urls_and_paths_whole() {
    let mut t = term(3, 60);
    t.feed(b"open https://ferrux.dev/docs?a=1 or src/main.rs:10:5, ok");
    t.start_selection(SelectionKind::Word, vp(0, 12));
    assert_eq!(
        t.selection_text().as_deref(),
        Some("https://ferrux.dev/docs?a=1")
    );
    t.start_selection(SelectionKind::Word, vp(0, 40));
    assert_eq!(t.selection_text().as_deref(), Some("src/main.rs:10:5"));
}

#[test]
fn dragging_extends_by_words_in_both_directions() {
    let mut t = term(3, 30);
    t.feed(b"one two three\r\nfour five");
    t.start_selection(SelectionKind::Word, vp(0, 5)); // "two"
    t.update_selection(vp(1, 6)); // into "five"
    assert_eq!(t.selection_text().as_deref(), Some("two three\nfour five"));
    t.update_selection(vp(0, 0)); // back before the anchor
    assert_eq!(t.selection_text().as_deref(), Some("one two"));
}

#[test]
fn line_selection_follows_soft_wraps_and_drops_the_newline() {
    let mut t = term(4, 10);
    t.feed(b"$ 0123456789abc\r\nnext");
    t.start_selection(SelectionKind::Line, vp(1, 2));
    // Trailing newline removed so a pasted line never runs by itself.
    assert_eq!(t.selection_text().as_deref(), Some("$ 0123456789abc"));
    let span = t.selection().unwrap();
    assert_eq!((span.start, span.end), (vp(0, 0), vp(1, 9)));
}

#[test]
fn set_selection_is_inclusive_in_either_order() {
    let mut t = term(3, 20);
    t.feed(b"abcdef\r\nghijkl");
    t.set_selection(vp(0, 2), vp(1, 1), false);
    assert_eq!(t.selection_text().as_deref(), Some("cdef\ngh"));
    t.set_selection(vp(1, 1), vp(0, 2), false);
    assert_eq!(t.selection_text().as_deref(), Some("cdef\ngh"));
    t.set_selection(vp(0, 3), vp(0, 3), false);
    assert_eq!(t.selection_text().as_deref(), Some("d"));
    t.set_selection(vp(0, 1), vp(1, 2), true);
    assert_eq!(t.selection_text().as_deref(), Some("bc\nhi"));
    assert!(t.snapshot().selection.unwrap().block);
}

#[test]
fn simple_selection_and_blank_selection() {
    let mut t = term(3, 20);
    t.feed(b"abc");
    t.start_selection(SelectionKind::Simple, vp(0, 1));
    assert_eq!(t.selection_text().as_deref(), Some("b"));
    t.update_selection(vp(0, 2));
    assert_eq!(t.selection_text().as_deref(), Some("bc"));
    // Only blanks selected: nothing to copy.
    t.start_selection(SelectionKind::Word, vp(2, 5));
    assert_eq!(t.selection_text(), None);
    t.clear_selection();
    assert_eq!(t.selection(), None);
    assert!(selected_cols(&t.snapshot(), 0).is_empty());
}

#[test]
fn select_all_includes_history() {
    let mut t = term(3, 10);
    for i in 0..6 {
        t.feed(format!("line{i}\r\n").as_bytes());
    }
    t.select_all();
    assert_eq!(
        t.selection_text().as_deref(),
        Some("line0\nline1\nline2\nline3\nline4\nline5")
    );
    let span = t.selection().unwrap();
    assert!(span.start.row < 0, "starts in history: {span:?}");
    assert_eq!(span.end, vp(2, 9));
}

#[test]
fn select_all_stops_at_the_last_line_with_content() {
    let mut t = term(10, 10);
    t.feed(b"one\r\ntwo\r\n$ ");
    t.select_all();
    let span = t.selection().unwrap();
    assert_eq!((span.start, span.end), (vp(0, 0), vp(2, 9)));
    assert_eq!(t.selection_text().as_deref(), Some("one\ntwo\n$"));
    // Content below the cursor still counts.
    t.feed(b"\x1b[8;1Hlow\x1b[3;3H");
    t.select_all();
    assert_eq!(t.selection().unwrap().end, vp(7, 9));
}

#[test]
fn selection_stays_on_its_text_while_scrolling() {
    let mut t = term(3, 10);
    for i in 0..8 {
        t.feed(format!("line{i}\r\n").as_bytes());
    }
    // Screen: line6, line7, "" ; select "line6".
    t.start_selection(SelectionKind::Word, vp(0, 1));
    assert_eq!(t.selection_text().as_deref(), Some("line6"));
    t.scroll(2);
    let s = t.snapshot();
    assert_eq!(s.row_text(2), "line6");
    assert_eq!(s.selection.unwrap().start, vp(2, 0));
    assert_eq!(selected_cols(&s, 2), (0..=4).collect::<Vec<_>>());
    t.scroll_to_bottom();
    // New output pushes the selected line into history (negative row).
    t.feed(b"more\r\nmore\r\n");
    let s = t.snapshot();
    assert_eq!(s.selection.unwrap().start.row, -2);
    assert_eq!(t.selection_text().as_deref(), Some("line6"));
    assert!((0..3).all(|r| selected_cols(&s, r).is_empty()));
}

#[test]
fn overwriting_the_selection_clears_it() {
    let mut t = term(3, 10);
    t.feed(b"hello");
    t.start_selection(SelectionKind::Word, vp(0, 0));
    assert!(t.selection().is_some());
    t.feed(b"\x1b[2J");
    assert_eq!(t.selection(), None);
}

#[test]
fn wide_characters_are_selected_whole() {
    let mut t = term(2, 10);
    t.feed("a漢b".as_bytes());
    // Select only the spacer half of 漢.
    t.set_selection(vp(0, 2), vp(0, 3), false);
    let s = t.snapshot();
    assert_eq!(selected_cols(&s, 0), vec![1, 2, 3]);
    assert_eq!(t.selection_text().as_deref(), Some("漢b"));
}

#[test]
fn selection_is_encoded_with_viewport_rows() {
    let mut t = term(2, 4);
    t.feed(b"ab\r\ncd\r\nef");
    // "ab" is in history now.
    t.set_selection(vp(-1, 0), vp(0, 1), false);
    let s = t.snapshot();
    let mut out = vec![0i32; s.encoded_len()];
    s.encode_ints(&mut out).unwrap();
    let tail = &out[H + 2 * 4 * 4..];
    assert_eq!(tail[0], 0, "no combining");
    assert_eq!(&tail[1..6], &[1, -1, 0, 0, 1]);
    assert_eq!(tail[6], 0, "no links");
}

// ---------------------------------------------------------------- hyperlinks

#[test]
fn osc8_hyperlinks_are_reported_as_spans() {
    let mut t = term(3, 12);
    t.feed(b"see \x1b]8;id=x;https://ferrux.dev/a\x1b\\ferrux\x1b]8;;\x1b\\ now");
    // A link that wraps onto the next row stays one span.
    t.feed(b"\r\n\x1b[2;9H\x1b]8;;http://b.example\x07linkwrap\x1b]8;;\x07");
    let s = t.snapshot();
    assert_eq!(
        s.links,
        vec![
            Link {
                start: 4,
                end: 10,
                kind: LinkKind::Hyperlink,
                uri: "https://ferrux.dev/a".into()
            },
            Link {
                start: 12 + 8,
                end: 12 + 8 + 8,
                kind: LinkKind::Hyperlink,
                uri: "http://b.example".into()
            },
        ]
    );
    assert!(s.cell(0, 4).flags.contains(CellFlags::HYPERLINK));
    assert!(!s.cell(0, 3).flags.contains(CellFlags::HYPERLINK));
    assert!(!s.cell(0, 10).flags.contains(CellFlags::HYPERLINK));
    assert_eq!(s.link_at(0, 9).unwrap().uri, "https://ferrux.dev/a");
    assert_eq!(s.link_at(2, 3).unwrap().uri, "http://b.example");
    assert_eq!(s.link_at(0, 2), None);

    let mut out = vec![0i32; s.encoded_len()];
    s.encode_ints(&mut out).unwrap();
    let tail = &out[H + 3 * 12 * 4 + 1 + 5..];
    assert_eq!(tail[0], 2);
    assert_eq!(&tail[1..5], &[4, 10, 1, 20]);
    let uri: String = tail[5..25]
        .iter()
        .map(|&c| char::from_u32(c as u32).unwrap())
        .collect();
    assert_eq!(uri, "https://ferrux.dev/a");
    assert_eq!(&tail[25..29], &[20, 28, 1, 16]);
    assert_eq!(out.len(), H + 3 * 12 * 4 + 1 + 5 + 1 + 4 + 20 + 4 + 16);
}

#[test]
fn urls_in_the_text_become_links() {
    let mut t = term(4, 20);
    t.feed(b"see https://ferrux.dev/docs. ok\r\n");
    // A URL that soft-wraps onto the next row is one link.
    t.feed(b"x https://example.com/long/path end");
    let s = t.snapshot();
    assert_eq!(s.links.len(), 2, "{:?}", s.links);
    assert_eq!(
        s.links[0],
        Link {
            start: 4,
            end: 4 + 23,
            kind: LinkKind::Url,
            uri: "https://ferrux.dev/docs".into()
        }
    );
    assert!(s.cell(1, 3).flags.contains(CellFlags::URL));
    assert!(
        !s.cell(1, 7).flags.contains(CellFlags::URL),
        "the period is not part of it"
    );
    let wrapped = &s.links[1];
    assert_eq!(wrapped.uri, "https://example.com/long/path");
    assert_eq!((wrapped.start, wrapped.end), (2 * 20 + 2, 2 * 20 + 2 + 29));
    assert_eq!(
        s.link_at(3, 5).unwrap().uri,
        "https://example.com/long/path"
    );
    assert_eq!(s.link_at(1, 9), None);

    let mut out = vec![0i32; s.encoded_len()];
    s.encode_ints(&mut out).unwrap();
    let tail = &out[H + 4 * 20 * 4 + 1 + 5..];
    assert_eq!(&tail[..5], &[2, 4, 27, 2, 23]);
}

#[test]
fn osc8_text_that_looks_like_a_url_is_one_link() {
    let mut t = term(2, 40);
    t.feed(b"\x1b]8;;https://real.example\x07https://shown.example\x1b]8;;\x07 www.ferrux.dev");
    let s = t.snapshot();
    assert_eq!(s.links.len(), 2);
    assert_eq!(s.links[0].kind, LinkKind::Hyperlink);
    assert_eq!(s.links[0].uri, "https://real.example");
    assert!(!s.cell(0, 0).flags.contains(CellFlags::URL));
    assert_eq!(s.links[1].kind, LinkKind::Url);
    assert_eq!(s.links[1].uri, "https://www.ferrux.dev");
}

#[test]
fn adjacent_different_links_are_separate_spans() {
    let mut t = term(2, 10);
    t.feed(b"\x1b]8;;a:1\x07ab\x1b]8;;a:2\x07cd\x1b]8;;\x07");
    let s = t.snapshot();
    assert_eq!(s.links.len(), 2);
    assert_eq!((s.links[0].start, s.links[0].end), (0, 2));
    assert_eq!((s.links[1].start, s.links[1].end), (2, 4));
}

/// What toybox `clear` writes on Android (`clear | od -c` on the API 35 emulator).
const TOYBOX_CLEAR: &[u8] = b"\x1b[2J\x1b[H";

fn rows_text(t: &Terminal) -> Vec<String> {
    let s = t.snapshot();
    (0..s.rows).map(|r| s.row_text(r)).collect()
}

#[test]
fn clear_moves_the_screen_into_history_and_homes_the_cursor() {
    let mut t = term(5, 20);
    for i in 0..10 {
        t.feed(format!("line{i}\r\n").as_bytes());
    }
    t.feed(b"~ $ clear\r\n");
    t.feed(TOYBOX_CLEAR);
    t.feed(b"~ $ ");
    assert_eq!(rows_text(&t), ["~ $", "", "", "", ""]);
    let s = t.snapshot();
    assert_eq!((s.cursor.row, s.cursor.col), (0, 4));
    assert!(
        s.history > 0,
        "the cleared lines are still in the scrollback"
    );
}

#[test]
fn a_taller_screen_does_not_bring_back_cleared_output() {
    // #30: `clear` with the soft keyboard up (a short screen), then hiding the
    // keyboard: the engine alone would pull the cleared lines back into view.
    let mut t = term(10, 20);
    for i in 0..30 {
        t.feed(format!("line{i}\r\n").as_bytes());
    }
    t.resize(TermSize::new(5, 20)); // keyboard up
    t.feed(b"~ $ clear\r\n");
    t.feed(TOYBOX_CLEAR);
    t.feed(b"~ $ ");
    assert_eq!(t.pullable(), 0);
    t.resize(TermSize::new(10, 20)); // keyboard down
    let rows = rows_text(&t);
    assert_eq!(rows[0], "~ $", "{rows:?}");
    assert!(rows[1..].iter().all(String::is_empty), "{rows:?}");
    let s = t.snapshot();
    assert_eq!((s.cursor.row, s.cursor.col), (0, 4));
    // Typing still lands on the prompt line, and the history is intact.
    t.feed(b"ls");
    assert_eq!(t.snapshot().row_text(0), "~ $ ls");
    t.scroll(10);
    assert!(
        rows_text(&t).contains(&"line29".to_owned()),
        "{:?}",
        rows_text(&t)
    );
}

#[test]
fn ris_and_ed3_also_stop_lines_coming_back() {
    for clear in [&b"\x1bc"[..], b"\x1b[H\x1b[2J\x1b[3J", b"\x1b[3J\x1b[2J"] {
        let mut t = term(10, 20);
        for i in 0..30 {
            t.feed(format!("line{i}\r\n").as_bytes());
        }
        t.resize(TermSize::new(5, 20));
        t.feed(clear);
        t.feed(b"\x1b[H$ ");
        t.resize(TermSize::new(10, 20));
        assert_eq!(
            t.snapshot().row_text(0),
            "$",
            "{clear:?}: {:?}",
            rows_text(&t)
        );
    }
}

#[test]
fn a_shorter_then_taller_screen_restores_the_lines() {
    // Without a clear, closing the keyboard shows the lines it pushed off again.
    let mut t = term(10, 20);
    for i in 0..20 {
        t.feed(format!("line{i}\r\n").as_bytes());
    }
    t.feed(b"$ ");
    let before = rows_text(&t);
    t.resize(TermSize::new(5, 20));
    assert_eq!(t.snapshot().row_text(4), "$");
    t.resize(TermSize::new(10, 20));
    assert_eq!(rows_text(&t), before);
    assert_eq!(t.snapshot().cursor.row, 9);
}

#[test]
fn output_on_a_short_screen_fills_a_taller_one_from_history() {
    let mut t = term(10, 20);
    t.feed(b"$\r\n");
    t.resize(TermSize::new(5, 20));
    for i in 0..8 {
        t.feed(format!("out{i}\r\n").as_bytes());
    }
    t.feed(b"$ ");
    t.resize(TermSize::new(10, 20));
    let rows = rows_text(&t);
    assert_eq!(rows[9], "$", "{rows:?}");
    assert_eq!(rows[0], "$", "{rows:?}");
    assert_eq!(rows[1], "out0", "{rows:?}");
}

#[test]
fn a_clear_split_across_reads_is_seen() {
    let mut t = term(10, 20);
    for i in 0..30 {
        t.feed(format!("line{i}\r\n").as_bytes());
    }
    t.resize(TermSize::new(5, 20));
    t.feed(b"\x1b[");
    t.feed(b"2J\x1b[H$ ");
    t.resize(TermSize::new(10, 20));
    assert_eq!(t.snapshot().row_text(0), "$");
}
