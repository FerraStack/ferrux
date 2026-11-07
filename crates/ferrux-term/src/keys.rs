// SPDX-License-Identifier: Apache-2.0
//! Keyboard and paste encoding: turns key presses into the bytes a program
//! running in the terminal expects (xterm conventions).
//!
//! The numeric key codes ([`Key::code`]) and modifier bits are a stable FFI
//! contract shared with the Kotlin `TerminalKeys` object and with Ferride.

use std::fmt;

/// Modifier key bitmask.
#[derive(Clone, Copy, PartialEq, Eq, Hash, Default)]
pub struct Modifiers(u8);

impl Modifiers {
    pub const NONE: Self = Self(0);
    pub const SHIFT: Self = Self(1);
    pub const ALT: Self = Self(2);
    pub const CTRL: Self = Self(4);
    pub const META: Self = Self(8);

    /// Builds modifiers from FFI bits; unknown bits are ignored.
    pub const fn from_bits_truncate(bits: u32) -> Self {
        Self((bits & 0xF) as u8)
    }

    pub const fn bits(self) -> u8 {
        self.0
    }

    pub const fn contains(self, other: Self) -> bool {
        self.0 & other.0 == other.0 && other.0 != 0
    }

    pub const fn is_empty(self) -> bool {
        self.0 == 0
    }

    #[must_use]
    pub const fn without(self, other: Self) -> Self {
        Self(self.0 & !other.0)
    }

    /// The xterm modifier parameter (`1 + shift + 2*alt + 4*ctrl + 8*meta`),
    /// or `None` when no modifier is held.
    fn xterm_param(self) -> Option<u8> {
        (!self.is_empty()).then_some(1 + self.0)
    }
}

impl std::ops::BitOr for Modifiers {
    type Output = Self;
    fn bitor(self, rhs: Self) -> Self {
        Self(self.0 | rhs.0)
    }
}

impl std::ops::BitOrAssign for Modifiers {
    fn bitor_assign(&mut self, rhs: Self) {
        self.0 |= rhs.0;
    }
}

impl fmt::Debug for Modifiers {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let names = [
            (Self::SHIFT, "SHIFT"),
            (Self::ALT, "ALT"),
            (Self::CTRL, "CTRL"),
            (Self::META, "META"),
        ];
        let set: Vec<_> = names
            .iter()
            .filter(|(m, _)| self.contains(*m))
            .map(|(_, n)| *n)
            .collect();
        write!(
            f,
            "Modifiers({})",
            if set.is_empty() {
                "NONE".into()
            } else {
                set.join("|")
            }
        )
    }
}

/// Non-text keys.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Key {
    Enter,
    Tab,
    Backspace,
    Escape,
    Up,
    Down,
    Left,
    Right,
    Home,
    End,
    PageUp,
    PageDown,
    Insert,
    Delete,
    /// Function key F1..=F12.
    F(u8),
}

impl Key {
    /// Stable numeric code used across FFI boundaries.
    pub const fn code(self) -> u32 {
        match self {
            Key::Enter => 1,
            Key::Tab => 2,
            Key::Backspace => 3,
            Key::Escape => 4,
            Key::Up => 5,
            Key::Down => 6,
            Key::Left => 7,
            Key::Right => 8,
            Key::Home => 9,
            Key::End => 10,
            Key::PageUp => 11,
            Key::PageDown => 12,
            Key::Insert => 13,
            Key::Delete => 14,
            Key::F(n) => 100 + n as u32,
        }
    }

    /// Inverse of [`Key::code`].
    pub const fn from_code(code: u32) -> Option<Self> {
        Some(match code {
            1 => Key::Enter,
            2 => Key::Tab,
            3 => Key::Backspace,
            4 => Key::Escape,
            5 => Key::Up,
            6 => Key::Down,
            7 => Key::Left,
            8 => Key::Right,
            9 => Key::Home,
            10 => Key::End,
            11 => Key::PageUp,
            12 => Key::PageDown,
            13 => Key::Insert,
            14 => Key::Delete,
            101..=112 => Key::F((code - 100) as u8),
            _ => return None,
        })
    }
}

/// Terminal modes that change how keys are encoded.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct KeyMode {
    /// DECCKM: cursor keys send `ESC O x` instead of `ESC [ x`.
    pub app_cursor: bool,
}

/// Encodes a non-text key.
pub fn encode_key(key: Key, mods: Modifiers, mode: KeyMode) -> Vec<u8> {
    let alt_prefixed = |bytes: &[u8]| -> Vec<u8> {
        let mut out = Vec::with_capacity(bytes.len() + 1);
        if mods.contains(Modifiers::ALT) {
            out.push(0x1b);
        }
        out.extend_from_slice(bytes);
        out
    };
    match key {
        Key::Enter => alt_prefixed(b"\r"),
        Key::Tab if mods.contains(Modifiers::SHIFT) => b"\x1b[Z".to_vec(),
        Key::Tab => alt_prefixed(b"\t"),
        Key::Backspace if mods.contains(Modifiers::CTRL) => alt_prefixed(b"\x08"),
        Key::Backspace => alt_prefixed(b"\x7f"),
        Key::Escape => alt_prefixed(b"\x1b"),
        Key::Up => cursor_key(b'A', mods, mode),
        Key::Down => cursor_key(b'B', mods, mode),
        Key::Right => cursor_key(b'C', mods, mode),
        Key::Left => cursor_key(b'D', mods, mode),
        Key::Home => cursor_key(b'H', mods, mode),
        Key::End => cursor_key(b'F', mods, mode),
        Key::Insert => tilde_key(2, mods),
        Key::Delete => tilde_key(3, mods),
        Key::PageUp => tilde_key(5, mods),
        Key::PageDown => tilde_key(6, mods),
        Key::F(n @ 1..=4) => {
            let final_byte = b'P' + (n - 1);
            match mods.xterm_param() {
                Some(m) => format!("\x1b[1;{m}{}", final_byte as char).into_bytes(),
                None => vec![0x1b, b'O', final_byte],
            }
        }
        Key::F(n @ 5..=12) => {
            const CODES: [u8; 8] = [15, 17, 18, 19, 20, 21, 23, 24];
            tilde_key(CODES[(n - 5) as usize], mods)
        }
        Key::F(_) => Vec::new(),
    }
}

fn cursor_key(final_byte: u8, mods: Modifiers, mode: KeyMode) -> Vec<u8> {
    match mods.xterm_param() {
        Some(m) => format!("\x1b[1;{m}{}", final_byte as char).into_bytes(),
        None if mode.app_cursor => vec![0x1b, b'O', final_byte],
        None => vec![0x1b, b'[', final_byte],
    }
}

fn tilde_key(code: u8, mods: Modifiers) -> Vec<u8> {
    match mods.xterm_param() {
        Some(m) => format!("\x1b[{code};{m}~").into_bytes(),
        None => format!("\x1b[{code}~").into_bytes(),
    }
}

/// Encodes a typed character with modifiers.
///
/// CTRL maps to the C0 control (`Ctrl+C` → `0x03`), ALT prefixes `ESC`.
/// SHIFT is assumed to be already applied by the keyboard (the char is the
/// shifted one).
pub fn encode_char(c: char, mods: Modifiers) -> Vec<u8> {
    let mut out = Vec::with_capacity(5);
    if mods.contains(Modifiers::ALT) {
        out.push(0x1b);
    }
    match ctrl_byte(c).filter(|_| mods.contains(Modifiers::CTRL)) {
        Some(b) => out.push(b),
        None => {
            let mut buf = [0u8; 4];
            out.extend_from_slice(c.encode_utf8(&mut buf).as_bytes());
        }
    }
    out
}

/// Encodes a string as typed text (each char through [`encode_char`]).
pub fn encode_text(text: &str, mods: Modifiers) -> Vec<u8> {
    if mods.without(Modifiers::SHIFT).is_empty() {
        return text.as_bytes().to_vec();
    }
    text.chars().flat_map(|c| encode_char(c, mods)).collect()
}

/// The C0 control byte for `Ctrl+c`, following xterm.
fn ctrl_byte(c: char) -> Option<u8> {
    Some(match c {
        'a'..='z' => c as u8 - b'a' + 1,
        'A'..='Z' => c as u8 - b'A' + 1,
        '@' | ' ' | '2' => 0,
        '[' | '3' => 0x1b,
        '\\' | '4' => 0x1c,
        ']' | '5' => 0x1d,
        '^' | '6' => 0x1e,
        '_' | '/' | '-' | '7' => 0x1f,
        '?' | '8' => 0x7f,
        _ => return None,
    })
}

/// Encodes pasted text.
///
/// With bracketed paste (DECSET 2004) the text is wrapped in
/// `ESC[200~ … ESC[201~`; `ESC` bytes inside the text are dropped so a
/// malicious clipboard can't terminate the bracket early. Without it, line
/// endings are normalised to `\r`, which is what the Enter key sends.
pub fn encode_paste(text: &str, bracketed: bool) -> Vec<u8> {
    let normalised = text.replace("\r\n", "\r").replace('\n', "\r");
    if bracketed {
        let mut out = Vec::with_capacity(normalised.len() + 12);
        out.extend_from_slice(b"\x1b[200~");
        out.extend(normalised.bytes().filter(|&b| b != 0x1b));
        out.extend_from_slice(b"\x1b[201~");
        out
    } else {
        normalised.into_bytes()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const NORMAL: KeyMode = KeyMode { app_cursor: false };
    const APP: KeyMode = KeyMode { app_cursor: true };

    #[test]
    fn key_codes_round_trip() {
        for code in (1..=14).chain(101..=112) {
            let key = Key::from_code(code).unwrap();
            assert_eq!(key.code(), code);
        }
        assert_eq!(Key::from_code(0), None);
        assert_eq!(Key::from_code(15), None);
        assert_eq!(Key::from_code(113), None);
    }

    #[test]
    fn plain_keys() {
        assert_eq!(encode_key(Key::Enter, Modifiers::NONE, NORMAL), b"\r");
        assert_eq!(encode_key(Key::Tab, Modifiers::NONE, NORMAL), b"\t");
        assert_eq!(encode_key(Key::Tab, Modifiers::SHIFT, NORMAL), b"\x1b[Z");
        assert_eq!(encode_key(Key::Backspace, Modifiers::NONE, NORMAL), b"\x7f");
        assert_eq!(encode_key(Key::Backspace, Modifiers::CTRL, NORMAL), b"\x08");
        assert_eq!(encode_key(Key::Escape, Modifiers::NONE, NORMAL), b"\x1b");
        assert_eq!(encode_key(Key::Enter, Modifiers::ALT, NORMAL), b"\x1b\r");
    }

    #[test]
    fn cursor_keys_respect_decckm() {
        assert_eq!(encode_key(Key::Up, Modifiers::NONE, NORMAL), b"\x1b[A");
        assert_eq!(encode_key(Key::Up, Modifiers::NONE, APP), b"\x1bOA");
        assert_eq!(encode_key(Key::Left, Modifiers::NONE, APP), b"\x1bOD");
        assert_eq!(encode_key(Key::Home, Modifiers::NONE, NORMAL), b"\x1b[H");
        assert_eq!(encode_key(Key::End, Modifiers::NONE, APP), b"\x1bOF");
    }

    #[test]
    fn modified_cursor_keys_use_csi_params() {
        assert_eq!(encode_key(Key::Right, Modifiers::CTRL, APP), b"\x1b[1;5C");
        assert_eq!(encode_key(Key::Up, Modifiers::SHIFT, NORMAL), b"\x1b[1;2A");
        assert_eq!(
            encode_key(Key::Down, Modifiers::ALT | Modifiers::SHIFT, NORMAL),
            b"\x1b[1;4B"
        );
    }

    #[test]
    fn tilde_and_function_keys() {
        assert_eq!(encode_key(Key::Delete, Modifiers::NONE, NORMAL), b"\x1b[3~");
        assert_eq!(encode_key(Key::PageUp, Modifiers::NONE, NORMAL), b"\x1b[5~");
        assert_eq!(
            encode_key(Key::PageDown, Modifiers::CTRL, NORMAL),
            b"\x1b[6;5~"
        );
        assert_eq!(encode_key(Key::F(1), Modifiers::NONE, NORMAL), b"\x1bOP");
        assert_eq!(
            encode_key(Key::F(4), Modifiers::SHIFT, NORMAL),
            b"\x1b[1;2S"
        );
        assert_eq!(encode_key(Key::F(5), Modifiers::NONE, NORMAL), b"\x1b[15~");
        assert_eq!(encode_key(Key::F(12), Modifiers::NONE, NORMAL), b"\x1b[24~");
    }

    #[test]
    fn ctrl_and_alt_chars() {
        assert_eq!(encode_char('c', Modifiers::CTRL), [0x03]);
        assert_eq!(encode_char('C', Modifiers::CTRL), [0x03]);
        assert_eq!(encode_char(' ', Modifiers::CTRL), [0x00]);
        assert_eq!(encode_char('[', Modifiers::CTRL), [0x1b]);
        assert_eq!(encode_char('?', Modifiers::CTRL), [0x7f]);
        assert_eq!(encode_char('x', Modifiers::ALT), b"\x1bx");
        assert_eq!(
            encode_char('d', Modifiers::ALT | Modifiers::CTRL),
            [0x1b, 0x04]
        );
        // Ctrl has no meaning for most non-ASCII chars: send them as-is.
        assert_eq!(encode_char('é', Modifiers::CTRL), "é".as_bytes());
    }

    #[test]
    fn text_is_utf8_passthrough_without_modifiers() {
        assert_eq!(
            encode_text("héllo ✓", Modifiers::NONE),
            "héllo ✓".as_bytes()
        );
        assert_eq!(encode_text("ab", Modifiers::CTRL), [0x01, 0x02]);
    }

    #[test]
    fn paste_modes() {
        assert_eq!(encode_paste("a\nb\r\nc", false), b"a\rb\rc");
        assert_eq!(
            encode_paste("x\x1b[201~y", true),
            b"\x1b[200~x[201~y\x1b[201~"
        );
    }

    #[test]
    fn modifiers_debug_and_bits() {
        let m = Modifiers::from_bits_truncate(0xFF);
        assert_eq!(m.bits(), 0xF);
        assert_eq!(
            format!("{:?}", Modifiers::CTRL | Modifiers::ALT),
            "Modifiers(ALT|CTRL)"
        );
        assert!(!Modifiers::NONE.contains(Modifiers::NONE));
    }
}
