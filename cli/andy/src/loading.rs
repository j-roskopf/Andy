use anyhow::Result;
use ratatui::prelude::*;
use ratatui::widgets::{Block, Borders, Paragraph};

const SPINNER: [&str; 10] = ["⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"];

pub fn spinner(frame: u8) -> &'static str {
    SPINNER[(frame as usize) % SPINNER.len()]
}

/// Full-screen loading panel with an animated spinner.
pub fn draw_loading_screen(
    terminal: &mut Terminal<impl Backend>,
    title: &str,
    message: &str,
    frame: u8,
) -> Result<()> {
    let spin = spinner(frame);
    terminal.draw(|f| {
        let area = f.area();
        let text = format!("\n  {spin}  {message}\n");
        f.render_widget(
            Paragraph::new(text).block(
                Block::default()
                    .title(format!(" {title} "))
                    .borders(Borders::ALL),
            ),
            area,
        );
    })?;
    Ok(())
}
