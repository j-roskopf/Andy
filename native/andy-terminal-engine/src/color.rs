//! Resolve alacritty/vte colors to opaque ARGB for Compose.

use alacritty_terminal::vte::ansi::{Color, NamedColor, Rgb};

/// One Dark–ish defaults used until Andy pushes a live theme palette over JNI.
const DEFAULT_FG: u32 = 0xFF_AB_B2_BF;
const DEFAULT_BG: u32 = 0xFF_28_2C_34;

const ANSI16: [u32; 16] = [
    0xFF_28_2C_34, // black / bg
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
];

pub fn color_to_argb(color: Color) -> u32 {
    match color {
        Color::Named(named) => named_to_argb(named),
        Color::Spec(rgb) => rgb_to_argb(rgb),
        Color::Indexed(idx) => indexed_to_argb(idx),
    }
}

fn named_to_argb(named: NamedColor) -> u32 {
    match named {
        NamedColor::Black => ANSI16[0],
        NamedColor::Red => ANSI16[1],
        NamedColor::Green => ANSI16[2],
        NamedColor::Yellow => ANSI16[3],
        NamedColor::Blue => ANSI16[4],
        NamedColor::Magenta => ANSI16[5],
        NamedColor::Cyan => ANSI16[6],
        NamedColor::White => ANSI16[7],
        NamedColor::BrightBlack => ANSI16[8],
        NamedColor::BrightRed => ANSI16[9],
        NamedColor::BrightGreen => ANSI16[10],
        NamedColor::BrightYellow => ANSI16[11],
        NamedColor::BrightBlue => ANSI16[12],
        NamedColor::BrightMagenta => ANSI16[13],
        NamedColor::BrightCyan => ANSI16[14],
        NamedColor::BrightWhite => ANSI16[15],
        NamedColor::Foreground | NamedColor::BrightForeground | NamedColor::DimForeground => {
            DEFAULT_FG
        }
        NamedColor::Background => DEFAULT_BG,
        NamedColor::Cursor => DEFAULT_FG,
        NamedColor::DimBlack => ANSI16[0],
        NamedColor::DimRed => ANSI16[1],
        NamedColor::DimGreen => ANSI16[2],
        NamedColor::DimYellow => ANSI16[3],
        NamedColor::DimBlue => ANSI16[4],
        NamedColor::DimMagenta => ANSI16[5],
        NamedColor::DimCyan => ANSI16[6],
        NamedColor::DimWhite => ANSI16[7],
    }
}

fn rgb_to_argb(rgb: Rgb) -> u32 {
    0xFF00_0000 | ((rgb.r as u32) << 16) | ((rgb.g as u32) << 8) | (rgb.b as u32)
}

fn indexed_to_argb(idx: u8) -> u32 {
    match idx {
        0..=15 => ANSI16[idx as usize],
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
