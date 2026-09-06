//! Pending user-input (ACP permissions, grill-me / question.json, ask-user).
//!
//! Shared by the ACP attach viewer and Terminal-lane pre-attach prompt so every
//! surface can answer `chat.respond` with question-id-keyed answers.

use anyhow::{Context, Result};
use crossterm::event::{self, Event, KeyCode, KeyEventKind, KeyModifiers};
use crossterm::terminal::{disable_raw_mode, enable_raw_mode, EnterAlternateScreen, LeaveAlternateScreen};
use crossterm::ExecutableCommand;
use ratatui::prelude::*;
use ratatui::widgets::{Block, Borders, Paragraph, Wrap};
use serde_json::{json, Map, Value};
use std::collections::HashMap;
use std::io::{stdout, Stdout};
use std::time::Duration;

use crate::mcp::McpClient;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UserOption {
    pub label: String,
    pub description: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UserQuestion {
    pub id: String,
    pub header: String,
    pub question: String,
    pub options: Vec<UserOption>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PendingUserInput {
    pub request_id: String,
    /// `AcpPermission` | `Artifact` | other / empty.
    pub origin: String,
    pub questions: Vec<UserQuestion>,
}

impl PendingUserInput {
    pub fn is_permission(&self) -> bool {
        self.origin.eq_ignore_ascii_case("AcpPermission")
    }

    pub fn summary_line(&self) -> String {
        let Some(q) = self.questions.first() else {
            return "Needs your input".into();
        };
        let tool = if q.header.is_empty() {
            if self.is_permission() {
                "permission"
            } else {
                "decision"
            }
        } else {
            q.header.as_str()
        };
        let text = q.question.trim();
        if text.is_empty() {
            format!("{tool}")
        } else {
            format!("{tool}: {text}")
        }
    }

    pub fn from_permission_event(
        request_id: String,
        tool_name: String,
        question: String,
        options: Vec<(String, String)>,
    ) -> Self {
        Self {
            request_id: request_id.clone(),
            origin: "AcpPermission".into(),
            questions: vec![UserQuestion {
                id: request_id,
                header: tool_name,
                question,
                options: options
                    .into_iter()
                    .map(|(label, description)| UserOption { label, description })
                    .collect(),
            }],
        }
    }
}

pub fn parse_user_input_request(value: Option<&Value>) -> Option<PendingUserInput> {
    let obj = value?.as_object()?;
    let request_id = obj
        .get("id")
        .and_then(|v| v.as_str())
        .filter(|s| !s.is_empty())?
        .to_string();
    let origin = obj
        .get("origin")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    let questions = obj
        .get("questions")
        .and_then(|v| v.as_array())
        .map(|arr| {
            arr.iter()
                .filter_map(|q| {
                    let id = q
                        .get("id")
                        .and_then(|v| v.as_str())
                        .filter(|s| !s.is_empty())?
                        .to_string();
                    let header = q
                        .get("header")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .to_string();
                    let question = q
                        .get("question")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .to_string();
                    let options = q
                        .get("options")
                        .and_then(|v| v.as_array())
                        .map(|opts| {
                            opts.iter()
                                .map(|o| UserOption {
                                    label: o
                                        .get("label")
                                        .and_then(|v| v.as_str())
                                        .unwrap_or("")
                                        .to_string(),
                                    description: o
                                        .get("description")
                                        .and_then(|v| v.as_str())
                                        .unwrap_or("")
                                        .to_string(),
                                })
                                .collect()
                        })
                        .unwrap_or_default();
                    Some(UserQuestion {
                        id,
                        header,
                        question,
                        options,
                    })
                })
                .collect::<Vec<_>>()
        })
        .unwrap_or_default();
    if questions.is_empty() {
        return None;
    }
    Some(PendingUserInput {
        request_id,
        origin,
        questions,
    })
}

pub async fn fetch_pending_user_input(
    client: &mut McpClient,
    task_id: &str,
) -> Option<PendingUserInput> {
    let raw = client
        .call_tool(
            "chat.status",
            json!({ "taskId": task_id, "includeTmuxAlive": false }),
        )
        .await
        .ok()?;
    let v: Value = serde_json::from_str(&raw).ok()?;
    parse_user_input_request(v.get("userInputRequest"))
}

pub async fn respond(
    client: &mut McpClient,
    task_id: &str,
    request: &PendingUserInput,
    answers: &HashMap<String, String>,
) -> Result<()> {
    let mut map = Map::new();
    for q in &request.questions {
        let value = answers.get(&q.id).cloned().unwrap_or_default();
        map.insert(q.id.clone(), Value::String(value));
    }
    client
        .call_tool(
            "chat.respond",
            json!({
                "taskId": task_id,
                "requestId": request.request_id,
                "answers": Value::Object(map),
            }),
        )
        .await
        .context("chat.respond")?;
    Ok(())
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PermissionChoice {
    Yes,
    No,
    Always,
}

pub fn pick_permission_label(
    options: &[(String, String)],
    choice: PermissionChoice,
) -> Option<String> {
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
            .or_else(|| options.first())
            .map(|(label, _)| label.clone()),
        PermissionChoice::No => options.last().map(|(label, _)| label.clone()),
        PermissionChoice::Always => options
            .iter()
            .find(|(label, description)| {
                format!("{label} {description}")
                    .to_lowercase()
                    .contains("always")
            })
            .map(|(label, _)| label.clone()),
    }
}

pub async fn respond_permission_choice(
    client: &mut McpClient,
    task_id: &str,
    request: &PendingUserInput,
    choice: PermissionChoice,
) -> Result<()> {
    let options: Vec<(String, String)> = request
        .questions
        .first()
        .map(|q| {
            q.options
                .iter()
                .map(|o| (o.label.clone(), o.description.clone()))
                .collect()
        })
        .unwrap_or_default();
    let label = pick_permission_label(&options, choice).unwrap_or_else(|| match choice {
        PermissionChoice::Yes => "Allow".into(),
        PermissionChoice::No => "Reject".into(),
        PermissionChoice::Always => "Allow always".into(),
    });
    let mut answers = HashMap::new();
    if let Some(q) = request.questions.first() {
        answers.insert(q.id.clone(), label);
    }
    respond(client, task_id, request, &answers).await
}

/// Numbered option pick (1-based). Returns the label when the index is valid.
pub fn option_label_at(question: &UserQuestion, one_based: usize) -> Option<String> {
    question
        .options
        .get(one_based.saturating_sub(1))
        .map(|o| o.label.clone())
}

/// Full-screen decision prompt for Terminal-lane attach (and any non-ACP path).
/// Returns Ok(true) when the user answered; Ok(false) when they quit without answering.
pub async fn run_standalone_prompt(
    client: &mut McpClient,
    task_id: &str,
    request: &PendingUserInput,
) -> Result<bool> {
    enable_raw_mode()?;
    stdout().execute(EnterAlternateScreen)?;
    let mut terminal = Terminal::new(CrosstermBackend::new(stdout()))?;
    let result = run_standalone_loop(client, &mut terminal, task_id, request).await;
    disable_raw_mode()?;
    stdout().execute(LeaveAlternateScreen)?;
    result
}

async fn run_standalone_loop(
    client: &mut McpClient,
    terminal: &mut Terminal<CrosstermBackend<Stdout>>,
    task_id: &str,
    request: &PendingUserInput,
) -> Result<bool> {
    let mut answers: HashMap<String, String> = HashMap::new();
    let mut focus = 0usize;
    let mut freeform = String::new();
    loop {
        terminal.draw(|frame| draw_standalone(frame, task_id, request, &answers, focus, &freeform))?;
        if !event::poll(Duration::from_millis(120))? {
            continue;
        }
        let Event::Key(key) = event::read()? else {
            continue;
        };
        if key.kind != KeyEventKind::Press {
            continue;
        }
        if key.code == KeyCode::Char('c') && key.modifiers.contains(KeyModifiers::CONTROL) {
            return Ok(false);
        }
        let typing = !key.modifiers.contains(KeyModifiers::CONTROL)
            && !key.modifiers.contains(KeyModifiers::ALT)
            && !key.modifiers.contains(KeyModifiers::SUPER);

        if request.is_permission() {
            match key.code {
                KeyCode::Char('y') | KeyCode::Char('Y') if typing => {
                    respond_permission_choice(client, task_id, request, PermissionChoice::Yes)
                        .await?;
                    return Ok(true);
                }
                KeyCode::Char('n') | KeyCode::Char('N') if typing => {
                    respond_permission_choice(client, task_id, request, PermissionChoice::No)
                        .await?;
                    return Ok(true);
                }
                KeyCode::Char('a') | KeyCode::Char('A') if typing => {
                    respond_permission_choice(client, task_id, request, PermissionChoice::Always)
                        .await?;
                    return Ok(true);
                }
                KeyCode::Esc | KeyCode::Char('q') if typing => return Ok(false),
                _ => {}
            }
            continue;
        }

        match key.code {
            KeyCode::Esc | KeyCode::Char('q') if typing && freeform.is_empty() => {
                return Ok(false);
            }
            KeyCode::Tab | KeyCode::Down if typing => {
                if !request.questions.is_empty() {
                    focus = (focus + 1) % request.questions.len();
                    freeform.clear();
                }
            }
            KeyCode::BackTab | KeyCode::Up if typing => {
                if !request.questions.is_empty() {
                    focus = (focus + request.questions.len() - 1) % request.questions.len();
                    freeform.clear();
                }
            }
            KeyCode::Char(c) if typing && c.is_ascii_digit() => {
                let Some(digit) = c.to_digit(10).map(|d| d as usize) else {
                    continue;
                };
                if digit == 0 {
                    continue;
                }
                let Some(q) = request.questions.get(focus) else {
                    continue;
                };
                if let Some(label) = option_label_at(q, digit) {
                    answers.insert(q.id.clone(), label);
                    freeform.clear();
                    if request.questions.len() == 1 {
                        respond(client, task_id, request, &answers).await?;
                        return Ok(true);
                    }
                    if answers.len() == request.questions.len()
                        && request
                            .questions
                            .iter()
                            .all(|qq| answers.get(&qq.id).is_some_and(|a| !a.is_empty()))
                    {
                        // stay for Enter confirm on multi
                    } else if focus + 1 < request.questions.len() {
                        focus += 1;
                    }
                }
            }
            KeyCode::Enter if typing => {
                let Some(q) = request.questions.get(focus) else {
                    continue;
                };
                if !freeform.trim().is_empty() {
                    answers.insert(q.id.clone(), freeform.trim().to_string());
                    freeform.clear();
                }
                if request.questions.iter().all(|qq| {
                    answers
                        .get(&qq.id)
                        .is_some_and(|a| !a.trim().is_empty())
                }) {
                    respond(client, task_id, request, &answers).await?;
                    return Ok(true);
                }
                if focus + 1 < request.questions.len() {
                    focus += 1;
                }
            }
            KeyCode::Backspace if typing => {
                freeform.pop();
            }
            KeyCode::Char(c) if typing => {
                freeform.push(c);
            }
            _ => {}
        }
    }
}

fn draw_standalone(
    frame: &mut Frame<'_>,
    task_id: &str,
    request: &PendingUserInput,
    answers: &HashMap<String, String>,
    focus: usize,
    freeform: &str,
) {
    let area = frame.area();
    let chunks = Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Length(3),
            Constraint::Min(5),
            Constraint::Length(3),
            Constraint::Length(2),
        ])
        .split(area);

    let title = if request.is_permission() {
        " Permission required "
    } else {
        " Decision needed "
    };
    frame.render_widget(
        Paragraph::new(format!(" {task_id} · needs your input ")).block(
            Block::default().borders(Borders::ALL).title(title),
        ),
        chunks[0],
    );

    let mut body = String::new();
    for (idx, q) in request.questions.iter().enumerate() {
        let marker = if idx == focus { ">" } else { " " };
        let header = if q.header.is_empty() {
            String::new()
        } else {
            format!("{} · ", q.header)
        };
        body.push_str(&format!("{marker} {header}{}\n", q.question));
        if let Some(ans) = answers.get(&q.id) {
            body.push_str(&format!("    selected: {ans}\n"));
        }
        for (oi, opt) in q.options.iter().enumerate() {
            let desc = if opt.description.is_empty() {
                String::new()
            } else {
                format!(" — {}", opt.description)
            };
            body.push_str(&format!("    {}. {}{}\n", oi + 1, opt.label, desc));
        }
        if !request.is_permission() {
            body.push_str("    Or type a freeform answer, then Enter.\n");
        }
        body.push('\n');
    }
    if !freeform.is_empty() {
        body.push_str(&format!("draft: {freeform}\n"));
    }
    frame.render_widget(
        Paragraph::new(body)
            .wrap(Wrap { trim: false })
            .block(Block::default().borders(Borders::ALL).title(" Questions ")),
        chunks[1],
    );

    let hint = if request.is_permission() {
        " [y]es  [n]o  [a]lways-allow  ·  q/Esc skip "
    } else if request.questions.len() == 1 {
        " 1/2/3 choose · type+Enter freeform · q/Esc skip "
    } else {
        " 1/2/3 choose · Tab next · Enter submit when complete · q/Esc skip "
    };
    frame.render_widget(
        Paragraph::new(hint).block(Block::default().borders(Borders::ALL)),
        chunks[2],
    );
    frame.render_widget(Paragraph::new(" Andy will continue the chat after you answer "), chunks[3]);
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn parses_grill_me_artifact_request() {
        let v = json!({
            "id": "q-1",
            "origin": "Artifact",
            "questions": [{
                "id": "platform_scope",
                "header": "",
                "question": "Which platforms should v1 ship on?",
                "options": [
                    { "label": "Desktop only (Recommended)", "description": "" },
                    { "label": "Desktop and web", "description": "" }
                ]
            }]
        });
        let pending = parse_user_input_request(Some(&v)).expect("parsed");
        assert!(!pending.is_permission());
        assert_eq!(pending.questions[0].id, "platform_scope");
        assert_eq!(pending.questions[0].options.len(), 2);
        assert!(pending.summary_line().contains("Which platforms"));
    }

    #[test]
    fn parses_acp_permission_request() {
        let v = json!({
            "id": "acp-permission-task-1",
            "origin": "AcpPermission",
            "questions": [{
                "id": "acp-permission-task-1",
                "header": "delete",
                "question": "Delete /tmp/foo.txt?",
                "options": [
                    { "label": "Allow", "description": "allow_once · 1" },
                    { "label": "Reject", "description": "reject_once · 2" }
                ]
            }]
        });
        let pending = parse_user_input_request(Some(&v)).expect("parsed");
        assert!(pending.is_permission());
        assert_eq!(
            pick_permission_label(
                &[
                    ("Allow".into(), "allow_once · 1".into()),
                    ("Reject".into(), "reject_once · 2".into())
                ],
                PermissionChoice::Yes
            )
            .as_deref(),
            Some("Allow")
        );
    }

    #[test]
    fn option_label_at_is_one_based() {
        let q = UserQuestion {
            id: "q".into(),
            header: String::new(),
            question: "Pick".into(),
            options: vec![
                UserOption {
                    label: "A".into(),
                    description: String::new(),
                },
                UserOption {
                    label: "B".into(),
                    description: String::new(),
                },
            ],
        };
        assert_eq!(option_label_at(&q, 1).as_deref(), Some("A"));
        assert_eq!(option_label_at(&q, 2).as_deref(), Some("B"));
        assert_eq!(option_label_at(&q, 3), None);
    }
}
