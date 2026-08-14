//! Resolve alacritty/vte colors to opaque ARGB for Compose.

use alacritty_terminal::vte::ansi::{Color, NamedColor, Rgb};

/// Mutable theme palette pushed from Andy Settings (BossTerm Theme ARGB values).
#[derive(Debug, Clone)]
pub struct ColorPalette {
    pub foreground: u32,
    pub background: u32,
    pub cursor: u32,
    pub ansi16: [u32; 16],
}

impl Default for ColorPalette {
    fn default() -> Self {
        Self {
            foreground: 0xFF_AB_B2_BF,
            background: 0xFF_28_2C_34,
            cursor: 0xFF_AB_B2_BF,
            ansi16: [
                0xFF_28_2C_34, // black
                0xFF_E0_6C_75, // red
                0xFF_98_C3_79, // green
                0xFF_E5_C0_7B, // yellow
                0xFF_61_AF_EF, // blue
                0xFF_C6_78_DD, // magenta
                0xFF_56_B6_C2, // cyan
                0xFF_AB_B2_BF, // white
                0xFF_5C_63_70, // bright black
                0xFF_E0_6C_75, // bright red
                0xFF_98_C3_79, // bright green
                0xFF_E5_C0_7B, // bright yellow
                0xFF_61_AF_EF, // bright blue
                0xFF_C6_78_DD, // bright magenta
                0xFF_56_B6_C2, // bright cyan
                0xFF_FF_FF_FF, // bright white
            ],
        }
    }
}

pub fn color_to_argb(color: Color, palette: &ColorPalette) -> u32 {
    match color {
        Color::Named(named) => named_to_argb(named, palette),
        Color::Spec(rgb) => rgb_to_argb(rgb),
        Color::Indexed(idx) => indexed_to_argb(idx, palette),
    }
}

fn named_to_argb(named: NamedColor, palette: &ColorPalette) -> u32 {
    match named {
        NamedColor::Black => palette.ansi16[0],
        NamedColor::Red => palette.ansi16[1],
        NamedColor::Green => palette.ansi16[2],
        NamedColor::Yellow => palette.ansi16[3],
        NamedColor::Blue => palette.ansi16[4],
        NamedColor::Magenta => palette.ansi16[5],
        NamedColor::Cyan => palette.ansi16[6],
        NamedColor::White => palette.ansi16[7],
        NamedColor::BrightBlack => palette.ansi16[8],
        NamedColor::BrightRed => palette.ansi16[9],
        NamedColor::BrightGreen => palette.ansi16[10],
        NamedColor::BrightYellow => palette.ansi16[11],
        NamedColor::BrightBlue => palette.ansi16[12],
        NamedColor::BrightMagenta => palette.ansi16[13],
        NamedColor::BrightCyan => palette.ansi16[14],
        NamedColor::BrightWhite => palette.ansi16[15],
        NamedColor::Foreground | NamedColor::BrightForeground | NamedColor::DimForeground => {
            palette.foreground
        }
        NamedColor::Background => palette.background,
        NamedColor::Cursor => palette.cursor,
        NamedColor::DimBlack => palette.ansi16[0],
        NamedColor::DimRed => palette.ansi16[1],
        NamedColor::DimGreen => palette.ansi16[2],
        NamedColor::DimYellow => palette.ansi16[3],
        NamedColor::DimBlue => palette.ansi16[4],
        NamedColor::DimMagenta => palette.ansi16[5],
        NamedColor::DimCyan => palette.ansi16[6],
        NamedColor::DimWhite => palette.ansi16[7],
    }
}

fn rgb_to_argb(rgb: Rgb) -> u32 {
    0xFF00_0000 | ((rgb.r as u32) << 16) | ((rgb.g as u32) << 8) | (rgb.b as u32)
}

fn indexed_to_argb(idx: u8, palette: &ColorPalette) -> u32 {
    match idx {
        0..=15 => palette.ansi16[idx as usize],
        16..=231 => {
            let n = idx - 16;
            let r = n / 36;
            let g = (n % 36) / 6;
            let b = n % 6;
            let level = |v: u8| if v == 0 { 0 } else { 55 + 40 * v };
            0xFF00_0000 | ((level(r) as u32) << 16) | ((level(g) as u32) << 8) | (level(b) as u32)
        }
        232..=255 => {
            let gray = 8 + 10 * (idx - 232) as u32;
            0xFF00_0000 | (gray << 16) | (gray << 8) | gray
        }
    }
}

pub const ATTR_BOLD: u8 = 1;
pub const ATTR_ITALIC: u8 = 2;
pub const ATTR_UNDERLINE: u8 = 4;
pub const ATTR_INVERSE: u8 = 8;
pub const ATTR_DIM: u8 = 16;
pub const ATTR_STRIKE: u8 = 32;

/// Mouse reporting capability flags returned to Kotlin.
pub const MOUSE_FLAG_REPORTING: u32 = 1;
pub const MOUSE_FLAG_SGR: u32 = 2;
pub const MOUSE_FLAG_MOTION: u32 = 4;
pub const MOUSE_FLAG_DRAG: u32 = 8;
pub const MOUSE_FLAG_ALT_SCROLL: u32 = 16;
