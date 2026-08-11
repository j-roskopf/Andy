use crossterm::event::KeyCode;
use ratatui::prelude::*;
use ratatui::widgets::{Block, Borders, List, ListItem, ListState};

use crate::skills::AgentSkill;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SlashCommand {
    pub name: String,
    pub description: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SlashToken {
    pub start: usize,
    pub end: usize,
    pub query: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SlashMenuAction {
    Pass,
    Dismiss,
    Move,
    Complete,
}

/// State for a menu attached to an append-at-end CLI composer.
#[derive(Debug, Default)]
pub struct SlashMenuState {
    selected: usize,
    query: Option<String>,
    dismissed: bool,
    filtered: Vec<SlashCommand>,
    active: bool,
}

impl SlashMenuState {
    pub fn sync(&mut self, input: &str, commands: &[SlashCommand]) {
        let Some(token) = active_slash_token(input) else {
            self.selected = 0;
            self.query = None;
            self.dismissed = false;
            self.filtered.clear();
            self.active = false;
            return;
        };

        if self.query.as_deref() != Some(token.query.as_str()) {
            self.query = Some(token.query.clone());
            self.selected = 0;
            self.dismissed = false;
        }
        self.filtered = filter_commands(commands, &token.query);
        if self.filtered.is_empty() {
            self.selected = 0;
        } else {
            self.selected = self.selected.min(self.filtered.len() - 1);
        }
        self.active = true;
    }

    pub fn handle_key(
        &mut self,
        input: &str,
        commands: &[SlashCommand],
        key: KeyCode,
    ) -> SlashMenuAction {
        self.sync(input, commands);
        if !self.active || self.dismissed {
            return SlashMenuAction::Pass;
        }
        match key {
            KeyCode::Esc => {
                self.dismissed = true;
                SlashMenuAction::Dismiss
            }
            KeyCode::Up | KeyCode::Down if !self.filtered.is_empty() => {
                let delta = if key == KeyCode::Up { -1 } else { 1 };
                self.selected = (self.selected as isize + delta)
                    .rem_euclid(self.filtered.len() as isize)
                    as usize;
                SlashMenuAction::Move
            }
            KeyCode::Tab | KeyCode::Enter if !self.filtered.is_empty() => {
                self.dismissed = true;
                SlashMenuAction::Complete
            }
            _ => SlashMenuAction::Pass,
        }
    }

    pub fn is_visible(&self) -> bool {
        self.active && !self.dismissed
    }

    pub fn commands(&self) -> &[SlashCommand] {
        &self.filtered
    }

    pub fn selected(&self) -> usize {
        self.selected
    }

    pub fn query(&self) -> &str {
        self.query.as_deref().unwrap_or_default()
    }

    pub fn selected_command(&self) -> Option<&SlashCommand> {
        self.filtered.get(self.selected)
    }
}

/// Andy-native commands that are available before a provider session exists.
/// Provider-advertised commands are added separately once ACP emits them.
pub fn native_commands_for_agent(agent: Option<&str>) -> Vec<SlashCommand> {
    let normalized = agent.unwrap_or_default().trim().to_ascii_lowercase();
    if matches!(
        normalized.as_str(),
        "codex" | "claudecode" | "claude-code" | "claude"
    ) {
        vec![SlashCommand {
            name: "goal".into(),
            description: "set or clear this task's persistent goal".into(),
        }]
    } else {
        Vec::new()
    }
}

pub fn merge_commands(
    native: impl IntoIterator<Item = SlashCommand>,
    provider: impl IntoIterator<Item = SlashCommand>,
) -> Vec<SlashCommand> {
    let mut merged = Vec::new();
    for command in native.into_iter().chain(provider) {
        let name = normalize_name(&command.name);
        let key = name.to_ascii_lowercase();
        if name.is_empty()
            || merged
                .iter()
                .any(|other: &SlashCommand| normalize_name(&other.name).to_ascii_lowercase() == key)
        {
            continue;
        }
        merged.push(SlashCommand {
            name,
            description: command.description,
        });
    }
    merged
}

/// Merge Andy/provider commands with user-invocable disk skills, omitting skills
/// whose normalized name is already represented by a command.
pub fn merge_commands_with_skills(
    native: impl IntoIterator<Item = SlashCommand>,
    provider: impl IntoIterator<Item = SlashCommand>,
    skills: impl IntoIterator<Item = AgentSkill>,
) -> Vec<SlashCommand> {
    let mut merged = merge_commands(native, provider);
    for skill in skills {
        if !skill.user_invocable {
            continue;
        }
        let name = normalize_name(&skill.name);
        let key = name.to_ascii_lowercase();
        if name.is_empty()
            || merged
                .iter()
                .any(|command| normalize_name(&command.name).to_ascii_lowercase() == key)
        {
            continue;
        }
        merged.push(SlashCommand {
            name,
            description: skill.description,
        });
    }
    merged
}

pub fn filter_commands(commands: &[SlashCommand], query: &str) -> Vec<SlashCommand> {
    let query = query.to_ascii_lowercase();
    commands
        .iter()
        .filter(|command| {
            command.name.to_ascii_lowercase().contains(&query)
                || command.description.to_ascii_lowercase().contains(&query)
        })
        .cloned()
        .collect()
}

pub fn active_slash_token(input: &str) -> Option<SlashToken> {
    let token_start = match input.rfind(char::is_whitespace) {
        Some(index) => {
            index
                + input[index..]
                    .chars()
                    .next()
                    .map(char::len_utf8)
                    .unwrap_or(1)
        }
        None => 0,
    };
    let token = &input[token_start..];
    let query = token.strip_prefix('/')?;
    if !query.chars().all(is_command_char) {
        return None;
    }
    Some(SlashToken {
        start: token_start,
        end: input.len(),
        query: query.to_string(),
    })
}

pub fn complete_command(input: &str, command: &str) -> Option<String> {
    let token = active_slash_token(input)?;
    Some(format!(
        "{}/{} ",
        &input[..token.start],
        normalize_name(command)
    ))
}

pub fn menu_height(state: &SlashMenuState) -> u16 {
    if !state.is_visible() {
        return 0;
    }
    // The List owns every filtered row and scrolls to the selected item when
    // the terminal is shorter than the full command set.
    (state.commands().len() as u16 + 2).clamp(3, 8)
}

pub fn render_menu(frame: &mut Frame<'_>, area: Rect, state: &SlashMenuState) {
    if !state.is_visible() {
        return;
    }
    let items = if state.commands().is_empty() {
        vec![ListItem::new("no matching slash commands")]
    } else {
        state
            .commands()
            .iter()
            .enumerate()
            .map(|(index, command)| {
                let description = if command.description.is_empty() {
                    String::new()
                } else {
                    format!(" — {}", command.description)
                };
                let item = ListItem::new(format!(" /{}{}", command.name, description));
                if index == state.selected() {
                    item.style(Style::default().add_modifier(Modifier::REVERSED))
                } else {
                    item
                }
            })
            .collect()
    };
    let mut list_state = ListState::default();
    if !state.commands().is_empty() {
        list_state.select(Some(state.selected()));
    }
    let list = List::new(items).block(
        Block::default()
            .title(format!(" Slash commands /{} ", state.query()))
            .borders(Borders::ALL),
    );
    frame.render_stateful_widget(list, area, &mut list_state);
}

fn normalize_name(name: &str) -> String {
    name.trim().trim_start_matches(['/', '$']).to_string()
}

fn is_command_char(c: char) -> bool {
    c.is_ascii_alphanumeric() || matches!(c, ':' | '_' | '-')
}

#[cfg(test)]
mod tests {
    use super::*;

    fn commands() -> Vec<SlashCommand> {
        vec![
            SlashCommand {
                name: "plan".into(),
                description: "make a plan".into(),
            },
            SlashCommand {
                name: "review".into(),
                description: "review the change".into(),
            },
        ]
    }

    #[test]
    fn finds_only_the_trailing_slash_token() {
        assert_eq!(active_slash_token("please /pl").unwrap().query, "pl");
        assert!(active_slash_token("please /pl next").is_none());
        assert!(active_slash_token("plain text").is_none());
    }

    #[test]
    fn filters_name_or_description_case_insensitively() {
        let commands = commands();
        assert_eq!(filter_commands(&commands, "PL")[0].name, "plan");
        assert_eq!(filter_commands(&commands, "change")[0].name, "review");
    }

    #[test]
    fn tab_or_enter_completes_when_menu_is_open() {
        let commands = commands();
        let mut state = SlashMenuState::default();
        assert_eq!(
            state.handle_key("/pl", &commands, KeyCode::Enter),
            SlashMenuAction::Complete
        );
        assert_eq!(complete_command("/pl", "plan").as_deref(), Some("/plan "));
    }

    #[test]
    fn up_and_down_move_the_selected_command() {
        let commands = commands();
        let mut state = SlashMenuState::default();
        assert_eq!(
            state.handle_key("/", &commands, KeyCode::Down),
            SlashMenuAction::Move
        );
        assert_eq!(state.selected_command().unwrap().name, "review");
        assert_eq!(
            state.handle_key("/", &commands, KeyCode::Up),
            SlashMenuAction::Move
        );
        assert_eq!(state.selected_command().unwrap().name, "plan");
    }

    #[test]
    fn enter_submits_when_menu_is_closed_or_has_no_match() {
        let commands = commands();
        let mut state = SlashMenuState::default();
        assert_eq!(
            state.handle_key("hello", &commands, KeyCode::Enter),
            SlashMenuAction::Pass
        );
        assert_eq!(
            state.handle_key("/zz", &commands, KeyCode::Enter),
            SlashMenuAction::Pass
        );
    }

    #[test]
    fn escape_dismisses_without_changing_input() {
        let commands = commands();
        let mut state = SlashMenuState::default();
        assert_eq!(
            state.handle_key("prefix /p", &commands, KeyCode::Esc),
            SlashMenuAction::Dismiss
        );
        assert!(!state.is_visible());
        assert_eq!(
            complete_command("prefix /p", "plan").as_deref(),
            Some("prefix /plan ")
        );
    }

    #[test]
    fn native_goal_is_limited_to_supported_agents() {
        assert_eq!(native_commands_for_agent(Some("Codex"))[0].name, "goal");
        assert!(native_commands_for_agent(Some("Cursor")).is_empty());
    }

    #[test]
    fn skills_are_added_after_commands_and_deduplicated_by_name() {
        let skills = vec![
            AgentSkill {
                name: "review".into(),
                description: "skill duplicate".into(),
                path: "/tmp/review/SKILL.md".into(),
                user_invocable: true,
            },
            AgentSkill {
                name: "gh-ship-pr".into(),
                description: "ship a pull request".into(),
                path: "/tmp/gh-ship-pr/SKILL.md".into(),
                user_invocable: true,
            },
            AgentSkill {
                name: "hidden".into(),
                description: "not invocable".into(),
                path: "/tmp/hidden/SKILL.md".into(),
                user_invocable: false,
            },
        ];
        let merged = merge_commands_with_skills(
            vec![SlashCommand {
                name: "review".into(),
                description: "native command".into(),
            }],
            Vec::new(),
            skills,
        );
        assert_eq!(
            merged.iter().map(|command| command.name.as_str()).collect::<Vec<_>>(),
            vec!["review", "gh-ship-pr"]
        );
        assert_eq!(merged[1].description, "ship a pull request");
    }
}
