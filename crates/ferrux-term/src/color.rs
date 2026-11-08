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
    /// The "Ferrux Night" [`Theme`].
    fn default() -> Self {
        THEMES[0].palette()
    }
}

/// A bundled colour theme: the 16 ANSI colours and the special colours, as
/// `0xRRGGBB`. Entries 16..256 are always the standard xterm cube and ramp.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Theme {
    /// Stable identifier, used in settings files.
    pub id: &'static str,
    /// Display name.
    pub name: &'static str,
    pub ansi: [u32; 16],
    pub foreground: u32,
    pub background: u32,
    pub cursor: u32,
    pub selection: u32,
    /// Accent for the UI around the terminal (active toggles, switches).
    pub accent: u32,
    /// Whether the background is dark (for system bar icons and chrome).
    pub dark: bool,
}

impl Theme {
    /// Looks a theme up by [`Theme::id`].
    pub fn by_id(id: &str) -> Option<&'static Theme> {
        THEMES.iter().find(|t| t.id == id)
    }

    /// The full 256-colour palette.
    pub fn palette(&self) -> Palette {
        let mut indexed = [Rgb::default(); 256];
        for (i, c) in self.ansi.iter().enumerate() {
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
        Palette {
            indexed,
            foreground: Rgb::from_u32(self.foreground),
            background: Rgb::from_u32(self.background),
            cursor: Rgb::from_u32(self.cursor),
            selection: Rgb::from_u32(self.selection),
        }
    }
}

const SOLARIZED_ANSI: [u32; 16] = [
    0x073642, 0xdc322f, 0x859900, 0xb58900, 0x268bd2, 0xd33682, 0x2aa198, 0xeee8d5, //
    0x002b36, 0xcb4b16, 0x586e75, 0x657b83, 0x839496, 0x6c71c4, 0x93a1a1, 0xfdf6e3,
];

/// The bundled themes; the first is the default. Gruvbox, Nord, Dracula and
/// Solarized use their published (MIT-licensed) palettes.
pub const THEMES: &[Theme] = &[
    Theme {
        id: "ferrux-night",
        name: "Ferrux Night",
        ansi: FERRUX_NIGHT_ANSI,
        foreground: 0xd6dce5,
        background: 0x0e1116,
        cursor: 0xf29e4c,
        selection: 0x264166,
        accent: 0xf29e4c,
        dark: true,
    },
    Theme {
        id: "gruvbox-dark",
        name: "Gruvbox Dark",
        ansi: [
            0x282828, 0xcc241d, 0x98971a, 0xd79921, 0x458588, 0xb16286, 0x689d6a, 0xa89984, //
            0x928374, 0xfb4934, 0xb8bb26, 0xfabd2f, 0x83a598, 0xd3869b, 0x8ec07c, 0xebdbb2,
        ],
        foreground: 0xebdbb2,
        background: 0x282828,
        cursor: 0xebdbb2,
        selection: 0x504945,
        accent: 0xfe8019,
        dark: true,
    },
    Theme {
        id: "nord",
        name: "Nord",
        ansi: [
            0x3b4252, 0xbf616a, 0xa3be8c, 0xebcb8b, 0x81a1c1, 0xb48ead, 0x88c0d0, 0xe5e9f0, //
            0x4c566a, 0xbf616a, 0xa3be8c, 0xebcb8b, 0x81a1c1, 0xb48ead, 0x8fbcbb, 0xeceff4,
        ],
        foreground: 0xd8dee9,
        background: 0x2e3440,
        cursor: 0xd8dee9,
        selection: 0x434c5e,
        accent: 0x88c0d0,
        dark: true,
    },
    Theme {
        id: "dracula",
        name: "Dracula",
        ansi: [
            0x21222c, 0xff5555, 0x50fa7b, 0xf1fa8c, 0xbd93f9, 0xff79c6, 0x8be9fd, 0xf8f8f2, //
            0x6272a4, 0xff6e6e, 0x69ff94, 0xffffa5, 0xd6acff, 0xff92df, 0xa4ffff, 0xffffff,
        ],
        foreground: 0xf8f8f2,
        background: 0x282a36,
        cursor: 0xf8f8f2,
        selection: 0x44475a,
        accent: 0xbd93f9,
        dark: true,
    },
    Theme {
        id: "solarized-dark",
        name: "Solarized Dark",
        ansi: SOLARIZED_ANSI,
        foreground: 0x839496,
        background: 0x002b36,
        cursor: 0x93a1a1,
        selection: 0x073642,
        accent: 0x268bd2,
        dark: true,
    },
    Theme {
        id: "solarized-light",
        name: "Solarized Light",
        ansi: SOLARIZED_ANSI,
        foreground: 0x657b83,
        background: 0xfdf6e3,
        cursor: 0x586e75,
        selection: 0xeee8d5,
        accent: 0x268bd2,
        dark: false,
    },
];

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
    fn themes_have_unique_ids_and_build_palettes() {
        let mut ids: Vec<_> = THEMES.iter().map(|t| t.id).collect();
        ids.sort_unstable();
        ids.dedup();
        assert_eq!(ids.len(), THEMES.len());
        assert_eq!(
            Palette::default(),
            Theme::by_id("ferrux-night").unwrap().palette()
        );
        let nord = Theme::by_id("nord").unwrap().palette();
        assert_eq!(nord.background, Rgb::from_u32(0x2e3440));
        assert_eq!(nord.indexed[14], Rgb::from_u32(0x8fbcbb));
        assert_eq!(nord.indexed[196], Rgb::new(255, 0, 0), "cube is shared");
        assert!(Theme::by_id("nope").is_none());
        assert!(!Theme::by_id("solarized-light").unwrap().dark);
    }

    #[test]
    fn argb_is_opaque() {
        assert_eq!(Rgb::new(1, 2, 3).to_argb(), 0xFF01_0203);
    }
}
