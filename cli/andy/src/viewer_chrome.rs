//! Shared attach UI chrome for ACP (ratatui) and Terminal (tmux status) lanes.
//!
//! Both lanes show the same framing: task id · title · [status] plus lane-specific
//! hotkey hints. Terminal keeps tmux as the real transport; chrome is applied as a
//! session-local status line for the duration of `andy attach`.

use anyhow::{Context, Result};

use crate::tmux;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Lane {
    Acp,
    Terminal,
}

impl Lane {
    pub fn parse(raw: &str) -> Self {
        if raw.eq_ignore_ascii_case("Acp") {
            Self::Acp
        } else {
            Self::Terminal
        }
    }

    pub fn frame_title(self) -> &'static str {
        match self {
            Self::Acp => " Andy ACP ",
            Self::Terminal => " Andy Terminal ",
        }
    }

    pub fn hotkey_hint(self) -> &'static str {
        match self {
            Self::Acp => "Esc/q quit · Ctrl-s stop · Ctrl-i image · v details · Enter send",
            Self::Terminal => "F12/Alt+d/Ctrl-b d detach",
        }
    }
}

/// One-line header shared by both attach viewers.
pub fn format_header(task_id: &str, title: &str, status: &str, lane: Lane) -> String {
    format!(" {task_id} · {title} · [{status}]  {} ", lane.hotkey_hint())
}

/// Footer / status flash line (hotkeys + detach mental model).
pub fn format_status_line(lane: Lane, flash: Option<&str>) -> String {
    flash.map(|s| s.to_string()).unwrap_or_else(|| match lane {
        Lane::Acp => {
            " y/n/a when prompted · v details · space expand tool · ↑↓ scroll ".into()
        }
        Lane::Terminal => {
            format!(" {} — task keeps running after detach ", lane.hotkey_hint())
        }
    })
}

/// Apply session-local tmux status chrome for a Terminal-lane attach.
///
/// Uses non-global (`-t session`) options so the Andy server's global `status off`
/// (required by the GUI scraper) is restored when [clear_tmux_session_chrome] runs.
pub fn apply_tmux_session_chrome(task_id: &str, title: &str, status: &str) -> Result<()> {
    let name = tmux::session_name(task_id);
    let header = format_header(task_id, title, status, Lane::Terminal);
    // tmux status-left interprets `#` — escape by doubling.
    let safe = header.replace('#', "##");
    tmux::run_tmux(&[
        "set-option",
        "-t",
        &name,
        "status",
        "on",
        ";",
        "set-option",
        "-t",
        &name,
        "status-position",
        "top",
        ";",
        "set-option",
        "-t",
        &name,
        "status-style",
        "bg=colour236,fg=colour255",
        ";",
        "set-option",
        "-t",
        &name,
        "status-left-length",
        "120",
        ";",
        "set-option",
        "-t",
        &name,
        "status-right-length",
        "80",
        ";",
        "set-option",
        "-t",
        &name,
        "status-left",
        &safe,
        ";",
        "set-option",
        "-t",
        &name,
        "status-right",
        &format!(" {} ", Lane::Terminal.frame_title().trim()),
        ";",
        "set-option",
        "-t",
        &name,
        "pane-border-status",
        "off",
    ])
    .context("apply terminal viewer chrome")?;
    Ok(())
}

pub fn clear_tmux_session_chrome(task_id: &str) -> Result<()> {
    let name = tmux::session_name(task_id);
    // Best-effort: session may already be gone after detach/kill.
    let _ = tmux::run_tmux(&["set-option", "-t", &name, "status", "off"]);
    Ok(())
}

/// Print the shared framing to stderr before handing the TTY to tmux (also used when
/// status cannot be applied). Returns the formatted header for tests.
pub fn print_attach_banner(task_id: &str, title: &str, status: &str) -> String {
    let lane = Lane::Terminal;
    let header = format_header(task_id, title, status, lane);
    let bar = "─".repeat(header.chars().count().clamp(20, 100));
    eprintln!("{}", lane.frame_title().trim());
    eprintln!("{bar}");
    eprintln!("{}", header.trim());
    eprintln!("{}", format_status_line(lane, None).trim());
    eprintln!("{bar}");
    header
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn acp_and_terminal_headers_share_framing_shape() {
        let acp = format_header("t1", "Demo", "Working", Lane::Acp);
        let term = format_header("t1", "Demo", "Working", Lane::Terminal);
        assert!(acp.contains("t1 · Demo · [Working]"));
        assert!(term.contains("t1 · Demo · [Working]"));
        assert!(acp.contains("Esc/q quit"));
        assert!(term.contains("F12"));
        assert_eq!(Lane::Acp.frame_title(), " Andy ACP ");
        assert_eq!(Lane::Terminal.frame_title(), " Andy Terminal ");
    }

    #[test]
    fn status_line_mentions_detach_keeps_running() {
        let line = format_status_line(Lane::Terminal, None);
        assert!(line.contains("detach"));
        assert!(line.contains("keeps running"));
    }
}
