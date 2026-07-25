use anyhow::Result;
use crossterm::event::{self, Event, KeyCode, KeyEventKind};
use crossterm::terminal::{disable_raw_mode, enable_raw_mode, EnterAlternateScreen, LeaveAlternateScreen};
use crossterm::ExecutableCommand;
use ratatui::prelude::*;
use ratatui::widgets::{Block, Borders, List, ListItem, ListState, Paragraph};
use serde_json::Value;
use std::collections::HashSet;
use std::io::{stdout, Stdout};
use std::time::Duration;

use crate::attach;
use crate::chats::{self, ListEntry, ProjectGroup};
use crate::compose;
use crate::mcp::McpClient;

pub async fn run_dashboard(mut client: McpClient) -> Result<()> {
    enable_raw_mode()?;
    stdout().execute(EnterAlternateScreen)?;
    let mut terminal = Terminal::new(CrosstermBackend::new(stdout()))?;

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
        &mut groups,
        &mut expanded,
        &mut entries,
        &mut selected,
        &mut list_state,
        &mut status,
    )
    .await;

    loop {
        status = flash.take().unwrap_or_else(|| {
            footer_status(
                entries.get(selected),
                groups.iter().map(|g| g.chats.len()).sum(),
            )
        });

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
            let list = List::new(list_items).block(
                Block::default()
                    .title(" Andy chats (andyd) ")
                    .borders(Borders::ALL),
            );
            list_state.select(Some(selected));
            frame.render_stateful_widget(list, chunks[0], &mut list_state);
            frame.render_widget(
                Paragraph::new(status.as_str()).block(Block::default().borders(Borders::ALL)),
                chunks[1],
            );
        })?;

        if event::poll(Duration::from_millis(250))? {
            if let Event::Key(key) = event::read()? {
                if key.kind != KeyEventKind::Press {
                    continue;
                }
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
                                    &mut groups,
                                    &mut expanded,
                                    &mut entries,
                                    &mut selected,
                                    &mut list_state,
                                    &mut status,
                                )
                                .await;
                                if let Some(idx) = entries.iter().position(|e| {
                                    matches!(e, ListEntry::Chat(c) if c.id == task_id)
                                }) {
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
                            &mut groups,
                            &mut expanded,
                            &mut entries,
                            &mut selected,
                            &mut list_state,
                            &mut status,
                        )
                        .await
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
                        if let Some(ListEntry::Header { key, expanded: open, .. }) =
                            entries.get(selected)
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
                    KeyCode::Enter => {
                        match entries.get(selected) {
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
                                restore_terminal(&mut terminal)?;
                                if let Err(err) =
                                    attach::attach_or_reattach(&mut client, &id).await
                                {
                                    eprintln!("{err:#}");
                                }
                                enable_raw_mode()?;
                                stdout().execute(EnterAlternateScreen)?;
                                terminal = Terminal::new(CrosstermBackend::new(stdout()))?;
                                refresh(
                                    &mut client,
                                    &mut groups,
                                    &mut expanded,
                                    &mut entries,
                                    &mut selected,
                                    &mut list_state,
                                    &mut status,
                                )
                                .await;
                            }
                            None => {}
                        }
                    }
                    KeyCode::Char('a') => {
                        if let Some(ListEntry::Chat(chat)) = entries.get(selected) {
                            let id = chat.id.clone();
                            restore_terminal(&mut terminal)?;
                            if let Err(err) = attach::attach_or_reattach(&mut client, &id).await {
                                eprintln!("{err:#}");
                            }
                            enable_raw_mode()?;
                            stdout().execute(EnterAlternateScreen)?;
                            terminal = Terminal::new(CrosstermBackend::new(stdout()))?;
                            refresh(
                                &mut client,
                                &mut groups,
                                &mut expanded,
                                &mut entries,
                                &mut selected,
                                &mut list_state,
                                &mut status,
                            )
                            .await;
                        }
                    }
                    _ => {}
                }
            }
        }
    }

    restore_terminal(&mut terminal)?;
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
        if let Some(idx) = entries.iter().position(|e| matches!(e, ListEntry::Chat(c) if c.id == chat_id))
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
    let Some(ListEntry::Header { key, expanded: open, .. }) = entries.get(*selected) else {
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

async fn refresh(
    client: &mut McpClient,
    groups: &mut Vec<ProjectGroup>,
    expanded: &mut HashSet<String>,
    entries: &mut Vec<ListEntry>,
    selected: &mut usize,
    list_state: &mut ListState,
    status: &mut String,
) {
    match client
        .call_tool("chat.list", Value::Object(Default::default()))
        .await
    {
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
}

fn footer_status(selected: Option<&ListEntry>, chat_count: usize) -> String {
    match selected {
        Some(ListEntry::Header { .. }) => {
            format!("{chat_count} chats · n new chat · Enter/Space expand · q quit")
        }
        Some(ListEntry::Chat(_)) => {
            format!("{chat_count} chats · a attach · Enter attach · q quit")
        }
        None => format!("{chat_count} chats · q quit"),
    }
}

fn restore_terminal(terminal: &mut Terminal<CrosstermBackend<Stdout>>) -> Result<()> {
    disable_raw_mode()?;
    stdout().execute(LeaveAlternateScreen)?;
    terminal.show_cursor()?;
    Ok(())
}
