//! Minimal ANSI SGR (Select Graphic Rendition) parser.
//!
//! `termimad`'s `Display` impl renders markdown by writing crossterm ANSI
//! escape sequences directly into the formatted string (colors, bold,
//! underline, ...). Ratatui does not interpret those escape sequences: if we
//! hand the raw string to `Line::from`, the escape bytes are treated as
//! literal, invisible-but-position-consuming characters, which corrupts
//! alignment and wrapping. This module turns such a string back into styled
//! ratatui `Line`s so the escape codes become real `Style`s instead of
//! stray bytes in the buffer.

use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};

/// Convert a string containing ANSI SGR escape sequences into styled
/// ratatui lines, one per `\n`-separated input line.
pub fn ansi_text_to_lines(input: &str) -> Vec<Line<'static>> {
    let mut lines = Vec::new();
    let mut spans: Vec<Span<'static>> = Vec::new();
    let mut buf = String::new();
    let mut style = Style::default();

    let mut chars = input.chars().peekable();
    while let Some(c) = chars.next() {
        match c {
            '\n' => {
                if !buf.is_empty() {
                    spans.push(Span::styled(std::mem::take(&mut buf), style));
                }
                lines.push(Line::from(std::mem::take(&mut spans)));
            }
            '\u{1b}' if chars.peek() == Some(&'[') => {
                chars.next(); // consume '['
                let mut params = String::new();
                let mut final_byte = None;
                for c2 in chars.by_ref() {
                    if c2.is_ascii_alphabetic() || c2 == '~' {
                        final_byte = Some(c2);
                        break;
                    }
                    params.push(c2);
                }
                if final_byte == Some('m') {
                    if !buf.is_empty() {
                        spans.push(Span::styled(std::mem::take(&mut buf), style));
                    }
                    apply_sgr(&mut style, &params);
                }
                // Other CSI sequences (cursor movement, etc.) are dropped;
                // termimad only ever emits SGR sequences for text styling.
            }
            _ => buf.push(c),
        }
    }
    if !buf.is_empty() {
        spans.push(Span::styled(buf, style));
    }
    if !spans.is_empty() {
        lines.push(Line::from(spans));
    }
    lines
}

fn apply_sgr(style: &mut Style, params: &str) {
    let codes: Vec<i64> = params
        .split(';')
        .map(|p| p.parse::<i64>().unwrap_or(0))
        .collect();
    let codes = if codes.is_empty() { vec![0] } else { codes };

    let mut i = 0;
    while i < codes.len() {
        match codes[i] {
            0 => *style = Style::default(),
            1 => *style = style.add_modifier(Modifier::BOLD),
            2 => *style = style.add_modifier(Modifier::DIM),
            3 => *style = style.add_modifier(Modifier::ITALIC),
            4 => *style = style.add_modifier(Modifier::UNDERLINED),
            7 => *style = style.add_modifier(Modifier::REVERSED),
            8 => *style = style.add_modifier(Modifier::HIDDEN),
            9 => *style = style.add_modifier(Modifier::CROSSED_OUT),
            22 => *style = style.remove_modifier(Modifier::BOLD | Modifier::DIM),
            23 => *style = style.remove_modifier(Modifier::ITALIC),
            24 => *style = style.remove_modifier(Modifier::UNDERLINED),
            27 => *style = style.remove_modifier(Modifier::REVERSED),
            28 => *style = style.remove_modifier(Modifier::HIDDEN),
            29 => *style = style.remove_modifier(Modifier::CROSSED_OUT),
            30..=37 => style.fg = Some(basic_color((codes[i] - 30) as u8)),
            38 => {
                if let Some((color, consumed)) = extended_color(&codes[i + 1..]) {
                    style.fg = Some(color);
                    i += consumed;
                }
            }
            39 => style.fg = None,
            40..=47 => style.bg = Some(basic_color((codes[i] - 40) as u8)),
            48 => {
                if let Some((color, consumed)) = extended_color(&codes[i + 1..]) {
                    style.bg = Some(color);
                    i += consumed;
                }
            }
            49 => style.bg = None,
            90..=97 => style.fg = Some(bright_color((codes[i] - 90) as u8)),
            100..=107 => style.bg = Some(bright_color((codes[i] - 100) as u8)),
            _ => {}
        }
        i += 1;
    }
}

/// Parses the parameters following a `38` or `48` code (`5;n` or `2;r;g;b`).
/// Returns the resolved color and how many extra params were consumed.
fn extended_color(rest: &[i64]) -> Option<(Color, usize)> {
    match rest.first() {
        Some(5) => rest.get(1).map(|n| (Color::Indexed(*n as u8), 2)),
        Some(2) => {
            if rest.len() >= 4 {
                Some((
                    Color::Rgb(rest[1] as u8, rest[2] as u8, rest[3] as u8),
                    4,
                ))
            } else {
                None
            }
        }
        _ => None,
    }
}

fn basic_color(n: u8) -> Color {
    match n {
        0 => Color::Black,
        1 => Color::Red,
        2 => Color::Green,
        3 => Color::Yellow,
        4 => Color::Blue,
        5 => Color::Magenta,
        6 => Color::Cyan,
        _ => Color::Gray,
    }
}

fn bright_color(n: u8) -> Color {
    match n {
        0 => Color::DarkGray,
        1 => Color::LightRed,
        2 => Color::LightGreen,
        3 => Color::LightYellow,
        4 => Color::LightBlue,
        5 => Color::LightMagenta,
        6 => Color::LightCyan,
        _ => Color::White,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plain_text_round_trips() {
        let lines = ansi_text_to_lines("hello world");
        assert_eq!(lines.len(), 1);
        assert_eq!(lines[0].spans.len(), 1);
        assert_eq!(lines[0].spans[0].content, "hello world");
    }

    #[test]
    fn strips_bold_escape_and_applies_style() {
        let input = "\u{1b}[1mbold\u{1b}[0m plain";
        let lines = ansi_text_to_lines(input);
        assert_eq!(lines.len(), 1);
        assert_eq!(lines[0].spans[0].content, "bold");
        assert!(lines[0].spans[0]
            .style
            .add_modifier
            .contains(Modifier::BOLD));
        assert_eq!(lines[0].spans[1].content, " plain");
        assert!(!lines[0].spans[1]
            .style
            .add_modifier
            .contains(Modifier::BOLD));
    }

    #[test]
    fn splits_multiple_lines() {
        let input = "line one\nline two";
        let lines = ansi_text_to_lines(input);
        assert_eq!(lines.len(), 2);
        assert_eq!(lines[0].spans[0].content, "line one");
        assert_eq!(lines[1].spans[0].content, "line two");
    }

    #[test]
    fn parses_256_color_foreground() {
        let input = "\u{1b}[38;5;242mgray\u{1b}[39m";
        let lines = ansi_text_to_lines(input);
        assert_eq!(lines[0].spans[0].style.fg, Some(Color::Indexed(242)));
    }

    #[test]
    fn empty_lines_are_preserved() {
        let input = "a\n\nb";
        let lines = ansi_text_to_lines(input);
        assert_eq!(lines.len(), 3);
        assert!(lines[1].spans.is_empty());
    }

    #[test]
    fn drops_non_sgr_csi_sequences_without_corrupting_text() {
        // e.g. a cursor-movement sequence sandwiched between text.
        let input = "before\u{1b}[2Kafter";
        let lines = ansi_text_to_lines(input);
        assert_eq!(lines[0].spans[0].content, "beforeafter");
    }
}
