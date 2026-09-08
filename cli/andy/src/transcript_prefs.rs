//! Reads the desktop transcript display preferences from `~/.andy/workspace.properties`
//! so the CLI's ACP chat viewer (`acp_view.rs`) can match the desktop app's transcript
//! rendering (mirrors `DesktopWorkspaceStore` in `data/workspace`).

use std::collections::HashMap;
use std::path::Path;

use crate::daemon::andy_home;

/// Desktop transcript display prefs relevant to the CLI viewer. Defaults all false,
/// matching `WorkspaceState` in `domain/src/commonMain/kotlin/app/andy/model/WorkspaceModels.kt`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct TranscriptPrefs {
    /// `agentTranscriptAutoExpandThinking` — keep thinking on the timeline as its own
    /// row (instead of folding it into grouped tool activity) and auto-expand it.
    pub show_thinking_on_timeline: bool,
    /// `agentTranscriptAutoExpandTools` — tool calls/results start expanded.
    pub auto_expand_tools: bool,
    /// `agentTranscriptCollapseActivityBlocks` — group consecutive thinking/tool/
    /// tool-result activity between messages into one collapsible block.
    pub collapse_activity: bool,
}

/// Load prefs from `$HOME/.andy/workspace.properties`. Missing file or unreadable
/// values fall back to [`TranscriptPrefs::default`].
pub fn load_from_workspace() -> TranscriptPrefs {
    load_from_path(&andy_home().join("workspace.properties"))
}

fn load_from_path(path: &Path) -> TranscriptPrefs {
    let Ok(contents) = std::fs::read_to_string(path) else {
        return TranscriptPrefs::default();
    };
    parse(&contents)
}

fn parse(contents: &str) -> TranscriptPrefs {
    let props = parse_properties(contents);
    TranscriptPrefs {
        show_thinking_on_timeline: bool_prop(&props, "agentTranscriptAutoExpandThinking"),
        auto_expand_tools: bool_prop(&props, "agentTranscriptAutoExpandTools"),
        collapse_activity: bool_prop(&props, "agentTranscriptCollapseActivityBlocks"),
    }
}

/// Simple `key=value` line parser for Java `Properties`-style files. Ignores blank
/// lines and `#`/`!` comments. Does not implement full Java `Properties` escaping
/// (unicode escapes, line continuations) — the boolean prefs read here never need it.
fn parse_properties(contents: &str) -> HashMap<String, String> {
    let mut map = HashMap::new();
    for line in contents.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() || trimmed.starts_with('#') || trimmed.starts_with('!') {
            continue;
        }
        if let Some((key, value)) = trimmed.split_once('=') {
            map.insert(key.trim().to_string(), value.trim().to_string());
        }
    }
    map
}

fn bool_prop(props: &HashMap<String, String>, key: &str) -> bool {
    props.get(key).is_some_and(|v| v == "true")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_are_all_false() {
        let prefs = TranscriptPrefs::default();
        assert!(!prefs.show_thinking_on_timeline);
        assert!(!prefs.auto_expand_tools);
        assert!(!prefs.collapse_activity);
    }

    #[test]
    fn missing_file_falls_back_to_default() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("does-not-exist.properties");
        assert_eq!(load_from_path(&path), TranscriptPrefs::default());
    }

    #[test]
    fn parses_all_three_prefs_true() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("workspace.properties");
        std::fs::write(
            &path,
            "#Andy workspace\n\
             #Tue Sep 08 08:29:53 CDT 2026\n\
             agentTranscriptAutoExpandThinking=true\n\
             agentTranscriptAutoExpandTools=true\n\
             agentTranscriptCollapseActivityBlocks=true\n\
             agentMessageDeliveryMode=Queue\n",
        )
        .unwrap();
        let prefs = load_from_path(&path);
        assert!(prefs.show_thinking_on_timeline);
        assert!(prefs.auto_expand_tools);
        assert!(prefs.collapse_activity);
    }

    #[test]
    fn missing_keys_default_to_false() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("workspace.properties");
        std::fs::write(&path, "agentTranscriptCollapseActivityBlocks=true\n").unwrap();
        let prefs = load_from_path(&path);
        assert!(!prefs.show_thinking_on_timeline);
        assert!(!prefs.auto_expand_tools);
        assert!(prefs.collapse_activity);
    }

    #[test]
    fn ignores_blank_lines_and_comments() {
        let contents =
            "\n  \n! legacy comment\n# another comment\nagentTranscriptAutoExpandTools=true\n";
        let prefs = parse(contents);
        assert!(prefs.auto_expand_tools);
        assert!(!prefs.show_thinking_on_timeline);
    }

    #[test]
    fn non_true_values_are_false() {
        let contents =
            "agentTranscriptAutoExpandThinking=false\nagentTranscriptAutoExpandTools=garbage\n";
        let prefs = parse(contents);
        assert!(!prefs.show_thinking_on_timeline);
        assert!(!prefs.auto_expand_tools);
    }
}
