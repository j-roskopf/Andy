use anyhow::{bail, Context, Result};
use crossterm::event::{
    self, DisableMouseCapture, EnableMouseCapture, Event, KeyCode, KeyEvent, KeyEventKind,
    KeyModifiers,
};
use crossterm::terminal::{
    disable_raw_mode, enable_raw_mode, EnterAlternateScreen, LeaveAlternateScreen,
};
use crossterm::ExecutableCommand;
use ratatui::prelude::*;
use ratatui::text::{Line, Span};
use ratatui::widgets::{Block, Borders, Paragraph, Wrap};
use serde_json::{json, Value};
use std::io::{stdout, Stdout};
use std::path::PathBuf;
use std::time::Duration;
use syntect::easy::HighlightLines;
use syntect::highlighting::ThemeSet;
use syntect::parsing::SyntaxSet;
use syntect::util::LinesWithEndings;
use termimad::MadSkin;
use tokio::sync::mpsc;
use tokio::task::JoinHandle;

use crate::events::AgentEvent;
use crate::file_picker;
use crate::mcp::{McpClient, SubscribeMessage, SUBSCRIBE_MISSING_ERROR};
use crate::skills::{
    discover_agent_skills, prompt_with_skill_hints, skills_referenced_in_prompt, AgentSkill,
};
use crate::slash::{
    complete_command, menu_height, merge_commands_with_skills, native_commands_for_agent,
    render_menu, SlashCommand, SlashMenuAction, SlashMenuState,
};
use crate::viewer_chrome::{self, Lane};

const DETAIL_TRUNCATE_LINES: usize = 40;

#[derive(Debug, Clone)]
struct ChatMeta {
    id: String,
    title: String,
    status: String,
    agent: Option<String>,
    #[allow(dead_code)]
    lane: String,
    cwd: String,
    origin_dir: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ConnectionIssue {
    Gone,
    Lost,
}

struct ViewState {
    events: Vec<AgentEvent>,
    status: String,
    input: String,
    image_paths: Vec<String>,
    scroll: u16,
    expanded_tools: std::collections::HashSet<usize>,
    pending_permission: Option<AgentEvent>,
    composer_enabled: bool,
    /// False once `chat.subscribe` ends (terminal/done) so a later resume can reopen it.
    subscribe_open: bool,
    connection_issue: Option<ConnectionIssue>,
    connection_error: Option<String>,
    status_flash: Option<String>,
    /// When false (default), hide tools/commands/raw/usage/thinking/etc. and show
    /// only the conversation transcript. Toggle with `v`; default on via
    /// `ANDY_ACP_VIEW_DETAILS=1`.
    show_details: bool,
    /// True until the first subscribe batch/finish/disconnect arrives (or while
    /// reconnecting after clearing the local transcript).
    loading_transcript: bool,
    slash_menu: SlashMenuState,
    /// Disk skills cached outside the render/poll loop (keyed by provider + workspace).
    cached_skills: Vec<AgentSkill>,
    cached_skills_key: Option<(String, String)>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum LoopAction {
    Continue,
    Exit,
    Retry,
}

/// Native ACP chat viewer for `andy attach` on ACP-lane tasks.
pub async fn run_acp_viewer(client: &mut McpClient, task_id: &str) -> Result<()> {
    enable_raw_mode()?;
    stdout().execute(EnterAlternateScreen)?;
    stdout().execute(EnableMouseCapture)?;
    let mut terminal = Terminal::new(CrosstermBackend::new(stdout()))?;

    let result = async {
        draw_busy_screen(&mut terminal, task_id, "Loading chat…")?;

        client.requires_chat_subscribe().await.map_err(|err| {
            if err.to_string().contains("chat.subscribe") {
                anyhow::anyhow!("{SUBSCRIBE_MISSING_ERROR}")
            } else {
                err
            }
        })?;

        draw_busy_screen(&mut terminal, task_id, "Loading chat…")?;
        let meta = load_meta(client, task_id).await?;

        draw_busy_screen(&mut terminal, &meta.id, "Loading transcript…")?;
        let skin = MadSkin::default();
        let syntax_set = SyntaxSet::load_defaults_newlines();
        let theme_set = ThemeSet::load_defaults();
        let theme = theme_set
            .themes
            .get("base16-ocean.dark")
            .or_else(|| theme_set.themes.values().next())
            .expect("syntect theme");

        // Persist viewer state across subscribe reconnects (lost socket, or continuing
        // a completed chat). A fresh subscribe still delivers the full backlog; we only
        // reset connection flags / subscribe_open when opening a new stream.
        let mut state = ViewState {
            events: Vec::new(),
            status: meta.status.clone(),
            input: String::new(),
            image_paths: Vec::new(),
            scroll: 0,
            expanded_tools: std::collections::HashSet::new(),
            pending_permission: None,
            // Done/Idle chats remain continuable (resume); only hard failures lock the composer.
            composer_enabled: !matches!(meta.status.as_str(), "Error" | "Failed" | "Stopped"),
            subscribe_open: true,
            connection_issue: None,
            connection_error: None,
            status_flash: None,
            show_details: details_default_from_env(),
            loading_transcript: true,
            slash_menu: SlashMenuState::default(),
            cached_skills: Vec::new(),
            cached_skills_key: None,
        };

        loop {
            let (tx, mut rx) = mpsc::channel::<SubscribeMessage>(64);
            let mut sub_client = McpClient::new(client.socket_path());
            let sub_task = task_id.to_string();
            // Each subscribe delivers the full backlog; reset transcript local state so
            // reconnect / continue-after-Done does not duplicate events.
            state.events.clear();
            state.expanded_tools.clear();
            state.pending_permission = None;
            state.subscribe_open = true;
            state.connection_issue = None;
            state.connection_error = None;
            state.loading_transcript = true;
            // Re-enable after Lost disconnect; Done chats stay continuable, hard errors don't.
            if !matches!(state.status.as_str(), "Error" | "Failed" | "Stopped") {
                state.composer_enabled = true;
            }
            let subscribe_handle: JoinHandle<()> = tokio::spawn(async move {
                if let Err(err) = sub_client
                    .subscribe_chat_events(&sub_task, tx.clone())
                    .await
                {
                    let msg = err.to_string();
                    let _ = tx
                        .send(SubscribeMessage::Disconnected(if msg.is_empty() {
                            "lost connection to andyd".into()
                        } else {
                            msg
                        }))
                        .await;
                }
            });

            let loop_result = run_loop(
                client,
                &mut terminal,
                &meta,
                &mut state,
                &mut rx,
                &skin,
                &syntax_set,
                theme,
            )
            .await;

            subscribe_handle.abort();

            match loop_result {
                Ok(LoopAction::Retry) => continue,
                Ok(LoopAction::Exit) | Ok(LoopAction::Continue) => return Ok(()),
                Err(err) => return Err(err),
            }
        }
    }
    .await;

    disable_raw_mode()?;
    stdout().execute(LeaveAlternateScreen)?;
    stdout().execute(DisableMouseCapture)?;
    result
}

fn draw_busy_screen(
    terminal: &mut Terminal<CrosstermBackend<Stdout>>,
    task_id: &str,
    message: &str,
) -> Result<()> {
    terminal.draw(|frame| {
        let area = frame.area();
        let text = format!("\n  {task_id}\n\n  {message}\n");
        frame.render_widget(
            Paragraph::new(text).block(
                Block::default()
                    .title(Lane::Acp.frame_title())
                    .borders(Borders::ALL),
            ),
            area,
        );
    })?;
    Ok(())
}

async fn run_loop(
    client: &mut McpClient,
    terminal: &mut Terminal<CrosstermBackend<Stdout>>,
    meta: &ChatMeta,
    state: &mut ViewState,
    rx: &mut mpsc::Receiver<SubscribeMessage>,
    skin: &MadSkin,
    syntax_set: &SyntaxSet,
    theme: &syntect::highlighting::Theme,
) -> Result<LoopAction> {
    loop {
        while let Ok(msg) = rx.try_recv() {
            apply_subscribe_message(state, msg);
        }

        let slash_commands = available_slash_commands(meta, state);
        state.slash_menu.sync(&state.input, &slash_commands);

        terminal.draw(|frame| draw(frame, meta, state, skin, syntax_set, theme))?;

        if let Some(err) = &state.connection_error {
            if err.contains("chat.subscribe") || err.contains(SUBSCRIBE_MISSING_ERROR) {
                bail!("{SUBSCRIBE_MISSING_ERROR}");
            }
        }

        // Deleted/missing chat: leave the viewer cleanly (same mental model as detach).
        if state.connection_issue == Some(ConnectionIssue::Gone) {
            eprintln!("chat no longer exists");
            return Ok(LoopAction::Exit);
        }

        if event::poll(Duration::from_millis(120))? {
            match event::read()? {
                Event::Key(key) if key.kind == KeyEventKind::Press => {
                    match handle_key(client, terminal, meta, state, key).await? {
                        LoopAction::Continue => {}
                        other => return Ok(other),
                    }
                }
                Event::Resize(_, _) => {}
                _ => {}
            }
        }
    }
}

fn apply_subscribe_message(state: &mut ViewState, msg: SubscribeMessage) {
    match msg {
        SubscribeMessage::Batch(batch) => {
            state.loading_transcript = false;
            if let Some(from) = batch.replace_from {
                if from <= state.events.len() {
                    state.events.truncate(from);
                }
                // expanded_tools indexes the coalesced display list, not raw events.
                let display_len = AgentEvent::coalesce_for_display(&state.events).len();
                state.expanded_tools.retain(|idx| *idx < display_len);
            }
            for raw in batch.events {
                let event = AgentEvent::from_wire(&raw);
                if let AgentEvent::Permission { request_id, .. } = &event {
                    state.pending_permission = Some(event.clone());
                    let _ = request_id;
                }
                if let AgentEvent::PermissionResolved { request_id, .. } = &event {
                    if let Some(AgentEvent::Permission {
                        request_id: pending_id,
                        ..
                    }) = &state.pending_permission
                    {
                        if pending_id == request_id {
                            state.pending_permission = None;
                        }
                    }
                }
                match &event {
                    AgentEvent::Error { .. } => {
                        state.composer_enabled = false;
                        state.status = "Error".into();
                    }
                    AgentEvent::Result { success, .. } => {
                        if *success {
                            state.composer_enabled = true;
                            state.status = "Done".into();
                        } else {
                            // Failed/crashed task: terminal, composer locked (matches GUI).
                            state.composer_enabled = false;
                            state.status = "Error".into();
                        }
                    }
                    _ => {}
                }
                state.events.push(event);
            }
            if let Some(err) = batch.error {
                if err.contains("chat no longer exists") {
                    state.connection_issue = Some(ConnectionIssue::Gone);
                } else {
                    state.connection_issue = Some(ConnectionIssue::Lost);
                }
                state.connection_error = Some(err);
                state.composer_enabled = false;
            }
            if batch.done && state.connection_error.is_none() {
                state.subscribe_open = false;
                state.status_flash = Some("subscription ended".into());
                // Defer Done vs Error promotion to Finished { reason }. A done batch alone
                // cannot tell AgentStatus.Error from Done (both close the subscribe).
            }
        }
        SubscribeMessage::Finished { reason } => {
            state.loading_transcript = false;
            state.subscribe_open = false;
            state.status_flash = Some(format!("subscribe finished ({reason})"));
            if reason == "gone" {
                state.connection_issue = Some(ConnectionIssue::Gone);
                state.connection_error = Some("chat no longer exists".into());
                state.composer_enabled = false;
            } else if reason == "error" {
                // Daemon closed subscribe on AgentStatus.Error — lock composer even when
                // no TaskError/TaskResult event was appended.
                state.status = "Error".into();
                state.composer_enabled = false;
            } else if reason == "terminal" || reason == "done" {
                // Stop/finish may not emit TaskResult; treat subscribe terminal as Done.
                mark_terminal_if_stopping(state);
                // Leave composer enabled so resume can continue the chat.
            }
        }
        SubscribeMessage::Disconnected(msg) => {
            state.loading_transcript = false;
            state.subscribe_open = false;
            if msg.contains("chat no longer exists") {
                state.connection_issue = Some(ConnectionIssue::Gone);
            } else {
                state.connection_issue = Some(ConnectionIssue::Lost);
            }
            state.connection_error = Some(msg);
            state.composer_enabled = false;
        }
    }
}

async fn handle_key(
    client: &mut McpClient,
    terminal: &mut Terminal<impl Backend>,
    meta: &ChatMeta,
    state: &mut ViewState,
    key: KeyEvent,
) -> Result<LoopAction> {
    if state.composer_enabled
        && state.pending_permission.is_none()
        && state.connection_issue.is_none()
    {
        let commands = available_slash_commands(meta, state);
        match state
            .slash_menu
            .handle_key(&state.input, &commands, key.code)
        {
            SlashMenuAction::Complete => {
                if let Some(command) = state.slash_menu.selected_command().map(|c| c.name.clone()) {
                    if let Some(completed) = complete_command(&state.input, &command) {
                        state.input = completed;
                        state.status_flash = Some(format!("completed /{command}"));
                    }
                }
                return Ok(LoopAction::Continue);
            }
            SlashMenuAction::Dismiss | SlashMenuAction::Move => {
                return Ok(LoopAction::Continue);
            }
            SlashMenuAction::Pass => {}
        }
    }

    match map_key_action(state, key) {
        MappedKey::Exit => return Ok(LoopAction::Exit),
        MappedKey::Retry => return Ok(LoopAction::Retry),
        MappedKey::Stop => {
            state.status = "Stopping".into();
            match client
                .call_tool("chat.stop", json!({ "taskId": meta.id }))
                .await
            {
                Ok(_) => {
                    // ACP stop calls finishTask(Done) without appending TaskResult.
                    // Prefer the daemon's status; fall back to Done when unavailable.
                    apply_stop_success(state, fetch_task_status(client, &meta.id).await);
                }
                Err(err) => {
                    state.status_flash = Some(format!("stop failed: {err:#}"));
                    // Leave Stopping only briefly — next subscribe terminal will promote.
                }
            }
        }
        MappedKey::PickImage => {
            let start = if !meta.cwd.is_empty() {
                PathBuf::from(&meta.cwd)
            } else if !meta.origin_dir.is_empty() {
                PathBuf::from(&meta.origin_dir)
            } else {
                std::env::current_dir().unwrap_or_else(|_| PathBuf::from("."))
            };
            if let Some(path) = file_picker::pick_image(terminal, start)? {
                state.image_paths.push(path.display().to_string());
            }
        }
        MappedKey::ToggleExpand => {
            if state.show_details {
                toggle_last_tool_expand(state);
            }
        }
        MappedKey::ToggleDetails => {
            state.show_details = !state.show_details;
            state.status_flash = Some(if state.show_details {
                "details on".into()
            } else {
                "conversation only".into()
            });
        }
        MappedKey::ScrollUp => state.scroll = state.scroll.saturating_add(1),
        MappedKey::ScrollDown => state.scroll = state.scroll.saturating_sub(1),
        MappedKey::PageUp => state.scroll = state.scroll.saturating_add(10),
        MappedKey::PageDown => state.scroll = state.scroll.saturating_sub(10),
        MappedKey::Backspace => {
            state.input.pop();
        }
        MappedKey::Submit => {
            if state.composer_enabled {
                let resubscribe = submit_follow_up(client, meta, state).await?;
                if resubscribe {
                    return Ok(LoopAction::Retry);
                }
            }
        }
        MappedKey::Insert(c) => {
            if state.composer_enabled {
                state.input.push(c);
            }
        }
        MappedKey::Permission(choice) => {
            if let Some(AgentEvent::Permission {
                request_id,
                options,
                ..
            }) = state.pending_permission.clone()
            {
                respond_permission(client, &meta.id, &request_id, &options, choice).await?;
                state.pending_permission = None;
            }
        }
        MappedKey::None => {}
    }
    Ok(LoopAction::Continue)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum PermissionChoice {
    Yes,
    No,
    Always,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum MappedKey {
    Exit,
    Retry,
    Stop,
    PickImage,
    ToggleExpand,
    ToggleDetails,
    ScrollUp,
    ScrollDown,
    PageUp,
    PageDown,
    Backspace,
    Submit,
    Insert(char),
    Permission(PermissionChoice),
    None,
}

fn modifiers_allow_typing(mods: KeyModifiers) -> bool {
    !mods.contains(KeyModifiers::CONTROL)
        && !mods.contains(KeyModifiers::ALT)
        && !mods.contains(KeyModifiers::SUPER)
}

/// Toggle expand/collapse for the last tool bubble using coalesced display indexes
/// (the same list `render_transcript` iterates).
fn toggle_last_tool_expand(state: &mut ViewState) {
    let display = AgentEvent::coalesce_for_display(&state.events);
    if let Some(idx) = display
        .iter()
        .enumerate()
        .rev()
        .find(|(_, e)| matches!(e, AgentEvent::Tool { .. } | AgentEvent::ToolResult { .. }))
        .map(|(i, _)| i)
    {
        if !state.expanded_tools.remove(&idx) {
            state.expanded_tools.insert(idx);
        }
    }
}

fn follow_up_tool_for_status(status: &str) -> &'static str {
    if matches!(status, "Working" | "Blocked" | "Stopping") {
        "chat.queue_follow_up"
    } else {
        "chat.resume"
    }
}

/// ACP user-stop finishes as Done without a TaskResult event.
fn mark_terminal_if_stopping(state: &mut ViewState) {
    if matches!(state.status.as_str(), "Stopping" | "Working" | "Blocked") {
        state.status = "Done".into();
    }
}

fn apply_stop_success(state: &mut ViewState, daemon_status: Option<String>) {
    state.status = daemon_status.unwrap_or_else(|| "Done".into());
    state.status_flash = Some("stopped".into());
    state.composer_enabled = !matches!(state.status.as_str(), "Error" | "Failed" | "Stopped");
}

async fn fetch_task_status(client: &mut McpClient, task_id: &str) -> Option<String> {
    let raw = client
        .call_tool("chat.status", json!({ "taskId": task_id }))
        .await
        .ok()?;
    let v: Value = serde_json::from_str(&raw).ok()?;
    v.get("status")
        .and_then(|s| s.as_str())
        .filter(|s| !s.is_empty())
        .map(|s| s.to_string())
}

/// Pure key → action mapping for the ACP composer/viewer (unit-tested).
fn map_key_action(state: &ViewState, key: KeyEvent) -> MappedKey {
    if key.code == KeyCode::Char('c') && key.modifiers.contains(KeyModifiers::CONTROL) {
        return MappedKey::Exit;
    }

    if state.connection_issue == Some(ConnectionIssue::Lost) {
        return match key.code {
            KeyCode::Char('r') | KeyCode::Char('R') if modifiers_allow_typing(key.modifiers) => {
                MappedKey::Retry
            }
            KeyCode::Esc | KeyCode::Char('q') if modifiers_allow_typing(key.modifiers) => {
                MappedKey::Exit
            }
            _ => MappedKey::None,
        };
    }

    if state.pending_permission.is_some() {
        return match key.code {
            KeyCode::Char('y') | KeyCode::Char('Y') if modifiers_allow_typing(key.modifiers) => {
                MappedKey::Permission(PermissionChoice::Yes)
            }
            KeyCode::Char('n') | KeyCode::Char('N') if modifiers_allow_typing(key.modifiers) => {
                MappedKey::Permission(PermissionChoice::No)
            }
            KeyCode::Char('a') | KeyCode::Char('A') if modifiers_allow_typing(key.modifiers) => {
                MappedKey::Permission(PermissionChoice::Always)
            }
            KeyCode::Esc | KeyCode::Char('q') if modifiers_allow_typing(key.modifiers) => {
                MappedKey::Exit
            }
            _ => MappedKey::None,
        };
    }

    match key.code {
        KeyCode::Esc => MappedKey::Exit,
        KeyCode::Char('q') if state.input.is_empty() && modifiers_allow_typing(key.modifiers) => {
            MappedKey::Exit
        }
        KeyCode::Char('s') if key.modifiers.contains(KeyModifiers::CONTROL) => MappedKey::Stop,
        KeyCode::Char('i')
            if key.modifiers.contains(KeyModifiers::CONTROL) && state.composer_enabled =>
        {
            MappedKey::PickImage
        }
        KeyCode::Char('v') if state.input.is_empty() && modifiers_allow_typing(key.modifiers) => {
            MappedKey::ToggleDetails
        }
        KeyCode::Char(' ') if state.input.is_empty() && modifiers_allow_typing(key.modifiers) => {
            MappedKey::ToggleExpand
        }
        KeyCode::Up => MappedKey::ScrollUp,
        KeyCode::Down => MappedKey::ScrollDown,
        KeyCode::PageUp => MappedKey::PageUp,
        KeyCode::PageDown => MappedKey::PageDown,
        KeyCode::Backspace => MappedKey::Backspace,
        KeyCode::Enter => MappedKey::Submit,
        KeyCode::Char(c) if modifiers_allow_typing(key.modifiers) => MappedKey::Insert(c),
        _ => MappedKey::None,
    }
}

async fn respond_permission(
    client: &mut McpClient,
    task_id: &str,
    request_id: &str,
    options: &[(String, String)],
    choice: PermissionChoice,
) -> Result<()> {
    let label = pick_permission_label(options, choice).unwrap_or_else(|| match choice {
        PermissionChoice::Yes => "Allow".into(),
        PermissionChoice::No => "Reject".into(),
        PermissionChoice::Always => "Allow always".into(),
    });
    client
        .call_tool(
            "chat.respond",
            json!({
                "taskId": task_id,
                "requestId": request_id,
                "answers": { request_id: label }
            }),
        )
        .await
        .context("chat.respond")?;
    Ok(())
}

fn pick_permission_label(options: &[(String, String)], choice: PermissionChoice) -> Option<String> {
    let ranked_needles: &[&str] = match choice {
        PermissionChoice::Yes => &["allow_once", "allow once"],
        PermissionChoice::No => &["reject_once", "reject once", "reject", "deny"],
        PermissionChoice::Always => &["allow_always", "allow always", "always"],
    };
    for needle in ranked_needles {
        for (label, description) in options {
            let blob = format!("{label} {description}").to_lowercase();
            if blob.contains(needle) {
                return Some(label.clone());
            }
        }
    }
    match choice {
        PermissionChoice::Yes => options
            .iter()
            .find(|(label, description)| {
                let blob = format!("{label} {description}").to_lowercase();
                blob.contains("allow") && !blob.contains("always")
            })
            .map(|(label, _)| label.clone())
            .or_else(|| options.first().map(|(label, _)| label.clone())),
        PermissionChoice::No => options.last().map(|(label, _)| label.clone()),
        PermissionChoice::Always => options
            .iter()
            .find(|(_, description)| description.to_lowercase().contains("always"))
            .map(|(label, _)| label.clone())
            .or_else(|| options.first().map(|(label, _)| label.clone())),
    }
}

/// Returns true when the caller must reopen `chat.subscribe` (e.g. after continuing
/// a completed chat whose prior subscription already finished).
async fn submit_follow_up(
    client: &mut McpClient,
    meta: &ChatMeta,
    state: &mut ViewState,
) -> Result<bool> {
    let text = state.input.trim().to_string();
    if text.is_empty() && state.image_paths.is_empty() {
        return Ok(false);
    }
    // Subscribe carries transcript events, not status transitions. Another client
    // (GUI) may have started a turn while this viewer still shows Done — refresh
    // authoritative status before choosing resume vs queue.
    if let Some(daemon_status) = fetch_task_status(client, &meta.id).await {
        state.status = daemon_status;
    }
    let tool = follow_up_tool_for_status(&state.status);
    refresh_cached_skills(meta, state);
    let selected = skills_referenced_in_prompt(&text, &state.cached_skills);
    let follow_up = if text.is_empty() {
        "(image)".to_string()
    } else {
        prompt_with_skill_hints(&text, &selected)
    };
    let mut args = json!({
        "taskId": meta.id,
        "followUp": follow_up,
    });
    if !state.image_paths.is_empty() {
        args["imagePaths"] = json!(state.image_paths);
    }
    client
        .call_tool(tool, args)
        .await
        .with_context(|| tool.to_string())?;
    state.input.clear();
    state.image_paths.clear();
    state.status = "Working".into();
    state.status_flash = Some(format!("sent via {tool}"));
    // Daemon closes subscribe on terminal status; continuing the chat needs a new stream
    // or the viewer stays stale until the user exits and reattaches.
    Ok(!state.subscribe_open)
}

fn workspace_for_meta(meta: &ChatMeta) -> Option<PathBuf> {
    if !meta.cwd.trim().is_empty() {
        Some(PathBuf::from(&meta.cwd))
    } else if !meta.origin_dir.trim().is_empty() {
        Some(PathBuf::from(&meta.origin_dir))
    } else {
        None
    }
}

fn refresh_cached_skills(meta: &ChatMeta, state: &mut ViewState) {
    let workspace = workspace_for_meta(meta)
        .map(|path| path.display().to_string())
        .unwrap_or_default();
    let key = (
        meta.agent.clone().unwrap_or_default(),
        workspace,
    );
    if state.cached_skills_key.as_ref() == Some(&key) {
        return;
    }
    let workspace_path = workspace_for_meta(meta);
    state.cached_skills =
        discover_agent_skills(meta.agent.as_deref(), workspace_path.as_deref());
    state.cached_skills_key = Some(key);
}

fn available_slash_commands(meta: &ChatMeta, state: &mut ViewState) -> Vec<SlashCommand> {
    let provider_commands = state
        .events
        .iter()
        .rev()
        .find_map(|event| match event {
            AgentEvent::Commands { commands, .. } => Some(
                commands
                    .iter()
                    .map(|(name, description)| SlashCommand {
                        name: name.clone(),
                        description: description.clone(),
                    })
                    .collect::<Vec<_>>(),
            ),
            _ => None,
        })
        .unwrap_or_default();
    refresh_cached_skills(meta, state);
    merge_commands_with_skills(
        native_commands_for_agent(meta.agent.as_deref()),
        provider_commands,
        state.cached_skills.clone(),
    )
}

fn draw(
    frame: &mut Frame<'_>,
    meta: &ChatMeta,
    state: &ViewState,
    skin: &MadSkin,
    syntax_set: &SyntaxSet,
    theme: &syntect::highlighting::Theme,
) {
    let area = frame.area();
    let chunks = Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Length(3),
            Constraint::Min(5),
            Constraint::Length(menu_height(&state.slash_menu)),
            Constraint::Length(3),
            Constraint::Length(
                if state.pending_permission.is_some()
                    || state.connection_issue == Some(ConnectionIssue::Lost)
                {
                    4
                } else {
                    2
                },
            ),
        ])
        .split(area);

    let header = viewer_chrome::format_header(&meta.id, &meta.title, &state.status, Lane::Acp);
    frame.render_widget(
        Paragraph::new(header).block(
            Block::default()
                .borders(Borders::ALL)
                .title(Lane::Acp.frame_title()),
        ),
        chunks[0],
    );

    let body = render_transcript(state, skin, syntax_set, theme, chunks[1].width);
    let transcript_title = if state.show_details {
        " Transcript · details "
    } else {
        " Transcript "
    };
    frame.render_widget(
        Paragraph::new(body)
            .wrap(Wrap { trim: false })
            .scroll((state.scroll, 0))
            .block(Block::default().borders(Borders::ALL).title(transcript_title)),
        chunks[1],
    );

    let chips = if state.image_paths.is_empty() {
        String::new()
    } else {
        state
            .image_paths
            .iter()
            .map(|p| {
                PathBuf::from(p)
                    .file_name()
                    .map(|n| n.to_string_lossy().to_string())
                    .unwrap_or_else(|| p.clone())
            })
            .map(|n| format!("[image: {n}]"))
            .collect::<Vec<_>>()
            .join(" ")
    };
    let composer = if let Some(err) = &state.connection_error {
        format!(" {err} ")
    } else if !state.composer_enabled {
        " (composer disabled) ".to_string()
    } else {
        format!(" {chips}{} ", state.input)
    };
    frame.render_widget(
        Paragraph::new(composer).block(Block::default().borders(Borders::ALL).title(" Follow-up ")),
        chunks[3],
    );
    render_menu(frame, chunks[2], &state.slash_menu);

    let footer = if state.connection_issue == Some(ConnectionIssue::Lost) {
        " Lost connection to andyd — press r to retry, q/Esc to quit ".to_string()
    } else if let Some(AgentEvent::Permission {
        tool_name,
        question,
        ..
    }) = &state.pending_permission
    {
        format!(" Permission: {tool_name} — {question}  [y]es [n]o [a]lways-allow ")
    } else {
        viewer_chrome::format_status_line(Lane::Acp, state.status_flash.as_deref())
    };
    frame.render_widget(Paragraph::new(footer), chunks[4]);
}

/// Conversation-only visibility (default). Mirrors the GUI hiding AvailableCommands /
/// Raw / ContextUsage, and also drops tools/thinking/plan/mode noise for the TUI.
fn event_visible_in_conversation(event: &AgentEvent) -> bool {
    match event {
        AgentEvent::User { .. } | AgentEvent::Assistant { .. } | AgentEvent::Error { .. } => true,
        // Failed completion with no separate Error event still needs a signal.
        AgentEvent::Result { success: false, .. } => true,
        _ => false,
    }
}

fn details_default_from_env() -> bool {
    match std::env::var("ANDY_ACP_VIEW_DETAILS") {
        Ok(v) => {
            let v = v.trim().to_ascii_lowercase();
            matches!(v.as_str(), "1" | "true" | "yes" | "on" | "full" | "all")
        }
        Err(_) => false,
    }
}

fn render_transcript(
    state: &ViewState,
    skin: &MadSkin,
    syntax_set: &SyntaxSet,
    theme: &syntect::highlighting::Theme,
    width: u16,
) -> Text<'static> {
    if state.loading_transcript && state.events.is_empty() {
        return Text::from(vec![
            Line::from(""),
            Line::from(Span::styled(
                "  Loading transcript…",
                Style::default().fg(Color::DarkGray),
            )),
        ]);
    }

    let display = AgentEvent::coalesce_for_display(&state.events);
    let mut lines: Vec<Line<'static>> = Vec::new();
    for (idx, event) in display.iter().enumerate() {
        if !state.show_details && !event_visible_in_conversation(event) {
            continue;
        }
        match event {
            AgentEvent::User { text, images, .. } => {
                lines.push(Line::from(Span::styled(
                    "you".to_string(),
                    Style::default()
                        .fg(Color::Cyan)
                        .add_modifier(Modifier::BOLD),
                )));
                lines.extend(markdown_lines(skin, text, width));
                for img in images {
                    let name = PathBuf::from(img)
                        .file_name()
                        .map(|n| n.to_string_lossy().to_string())
                        .unwrap_or_else(|| img.clone());
                    lines.push(Line::from(format!("  [image: {name}] ({img})")));
                }
                lines.push(Line::from(""));
            }
            AgentEvent::Assistant { text, .. } => {
                lines.push(Line::from(Span::styled(
                    "assistant".to_string(),
                    Style::default()
                        .fg(Color::Green)
                        .add_modifier(Modifier::BOLD),
                )));
                lines.extend(markdown_lines(skin, text, width));
                lines.push(Line::from(""));
            }
            AgentEvent::Thinking { text, .. } => {
                lines.push(Line::from(Span::styled(
                    "thinking".to_string(),
                    Style::default().fg(Color::DarkGray),
                )));
                lines.extend(markdown_lines(skin, text, width));
                lines.push(Line::from(""));
            }
            AgentEvent::Tool {
                tool_name,
                summary,
                detail,
                state: tool_state,
                ..
            } => {
                let expanded = state.expanded_tools.contains(&idx);
                lines.push(Line::from(format!(
                    "▸ {tool_name} [{tool_state}] {summary}"
                )));
                if expanded {
                    lines.extend(highlight_detail(syntax_set, theme, detail));
                }
            }
            AgentEvent::ToolResult {
                tool_name,
                summary,
                detail,
                is_error,
                ..
            } => {
                let expanded = state.expanded_tools.contains(&idx);
                let tag = if *is_error { "err" } else { "ok" };
                lines.push(Line::from(format!("◂ {tool_name} [{tag}] {summary}")));
                if expanded {
                    lines.extend(highlight_detail(syntax_set, theme, detail));
                }
            }
            AgentEvent::Plan {
                entries, markdown, ..
            } => {
                lines.push(Line::from(Span::styled(
                    "plan".to_string(),
                    Style::default().fg(Color::Yellow),
                )));
                if let Some(md) = markdown {
                    lines.extend(markdown_lines(skin, md, width));
                } else {
                    for (content, status) in entries {
                        lines.push(Line::from(format!("  - [{status}] {content}")));
                    }
                }
                lines.push(Line::from(""));
            }
            AgentEvent::Mode { mode_id, .. } => {
                lines.push(Line::from(format!("mode → {mode_id}")));
            }
            AgentEvent::Commands { commands, .. } => {
                lines.push(Line::from(Span::styled(
                    "available commands".to_string(),
                    Style::default().fg(Color::Blue),
                )));
                for (name, description) in commands {
                    if description.is_empty() {
                        lines.push(Line::from(format!("  /{name}")));
                    } else {
                        lines.push(Line::from(format!("  /{name} — {description}")));
                    }
                }
                lines.push(Line::from(""));
            }
            AgentEvent::Modes {
                current_mode_id,
                modes,
                ..
            } => {
                lines.push(Line::from(Span::styled(
                    format!("available modes (current={current_mode_id})"),
                    Style::default().fg(Color::Blue),
                )));
                for (id, name) in modes {
                    lines.push(Line::from(format!("  {id} — {name}")));
                }
                lines.push(Line::from(""));
            }
            AgentEvent::Permission {
                tool_name,
                question,
                ..
            } => {
                lines.push(Line::from(Span::styled(
                    format!("permission · {tool_name}: {question}"),
                    Style::default().fg(Color::Magenta),
                )));
            }
            AgentEvent::PermissionResolved {
                allowed,
                option_id,
                note,
                ..
            } => {
                let note = note.clone().unwrap_or_default();
                lines.push(Line::from(format!(
                    "permission resolved · allowed={allowed} · {option_id} {note}"
                )));
            }
            AgentEvent::Result {
                success,
                final_text,
                ..
            } => {
                lines.push(Line::from(format!(
                    "result · success={success} {}",
                    final_text.chars().take(200).collect::<String>()
                )));
            }
            AgentEvent::Error { text, .. } => {
                lines.push(Line::from(Span::styled(
                    format!("error: {text}"),
                    Style::default().fg(Color::Red),
                )));
            }
            AgentEvent::Raw { line, .. } => {
                lines.push(Line::from(format!("raw: {line}")));
            }
            AgentEvent::Session { model, .. } => {
                lines.push(Line::from(format!("session · model={model}")));
            }
            AgentEvent::Usage {
                used_tokens,
                window_tokens,
                ..
            } => {
                lines.push(Line::from(format!(
                    "usage · {used_tokens}/{window_tokens} tokens"
                )));
            }
            AgentEvent::Unknown { .. } => {}
        }
    }
    Text::from(lines)
}

fn markdown_lines(skin: &MadSkin, text: &str, width: u16) -> Vec<Line<'static>> {
    // termimad renders markdown by writing crossterm ANSI escape codes
    // directly into the string it returns. Ratatui doesn't interpret those
    // sequences, so they must be parsed into real Spans/Styles here rather
    // than handed to `Line::from` as literal text (which corrupts wrapping
    // and alignment, since the escape bytes eat into the line's width).
    let unwrapped = unwrap_outer_markdown_fence(text);
    let rendered = skin
        .text(&unwrapped, Some(width.saturating_sub(2) as usize))
        .to_string();
    crate::ansi::ansi_text_to_lines(&rendered)
}

/// Agents often demonstrate markdown by wrapping an entire reply in a single
/// outer ` ```markdown ` fence (sometimes nesting further fences inside it,
/// e.g. a code sample). Per CommonMark, a fence only closes on a bare line of
/// backticks/tildes with no info string, so an inner ` ```lang ` line doesn't
/// close it — the whole reply parses as one literal code block and nothing
/// renders as real headings/lists/tables. If the whole message is exactly
/// that pattern, strip the outer fence so the interior renders as markdown.
fn unwrap_outer_markdown_fence(text: &str) -> std::borrow::Cow<'_, str> {
    let trimmed = text.trim();
    let lines: Vec<&str> = trimmed.lines().collect();
    if lines.len() < 3 {
        return std::borrow::Cow::Borrowed(text);
    }

    let first = lines[0].trim();
    let fence_char = if first.starts_with("```") {
        '`'
    } else if first.starts_with("~~~") {
        '~'
    } else {
        return std::borrow::Cow::Borrowed(text);
    };
    let open_len = first.chars().take_while(|&c| c == fence_char).count();
    let info = first[open_len..].trim().to_ascii_lowercase();
    if info != "markdown" && info != "md" {
        return std::borrow::Cow::Borrowed(text);
    }

    let last = lines[lines.len() - 1].trim();
    let closes_fence = !last.is_empty()
        && last.chars().all(|c| c == fence_char)
        && last.len() >= open_len;
    if !closes_fence {
        return std::borrow::Cow::Borrowed(text);
    }

    std::borrow::Cow::Owned(lines[1..lines.len() - 1].join("\n"))
}

fn highlight_detail(
    syntax_set: &SyntaxSet,
    theme: &syntect::highlighting::Theme,
    detail: &str,
) -> Vec<Line<'static>> {
    let lines: Vec<&str> = detail.lines().collect();
    let truncated = lines.len() > DETAIL_TRUNCATE_LINES;
    let slice = if truncated {
        &lines[..DETAIL_TRUNCATE_LINES]
    } else {
        &lines[..]
    };
    let syntax = syntax_set
        .find_syntax_by_extension("diff")
        .or_else(|| syntax_set.find_syntax_by_extension("rs"))
        .unwrap_or_else(|| syntax_set.find_syntax_plain_text());
    let mut highlighter = HighlightLines::new(syntax, theme);
    let mut out = Vec::new();
    let joined = slice.join("\n");
    for line in LinesWithEndings::from(&joined) {
        let ranges = highlighter
            .highlight_line(line, syntax_set)
            .unwrap_or_default();
        let mut spans = vec![Span::raw("    ")];
        for (style, text) in ranges {
            let fg = style.foreground;
            spans.push(Span::styled(
                text.to_string(),
                Style::default().fg(Color::Rgb(fg.r, fg.g, fg.b)),
            ));
        }
        out.push(Line::from(spans));
    }
    if truncated {
        out.push(Line::from(format!(
            "    … {} more lines (space to collapse)",
            lines.len() - DETAIL_TRUNCATE_LINES
        )));
    }
    out
}

async fn load_meta(client: &mut McpClient, task_id: &str) -> Result<ChatMeta> {
    let status_raw = client
        .call_tool("chat.status", json!({ "taskId": task_id }))
        .await
        .context("chat.status")?;
    let status_v: Value = serde_json::from_str(&status_raw).unwrap_or(Value::Null);
    let list_raw = client
        .call_tool("chat.list", Value::Object(Default::default()))
        .await
        .unwrap_or_else(|_| "[]".into());
    let list: Value = serde_json::from_str(&list_raw).unwrap_or(Value::Null);
    let row = list.as_array().and_then(|arr| {
        arr.iter()
            .find(|e| e.get("id").and_then(|id| id.as_str()) == Some(task_id))
    });
    Ok(ChatMeta {
        id: task_id.to_string(),
        title: row
            .and_then(|r| r.get("title"))
            .and_then(|v| v.as_str())
            .unwrap_or(task_id)
            .to_string(),
        status: status_v
            .get("status")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string(),
        agent: row
            .and_then(|r| r.get("agent"))
            .and_then(|v| v.as_str())
            .map(str::to_string),
        lane: status_v
            .get("lane")
            .or_else(|| row.and_then(|r| r.get("lane")))
            .and_then(|v| v.as_str())
            .unwrap_or("Acp")
            .to_string(),
        cwd: status_v
            .get("cwd")
            .or_else(|| row.and_then(|r| r.get("cwd")))
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string(),
        origin_dir: status_v
            .get("originDir")
            .or_else(|| row.and_then(|r| r.get("originDir")))
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string(),
    })
}

/// Resolve task lane for attach routing. Hard-fails ACP when subscribe is missing.
pub async fn resolve_lane(client: &mut McpClient, task_id: &str) -> Result<String> {
    let raw = client
        .call_tool("chat.status", json!({ "taskId": task_id }))
        .await
        .context("chat.status")?;
    let v: Value = serde_json::from_str(&raw).unwrap_or(Value::Null);
    if let Some(lane) = v
        .get("lane")
        .and_then(|l| l.as_str())
        .filter(|s| !s.is_empty())
    {
        return Ok(lane.to_string());
    }
    let list_raw = client
        .call_tool("chat.list", Value::Object(Default::default()))
        .await
        .context("chat.list")?;
    let list: Value = serde_json::from_str(&list_raw).unwrap_or(Value::Null);
    let lane = list
        .as_array()
        .and_then(|arr| {
            arr.iter().find_map(|e| {
                if e.get("id").and_then(|id| id.as_str()) == Some(task_id) {
                    e.get("lane")
                        .and_then(|l| l.as_str())
                        .map(|s| s.to_string())
                } else {
                    None
                }
            })
        })
        .unwrap_or_else(|| "Terminal".to_string());
    Ok(lane)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn press(code: KeyCode) -> KeyEvent {
        KeyEvent::new(code, KeyModifiers::NONE)
    }

    fn ctrl(code: KeyCode) -> KeyEvent {
        KeyEvent::new(code, KeyModifiers::CONTROL)
    }

    fn empty_state() -> ViewState {
        ViewState {
            events: Vec::new(),
            status: "Idle".into(),
            input: String::new(),
            image_paths: Vec::new(),
            scroll: 0,
            expanded_tools: Default::default(),
            pending_permission: None,
            composer_enabled: true,
            subscribe_open: true,
            connection_issue: None,
            connection_error: None,
            status_flash: None,
            show_details: false,
            loading_transcript: false,
            slash_menu: SlashMenuState::default(),
            cached_skills: Vec::new(),
            cached_skills_key: None,
        }
    }

    #[test]
    fn conversation_filter_hides_noise_keeps_dialogue() {
        assert!(event_visible_in_conversation(&AgentEvent::User {
            at_millis: 1,
            text: "hi".into(),
            images: vec![],
        }));
        assert!(event_visible_in_conversation(&AgentEvent::Assistant {
            at_millis: 2,
            text: "yo".into(),
            stream: false,
        }));
        assert!(event_visible_in_conversation(&AgentEvent::Error {
            at_millis: 3,
            text: "boom".into(),
        }));
        assert!(event_visible_in_conversation(&AgentEvent::Result {
            at_millis: 4,
            success: false,
            final_text: "failed".into(),
        }));
        assert!(!event_visible_in_conversation(&AgentEvent::Result {
            at_millis: 5,
            success: true,
            final_text: "done".into(),
        }));
        assert!(!event_visible_in_conversation(&AgentEvent::Thinking {
            at_millis: 6,
            text: "...".into(),
            stream: true,
        }));
        assert!(!event_visible_in_conversation(&AgentEvent::Tool {
            at_millis: 7,
            tool_name: "Bash".into(),
            tool_call_id: "1".into(),
            summary: "ls".into(),
            detail: "".into(),
            kind: "".into(),
            state: "".into(),
            locations: vec![],
        }));
        assert!(!event_visible_in_conversation(&AgentEvent::Commands {
            at_millis: 8,
            commands: vec![("skill".into(), "desc".into())],
        }));
        assert!(!event_visible_in_conversation(&AgentEvent::Raw {
            at_millis: 9,
            line: "session: null".into(),
        }));
        assert!(!event_visible_in_conversation(&AgentEvent::Usage {
            at_millis: 10,
            used_tokens: 1,
            window_tokens: 2,
        }));
    }

    #[test]
    fn v_toggles_details_when_input_empty() {
        let state = empty_state();
        assert_eq!(
            map_key_action(&state, press(KeyCode::Char('v'))),
            MappedKey::ToggleDetails
        );
        let mut typing = empty_state();
        typing.input = "hi".into();
        assert_eq!(
            map_key_action(&typing, press(KeyCode::Char('v'))),
            MappedKey::Insert('v')
        );
    }

    #[test]
    fn subscribe_batch_clears_loading_transcript() {
        let mut state = empty_state();
        state.loading_transcript = true;
        apply_subscribe_message(
            &mut state,
            SubscribeMessage::Batch(crate::mcp::SubscribeBatch {
                subscription_id: "s".into(),
                task_id: "t".into(),
                events: vec![],
                replace_from: None,
                done: false,
                error: None,
            }),
        );
        assert!(!state.loading_transcript);
    }

    #[test]
    fn finished_terminal_marks_subscribe_closed_but_keeps_composer() {
        let mut state = empty_state();
        state.status = "Working".into();
        apply_subscribe_message(
            &mut state,
            SubscribeMessage::Batch(crate::mcp::SubscribeBatch {
                subscription_id: "s".into(),
                task_id: "t".into(),
                events: vec![json!({
                    "type": "result",
                    "atMillis": 1,
                    "success": true,
                    "finalText": "done"
                })],
                replace_from: None,
                done: false,
                error: None,
            }),
        );
        assert!(state.composer_enabled);
        assert_eq!(state.status, "Done");

        apply_subscribe_message(
            &mut state,
            SubscribeMessage::Finished {
                reason: "terminal".into(),
            },
        );
        assert!(!state.subscribe_open);
        assert!(state.composer_enabled);
        assert!(state.connection_issue.is_none());
    }

    #[test]
    fn failed_result_disables_composer() {
        let mut state = empty_state();
        state.status = "Working".into();
        apply_subscribe_message(
            &mut state,
            SubscribeMessage::Batch(crate::mcp::SubscribeBatch {
                subscription_id: "s".into(),
                task_id: "t".into(),
                events: vec![json!({
                    "type": "result",
                    "atMillis": 1,
                    "success": false,
                    "finalText": "crashed"
                })],
                replace_from: None,
                done: false,
                error: None,
            }),
        );
        assert!(!state.composer_enabled);
        assert_eq!(state.status, "Error");
    }

    #[test]
    fn follow_up_routes_active_status_to_queue() {
        assert_eq!(follow_up_tool_for_status("Done"), "chat.resume");
        assert_eq!(follow_up_tool_for_status("Idle"), "chat.resume");
        assert_eq!(follow_up_tool_for_status("Working"), "chat.queue_follow_up");
        assert_eq!(follow_up_tool_for_status("Blocked"), "chat.queue_follow_up");
        assert_eq!(
            follow_up_tool_for_status("Stopping"),
            "chat.queue_follow_up"
        );
    }

    #[test]
    fn plain_c_inserts_into_composer() {
        let state = empty_state();
        assert_eq!(
            map_key_action(&state, press(KeyCode::Char('c'))),
            MappedKey::Insert('c')
        );
    }

    #[test]
    fn plain_s_and_i_insert_into_composer() {
        let state = empty_state();
        assert_eq!(
            map_key_action(&state, press(KeyCode::Char('s'))),
            MappedKey::Insert('s')
        );
        assert_eq!(
            map_key_action(&state, press(KeyCode::Char('i'))),
            MappedKey::Insert('i')
        );
    }

    #[test]
    fn ctrl_s_stops_and_ctrl_i_picks_image() {
        let state = empty_state();
        assert_eq!(
            map_key_action(&state, ctrl(KeyCode::Char('s'))),
            MappedKey::Stop
        );
        assert_eq!(
            map_key_action(&state, ctrl(KeyCode::Char('i'))),
            MappedKey::PickImage
        );
    }

    #[test]
    fn permission_yes_prefers_allow_once_over_allow_always() {
        let options = vec![
            ("Allow always".into(), "allow_always".into()),
            ("Allow once".into(), "allow_once".into()),
        ];
        assert_eq!(
            pick_permission_label(&options, PermissionChoice::Yes).as_deref(),
            Some("Allow once")
        );
        assert_eq!(
            pick_permission_label(&options, PermissionChoice::Always).as_deref(),
            Some("Allow always")
        );
    }

    #[test]
    fn ctrl_c_exits() {
        let state = empty_state();
        assert_eq!(
            map_key_action(&state, ctrl(KeyCode::Char('c'))),
            MappedKey::Exit
        );
    }

    #[test]
    fn lost_connection_offers_retry() {
        let mut state = empty_state();
        state.connection_issue = Some(ConnectionIssue::Lost);
        state.composer_enabled = false;
        assert_eq!(
            map_key_action(&state, press(KeyCode::Char('r'))),
            MappedKey::Retry
        );
        assert_eq!(
            map_key_action(&state, press(KeyCode::Char('q'))),
            MappedKey::Exit
        );
    }

    #[test]
    fn highlight_detail_emits_colored_spans() {
        let syntax_set = SyntaxSet::load_defaults_newlines();
        let theme_set = ThemeSet::load_defaults();
        let theme = theme_set
            .themes
            .get("base16-ocean.dark")
            .or_else(|| theme_set.themes.values().next())
            .unwrap();
        let lines = highlight_detail(&syntax_set, theme, "@@ -1 +1 @@\n-old\n+new\n");
        assert!(!lines.is_empty());
        let has_rgb = lines.iter().any(|line| {
            line.spans
                .iter()
                .any(|span| matches!(span.style.fg, Some(Color::Rgb(_, _, _))))
        });
        assert!(has_rgb, "expected RGB-styled spans from syntect");
    }

    #[test]
    fn apply_replace_from_updates_in_place() {
        let mut state = empty_state();
        state.events.push(AgentEvent::User {
            at_millis: 1,
            text: "hi".into(),
            images: vec![],
        });
        state.events.push(AgentEvent::Assistant {
            at_millis: 2,
            text: "hel".into(),
            stream: true,
        });
        apply_subscribe_message(
            &mut state,
            SubscribeMessage::Batch(crate::mcp::SubscribeBatch {
                subscription_id: "s".into(),
                task_id: "t".into(),
                events: vec![json!({
                    "type": "assistant",
                    "atMillis": 2,
                    "text": "hello",
                    "stream": true
                })],
                replace_from: Some(1),
                done: false,
                error: None,
            }),
        );
        assert_eq!(state.events.len(), 2);
        match &state.events[1] {
            AgentEvent::Assistant { text, .. } => assert_eq!(text, "hello"),
            other => panic!("unexpected {other:?}"),
        }
    }

    #[test]
    fn toggle_expand_uses_coalesced_display_index() {
        let mut state = empty_state();
        // Two streamed assistant deltas coalesce to one display bubble; tool follows.
        state.events.push(AgentEvent::Assistant {
            at_millis: 1,
            text: "hel".into(),
            stream: true,
        });
        state.events.push(AgentEvent::Assistant {
            at_millis: 2,
            text: "lo".into(),
            stream: true,
        });
        state.events.push(AgentEvent::Tool {
            at_millis: 3,
            tool_name: "edit".into(),
            tool_call_id: "1".into(),
            summary: "patch".into(),
            detail: "@@ -1 +1 @@\n-old\n+new\n".into(),
            kind: String::new(),
            state: "completed".into(),
            locations: vec![],
        });

        let display = AgentEvent::coalesce_for_display(&state.events);
        assert_eq!(display.len(), 2, "assistant deltas should coalesce");
        let raw_tool_idx = 2;
        let display_tool_idx = 1;
        assert!(matches!(display[display_tool_idx], AgentEvent::Tool { .. }));

        toggle_last_tool_expand(&mut state);
        assert!(
            state.expanded_tools.contains(&display_tool_idx),
            "expand set should use display index {display_tool_idx}"
        );
        assert!(
            !state.expanded_tools.contains(&raw_tool_idx),
            "must not store the raw events index"
        );
    }

    #[test]
    fn stop_without_task_result_reaches_done_on_subscribe_end() {
        let mut state = empty_state();
        state.status = "Stopping".into();
        apply_subscribe_message(
            &mut state,
            SubscribeMessage::Batch(crate::mcp::SubscribeBatch {
                subscription_id: "s".into(),
                task_id: "t".into(),
                events: vec![],
                replace_from: None,
                done: true,
                error: None,
            }),
        );
        // done batch alone does not promote — Finished reason distinguishes Done vs Error.
        assert_eq!(state.status, "Stopping");
        assert!(!state.subscribe_open);

        apply_subscribe_message(
            &mut state,
            SubscribeMessage::Finished {
                reason: "terminal".into(),
            },
        );
        assert_eq!(state.status, "Done");
        assert!(state.composer_enabled);
    }

    #[test]
    fn finished_error_disables_composer_without_task_result() {
        let mut state = empty_state();
        state.status = "Working".into();
        apply_subscribe_message(
            &mut state,
            SubscribeMessage::Finished {
                reason: "error".into(),
            },
        );
        assert!(!state.subscribe_open);
        assert!(!state.composer_enabled);
        assert_eq!(state.status, "Error");
    }

    #[test]
    fn apply_stop_success_prefers_daemon_status() {
        let mut state = empty_state();
        state.status = "Stopping".into();
        apply_stop_success(&mut state, Some("Done".into()));
        assert_eq!(state.status, "Done");
        assert_eq!(state.status_flash.as_deref(), Some("stopped"));

        let mut state = empty_state();
        state.status = "Stopping".into();
        apply_stop_success(&mut state, None);
        assert_eq!(state.status, "Done");
    }

    #[test]
    fn unwraps_whole_message_markdown_fence_with_nested_code_fence() {
        let text = "```markdown\n# Title\n\n```kotlin\nfun main() {}\n```\n\n| A | B |\n|---|---|\n| 1 | 2 |\n```";
        let unwrapped = unwrap_outer_markdown_fence(text);
        assert_eq!(
            unwrapped,
            "# Title\n\n```kotlin\nfun main() {}\n```\n\n| A | B |\n|---|---|\n| 1 | 2 |"
        );
    }

    #[test]
    fn leaves_normal_code_fence_untouched() {
        let text = "```kotlin\nfun main() {}\n```";
        assert_eq!(unwrap_outer_markdown_fence(text), text);
    }

    #[test]
    fn leaves_prose_with_trailing_fence_untouched() {
        let text = "Some prose.\n\n```markdown\n# Title\n```";
        assert_eq!(unwrap_outer_markdown_fence(text), text);
    }

    #[test]
    fn leaves_unclosed_markdown_fence_untouched() {
        let text = "```markdown\n# Title\nno closing fence here";
        assert_eq!(unwrap_outer_markdown_fence(text), text);
    }

    /// Regression test for a real Codex reply that wrapped an entire markdown
    /// demo (headings, list, blockquote, nested code fence, table) inside one
    /// outer ` ```markdown ` fence. Before the unwrap fix, `markdown_lines`
    /// rendered the whole reply as one literal, unstyled block; it should now
    /// surface the interior elements (heading text without its `#`, the
    /// nested kotlin snippet, and the table) as distinct lines.
    #[test]
    fn markdown_lines_unwraps_and_renders_real_agent_reply() {
        let text = "```markdown\n# Sample Markdown\n\nThis is a paragraph with **bold text**, *italic text*, and a [link](https://example.com).\n\n## Features\n\n- Bullet list item\n- Another item\n  - Nested item\n\n1. First step\n2. Second step\n\n> This is a blockquote.\n\n```kotlin\nfun main() {\n    println(\"Hello, Markdown!\")\n}\n```\n\n| Name | Value |\n|------|-------|\n| Example | 42 |\n```";
        let skin = MadSkin::default();
        let lines = markdown_lines(&skin, text, 82);
        let plain: Vec<String> = lines
            .iter()
            .map(|l| l.spans.iter().map(|s| s.content.as_ref()).collect::<String>())
            .collect();

        // The heading is rendered (no leading literal `#`), not dumped as raw fenced text.
        assert!(plain.iter().any(|l| l.contains("Sample Markdown") && !l.contains('#')));
        // The nested code sample still renders as code, without stray backtick fence markers.
        assert!(plain.iter().any(|l| l.contains("fun main")));
        assert!(!plain.iter().any(|l| l.trim() == "```kotlin"));
        // The table renders with box-drawing borders rather than literal pipes.
        assert!(plain.iter().any(|l| l.contains('│') && l.contains("Name")));
    }
}
