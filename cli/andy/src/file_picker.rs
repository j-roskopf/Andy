use anyhow::Result;
use crossterm::event::{self, Event, KeyCode, KeyEventKind};
use crossterm::terminal::{
    disable_raw_mode, enable_raw_mode, EnterAlternateScreen, LeaveAlternateScreen,
};
use crossterm::ExecutableCommand;
use ratatui::prelude::*;
use ratatui::widgets::{Block, Borders, List, ListItem, ListState, Paragraph};
use std::fs;
use std::io::stdout;
use std::path::{Path, PathBuf};
use std::time::Duration;

const IMAGE_EXTENSIONS: &[&str] = &["jpg", "jpeg", "png", "gif", "webp"];

#[derive(Debug, Clone)]
pub struct DirEntry {
    pub name: String,
    pub path: PathBuf,
    pub is_dir: bool,
}

/// Pure listing/filter helpers — unit-tested without a TUI.
pub fn is_image_path(path: &Path) -> bool {
    path.extension()
        .and_then(|e| e.to_str())
        .map(|e| {
            IMAGE_EXTENSIONS
                .iter()
                .any(|ext| e.eq_ignore_ascii_case(ext))
        })
        .unwrap_or(false)
}

pub fn list_picker_entries(dir: &Path) -> Result<Vec<DirEntry>> {
    let mut dirs = Vec::new();
    let mut files = Vec::new();
    let read = fs::read_dir(dir)?;
    for entry in read.flatten() {
        let path = entry.path();
        let name = entry.file_name().to_string_lossy().to_string();
        if name.starts_with('.') {
            continue;
        }
        let is_dir = path.is_dir();
        if is_dir {
            dirs.push(DirEntry {
                name,
                path,
                is_dir: true,
            });
        } else if is_image_path(&path) {
            files.push(DirEntry {
                name,
                path,
                is_dir: false,
            });
        }
    }
    dirs.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()));
    files.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()));
    let mut out = dirs;
    out.extend(files);
    Ok(out)
}

/// Standalone picker for non-TUI entry points (e.g. `andy chat start --pick-image`).
/// Enters/leaves the alternate screen around [pick_image].
pub fn pick_image_standalone(start_dir: impl AsRef<Path>) -> Result<Option<PathBuf>> {
    enable_raw_mode()?;
    stdout().execute(EnterAlternateScreen)?;
    let mut terminal = Terminal::new(CrosstermBackend::new(stdout()))?;
    let result = pick_image(&mut terminal, start_dir);
    let _ = disable_raw_mode();
    let _ = stdout().execute(LeaveAlternateScreen);
    let _ = terminal.show_cursor();
    result
}

/// Modal filesystem browser that returns one absolute image path, or `None` if cancelled.
pub fn pick_image(
    terminal: &mut Terminal<impl Backend>,
    start_dir: impl AsRef<Path>,
) -> Result<Option<PathBuf>> {
    let mut cwd = start_dir.as_ref().to_path_buf();
    if !cwd.is_dir() {
        cwd = std::env::current_dir().unwrap_or_else(|_| PathBuf::from("."));
    }
    let mut selected: usize = 0;
    let mut list_state = ListState::default();
    list_state.select(Some(0));

    loop {
        let entries = list_picker_entries(&cwd).unwrap_or_default();
        if selected >= entries.len() && !entries.is_empty() {
            selected = entries.len() - 1;
        }
        if entries.is_empty() {
            selected = 0;
        }
        list_state.select(if entries.is_empty() {
            None
        } else {
            Some(selected)
        });

        terminal.draw(|frame| {
            let area = frame.area();
            let chunks = Layout::default()
                .direction(Direction::Vertical)
                .constraints([
                    Constraint::Length(3),
                    Constraint::Min(3),
                    Constraint::Length(2),
                ])
                .split(area);

            frame.render_widget(
                Paragraph::new(format!(" Attach image · {}", cwd.display())).block(
                    Block::default()
                        .borders(Borders::ALL)
                        .title(" Image picker "),
                ),
                chunks[0],
            );

            let items: Vec<ListItem> = if entries.is_empty() {
                vec![ListItem::new(" (no images or folders here) ")]
            } else {
                entries
                    .iter()
                    .map(|e| {
                        let prefix = if e.is_dir { "[dir] " } else { "[img] " };
                        ListItem::new(format!("{prefix}{}", e.name))
                    })
                    .collect()
            };
            frame.render_stateful_widget(
                List::new(items)
                    .block(Block::default().borders(Borders::ALL))
                    .highlight_style(Style::default().add_modifier(Modifier::REVERSED)),
                chunks[1],
                &mut list_state,
            );
            frame.render_widget(
                Paragraph::new("Enter open/select · Backspace up · Esc cancel"),
                chunks[2],
            );
        })?;

        if !event::poll(Duration::from_millis(200))? {
            continue;
        }
        let Event::Key(key) = event::read()? else {
            continue;
        };
        if key.kind != KeyEventKind::Press {
            continue;
        }
        match key.code {
            KeyCode::Esc => return Ok(None),
            KeyCode::Up | KeyCode::Char('k') => {
                if !entries.is_empty() {
                    selected = selected.saturating_sub(1);
                }
            }
            KeyCode::Down | KeyCode::Char('j') => {
                if !entries.is_empty() {
                    selected = (selected + 1).min(entries.len() - 1);
                }
            }
            KeyCode::Backspace => {
                if let Some(parent) = cwd.parent() {
                    cwd = parent.to_path_buf();
                    selected = 0;
                }
            }
            KeyCode::Enter => {
                if let Some(entry) = entries.get(selected) {
                    if entry.is_dir {
                        cwd = entry.path.clone();
                        selected = 0;
                    } else {
                        return Ok(Some(
                            entry.path.canonicalize().unwrap_or(entry.path.clone()),
                        ));
                    }
                }
            }
            KeyCode::Char('.') => {
                // Allow selecting `..` via a synthetic parent navigation.
                if let Some(parent) = cwd.parent() {
                    cwd = parent.to_path_buf();
                    selected = 0;
                }
            }
            _ => {}
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use tempfile::tempdir;

    #[test]
    fn filters_to_images_and_dirs_sorted() {
        let dir = tempdir().unwrap();
        fs::create_dir(dir.path().join("subdir")).unwrap();
        fs::write(dir.path().join("a.png"), b"x").unwrap();
        fs::write(dir.path().join("b.txt"), b"x").unwrap();
        fs::write(dir.path().join("c.JPG"), b"x").unwrap();
        fs::write(dir.path().join(".hidden.png"), b"x").unwrap();

        let entries = list_picker_entries(dir.path()).unwrap();
        let names: Vec<_> = entries.iter().map(|e| e.name.as_str()).collect();
        assert_eq!(names, vec!["subdir", "a.png", "c.JPG"]);
        assert!(entries[0].is_dir);
        assert!(!entries[1].is_dir);
    }

    #[test]
    fn is_image_path_accepts_known_extensions() {
        assert!(is_image_path(Path::new("/tmp/x.webp")));
        assert!(is_image_path(Path::new("/tmp/x.GIF")));
        assert!(!is_image_path(Path::new("/tmp/x.pdf")));
    }
}
