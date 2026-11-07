// SPDX-License-Identifier: Apache-2.0
//! Mouse reporting (xterm): turns touches, taps and wheel scrolls into the
//! escape sequences a program asked for with DECSET 1000/1002/1003, in the
//! X10-compatible, UTF-8 (1005) or SGR (1006) encoding.
//!
//! [`MouseButton::code`] and [`MouseAction::code`] are a stable FFI contract
//! with the Kotlin `TerminalKeys` object.

use crate::keys::Modifiers;

/// Which button an event is about.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum MouseButton {
    Left,
    Middle,
    Right,
    /// One notch of the wheel towards older content (scroll up).
    WheelUp,
    /// One notch of the wheel towards newer content (scroll down).
    WheelDown,
    /// Motion with no button held (only reported in any-event mode).
    None,
}

impl MouseButton {
    /// FFI code: 0 left, 1 middle, 2 right, 4 wheel up, 5 wheel down, 3 none.
    pub const fn code(self) -> u32 {
        match self {
            MouseButton::Left => 0,
            MouseButton::Middle => 1,
            MouseButton::Right => 2,
            MouseButton::None => 3,
            MouseButton::WheelUp => 4,
            MouseButton::WheelDown => 5,
        }
    }

    pub const fn from_code(code: u32) -> Option<Self> {
        Some(match code {
            0 => MouseButton::Left,
            1 => MouseButton::Middle,
            2 => MouseButton::Right,
            3 => MouseButton::None,
            4 => MouseButton::WheelUp,
            5 => MouseButton::WheelDown,
            _ => return None,
        })
    }

    fn is_wheel(self) -> bool {
        matches!(self, MouseButton::WheelUp | MouseButton::WheelDown)
    }
}

/// What happened to the button.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum MouseAction {
    Press,
    Release,
    /// The pointer moved (with the button held, or with none).
    Motion,
}

impl MouseAction {
    /// FFI code: 0 press, 1 release, 2 motion.
    pub const fn code(self) -> u32 {
        match self {
            MouseAction::Press => 0,
            MouseAction::Release => 1,
            MouseAction::Motion => 2,
        }
    }

    pub const fn from_code(code: u32) -> Option<Self> {
        Some(match code {
            0 => MouseAction::Press,
            1 => MouseAction::Release,
            2 => MouseAction::Motion,
            _ => return None,
        })
    }
}

/// Which events the program wants (DECSET 1000 / 1002 / 1003), and how they
/// are encoded.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct MouseMode {
    /// 1000: presses and releases (and wheel).
    pub click: bool,
    /// 1002: also motion while a button is held.
    pub drag: bool,
    /// 1003: all motion.
    pub motion: bool,
    /// 1006: `CSI < b ; x ; y M/m`.
    pub sgr: bool,
    /// 1005: coordinates as UTF-8 characters.
    pub utf8: bool,
}

impl MouseMode {
    /// Whether any mouse reporting is on.
    pub fn reporting(self) -> bool {
        self.click || self.drag || self.motion
    }
}

/// Encodes a mouse event at a 0-based cell, or returns an empty vector when
/// the current mode doesn't report it.
pub fn encode_mouse(
    button: MouseButton,
    action: MouseAction,
    col: usize,
    row: usize,
    mods: Modifiers,
    mode: MouseMode,
) -> Vec<u8> {
    if !mode.reporting() {
        return Vec::new();
    }
    match action {
        MouseAction::Motion if mode.motion => {}
        MouseAction::Motion if mode.drag && button != MouseButton::None => {}
        MouseAction::Motion => return Vec::new(),
        // Wheels have no release.
        MouseAction::Release if button.is_wheel() => return Vec::new(),
        _ => {}
    }

    let mut cb: u32 = match button {
        MouseButton::Left => 0,
        MouseButton::Middle => 1,
        MouseButton::Right => 2,
        MouseButton::None => 3,
        MouseButton::WheelUp => 64,
        MouseButton::WheelDown => 65,
    };
    if action == MouseAction::Motion {
        cb += 32;
    }
    if mods.contains(Modifiers::SHIFT) {
        cb += 4;
    }
    if mods.contains(Modifiers::ALT) || mods.contains(Modifiers::META) {
        cb += 8;
    }
    if mods.contains(Modifiers::CTRL) {
        cb += 16;
    }
    let (x, y) = (col as u32 + 1, row as u32 + 1);

    if mode.sgr {
        let end = if action == MouseAction::Release {
            'm'
        } else {
            'M'
        };
        return format!("\x1b[<{cb};{x};{y}{end}").into_bytes();
    }
    // The legacy encodings report releases as button 3.
    if action == MouseAction::Release {
        cb = (cb & !0b11) | 3;
    }
    let mut out = b"\x1b[M".to_vec();
    let push = |out: &mut Vec<u8>, v: u32| -> bool {
        let v = v + 32;
        if mode.utf8 {
            // 1005 encodes values up to 2047 as UTF-8.
            match char::from_u32(v).filter(|_| v < 2048) {
                Some(c) => {
                    let mut buf = [0u8; 4];
                    out.extend_from_slice(c.encode_utf8(&mut buf).as_bytes());
                    true
                }
                None => false,
            }
        } else if v <= 255 {
            out.push(v as u8);
            true
        } else {
            false
        }
    };
    if push(&mut out, cb) && push(&mut out, x) && push(&mut out, y) {
        out
    } else {
        // Outside what the encoding can express: xterm sends nothing.
        Vec::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const CLICK: MouseMode = MouseMode {
        click: true,
        drag: false,
        motion: false,
        sgr: false,
        utf8: false,
    };
    const SGR: MouseMode = MouseMode { sgr: true, ..CLICK };

    #[test]
    fn codes_round_trip() {
        for code in 0..=5 {
            assert_eq!(MouseButton::from_code(code).unwrap().code(), code);
        }
        for code in 0..=2 {
            assert_eq!(MouseAction::from_code(code).unwrap().code(), code);
        }
        assert_eq!(MouseButton::from_code(6), None);
        assert_eq!(MouseAction::from_code(3), None);
    }

    #[test]
    fn nothing_is_sent_without_reporting() {
        let off = MouseMode::default();
        let e = encode_mouse(
            MouseButton::Left,
            MouseAction::Press,
            0,
            0,
            Modifiers::NONE,
            off,
        );
        assert!(e.is_empty());
    }

    #[test]
    fn x10_press_release_and_wheel() {
        let none = Modifiers::NONE;
        let press = encode_mouse(MouseButton::Left, MouseAction::Press, 2, 4, none, CLICK);
        assert_eq!(press, b"\x1b[M\x20\x23\x25");
        let release = encode_mouse(MouseButton::Left, MouseAction::Release, 2, 4, none, CLICK);
        assert_eq!(release, b"\x1b[M\x23\x23\x25");
        let wheel = encode_mouse(MouseButton::WheelUp, MouseAction::Press, 0, 0, none, CLICK);
        assert_eq!(wheel, b"\x1b[M\x60\x21\x21");
        let down = encode_mouse(
            MouseButton::WheelDown,
            MouseAction::Press,
            0,
            0,
            none,
            CLICK,
        );
        assert_eq!(down, b"\x1b[M\x61\x21\x21");
        // Wheel releases don't exist; motion isn't reported in click mode.
        assert!(
            encode_mouse(
                MouseButton::WheelUp,
                MouseAction::Release,
                0,
                0,
                none,
                CLICK
            )
            .is_empty()
        );
        assert!(encode_mouse(MouseButton::Left, MouseAction::Motion, 0, 0, none, CLICK).is_empty());
        // Beyond column 223 the X10 encoding can't express the position.
        assert!(
            encode_mouse(MouseButton::Left, MouseAction::Press, 300, 0, none, CLICK).is_empty()
        );
    }

    #[test]
    fn sgr_encoding_with_modifiers() {
        let p = encode_mouse(
            MouseButton::Left,
            MouseAction::Press,
            299,
            9,
            Modifiers::CTRL | Modifiers::SHIFT,
            SGR,
        );
        assert_eq!(p, b"\x1b[<20;300;10M");
        let r = encode_mouse(
            MouseButton::Right,
            MouseAction::Release,
            0,
            0,
            Modifiers::ALT,
            SGR,
        );
        assert_eq!(r, b"\x1b[<10;1;1m");
        let w = encode_mouse(
            MouseButton::WheelDown,
            MouseAction::Press,
            4,
            2,
            Modifiers::NONE,
            SGR,
        );
        assert_eq!(w, b"\x1b[<65;5;3M");
    }

    #[test]
    fn motion_depends_on_the_tracking_mode() {
        let drag = MouseMode { drag: true, ..SGR };
        let held = encode_mouse(
            MouseButton::Left,
            MouseAction::Motion,
            1,
            1,
            Modifiers::NONE,
            drag,
        );
        assert_eq!(held, b"\x1b[<32;2;2M");
        assert!(
            encode_mouse(
                MouseButton::None,
                MouseAction::Motion,
                1,
                1,
                Modifiers::NONE,
                drag
            )
            .is_empty()
        );
        let any = MouseMode {
            motion: true,
            ..SGR
        };
        let hover = encode_mouse(
            MouseButton::None,
            MouseAction::Motion,
            1,
            1,
            Modifiers::NONE,
            any,
        );
        assert_eq!(hover, b"\x1b[<35;2;2M");
    }

    #[test]
    fn utf8_extends_the_range() {
        let utf8 = MouseMode {
            utf8: true,
            ..CLICK
        };
        let e = encode_mouse(
            MouseButton::Left,
            MouseAction::Press,
            299,
            0,
            Modifiers::NONE,
            utf8,
        );
        let mut expected = b"\x1b[M\x20".to_vec();
        expected.extend("\u{14C}".as_bytes()); // 300 + 32 = 332
        expected.push(0x21);
        assert_eq!(e, expected);
    }
}
