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
}

impl Default for Palette {
    /// A dark theme. The 16 ANSI colors are Ferrux's own picks; entries
    /// 16..256 are the standard xterm 6×6×6 cube and grayscale ramp.
    fn default() -> Self {
        const ANSI: [u32; 16] = [
            0x1c1f24, 0xe06c75, 0x98c379, 0xe5c07b, 0x61afef, 0xc678dd, 0x56b6c2, 0xc8ccd4,
            0x5c6370, 0xff7b86, 0xb5e890, 0xffd68a, 0x80c4ff, 0xde9bf0, 0x7fd6e0, 0xffffff,
        ];
        let mut indexed = [Rgb::default(); 256];
        for (i, c) in ANSI.iter().enumerate() {
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
            foreground: Rgb::from_u32(0xd7dae0),
            background: Rgb::from_u32(0x101216),
            cursor: Rgb::from_u32(0xf0a03c),
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
