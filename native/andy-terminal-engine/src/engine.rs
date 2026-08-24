//! Headless terminal engine built on `alacritty_terminal`.
//!
//! Andy owns the PTY (via pty4j) and the renderer (Compose/Skia). This type only
//! owns VT parsing + grid state, and exposes a snapshot API the JVM can poll on
//! a coalesced redraw cadence.

use alacritty_terminal::event::VoidListener;
use alacritty_terminal::grid::{Dimensions, Scroll};
use alacritty_terminal::index::{Column, Line};
use alacritty_terminal::term::cell::Flags;
use alacritty_terminal::term::{point_to_viewport, Config, Term, TermMode};
use alacritty_terminal::vte::ansi::{self, Timeout};

use crate::color::{
    color_to_argb, ColorPalette, MOUSE_FLAG_ALT_SCROLL, MOUSE_FLAG_DRAG, MOUSE_FLAG_MOTION,
    MOUSE_FLAG_REPORTING, MOUSE_FLAG_SGR,
};

/// Viewport size in cells.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct EngineSize {
    pub columns: usize,
    pub rows: usize,
}

impl Dimensions for EngineSize {
    fn total_lines(&self) -> usize {
        self.rows
    }

    fn screen_lines(&self) -> usize {
        self.rows
    }

    fn columns(&self) -> usize {
        self.columns
    }
}

/// Compact attribute flags for a single cell (subset useful to Compose).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct CellAttrFlags {
    pub bold: bool,
    pub italic: bool,
    pub underline: bool,
    pub inverse: bool,
    pub dim: bool,
    pub strikethrough: bool,
}

/// One cell of grid state, flattened for FFI transfer.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CellSnapshot {
    pub ch: char,
    /// Opaque ARGB (`0xAARRGGBB`) resolved with the engine palette.
    pub fg_argb: u32,
    pub bg_argb: u32,
    pub attrs: CellAttrFlags,
}

/// Cursor position in the active grid (viewport coordinates).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CursorSnapshot {
    pub row: i32,
    pub col: usize,
}

/// Full viewport snapshot returned to the host.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GridSnapshot {
    pub columns: usize,
    pub rows: usize,
    pub cursor: CursorSnapshot,
    pub alt_screen: bool,
    /// Flat row-major cells: `cells[row * columns + col]`.
    pub cells: Vec<CellSnapshot>,
    /// Bytes currently held in the DEC 2026 sync buffer (0 when not syncing).
    pub sync_buffered_bytes: usize,
    pub display_offset: usize,
    pub history_size: usize,
}

/// Headless VT engine: parse bytes into grid state Andy can render.
pub struct TerminalEngine {
    term: Term<VoidListener>,
    parser: ansi::Processor,
    size: EngineSize,
    palette: ColorPalette,
}

impl TerminalEngine {
    pub fn new(columns: usize, rows: usize) -> Self {
        let size = EngineSize { columns, rows };
        let mut config = Config::default();
        config.scrolling_history = 10_000;
        let term = Term::new(config, &size, VoidListener);
        Self {
            term,
            parser: ansi::Processor::new(),
            size,
            palette: ColorPalette::default(),
        }
    }

    pub fn size(&self) -> EngineSize {
        self.size
    }

    pub fn set_palette(&mut self, palette: ColorPalette) {
        self.palette = palette;
    }

    /// Feed a raw PTY/ANSI chunk. Andy controls when to call this and when to
    /// snapshot/repaint — there is no per-character redraw callback.
    pub fn advance(&mut self, bytes: &[u8]) {
        self.parser.advance(&mut self.term, bytes);
    }

    pub fn resize(&mut self, columns: usize, rows: usize) {
        self.size = EngineSize { columns, rows };
        self.term.resize(self.size);
    }

    pub fn is_alt_screen(&self) -> bool {
        self.term.mode().contains(TermMode::ALT_SCREEN)
    }

    pub fn display_offset(&self) -> usize {
        self.term.grid().display_offset()
    }

    pub fn history_size(&self) -> usize {
        self.term.grid().history_size()
    }

    pub fn scroll_display_delta(&mut self, delta: i32) {
        if delta == 0 {
            return;
        }
        self.term.scroll_display(Scroll::Delta(delta));
    }

    pub fn scroll_display_bottom(&mut self) {
        self.term.scroll_display(Scroll::Bottom);
    }

    /// Mouse reporting capability flags for the host pointer path.
    pub fn bracketed_paste_enabled(&self) -> bool {
        self.term.mode().contains(TermMode::BRACKETED_PASTE)
    }

    pub fn mouse_flags(&self) -> u32 {
        let mode = self.term.mode();
        let mut flags = 0u32;
        if mode.intersects(TermMode::MOUSE_MODE) {
            flags |= MOUSE_FLAG_REPORTING;
        }
        if mode.contains(TermMode::SGR_MOUSE) {
            flags |= MOUSE_FLAG_SGR;
        }
        if mode.contains(TermMode::MOUSE_MOTION) {
            flags |= MOUSE_FLAG_MOTION;
        }
        if mode.contains(TermMode::MOUSE_DRAG) {
            flags |= MOUSE_FLAG_DRAG;
        }
        if mode.contains(TermMode::ALTERNATE_SCROLL) {
            flags |= MOUSE_FLAG_ALT_SCROLL;
        }
        flags
    }

    /// Bytes currently buffered under an open DEC 2026 synchronized update.
    pub fn sync_buffered_bytes(&self) -> usize {
        self.parser.sync_bytes_count()
    }

    pub fn is_synchronized(&self) -> bool {
        self.sync_buffered_bytes() > 0 || self.parser.sync_timeout().pending_timeout()
    }

    /// Force-end a synchronized update (mirrors Alacritty's timeout path).
    pub fn stop_sync(&mut self) {
        self.parser.stop_sync(&mut self.term);
    }

    pub fn snapshot(&self) -> GridSnapshot {
        let columns = self.size.columns;
        let rows = self.size.rows;
        let grid = self.term.grid();
        let display_offset = grid.display_offset();
        let history_size = grid.history_size();
        let cursor_point = grid.cursor.point;
        let cursor_viewport = point_to_viewport(display_offset, cursor_point)
            .map(|p| CursorSnapshot {
                row: p.line as i32,
                col: p.column.0,
            })
            .unwrap_or(CursorSnapshot {
                row: -1,
                col: 0,
            });

        let mut cells = Vec::with_capacity(columns * rows);
        for row in 0..rows {
            // Line 0 is the top of the viewport; history is at negative lines.
            let line = Line(row as i32 - display_offset as i32);
            for col in 0..columns {
                let cell = &grid[line][Column(col)];
                cells.push(cell_to_snapshot(cell, &self.palette));
            }
        }

        GridSnapshot {
            columns,
            rows,
            cursor: cursor_viewport,
            alt_screen: self.is_alt_screen(),
            cells,
            sync_buffered_bytes: self.sync_buffered_bytes(),
            display_offset,
            history_size,
        }
    }

    /// Visible viewport as plain text (trailing spaces trimmed per line).
    pub fn viewport_text(&self) -> String {
        let snap = self.snapshot();
        let mut lines = Vec::with_capacity(snap.rows);
        for row in 0..snap.rows {
            let start = row * snap.columns;
            let end = start + snap.columns;
            let line: String = snap.cells[start..end].iter().map(|c| c.ch).collect();
            lines.push(line.trim_end().to_string());
        }
        while lines.last().is_some_and(|l| l.is_empty()) {
            lines.pop();
        }
        lines.join("\n")
    }
}

fn cell_to_snapshot(
    cell: &alacritty_terminal::term::cell::Cell,
    palette: &ColorPalette,
) -> CellSnapshot {
    CellSnapshot {
        ch: cell.c,
        fg_argb: color_to_argb(cell.fg, palette),
        bg_argb: color_to_argb(cell.bg, palette),
        attrs: CellAttrFlags {
            bold: cell.flags.contains(Flags::BOLD),
            italic: cell.flags.contains(Flags::ITALIC),
            underline: cell.flags.intersects(Flags::ALL_UNDERLINES),
            inverse: cell.flags.contains(Flags::INVERSE),
            dim: cell.flags.contains(Flags::DIM),
            strikethrough: cell.flags.contains(Flags::STRIKEOUT),
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn feeds_plain_text_into_grid() {
        let mut eng = TerminalEngine::new(40, 10);
        eng.advance(b"hello");
        let snap = eng.snapshot();
        assert_eq!(snap.cells[0].ch, 'h');
        assert_eq!(snap.cells[4].ch, 'o');
        assert_eq!(snap.cursor.col, 5);
        assert_eq!(snap.cursor.row, 0);
        assert_eq!(eng.viewport_text(), "hello");
    }

    #[test]
    fn parses_sgr_bold_and_named_color() {
        let mut eng = TerminalEngine::new(40, 5);
        eng.advance(b"\x1b[1;31mX\x1b[0m");
        let cell = &eng.snapshot().cells[0];
        assert_eq!(cell.ch, 'X');
        assert!(cell.attrs.bold);
        assert_eq!(cell.fg_argb, 0xFF_E0_6C_75);
    }

    #[test]
    fn set_palette_recolors_named_cells() {
        let mut eng = TerminalEngine::new(40, 5);
        eng.advance(b"\x1b[31mX");
        let mut palette = ColorPalette::default();
        palette.ansi16[1] = 0xFF_FF_00_00;
        eng.set_palette(palette);
        assert_eq!(eng.snapshot().cells[0].fg_argb, 0xFF_FF_00_00);
    }

    #[test]
    fn scrollback_display_offset_changes_viewport() {
        let mut eng = TerminalEngine::new(20, 5);
        for i in 0..20 {
            eng.advance(format!("line{i}\r\n").as_bytes());
        }
        assert!(eng.history_size() > 0);
        eng.scroll_display_delta(5);
        assert!(eng.display_offset() >= 5);
        eng.scroll_display_bottom();
        assert_eq!(eng.display_offset(), 0);
    }

    #[test]
    fn mouse_flags_set_with_sgr_mode() {
        let mut eng = TerminalEngine::new(40, 5);
        assert_eq!(eng.mouse_flags() & MOUSE_FLAG_REPORTING, 0);
        eng.advance(b"\x1b[?1000h\x1b[?1006h");
        assert_ne!(eng.mouse_flags() & MOUSE_FLAG_REPORTING, 0);
        assert_ne!(eng.mouse_flags() & MOUSE_FLAG_SGR, 0);
    }

    #[test]
    fn resize_updates_dimensions() {
        let mut eng = TerminalEngine::new(10, 5);
        eng.advance(b"abc");
        eng.resize(20, 8);
        let snap = eng.snapshot();
        assert_eq!(snap.columns, 20);
        assert_eq!(snap.rows, 8);
        assert_eq!(snap.cells[0].ch, 'a');
        assert_eq!(snap.cells.len(), 20 * 8);
    }

    #[test]
    fn alternate_screen_swaps_buffers() {
        let mut eng = TerminalEngine::new(20, 5);
        eng.advance(b"main");
        assert!(!eng.is_alt_screen());
        eng.advance(b"\x1b[?1049h");
        assert!(eng.is_alt_screen());
        eng.advance(b"\x1b[H\x1b[2Jalt");
        assert_eq!(eng.viewport_text(), "alt");
        eng.advance(b"\x1b[?1049l");
        assert!(!eng.is_alt_screen());
        assert_eq!(eng.viewport_text(), "main");
    }

    #[test]
    fn dec_2026_buffers_until_end_of_sync() {
        let mut eng = TerminalEngine::new(40, 5);
        eng.advance(b"\x1b[?2026h");
        assert!(eng.is_synchronized());
        eng.advance(b"secret");
        assert!(eng.sync_buffered_bytes() > 0);
        assert_eq!(eng.viewport_text(), "");
        eng.advance(b"\x1b[?2026l");
        assert!(!eng.is_synchronized());
        assert_eq!(eng.viewport_text(), "secret");
    }

    #[test]
    fn dec_2026_stop_sync_flushes_on_timeout_path() {
        let mut eng = TerminalEngine::new(40, 5);
        eng.advance(b"\x1b[?2026hbuffered\x1b[31m!");
        assert!(eng.is_synchronized());
        eng.stop_sync();
        assert!(!eng.is_synchronized());
        assert_eq!(eng.viewport_text(), "buffered!");
        assert_eq!(eng.snapshot().cells[8].fg_argb, 0xFF_E0_6C_75);
    }

    #[test]
    fn cursor_moves_with_cup() {
        let mut eng = TerminalEngine::new(40, 10);
        eng.advance(b"\x1b[3;5H*");
        let snap = eng.snapshot();
        assert_eq!(snap.cursor.row, 2);
        assert_eq!(snap.cursor.col, 5);
        let idx = 2 * 40 + 4;
        assert_eq!(snap.cells[idx].ch, '*');
    }
}
