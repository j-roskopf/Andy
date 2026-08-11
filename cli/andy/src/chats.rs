use serde_json::Value;
use std::collections::HashSet;

#[derive(Clone, Debug)]
pub struct ChatRow {
    pub id: String,
    pub title: String,
    pub status: String,
    pub project_id: String,
    pub tmux_alive: bool,
    pub created_at_millis: i64,
}

#[derive(Clone, Debug)]
pub struct ProjectGroup {
    /// Stable key (`""` for Inbox).
    pub key: String,
    pub label: String,
    pub chats: Vec<ChatRow>,
}

#[derive(Clone, Debug)]
pub enum ListEntry {
    Header {
        key: String,
        label: String,
        count: usize,
        expanded: bool,
    },
    Chat(ChatRow),
}

pub fn parse_chats(raw: &str) -> Vec<ChatRow> {
    let Ok(Value::Array(arr)) = serde_json::from_str::<Value>(raw) else {
        return Vec::new();
    };
    arr.into_iter()
        .filter_map(|el| {
            let id = el.get("id")?.as_str()?.to_string();
            if id.is_empty() {
                return None;
            }
            // CLI never shows archived chats (still present in --json raw payload).
            if el
                .get("archived")
                .and_then(|v| v.as_bool())
                .unwrap_or(false)
            {
                return None;
            }
            Some(ChatRow {
                id,
                title: el
                    .get("title")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string(),
                status: el
                    .get("status")
                    .and_then(|v| v.as_str())
                    .unwrap_or("?")
                    .to_string(),
                project_id: el
                    .get("projectId")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string(),
                tmux_alive: el
                    .get("tmuxAlive")
                    .and_then(|v| v.as_bool())
                    .unwrap_or(false),
                created_at_millis: el
                    .get("createdAtMillis")
                    .and_then(|v| v.as_i64())
                    .unwrap_or(0),
            })
        })
        .collect()
}

/// Sort by project (Inbox first), then live sessions, then newest first.
pub fn project_groups(mut chats: Vec<ChatRow>) -> Vec<ProjectGroup> {
    chats.sort_by(|a, b| {
        let pa = project_sort_key(&a.project_id);
        let pb = project_sort_key(&b.project_id);
        pa.cmp(&pb)
            .then_with(|| b.tmux_alive.cmp(&a.tmux_alive))
            .then_with(|| b.created_at_millis.cmp(&a.created_at_millis))
            .then_with(|| a.id.cmp(&b.id))
    });

    let mut groups: Vec<ProjectGroup> = Vec::new();
    for chat in chats {
        let key = chat.project_id.clone();
        if groups.last().map(|g| g.key.as_str()) != Some(key.as_str()) {
            groups.push(ProjectGroup {
                label: project_label(&key),
                key,
                chats: Vec::new(),
            });
        }
        groups.last_mut().unwrap().chats.push(chat);
    }
    groups
}

/// Visible rows for the TUI given which project keys are expanded.
pub fn visible_entries(groups: &[ProjectGroup], expanded: &HashSet<String>) -> Vec<ListEntry> {
    let mut out = Vec::new();
    for group in groups {
        let is_open = expanded.contains(&group.key);
        out.push(ListEntry::Header {
            key: group.key.clone(),
            label: group.label.clone(),
            count: group.chats.len(),
            expanded: is_open,
        });
        if is_open {
            for chat in &group.chats {
                out.push(ListEntry::Chat(chat.clone()));
            }
        }
    }
    out
}

/// Fully expanded grouping for `andy chat list` text output.
pub fn grouped_entries(chats: Vec<ChatRow>) -> Vec<ListEntry> {
    let groups = project_groups(chats);
    let all: HashSet<String> = groups.iter().map(|g| g.key.clone()).collect();
    visible_entries(&groups, &all)
}

pub fn format_grouped(entries: &[ListEntry]) -> String {
    let mut lines = Vec::new();
    for entry in entries {
        match entry {
            ListEntry::Header {
                label,
                count,
                expanded,
                ..
            } => {
                if !lines.is_empty() {
                    lines.push(String::new());
                }
                let marker = if *expanded { "▾" } else { "▸" };
                lines.push(format!("── {marker} {label} ({count}) ──"));
            }
            ListEntry::Chat(chat) => {
                let live = if chat.tmux_alive { " live" } else { "" };
                lines.push(format!(
                    "  {}  [{}{}]  {}",
                    chat.id, chat.status, live, chat.title
                ));
            }
        }
    }
    if lines.is_empty() {
        "No chats".to_string()
    } else {
        lines.join("\n")
    }
}

fn project_label(project_id: &str) -> String {
    if project_id.is_empty() {
        "Inbox".to_string()
    } else {
        project_id.to_string()
    }
}

fn project_sort_key(project_id: &str) -> (u8, String) {
    if project_id.is_empty() {
        (0, String::new())
    } else {
        (1, project_id.to_lowercase())
    }
}
