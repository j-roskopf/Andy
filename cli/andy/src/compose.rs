use anyhow::{bail, Context, Result};
use crossterm::event::{self, Event, KeyCode, KeyEventKind};
use ratatui::prelude::*;
use ratatui::widgets::{Block, Borders, List, ListItem, ListState, Paragraph, Wrap};
use serde_json::{json, Value};
use std::path::PathBuf;
use std::time::Duration;

use crate::attach;
use crate::file_picker;
use crate::mcp::McpClient;
use crate::skills::{
    discover_agent_skills, is_orchestration_skill_name, prompt_with_skill_hints,
    skills_referenced_in_prompt, AgentSkill,
};
use crate::slash::{
    complete_command, menu_height, merge_commands_with_skills, native_commands_for_agent,
    render_menu, SlashMenuAction, SlashMenuState,
};

#[derive(Clone, Debug)]
struct OptionRow {
    id: String,
    label: String,
    detail: String,
    /// When false, still selectable but not preferred.
    ready: bool,
    /// Project context directory when known.
    directory: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Step {
    Agent,
    Model,
    Autonomy,
    Project,
    Directory,
    Prompt,
    Confirm,
}

pub struct ComposeDraft {
    agent_id: String,
    agent_label: String,
    model_id: String,
    model_label: String,
    autonomy_id: String,
    autonomy_label: String,
    project_id: String,
    project_label: String,
    directory: String,
    prompt: String,
    image_paths: Vec<String>,
}

enum StepView {
    Pick {
        title: String,
        rows: Vec<OptionRow>,
        selected: usize,
        list_state: ListState,
    },
    Text {
        title: String,
        value: String,
        hint: String,
    },
    Confirm,
}

pub struct ComposeOutcome {
    pub task_id: String,
    pub attach_error: Option<String>,
}

/// Interactive new-chat wizard.
///
/// Returns `Ok(Some(...))` when a chat was started, `Ok(None)` when cancelled.
/// [preset_project] pre-fills the project from the TUI header under the cursor
/// (`None` / `Some("")` = Inbox).
pub async fn run_composer(
    client: &mut McpClient,
    terminal: &mut Terminal<impl Backend>,
    preset_project: Option<(&str, &str)>,
) -> Result<Option<ComposeOutcome>> {
    let catalog = load_catalog(client).await?;
    let cwd = std::env::current_dir()
        .map(|p| p.display().to_string())
        .unwrap_or_default();

    // When opened from a TUI project/Inbox header, lock that project and skip the picker.
    let project_locked = preset_project.is_some();
    let (project_id, project_label) = match preset_project {
        Some((id, label)) => (id.to_string(), label.to_string()),
        None => (String::new(), "Inbox (no project)".into()),
    };
    let project_dir = catalog
        .projects
        .iter()
        .find(|p| p.id == project_id)
        .map(|p| p.directory.clone())
        .filter(|d| !d.is_empty())
        .unwrap_or_else(|| cwd.clone());

    let mut draft = ComposeDraft {
        agent_id: String::new(),
        agent_label: String::new(),
        model_id: String::new(),
        model_label: "provider default".into(),
        autonomy_id: "Standard".into(),
        autonomy_label: "standard".into(),
        project_id,
        project_label,
        directory: project_dir,
        prompt: String::new(),
        image_paths: Vec::new(),
    };

    let mut step = Step::Agent;
    let mut view = pick_view(
        "Provider",
        catalog.agents.clone(),
        preferred_index(&catalog.agents),
    );
    let mut status = if project_locked {
        format!("project: {} · Esc cancel · Enter next", draft.project_label)
    } else {
        "Esc cancel · Enter next".into()
    };
    let mut slash_menu = SlashMenuState::default();
    // Discover once per provider/directory — not on every redraw of the prompt step.
    let mut cached_skills: Vec<AgentSkill> = Vec::new();
    let mut cached_skills_key: Option<(String, String)> = None;

    loop {
        let prompt_value = match (&view, step) {
            (StepView::Text { value, .. }, Step::Prompt) => value.clone(),
            _ => String::new(),
        };
        // Provider commands are not available until an ACP session emits them.
        // New-chat still exposes native commands and locally discovered skills.
        let slash_commands = if step == Step::Prompt {
            let key = (draft.agent_id.clone(), draft.directory.clone());
            if cached_skills_key.as_ref() != Some(&key) {
                cached_skills = discover_agent_skills(
                    Some(&draft.agent_id),
                    Some(std::path::Path::new(&draft.directory)),
                );
                cached_skills_key = Some(key);
            }
            merge_commands_with_skills(
                native_commands_for_agent(Some(&draft.agent_id)),
                Vec::new(),
                cached_skills.clone(),
            )
        } else {
            Vec::new()
        };
        slash_menu.sync(&prompt_value, &slash_commands);
        let slash_menu_height = menu_height(&slash_menu);
        terminal.draw(|frame| {
            let area = frame.area();
            let chunks = Layout::default()
                .direction(Direction::Vertical)
                .constraints([
                    Constraint::Length(3),
                    Constraint::Min(5),
                    Constraint::Length(slash_menu_height),
                    Constraint::Length(3),
                ])
                .split(area);

            let header = Paragraph::new(format!(
                "New chat  ·  {}  ·  step: {}  ·  {} / {}",
                draft.project_label,
                step_label(step),
                draft.agent_label,
                draft.model_label
            ))
            .block(Block::default().title(" Compose ").borders(Borders::ALL));
            frame.render_widget(header, chunks[0]);

            match &mut view {
                StepView::Pick {
                    title,
                    rows,
                    selected,
                    list_state,
                } => {
                    let items: Vec<ListItem> = rows
                        .iter()
                        .enumerate()
                        .map(|(i, row)| {
                            let marker = if i == *selected { ">" } else { " " };
                            let detail = if row.detail.is_empty() {
                                String::new()
                            } else {
                                format!("  — {}", row.detail)
                            };
                            let item = ListItem::new(format!(
                                "{marker} {}{}",
                                row.label, detail
                            ));
                            if i == *selected {
                                item.style(Style::default().add_modifier(Modifier::REVERSED))
                            } else {
                                item
                            }
                        })
                        .collect();
                    list_state.select(Some(*selected));
                    let list = List::new(items).block(
                        Block::default()
                            .title(format!(" {title} "))
                            .borders(Borders::ALL),
                    );
                    frame.render_stateful_widget(list, chunks[1], list_state);
                }
                StepView::Text { title, value, hint } => {
                    let body = Paragraph::new(format!("{value}▌\n\n{hint}"))
                        .wrap(Wrap { trim: false })
                        .block(
                            Block::default()
                                .title(format!(" {title} "))
                                .borders(Borders::ALL),
                        );
                    frame.render_widget(body, chunks[1]);
                }
                StepView::Confirm => {
                    let images = if draft.image_paths.is_empty() {
                        "(none)".to_string()
                    } else {
                        draft
                            .image_paths
                            .iter()
                            .map(|p| {
                                PathBuf::from(p)
                                    .file_name()
                                    .map(|n| n.to_string_lossy().into_owned())
                                    .unwrap_or_else(|| p.clone())
                            })
                            .collect::<Vec<_>>()
                            .join(", ")
                    };
                    let summary = format!(
                        "Provider:  {}\nModel:     {}\nAutonomy:  {}\nProject:   {}\nDirectory: {}\nImages:    {}\n\nPrompt:\n{}",
                        draft.agent_label,
                        draft.model_label,
                        draft.autonomy_label,
                        draft.project_label,
                        draft.directory,
                        images,
                        draft.prompt
                    );
                    let body = Paragraph::new(summary)
                        .wrap(Wrap { trim: false })
                        .block(
                            Block::default()
                                .title(" Confirm · Enter start · a start+attach · Esc back ")
                                .borders(Borders::ALL),
                        );
                    frame.render_widget(body, chunks[1]);
                }
            }

            render_menu(frame, chunks[2], &slash_menu);

            frame.render_widget(
                Paragraph::new(status.as_str()).block(Block::default().borders(Borders::ALL)),
                chunks[3],
            );
        })?;

        if !event::poll(Duration::from_millis(250))? {
            continue;
        }
        let Event::Key(key) = event::read()? else {
            continue;
        };
        if key.kind != KeyEventKind::Press {
            continue;
        }

        let slash_action = if step == Step::Prompt {
            if let StepView::Text { value, .. } = &view {
                slash_menu.handle_key(value, &slash_commands, key.code)
            } else {
                SlashMenuAction::Pass
            }
        } else {
            SlashMenuAction::Pass
        };
        match slash_action {
            SlashMenuAction::Complete => {
                let Some(command) = slash_menu.selected_command().map(|c| c.name.clone()) else {
                    continue;
                };
                if let StepView::Text { value, .. } = &mut view {
                    if let Some(completed) = complete_command(value, &command) {
                        *value = completed;
                        status = format!("completed /{command} · Enter next · Esc back");
                    }
                }
                continue;
            }
            SlashMenuAction::Dismiss | SlashMenuAction::Move => continue,
            SlashMenuAction::Pass => {}
        }

        match key.code {
            KeyCode::Esc => {
                if step == Step::Agent {
                    return Ok(None);
                }
                step = prev_step(step, project_locked);
                view = view_for_step(step, &catalog, &draft);
                status = step_status(step);
            }
            KeyCode::Down => move_pick(&mut view, 1),
            KeyCode::Up => move_pick(&mut view, -1),
            KeyCode::Char('j') if matches!(view, StepView::Pick { .. }) => move_pick(&mut view, 1),
            KeyCode::Char('k') if matches!(view, StepView::Pick { .. }) => move_pick(&mut view, -1),
            KeyCode::Backspace => {
                if let StepView::Text { value, .. } = &mut view {
                    value.pop();
                }
            }
            KeyCode::Char('a') if step == Step::Confirm => {
                match start_and_attach(client, terminal, &draft, &mut status).await {
                    Ok(outcome) => return Ok(Some(outcome)),
                    Err(err) => status = format!("error: {err:#}"),
                }
            }
            KeyCode::Char('i')
                if step == Step::Prompt
                    && matches!(&view, StepView::Text { value, .. } if value.is_empty())
                    || step == Step::Confirm =>
            {
                // Empty prompt (or Confirm): open image picker. Typing 'i' still works once text exists.
                let start = if !draft.directory.trim().is_empty() {
                    PathBuf::from(draft.directory.trim())
                } else {
                    std::env::current_dir().unwrap_or_else(|_| PathBuf::from("."))
                };
                match file_picker::pick_image(terminal, start) {
                    Ok(Some(path)) => {
                        draft.image_paths.push(path.display().to_string());
                        status = format!(
                            "attached {} image(s) · i attach another · Enter confirm",
                            draft.image_paths.len()
                        );
                        if let StepView::Text { hint, .. } = &mut view {
                            *hint = prompt_hint(&draft);
                        }
                    }
                    Ok(None) => status = "image attach cancelled".into(),
                    Err(err) => status = format!("image picker error: {err:#}"),
                }
            }
            KeyCode::Char(c) if matches!(view, StepView::Text { .. }) => {
                if let StepView::Text { value, .. } = &mut view {
                    if !c.is_control() {
                        value.push(c);
                    }
                }
            }
            KeyCode::Enter => match step {
                Step::Agent | Step::Model | Step::Autonomy | Step::Project => {
                    if let StepView::Pick { rows, selected, .. } = &view {
                        let Some(row) = rows.get(*selected) else {
                            continue;
                        };
                        apply_pick(step, &mut draft, row);
                    }
                    step = next_step(step, project_locked);
                    view = view_for_step(step, &catalog, &draft);
                    status = step_status(step);
                }
                Step::Directory | Step::Prompt => {
                    if let StepView::Text { value, .. } = &view {
                        apply_text(step, &mut draft, value);
                    }
                    if step == Step::Prompt && draft.prompt.trim().is_empty() {
                        status = "prompt required".into();
                        continue;
                    }
                    step = next_step(step, project_locked);
                    view = view_for_step(step, &catalog, &draft);
                    status = step_status(step);
                }
                Step::Confirm => {
                    match start_and_attach(client, terminal, &draft, &mut status).await {
                        Ok(outcome) => return Ok(Some(outcome)),
                        Err(err) => status = format!("error: {err:#}"),
                    }
                }
            },
            _ => {}
        }
    }
}

struct Catalog {
    agents: Vec<OptionRow>,
    models: Value,
    autonomies: Vec<OptionRow>,
    projects: Vec<OptionRow>,
}

async fn load_catalog(client: &mut McpClient) -> Result<Catalog> {
    let raw = client
        .call_tool("chat.composer_options", Value::Object(Default::default()))
        .await
        .context("chat.composer_options")?;
    if raw.trim().is_empty() {
        bail!("chat.composer_options returned empty — is andyd up to date? restart with ./gradlew runAndyd");
    }
    let v: Value = serde_json::from_str(&raw).with_context(|| {
        format!(
            "parse composer_options (restart andyd if tool is missing): {}",
            raw.chars().take(120).collect::<String>()
        )
    })?;
    let agents = parse_agent_rows(v.get("agents"));
    let autonomies = parse_rows(v.get("autonomies"), /*with_directory*/ false);
    let projects = parse_rows(v.get("projects"), /*with_directory*/ true);
    Ok(Catalog {
        agents,
        models: v
            .get("models")
            .cloned()
            .unwrap_or(Value::Object(Default::default())),
        autonomies,
        projects,
    })
}

fn parse_rows(value: Option<&Value>, with_directory: bool) -> Vec<OptionRow> {
    let Some(Value::Array(arr)) = value else {
        return Vec::new();
    };
    arr.iter()
        .filter_map(|el| {
            let id = el.get("id")?.as_str()?.to_string();
            let label = el
                .get("label")
                .and_then(|s| s.as_str())
                .unwrap_or(id.as_str())
                .to_string();
            let directory = if with_directory {
                el.get("directory")
                    .and_then(|s| s.as_str())
                    .unwrap_or("")
                    .to_string()
            } else {
                String::new()
            };
            Some(OptionRow {
                id,
                label,
                detail: String::new(),
                ready: true,
                directory,
            })
        })
        .collect()
}

fn parse_agent_rows(value: Option<&Value>) -> Vec<OptionRow> {
    let Some(Value::Array(arr)) = value else {
        return Vec::new();
    };
    arr.iter()
        .filter_map(|el| {
            let ready = el.get("ready").and_then(|b| b.as_bool()).unwrap_or(false);
            let issue = el.get("issue").and_then(|s| s.as_str()).unwrap_or("");
            let detail = if !ready {
                if issue.is_empty() {
                    "not ready".into()
                } else {
                    issue.to_string()
                }
            } else {
                el.get("version")
                    .and_then(|s| s.as_str())
                    .unwrap_or("")
                    .to_string()
            };
            let id = el.get("id")?.as_str()?.to_string();
            let label = el
                .get("label")
                .and_then(|s| s.as_str())
                .unwrap_or(id.as_str())
                .to_string();
            Some(OptionRow {
                id,
                label,
                detail,
                ready,
                directory: String::new(),
            })
        })
        .collect()
}

fn models_for(catalog: &Catalog, agent_id: &str) -> Vec<OptionRow> {
    let Some(Value::Array(arr)) = catalog.models.get(agent_id) else {
        return vec![OptionRow {
            id: String::new(),
            label: "provider default".into(),
            detail: String::new(),
            ready: true,
            directory: String::new(),
        }];
    };
    arr.iter()
        .filter_map(|el| {
            Some(OptionRow {
                id: el.get("id")?.as_str()?.to_string(),
                label: el
                    .get("label")
                    .and_then(|s| s.as_str())
                    .unwrap_or("model")
                    .to_string(),
                detail: String::new(),
                ready: true,
                directory: String::new(),
            })
        })
        .collect()
}

fn preferred_index(rows: &[OptionRow]) -> usize {
    rows.iter().position(|r| r.ready).unwrap_or(0)
}

fn pick_view(title: &str, rows: Vec<OptionRow>, selected: usize) -> StepView {
    let mut list_state = ListState::default();
    let selected = if rows.is_empty() {
        0
    } else {
        selected.min(rows.len() - 1)
    };
    list_state.select(Some(selected));
    StepView::Pick {
        title: title.into(),
        rows,
        selected,
        list_state,
    }
}

fn view_for_step(step: Step, catalog: &Catalog, draft: &ComposeDraft) -> StepView {
    match step {
        Step::Agent => pick_view(
            "Provider",
            catalog.agents.clone(),
            preferred_index(&catalog.agents),
        ),
        Step::Model => {
            let rows = models_for(catalog, &draft.agent_id);
            pick_view("Model", rows, 0)
        }
        Step::Autonomy => {
            let idx = catalog
                .autonomies
                .iter()
                .position(|r| r.id == draft.autonomy_id)
                .unwrap_or(0);
            pick_view("Autonomy", catalog.autonomies.clone(), idx)
        }
        Step::Project => {
            let idx = catalog
                .projects
                .iter()
                .position(|r| r.id == draft.project_id)
                .unwrap_or(0);
            pick_view("Project", catalog.projects.clone(), idx)
        }
        Step::Directory => StepView::Text {
            title: "Working directory".into(),
            value: draft.directory.clone(),
            hint: "type path · Enter next · Esc back".into(),
        },
        Step::Prompt => StepView::Text {
            title: "Prompt".into(),
            value: draft.prompt.clone(),
            hint: prompt_hint(draft),
        },
        Step::Confirm => StepView::Confirm,
    }
}

fn prompt_hint(draft: &ComposeDraft) -> String {
    if draft.image_paths.is_empty() {
        "type prompt · i attach image · Enter confirm · Esc back".into()
    } else {
        let names = draft
            .image_paths
            .iter()
            .map(|p| {
                PathBuf::from(p)
                    .file_name()
                    .map(|n| n.to_string_lossy().into_owned())
                    .unwrap_or_else(|| p.clone())
            })
            .collect::<Vec<_>>()
            .join(", ");
        format!("images: {names} · i attach another · Enter confirm · Esc back")
    }
}

fn apply_pick(step: Step, draft: &mut ComposeDraft, row: &OptionRow) {
    match step {
        Step::Agent => {
            draft.agent_id = row.id.clone();
            draft.agent_label = row.label.clone();
        }
        Step::Model => {
            draft.model_id = row.id.clone();
            draft.model_label = row.label.clone();
        }
        Step::Autonomy => {
            draft.autonomy_id = row.id.clone();
            draft.autonomy_label = row.label.clone();
        }
        Step::Project => {
            draft.project_id = row.id.clone();
            draft.project_label = row.label.clone();
            if !row.directory.is_empty() {
                draft.directory = row.directory.clone();
            }
        }
        _ => {}
    }
}

fn apply_text(step: Step, draft: &mut ComposeDraft, value: &str) {
    match step {
        Step::Directory => draft.directory = value.to_string(),
        Step::Prompt => draft.prompt = value.to_string(),
        _ => {}
    }
}

fn next_step(step: Step, project_locked: bool) -> Step {
    match step {
        Step::Agent => Step::Model,
        Step::Model => Step::Autonomy,
        // From a TUI project/Inbox header: skip project + directory pickers.
        Step::Autonomy if project_locked => Step::Prompt,
        Step::Autonomy => Step::Project,
        Step::Project => Step::Directory,
        Step::Directory => Step::Prompt,
        Step::Prompt => Step::Confirm,
        Step::Confirm => Step::Confirm,
    }
}

fn prev_step(step: Step, project_locked: bool) -> Step {
    match step {
        Step::Agent => Step::Agent,
        Step::Model => Step::Agent,
        Step::Autonomy => Step::Model,
        Step::Project => Step::Autonomy,
        Step::Directory => Step::Project,
        Step::Prompt if project_locked => Step::Autonomy,
        Step::Prompt => Step::Directory,
        Step::Confirm => Step::Prompt,
    }
}

fn step_label(step: Step) -> &'static str {
    match step {
        Step::Agent => "provider",
        Step::Model => "model",
        Step::Autonomy => "autonomy",
        Step::Project => "project",
        Step::Directory => "directory",
        Step::Prompt => "prompt",
        Step::Confirm => "confirm",
    }
}

fn step_status(step: Step) -> String {
    match step {
        Step::Confirm => "Enter / a  start + attach · Esc back".into(),
        Step::Prompt => "type · i attach image · Enter next · Esc back".into(),
        Step::Directory => "type · Enter next · Esc back".into(),
        _ => "↑↓ select · Enter next · Esc back".into(),
    }
}

fn move_pick(view: &mut StepView, delta: isize) {
    let StepView::Pick {
        rows,
        selected,
        list_state,
        ..
    } = view
    else {
        return;
    };
    if rows.is_empty() {
        return;
    }
    let next = (*selected as isize + delta).rem_euclid(rows.len() as isize);
    *selected = next as usize;
    list_state.select(Some(*selected));
}

/// Start failures are `Err`. Attach failures are returned on the outcome.
async fn start_and_attach(
    client: &mut McpClient,
    terminal: &mut Terminal<impl Backend>,
    draft: &ComposeDraft,
    status: &mut String,
) -> Result<ComposeOutcome> {
    let id = start_chat(client, draft).await?;
    *status = format!("started {id} · waiting for terminal…");
    // Show status once before attach takes over the TTY.
    let _ = terminal.draw(|f| {
        let area = f.area();
        f.render_widget(
            Paragraph::new(status.as_str()).block(Block::default().borders(Borders::ALL)),
            area,
        );
    });
    let attach_error = match attach::attach_or_reattach(client, &id).await {
        Ok(()) => None,
        Err(err) => Some(format!("{err:#}")),
    };
    Ok(ComposeOutcome {
        task_id: id,
        attach_error,
    })
}

async fn start_chat(client: &mut McpClient, draft: &ComposeDraft) -> Result<String> {
    if draft.agent_id.is_empty() {
        bail!("provider required");
    }
    if draft.prompt.trim().is_empty() {
        bail!("prompt required");
    }
    let discovered = discover_agent_skills(
        Some(&draft.agent_id),
        Some(std::path::Path::new(&draft.directory)),
    );
    let selected = skills_referenced_in_prompt(draft.prompt.trim(), &discovered);
    let prompt = prompt_with_skill_hints(draft.prompt.trim(), &selected);
    let attach_andy_mcp = selected
        .iter()
        .any(|skill| is_orchestration_skill_name(&skill.name));
    let mut args = json!({
        "agent": draft.agent_id,
        "prompt": prompt,
        "autonomy": draft.autonomy_id,
        "attachAndyMcp": attach_andy_mcp,
    });
    if !draft.model_id.is_empty() {
        args["model"] = json!(draft.model_id);
    }
    if !draft.project_id.is_empty() {
        args["projectId"] = json!(draft.project_id);
    }
    if !draft.directory.trim().is_empty() {
        args["directory"] = json!(draft.directory.trim());
    }
    if !draft.image_paths.is_empty() {
        args["imagePaths"] = json!(draft.image_paths);
    }
    let raw = client.call_tool("chat.start", args).await?;
    let v: Value = serde_json::from_str(&raw).unwrap_or(Value::Null);
    if let Some(err) = v.as_str().filter(|s| s.starts_with("Error:")) {
        bail!("{err}");
    }
    v.get("id")
        .and_then(|id| id.as_str())
        .map(|s| s.to_string())
        .with_context(|| format!("unexpected chat.start response: {raw}"))
}
