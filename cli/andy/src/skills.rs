use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};

/// A user-invocable skill discovered from a provider's local skill roots.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AgentSkill {
    pub name: String,
    pub description: String,
    pub path: String,
    pub user_invocable: bool,
}

/// Discover skills using the same provider roots as Andy Desktop.
pub fn discover_agent_skills(agent: Option<&str>, directory: Option<&Path>) -> Vec<AgentSkill> {
    let Some(home) = dirs::home_dir() else {
        return Vec::new();
    };
    let codex_home = std::env::var_os("CODEX_HOME")
        .filter(|value| !value.is_empty())
        .map(PathBuf::from)
        .unwrap_or_else(|| home.join(".codex"));
    let workspace = directory.filter(|path| path.is_dir());
    discover_agent_skills_from_roots(agent, workspace, &home, &codex_home)
}

fn discover_agent_skills_from_roots(
    agent: Option<&str>,
    workspace: Option<&Path>,
    home: &Path,
    codex_home: &Path,
) -> Vec<AgentSkill> {
    let mut discovered = BTreeMap::new();

    for root in skill_roots_for(agent, workspace, home, codex_home) {
        if !root.is_dir() {
            continue;
        }
        let mut files = Vec::new();
        collect_skill_files(&root, 0, &mut files);
        files.sort();

        for file in files.into_iter().take(200) {
            let Some((name, description, user_invocable)) = parse_skill_file(&file) else {
                continue;
            };
            if !user_invocable {
                continue;
            }

            let key = normalize_skill_name(&name);
            if key.is_empty() {
                continue;
            }
            discovered.entry(key).or_insert_with(|| AgentSkill {
                name: name.trim().to_string(),
                description,
                path: absolute_path(&file).display().to_string(),
                user_invocable,
            });
        }
    }

    discovered.into_values().collect()
}

/// Skill roots are ordered from provider-native locations to compatibility roots.
/// Earlier roots win when multiple roots contain the same normalized skill name.
fn skill_roots_for(
    agent: Option<&str>,
    workspace: Option<&Path>,
    home: &Path,
    codex_home: &Path,
) -> Vec<PathBuf> {
    let normalized = agent.unwrap_or_default().trim().to_ascii_lowercase();
    let workspace = workspace.filter(|path| path.is_dir());
    let workspace_root = |name: &str| workspace.map(|path| path.join(name).join("skills"));

    match normalized.as_str() {
        "codex" => vec![
            codex_home.join("skills"),
            home.join(".agents").join("skills"),
            codex_home.join("plugins").join("cache"),
        ],
        "claudecode" | "claude-code" | "claude" => [
            Some(home.join(".claude").join("skills")),
            workspace_root(".claude"),
            Some(home.join(".agents").join("skills")),
        ]
        .into_iter()
        .flatten()
        .collect(),
        "cursor" => [
            workspace_root(".cursor"),
            workspace_root(".agents"),
            Some(home.join(".cursor").join("skills")),
            Some(home.join(".cursor").join("skills-cursor")),
            Some(home.join(".agents").join("skills")),
            Some(codex_home.join("skills")),
        ]
        .into_iter()
        .flatten()
        .collect(),
        "antigravity" => [
            workspace_root(".agents"),
            Some(home.join(".gemini").join("antigravity-cli").join("skills")),
        ]
        .into_iter()
        .flatten()
        .collect(),
        "opencode" => [
            workspace_root(".opencode"),
            Some(home.join(".config").join("opencode").join("skills")),
            Some(home.join(".opencode").join("skills")),
        ]
        .into_iter()
        .flatten()
        .collect(),
        "pi" => [
            workspace_root(".pi"),
            workspace_root(".agents"),
            Some(home.join(".pi").join("agent").join("skills")),
            Some(home.join(".agents").join("skills")),
        ]
        .into_iter()
        .flatten()
        .collect(),
        "hermes" => [
            workspace.map(|path| path.join(".hermes").join("skills")),
            Some(home.join(".hermes").join("skills")),
        ]
        .into_iter()
        .flatten()
        .collect(),
        "openclaw" => [
            workspace.map(|path| path.join(".openclaw").join("skills")),
            workspace.map(|path| path.join("skills")),
            Some(home.join(".openclaw").join("skills")),
        ]
        .into_iter()
        .flatten()
        .collect(),
        _ => Vec::new(),
    }
}

fn collect_skill_files(root: &Path, depth: usize, files: &mut Vec<PathBuf>) {
    let Ok(entries) = fs::read_dir(root) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        let Ok(file_type) = entry.file_type() else {
            continue;
        };
        if file_type.is_file()
            && path.file_name().and_then(|name| name.to_str()) == Some("SKILL.md")
        {
            files.push(path);
        } else if file_type.is_dir() && depth < 8 {
            collect_skill_files(&path, depth + 1, files);
        }
    }
}

fn parse_skill_file(path: &Path) -> Option<(String, String, bool)> {
    let content = fs::read_to_string(path).ok()?;
    let header: Vec<&str> = content.lines().take(24).collect();
    let name = field_value(&header, "name")
        .filter(|value| !value.is_empty())
        .or_else(|| {
            path.parent()
                .and_then(Path::file_name)
                .and_then(|name| name.to_str())
                .map(str::to_string)
        })?;
    let description = field_value(&header, "description").unwrap_or_default();
    let user_invocable = field_value(&header, "user-invocable")
        .map(|value| !value.eq_ignore_ascii_case("false"))
        .unwrap_or(true);
    Some((name, description, user_invocable))
}

fn field_value(lines: &[&str], field: &str) -> Option<String> {
    let prefix = format!("{field}:");
    let (index, value) = lines
        .iter()
        .enumerate()
        .find_map(|(index, line)| line.strip_prefix(&prefix).map(|value| (index, value)))?;
    let value = value.trim().trim_matches(['"', '\'']);
    if !matches!(value, ">" | ">-" | "|" | "|-") {
        return Some(value.to_string());
    }

    let continuation = lines[index + 1..]
        .iter()
        .take_while(|line| line.trim().is_empty() || line.starts_with([' ', '\t']))
        .filter(|line| !line.trim().is_empty())
        .map(|line| line.trim())
        .collect::<Vec<_>>();
    Some(continuation.join(" "))
}

fn normalize_skill_name(name: &str) -> String {
    name.trim().trim_start_matches(['/', '$']).to_lowercase()
}

/// Orchestration skills that need Andy MCP (`chat.*`) attached on launch.
pub fn is_orchestration_skill_name(name: &str) -> bool {
    matches!(
        normalize_skill_name(name).as_str(),
        "andy-handoff" | "andy-loop" | "andy-advisor" | "andy-committee"
    )
}

/// Skills referenced in [prompt] via `/name` tokens (same matching as the desktop composer).
pub fn skills_referenced_in_prompt<'a>(
    prompt: &str,
    skills: &'a [AgentSkill],
) -> Vec<&'a AgentSkill> {
    skills
        .iter()
        .filter(|skill| {
            let name = normalize_skill_name(&skill.name);
            if name.is_empty() {
                return false;
            }
            prompt.split_whitespace().any(|token| {
                token
                    .strip_prefix('/')
                    .is_some_and(|rest| normalize_skill_name(rest) == name)
            })
        })
        .collect()
}

/// Append portable skill path hints so providers that ignore bare `/name` still load SKILL.md.
pub fn prompt_with_skill_hints(prompt: &str, skills: &[&AgentSkill]) -> String {
    if skills.is_empty() {
        return prompt.to_string();
    }
    let mut out = String::from(prompt.trim_end());
    out.push_str("\n\nUse these local skill instructions before responding:\n");
    let mut seen = std::collections::BTreeSet::new();
    for skill in skills {
        if !seen.insert(skill.path.as_str()) {
            continue;
        }
        out.push_str("- ");
        out.push_str(&skill.name);
        out.push_str(": ");
        out.push_str(&skill.path);
        out.push('\n');
    }
    out
}

fn absolute_path(path: &Path) -> PathBuf {
    if path.is_absolute() {
        path.to_path_buf()
    } else {
        std::env::current_dir()
            .map(|cwd| cwd.join(path))
            .unwrap_or_else(|_| path.to_path_buf())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::slash::{active_slash_token, filter_commands, SlashCommand};
    use std::fs;
    use tempfile::tempdir;

    fn write_skill(root: &Path, name: &str, description: &str, user_invocable: Option<&str>) {
        let dir = root.join(name);
        fs::create_dir_all(&dir).unwrap();
        let user_invocable = user_invocable
            .map(|value| format!("user-invocable: {value}\n"))
            .unwrap_or_default();
        fs::write(
            dir.join("SKILL.md"),
            format!("---\nname: {name}\ndescription: {description}\n{user_invocable}---\n"),
        )
        .unwrap();
    }

    #[test]
    fn cursor_roots_find_gh_ship_pr_and_filter_the_slash_query() {
        let home = tempdir().unwrap();
        let codex_home = tempdir().unwrap();
        let workspace = tempdir().unwrap();

        write_skill(
            &home.path().join(".cursor").join("skills"),
            "gh-ship-pr",
            "ship a pull request",
            None,
        );
        write_skill(
            &codex_home.path().join("skills"),
            "gh-ship-pr",
            "lower-priority duplicate",
            None,
        );
        write_skill(
            &home.path().join(".agents").join("skills"),
            "hidden-skill",
            "not shown",
            Some("false"),
        );
        write_skill(
            &workspace.path().join(".cursor").join("skills"),
            "workspace-skill",
            "workspace skill",
            None,
        );

        let skills = discover_agent_skills_from_roots(
            Some("Cursor"),
            Some(workspace.path()),
            home.path(),
            codex_home.path(),
        );
        assert_eq!(
            skills
                .iter()
                .map(|skill| skill.name.as_str())
                .collect::<Vec<_>>(),
            vec!["gh-ship-pr", "workspace-skill"]
        );
        assert_eq!(skills[0].description, "ship a pull request");
        assert!(skills.iter().all(|skill| skill.user_invocable));

        let commands = skills
            .iter()
            .map(|skill| SlashCommand {
                name: skill.name.clone(),
                description: skill.description.clone(),
            })
            .collect::<Vec<_>>();
        let token = active_slash_token("/gh-ship").unwrap();
        let matches = filter_commands(&commands, &token.query);
        assert_eq!(
            matches
                .iter()
                .map(|command| command.name.as_str())
                .collect::<Vec<_>>(),
            vec!["gh-ship-pr"]
        );
    }

    #[test]
    fn prompt_skill_hints_and_orchestration_detection() {
        let skills = vec![
            AgentSkill {
                name: "andy-loop".into(),
                description: "loop".into(),
                path: "/tmp/andy-loop/SKILL.md".into(),
                user_invocable: true,
            },
            AgentSkill {
                name: "gh-ship-pr".into(),
                description: "ship".into(),
                path: "/tmp/gh-ship-pr/SKILL.md".into(),
                user_invocable: true,
            },
        ];
        assert!(is_orchestration_skill_name("andy-loop"));
        assert!(!is_orchestration_skill_name("gh-ship-pr"));
        let selected = skills_referenced_in_prompt("/andy-loop babysit CI", &skills);
        assert_eq!(selected.len(), 1);
        assert_eq!(selected[0].name, "andy-loop");
        let prompted = prompt_with_skill_hints("/andy-loop babysit CI", &selected);
        assert!(prompted.contains("Use these local skill instructions before responding:"));
        assert!(prompted.contains("andy-loop: /tmp/andy-loop/SKILL.md"));
    }

    #[test]
    fn parses_folded_description_block_scalar() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("SKILL.md");
        fs::write(
            &path,
            "---\nname: gh-ship-pr\ndescription: >-\n  End-to-end GitHub pull request shipping.\n  Handles review, CI, and merge follow-up.\nuser-invocable: true\n---\n",
        )
        .unwrap();

        let (_, description, _) = parse_skill_file(&path).unwrap();
        assert_eq!(
            description,
            "End-to-end GitHub pull request shipping. Handles review, CI, and merge follow-up."
        );
        assert_ne!(description, ">-");
    }
}
