// SPDX-License-Identifier: Apache-2.0
//! Colors and the default palette.

/// A 24-bit RGB color.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub struct Rgb {
    pub r: u8,
    pub g: u8,
    pub b: u8,
}

impl Rgb {
    pub const fn new(r: u8, g: u8, b: u8) -> Self {
        Self { r, g, b }
    }

    /// Builds a color from `0xRRGGBB`.
    pub const fn from_u32(rgb: u32) -> Self {
        Self::new((rgb >> 16) as u8, (rgb >> 8) as u8, rgb as u8)
    }

    /// Opaque `0xAARRGGBB`, the layout Android's `Canvas`/`Paint` use.
    pub const fn to_argb(self) -> u32 {
        0xFF00_0000 | (self.r as u32) << 16 | (self.g as u32) << 8 | self.b as u32
    }

    /// Scales the color towards black (used for SGR 2 "dim").
    pub fn dim(self) -> Self {
        let f = |c: u8| ((c as u16 * 2) / 3) as u8;
        Self::new(f(self.r), f(self.g), f(self.b))
    }
}

impl From<alacritty_terminal::vte::ansi::Rgb> for Rgb {
    fn from(c: alacritty_terminal::vte::ansi::Rgb) -> Self {
        Self::new(c.r, c.g, c.b)
    }
}

impl From<Rgb> for alacritty_terminal::vte::ansi::Rgb {
    fn from(c: Rgb) -> Self {
        Self {
            r: c.r,
            g: c.g,
            b: c.b,
        }
    }
}

/// The color theme: 256 indexed colors plus the default foreground,
/// background and cursor colors.
///
/// Programs may override individual entries at runtime with OSC 4/10/11/12;
/// those overrides live in the terminal and take precedence over the palette.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Palette {
    pub indexed: [Rgb; 256],
    pub foreground: Rgb,
    pub background: Rgb,
    pub cursor: Rgb,
    /// Background of selected cells (their foreground is kept).
    pub selection: Rgb,
}

/// The default theme, "Ferrux Night": a dark, low-glare palette tuned for
/// small text on phone screens. Documented in the README; keep them in sync.
///
/// | Index | Name    | Normal    | Bright    |
/// |-------|---------|-----------|-----------|
/// | 0 / 8 | black   | `#1C2029` | `#4E5666` |
/// | 1 / 9 | red     | `#F0717A` | `#FF8E96` |
/// | 2 /10 | green   | `#9BD68A` | `#B6EBA3` |
/// | 3 /11 | yellow  | `#EBC47A` | `#F7D99B` |
/// | 4 /12 | blue    | `#6CB4F5` | `#92CBFF` |
/// | 5 /13 | magenta | `#C79BF2` | `#DDB8FF` |
/// | 6 /14 | cyan    | `#5FD1C9` | `#8CE6DF` |
/// | 7 /15 | white   | `#C9D1DC` | `#F2F5F9` |
///
/// Foreground `#D6DCE5`, background `#0E1116`, cursor `#F29E4C`, selection
/// `#264166`.
pub const FERRUX_NIGHT_ANSI: [u32; 16] = [
    0x1c2029, 0xf0717a, 0x9bd68a, 0xebc47a, 0x6cb4f5, 0xc79bf2, 0x5fd1c9, 0xc9d1dc, //
    0x4e5666, 0xff8e96, 0xb6eba3, 0xf7d99b, 0x92cbff, 0xddb8ff, 0x8ce6df, 0xf2f5f9,
];

impl Default for Palette {
    /// [`FERRUX_NIGHT_ANSI`] plus the standard xterm 6×6×6 cube and grayscale
    /// ramp for entries 16..256.
    fn default() -> Self {
        let mut indexed = [Rgb::default(); 256];
        for (i, c) in FERRUX_NIGHT_ANSI.iter().enumerate() {
            indexed[i] = Rgb::from_u32(*c);
        }
        let level = |v: usize| if v == 0 { 0 } else { (55 + v * 40) as u8 };
        for i in 0..216 {
            indexed[16 + i] = Rgb::new(level(i / 36), level((i / 6) % 6), level(i % 6));
        }
        for i in 0..24 {
            let v = (8 + i * 10) as u8;
            indexed[232 + i] = Rgb::new(v, v, v);
        }
        Self {
            indexed,
            foreground: Rgb::from_u32(0xd6dce5),
            background: Rgb::from_u32(0x0e1116),
            cursor: Rgb::from_u32(0xf29e4c),
            selection: Rgb::from_u32(0x264166),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cube_and_ramp_match_xterm() {
        let p = Palette::default();
        assert_eq!(p.indexed[16], Rgb::new(0, 0, 0));
        assert_eq!(p.indexed[196], Rgb::new(255, 0, 0));
        assert_eq!(p.indexed[231], Rgb::new(255, 255, 255));
        assert_eq!(p.indexed[232], Rgb::new(8, 8, 8));
        assert_eq!(p.indexed[255], Rgb::new(238, 238, 238));
    }

    #[test]
    fn argb_is_opaque() {
        assert_eq!(Rgb::new(1, 2, 3).to_argb(), 0xFF01_0203);
    }
}
