use anyhow::Result;
use crossterm::event::{
    self, DisableMouseCapture, EnableMouseCapture, Event, KeyCode, KeyEventKind, MouseButton,
    MouseEventKind,
};
use crossterm::terminal::{
    disable_raw_mode, enable_raw_mode, EnterAlternateScreen, LeaveAlternateScreen,
};
use crossterm::ExecutableCommand;
use ratatui::prelude::*;
use ratatui::widgets::{Block, Borders, List, ListItem, ListState, Paragraph};
use serde_json::Value;
use std::collections::HashSet;
use std::io::{stdout, Stdout};
use std::time::Duration;

use crate::acp_view;
use crate::attach;
use crate::chats::{self, ListEntry, ProjectGroup};
use crate::compose;
use crate::daemon;
use crate::loading;
use crate::mcp::McpClient;
use crate::tmux;
use std::path::PathBuf;

/// Restores the terminal on every exit path (including startup failures).
struct TerminalGuard(Terminal<CrosstermBackend<Stdout>>);

impl TerminalGuard {
    fn setup() -> Result<Self> {
        enable_raw_mode()?;
        stdout().execute(EnterAlternateScreen)?;
        stdout().execute(EnableMouseCapture)?;
        Ok(Self(Terminal::new(CrosstermBackend::new(stdout()))?))
    }

    fn terminal(&mut self) -> &mut Terminal<CrosstermBackend<Stdout>> {
        &mut self.0
    }
}

impl Drop for TerminalGuard {
    fn drop(&mut self) {
        let _ = restore_terminal(&mut self.0);
    }
}

pub async fn run_dashboard(socket: PathBuf, ensure_local_daemon: bool) -> Result<()> {
    let mut guard = TerminalGuard::setup()?;
    let mut terminal = guard.terminal();

    if ensure_local_daemon {
        daemon::ensure_running_with_feedback(&socket, |message, tick| {
            loading::draw_loading_screen(&mut terminal, " Andy ", message, tick)
        })
        .await?;
    } else {
        daemon::wait_until_live_with_feedback(
            &socket,
            std::time::Duration::from_secs(15),
            |message, tick| loading::draw_loading_screen(&mut terminal, " Andy ", message, tick),
        )
        .await?;
    }
    let mut client = McpClient::new(socket);

    let mut selected: usize = 0;
    let mut list_state = ListState::default();
    let mut groups: Vec<ProjectGroup> = Vec::new();
    // Empty = all projects start collapsed.
    let mut expanded: HashSet<String> = HashSet::new();
    let mut entries: Vec<ListEntry> = Vec::new();
    let mut status = String::new();
    let mut flash: Option<String> = None;

    refresh(
        &mut client,
        &mut terminal,
        &mut groups,
        &mut expanded,
        &mut entries,
        &mut selected,
        &mut list_state,
        &mut status,
    )
    .await?;

    loop {
        // Keep flash until the next keypress — take() every redraw made it vanish in ~250ms.
        status = flash.clone().unwrap_or_else(|| {
            footer_status(
                entries.get(selected),
                groups.iter().map(|g| g.chats.len()).sum(),
            )
        });

        draw_dashboard(&mut terminal, &entries, &mut list_state, selected, &status)?;

        if event::poll(Duration::from_millis(250))? {
            match event::read()? {
                Event::Key(key) => {
                    if key.kind != KeyEventKind::Press {
                        continue;
                    }
                    flash = None;
                    match key.code {
                        KeyCode::Char('q') | KeyCode::Esc => break,
                        KeyCode::Char('n') => {
                            let Some(ListEntry::Header { key, label, .. }) = entries.get(selected)
                            else {
                                // New chat only from project / Inbox headers.
                                continue;
                            };
                            let project_key = key.clone();
                            let project_label = label.clone();
                            let preset = (project_key.as_str(), project_label.as_str());
                            match compose::run_composer(&mut client, &mut terminal, Some(preset))
                                .await
                            {
                                Ok(Some(outcome)) => {
                                    let task_id = outcome.task_id;
                                    // Keep the project open so the new chat is visible at the top.
                                    expanded.insert(project_key);
                                    refresh(
                                        &mut client,
                                        &mut terminal,
                                        &mut groups,
                                        &mut expanded,
                                        &mut entries,
                                        &mut selected,
                                        &mut list_state,
                                        &mut status,
                                    )
                                    .await?;
                                    if let Some(idx) = entries.iter().position(
                                        |e| matches!(e, ListEntry::Chat(c) if c.id == task_id),
                                    ) {
                                        selected = idx;
                                        list_state.select(Some(selected));
                                    }
                                    flash = Some(match outcome.attach_error {
                                        Some(err) => {
                                            format!("started {task_id} but attach failed: {err}")
                                        }
                                        None if entries.iter().any(|e| {
                                            matches!(e, ListEntry::Chat(c) if c.id == task_id)
                                        }) =>
                                        {
                                            format!("started {task_id}")
                                        }
                                        None => format!(
                                            "started {task_id} · not in list yet — press r to refresh"
                                        ),
                                    });
                                }
                                Ok(None) => {}
                                Err(err) => flash = Some(format!("compose error: {err:#}")),
                            }
                        }
                        KeyCode::Char('r') => {
                            refresh(
                                &mut client,
                                &mut terminal,
                                &mut groups,
                                &mut expanded,
                                &mut entries,
                                &mut selected,
                                &mut list_state,
                                &mut status,
                            )
                            .await?;
                        }
                        KeyCode::Down | KeyCode::Char('j') => {
                            move_selection(entries.len(), &mut selected, 1);
                            list_state.select(Some(selected));
                        }
                        KeyCode::Up | KeyCode::Char('k') => {
                            move_selection(entries.len(), &mut selected, -1);
                            list_state.select(Some(selected));
                        }
                        KeyCode::PageDown => {
                            let page = visible_page(&terminal);
                            move_selection(entries.len(), &mut selected, page as isize);
                            list_state.select(Some(selected));
                        }
                        KeyCode::PageUp => {
                            let page = visible_page(&terminal);
                            move_selection(entries.len(), &mut selected, -(page as isize));
                            list_state.select(Some(selected));
                        }
                        KeyCode::Home => {
                            selected = 0;
                            list_state.select(Some(selected));
                        }
                        KeyCode::End => {
                            if !entries.is_empty() {
                                selected = entries.len() - 1;
                            }
                            list_state.select(Some(selected));
                        }
                        KeyCode::Right | KeyCode::Char('l') => {
                            if let Some(ListEntry::Header {
                                key,
                                expanded: open,
                                ..
                            }) = entries.get(selected)
                            {
                                if !*open {
                                    expanded.insert(key.clone());
                                    rebuild_visible(
                                        &groups,
                                        &expanded,
                                        &mut entries,
                                        &mut selected,
                                        &mut list_state,
                                    );
                                }
                            }
                        }
                        KeyCode::Left | KeyCode::Char('h') => {
                            collapse_current(
                                &groups,
                                &mut expanded,
                                &mut entries,
                                &mut selected,
                                &mut list_state,
                            );
                        }
                        KeyCode::Char(' ') => {
                            toggle_or_noop(
                                &groups,
                                &mut expanded,
                                &mut entries,
                                &mut selected,
                                &mut list_state,
                            );
                        }
                        KeyCode::Enter => match entries.get(selected) {
                            Some(ListEntry::Header { .. }) => {
                                toggle_or_noop(
                                    &groups,
                                    &mut expanded,
                                    &mut entries,
                                    &mut selected,
                                    &mut list_state,
                                );
                            }
                            Some(ListEntry::Chat(chat)) => {
                                let id = chat.id.clone();
                                if confirm_attach(&mut terminal, &id)? {
                                    flash = attach_selected_chat(
                                        &mut client,
                                        &mut terminal,
                                        &mut groups,
                                        &mut expanded,
                                        &mut entries,
                                        &mut selected,
                                        &mut list_state,
                                        &mut status,
                                        &id,
                                    )
                                    .await?;
                                }
                            }
                            None => {}
                        },
                        KeyCode::Char('a') => {
                            if let Some(ListEntry::Chat(chat)) = entries.get(selected) {
                                let id = chat.id.clone();
                                if confirm_attach(&mut terminal, &id)? {
                                    flash = attach_selected_chat(
                                        &mut client,
                                        &mut terminal,
                                        &mut groups,
                                        &mut expanded,
                                        &mut entries,
                                        &mut selected,
                                        &mut list_state,
                                        &mut status,
                                        &id,
                                    )
                                    .await?;
                                }
                            }
                        }
                        _ => {}
                    }
                }
                Event::Mouse(mouse) => {
                    if mouse.kind == MouseEventKind::Down(MouseButton::Left) {
                        flash = None;
                        let size = terminal.size().unwrap_or_default();
                        if mouse.row == 0 && mouse.column >= size.width.saturating_sub(15) {
                            refresh(
                                &mut client,
                                &mut terminal,
                                &mut groups,
                                &mut expanded,
                                &mut entries,
                                &mut selected,
                                &mut list_state,
                                &mut status,
                            )
                            .await?;
                        } else if mouse.row > 0 && (mouse.row as usize) <= entries.len() {
                            let clicked_idx = (mouse.row as usize) - 1;
                            if clicked_idx < entries.len() {
                                selected = clicked_idx;
                                list_state.select(Some(selected));
                            }
                        }
                    }
                }
                _ => {}
            }
        }
    }

    Ok(())
}

fn visible_page(terminal: &Terminal<CrosstermBackend<Stdout>>) -> usize {
    terminal
        .size()
        .map(|s| s.height.saturating_sub(5) as usize)
        .unwrap_or(10)
        .max(1)
}

fn move_selection(len: usize, selected: &mut usize, delta: isize) {
    if len == 0 {
        *selected = 0;
        return;
    }
    let next = (*selected as isize + delta).clamp(0, (len - 1) as isize);
    *selected = next as usize;
}

fn rebuild_visible(
    groups: &[ProjectGroup],
    expanded: &HashSet<String>,
    entries: &mut Vec<ListEntry>,
    selected: &mut usize,
    list_state: &mut ListState,
) {
    let prev_key = entries.get(*selected).and_then(|e| match e {
        ListEntry::Header { key, .. } => Some(key.clone()),
        ListEntry::Chat(chat) => Some(chat.project_id.clone()),
    });
    let prev_chat_id = entries.get(*selected).and_then(|e| match e {
        ListEntry::Chat(chat) => Some(chat.id.clone()),
        _ => None,
    });

    *entries = chats::visible_entries(groups, expanded);

    if let Some(chat_id) = prev_chat_id {
        if let Some(idx) = entries
            .iter()
            .position(|e| matches!(e, ListEntry::Chat(c) if c.id == chat_id))
        {
            *selected = idx;
            list_state.select(Some(*selected));
            return;
        }
    }
    if let Some(key) = prev_key {
        if let Some(idx) = entries
            .iter()
            .position(|e| matches!(e, ListEntry::Header { key: k, .. } if k == &key))
        {
            *selected = idx;
            list_state.select(Some(*selected));
            return;
        }
    }
    if *selected >= entries.len() {
        *selected = entries.len().saturating_sub(1);
    }
    list_state.select(Some(*selected));
}

fn toggle_or_noop(
    groups: &[ProjectGroup],
    expanded: &mut HashSet<String>,
    entries: &mut Vec<ListEntry>,
    selected: &mut usize,
    list_state: &mut ListState,
) {
    let Some(ListEntry::Header {
        key,
        expanded: open,
        ..
    }) = entries.get(*selected)
    else {
        return;
    };
    let key = key.clone();
    if *open {
        expanded.remove(&key);
    } else {
        expanded.insert(key);
    }
    rebuild_visible(groups, expanded, entries, selected, list_state);
}

fn collapse_current(
    groups: &[ProjectGroup],
    expanded: &mut HashSet<String>,
    entries: &mut Vec<ListEntry>,
    selected: &mut usize,
    list_state: &mut ListState,
) {
    let key = match entries.get(*selected) {
        Some(ListEntry::Header { key, .. }) => key.clone(),
        Some(ListEntry::Chat(chat)) => chat.project_id.clone(),
        None => return,
    };
    if expanded.remove(&key) {
        rebuild_visible(groups, expanded, entries, selected, list_state);
    }
}

fn draw_dashboard(
    terminal: &mut Terminal<CrosstermBackend<Stdout>>,
    entries: &[ListEntry],
    list_state: &mut ListState,
    selected: usize,
    status: &str,
) -> Result<()> {
    terminal.draw(|frame| {
        let area = frame.area();
        let chunks = Layout::default()
            .direction(Direction::Vertical)
            .constraints([Constraint::Min(3), Constraint::Length(3)])
            .split(area);

        let list_items: Vec<ListItem> = entries
            .iter()
            .enumerate()
            .map(|(i, entry)| {
                let item = match entry {
                    ListEntry::Header {
                        label,
                        count,
                        expanded,
                        ..
                    } => {
                        let marker = if *expanded { "▾" } else { "▸" };
                        ListItem::new(format!(" {marker} {label} ({count}) "))
                            .style(Style::default().fg(Color::Cyan))
                    }
                    ListEntry::Chat(chat) => {
                        let live = if chat.tmux_alive { " live" } else { "" };
                        ListItem::new(format!(
                            "   {}  [{}{}]  {}",
                            chat.id, chat.status, live, chat.title
                        ))
                    }
                };
                if i == selected {
                    item.style(Style::default().add_modifier(Modifier::REVERSED))
                } else {
                    item
                }
            })
            .collect();

        let refresh_button = Line::from(vec![
            Span::styled("[", Style::default().fg(Color::DarkGray)),
            Span::styled(
                "r",
                Style::default()
                    .fg(Color::Yellow)
                    .add_modifier(Modifier::BOLD),
            ),
            Span::styled("] Refresh ", Style::default().fg(Color::Cyan)),
        ])
        .right_aligned();

        let list = List::new(list_items).block(
            Block::default()
                .title(" Andy chats (andyd) ")
                .title_top(refresh_button)
                .borders(Borders::ALL),
        );
        list_state.select(Some(selected));
        frame.render_stateful_widget(list, chunks[0], list_state);
        frame.render_widget(
            Paragraph::new(status).block(Block::default().borders(Borders::ALL)),
            chunks[1],
        );
    })?;
    Ok(())
}

async fn refresh(
    client: &mut McpClient,
    terminal: &mut Terminal<CrosstermBackend<Stdout>>,
    groups: &mut Vec<ProjectGroup>,
    expanded: &mut HashSet<String>,
    entries: &mut Vec<ListEntry>,
    selected: &mut usize,
    list_state: &mut ListState,
    status: &mut String,
) -> Result<()> {
    *status = "Loading chats…".to_string();
    let mut frame = 0u8;
    let list_fut = client.call_tool("chat.list", Value::Object(Default::default()));
    tokio::pin!(list_fut);

    let raw = loop {
        loading::draw_loading_screen(terminal, " Andy chats ", status, frame)?;
        frame = frame.wrapping_add(1);
        tokio::select! {
            result = &mut list_fut => break result,
            _ = tokio::time::sleep(Duration::from_millis(100)) => {}
        }
    };

    match raw {
        Ok(raw) => {
            let chats = chats::parse_chats(&raw);
            let chat_count = chats.len();
            *groups = chats::project_groups(chats);
            let valid: HashSet<String> = groups.iter().map(|g| g.key.clone()).collect();
            expanded.retain(|k| valid.contains(k));
            rebuild_visible(groups, expanded, entries, selected, list_state);
            *status = footer_status(entries.get(*selected), chat_count);
        }
        Err(err) => *status = format!("error: {err}"),
    }
    Ok(())
}

fn footer_status(selected: Option<&ListEntry>, chat_count: usize) -> String {
    match selected {
        Some(ListEntry::Header { .. }) => {
            format!("{chat_count} chats · n new chat · r refresh · Enter/Space expand · q quit")
        }
        Some(ListEntry::Chat(_)) => {
            format!(
                "{chat_count} chats · r refresh · a/Enter attach · {} · q quit",
                tmux::detach_hint()
            )
        }
        None => format!("{chat_count} chats · r refresh · q quit"),
    }
}

fn restore_terminal(terminal: &mut Terminal<CrosstermBackend<Stdout>>) -> Result<()> {
    disable_raw_mode()?;
    let _ = stdout().execute(DisableMouseCapture);
    stdout().execute(LeaveAlternateScreen)?;
    terminal.show_cursor()?;
    Ok(())
}

/// Pause on a full-screen hint so users see how to get back before tmux takes over.
fn confirm_attach(
    terminal: &mut Terminal<CrosstermBackend<Stdout>>,
    task_id: &str,
) -> Result<bool> {
    loop {
        terminal.draw(|frame| {
            let area = frame.area();
            let text = format!(
                "\n  {task_id}\n\n  {}\n\n  Enter attach · Esc cancel\n",
                tmux::detach_hint()
            );
            frame.render_widget(
                Paragraph::new(text).block(
                    Block::default()
                        .title(" Attach to live chat ")
                        .borders(Borders::ALL),
                ),
                area,
            );
        })?;

        if event::poll(Duration::from_millis(250))? {
            if let Event::Key(key) = event::read()? {
                if key.kind != KeyEventKind::Press {
                    continue;
                }
                match key.code {
                    KeyCode::Esc => return Ok(false),
                    KeyCode::Enter | KeyCode::Char('a') | KeyCode::Char(' ') => return Ok(true),
                    _ => {}
                }
            }
        }
    }
}

/// Returns a footer flash when attach fails (also shown full-screen before returning).
async fn attach_selected_chat(
    client: &mut McpClient,
    terminal: &mut Terminal<CrosstermBackend<Stdout>>,
    groups: &mut Vec<ProjectGroup>,
    expanded: &mut HashSet<String>,
    entries: &mut Vec<ListEntry>,
    selected: &mut usize,
    list_state: &mut ListState,
    status: &mut String,
    task_id: &str,
) -> Result<Option<String>> {
    *status = format!("Opening {task_id}…");
    draw_dashboard(terminal, entries, list_state, *selected, status)?;

    // Resolve lane while the dashboard still owns the alt screen so opening a chat
    // does not flash a blank terminal. ACP takes over that screen; Terminal needs
    // a normal TTY for tmux.
    let attach_err = match acp_view::resolve_lane(client, task_id).await {
        Ok(lane) if lane.eq_ignore_ascii_case("Acp") => {
            let err = match acp_view::run_acp_viewer(client, task_id).await {
                Ok(()) => None,
                Err(err) => Some(format!("{err:#}")),
            };
            // ACP viewer leaves the alt screen on exit; restore dashboard chrome.
            enable_raw_mode()?;
            stdout().execute(EnterAlternateScreen)?;
            let _ = stdout().execute(EnableMouseCapture);
            *terminal = Terminal::new(CrosstermBackend::new(stdout()))?;
            err
        }
        Ok(_) => {
            restore_terminal(terminal)?;
            let err = match attach::attach_or_reattach(client, task_id).await {
                Ok(()) => None,
                Err(err) => Some(format!("{err:#}")),
            };
            enable_raw_mode()?;
            stdout().execute(EnterAlternateScreen)?;
            let _ = stdout().execute(EnableMouseCapture);
            *terminal = Terminal::new(CrosstermBackend::new(stdout()))?;
            err
        }
        Err(err) => Some(format!("{err:#}")),
    };

    let flash = if let Some(err) = attach_err {
        show_attach_error(terminal, task_id, &err)?;
        // Single-line footer summary; full text was already on the modal.
        Some(format!(
            "attach failed for {task_id}: {}",
            err.lines().next().unwrap_or("unknown error")
        ))
    } else {
        None
    };
    refresh(
        client, terminal, groups, expanded, entries, selected, list_state, status,
    )
    .await?;
    Ok(flash)
}

/// Full-screen error so attach failures are readable (eprintln was under the alt screen).
fn show_attach_error(
    terminal: &mut Terminal<CrosstermBackend<Stdout>>,
    task_id: &str,
    err: &str,
) -> Result<()> {
    loop {
        terminal.draw(|frame| {
            let area = frame.area();
            let text = format!(
                "\n  Could not attach to {task_id}\n\n  {}\n\n  Press any key to return to the chat list\n",
                err.replace('\n', "\n  ")
            );
            frame.render_widget(
                Paragraph::new(text).block(
                    Block::default()
                        .title(" Attach failed ")
                        .borders(Borders::ALL)
                        .border_style(Style::default().fg(Color::Red)),
                ),
                area,
            );
        })?;

        if event::poll(Duration::from_millis(250))? {
            if let Event::Key(key) = event::read()? {
                if key.kind == KeyEventKind::Press {
                    return Ok(());
                }
            }
        }
    }
}
