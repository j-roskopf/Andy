//! Transcript display grouping for the CLI ACP viewer. Ports the semantics of
//! Kotlin `transcriptDisplayItems` (`domain/src/commonMain/kotlin/app/andy/model/TranscriptDisplay.kt`)
//! for the CLI's wire [`AgentEvent`], which is a display-oriented subset of the
//! desktop `AgentEvent` union (no separate `FileChanges`/`ContextUsage`/etc. variants).

use std::collections::HashSet;

use crate::events::AgentEvent;

/// Thinking, tool calls, and tool results are "activity" — the events that get
/// grouped/collapsed between user and assistant messages.
pub fn is_transcript_activity_event(event: &AgentEvent) -> bool {
    matches!(
        event,
        AgentEvent::Thinking { .. } | AgentEvent::Tool { .. } | AgentEvent::ToolResult { .. }
    )
}

/// One row (or collapsible block of rows) in the rendered transcript. Indexes refer
/// to positions in the coalesced display event list (`AgentEvent::coalesce_for_display`),
/// matching how `ViewState::expanded_tools` keys its overrides.
#[derive(Debug, Clone, PartialEq)]
pub enum TranscriptDisplayItem {
    /// A single event at `index`, whether or not it's activity.
    Event(usize, AgentEvent),
    /// A run of consecutive activity events collapsed into one block, starting at
    /// `start_index`. Each member keeps its own original index for detail rendering.
    ToolCalls(usize, Vec<(usize, AgentEvent)>),
}

/// Whether an activity item at `key` should render expanded, given the user's
/// per-item overrides (toggled via the `space` key) and whether the relevant
/// workspace pref auto-expands by default. Mirrors Kotlin `transcriptActivityExpanded`.
pub fn transcript_activity_expanded(
    key: usize,
    overrides: &HashSet<usize>,
    auto_expand: bool,
) -> bool {
    if auto_expand {
        !overrides.contains(&key)
    } else {
        overrides.contains(&key)
    }
}

/// Group a coalesced event stream into display items.
///
/// - When `keep_thinking_on_timeline`, `Thinking` events are always broken out as
///   their own [`TranscriptDisplayItem::Event`], even while collapsing is on.
/// - When `collapse_activity_between_messages`, any run of 2+ consecutive activity
///   events (after thinking is pulled out, if requested) becomes one
///   [`TranscriptDisplayItem::ToolCalls`] block.
/// - Otherwise, a run of 2+ consecutive activity events collapses only if every
///   member is a `Tool`/`ToolResult` (a mixed thinking+tool run explodes back into
///   individual rows, since there's no "thinking or tool" summary to show for it).
pub fn transcript_display_items(
    events: &[AgentEvent],
    collapse_activity_between_messages: bool,
    keep_thinking_on_timeline: bool,
) -> Vec<TranscriptDisplayItem> {
    let mut items = Vec::new();
    let mut index = 0;
    while index < events.len() {
        let event = &events[index];
        if !is_transcript_activity_event(event) {
            items.push(TranscriptDisplayItem::Event(index, event.clone()));
            index += 1;
            continue;
        }
        if keep_thinking_on_timeline && matches!(event, AgentEvent::Thinking { .. }) {
            items.push(TranscriptDisplayItem::Event(index, event.clone()));
            index += 1;
            continue;
        }

        let start_index = index;
        let mut group: Vec<(usize, AgentEvent)> = Vec::new();
        while index < events.len() && is_transcript_activity_event(&events[index]) {
            let next = &events[index];
            if keep_thinking_on_timeline && matches!(next, AgentEvent::Thinking { .. }) {
                break;
            }
            group.push((index, next.clone()));
            index += 1;
        }

        match group.len() {
            0 => {}
            1 => {
                let (idx, event) = group.into_iter().next().unwrap();
                items.push(TranscriptDisplayItem::Event(idx, event));
            }
            _ if collapse_activity_between_messages => {
                items.push(TranscriptDisplayItem::ToolCalls(start_index, group));
            }
            _ if group.iter().all(|(_, e)| {
                matches!(e, AgentEvent::Tool { .. } | AgentEvent::ToolResult { .. })
            }) =>
            {
                items.push(TranscriptDisplayItem::ToolCalls(start_index, group));
            }
            _ => {
                for (idx, event) in group {
                    items.push(TranscriptDisplayItem::Event(idx, event));
                }
            }
        }
    }
    items
}

#[cfg(test)]
mod tests {
    use super::*;

    fn thinking(at: i64, text: &str) -> AgentEvent {
        AgentEvent::Thinking {
            at_millis: at,
            text: text.into(),
            stream: false,
        }
    }

    fn tool(at: i64, name: &str) -> AgentEvent {
        AgentEvent::Tool {
            at_millis: at,
            tool_name: name.into(),
            tool_call_id: at.to_string(),
            summary: "summary".into(),
            detail: "detail".into(),
            kind: String::new(),
            state: "completed".into(),
            locations: vec![],
        }
    }

    fn tool_result(at: i64, name: &str) -> AgentEvent {
        AgentEvent::ToolResult {
            at_millis: at,
            tool_name: name.into(),
            summary: "ok".into(),
            detail: "out".into(),
            is_error: false,
        }
    }

    fn user(at: i64, text: &str) -> AgentEvent {
        AgentEvent::User {
            at_millis: at,
            text: text.into(),
            images: vec![],
        }
    }

    fn assistant(at: i64, text: &str) -> AgentEvent {
        AgentEvent::Assistant {
            at_millis: at,
            text: text.into(),
            stream: false,
        }
    }

    #[test]
    fn collapse_groups_thinking_and_tool_together() {
        let events = vec![
            user(1, "hi"),
            thinking(2, "hmm"),
            tool(3, "Bash"),
            tool_result(4, "Bash"),
            assistant(5, "done"),
        ];
        let items = transcript_display_items(&events, true, false);
        assert_eq!(items.len(), 3);
        assert!(matches!(
            items[0],
            TranscriptDisplayItem::Event(0, AgentEvent::User { .. })
        ));
        match &items[1] {
            TranscriptDisplayItem::ToolCalls(start, group) => {
                assert_eq!(*start, 1);
                assert_eq!(group.len(), 3);
            }
            other => panic!("expected ToolCalls, got {other:?}"),
        }
        assert!(matches!(
            items[2],
            TranscriptDisplayItem::Event(4, AgentEvent::Assistant { .. })
        ));
    }

    #[test]
    fn keep_thinking_leaves_thoughts_out_of_tool_groups() {
        let events = vec![
            user(1, "hi"),
            thinking(2, "hmm"),
            tool(3, "Bash"),
            tool_result(4, "Bash"),
            assistant(5, "done"),
        ];
        let items = transcript_display_items(&events, true, true);
        assert_eq!(items.len(), 4);
        assert!(matches!(
            items[0],
            TranscriptDisplayItem::Event(0, AgentEvent::User { .. })
        ));
        assert!(matches!(
            items[1],
            TranscriptDisplayItem::Event(1, AgentEvent::Thinking { .. })
        ));
        match &items[2] {
            TranscriptDisplayItem::ToolCalls(start, group) => {
                assert_eq!(*start, 2);
                assert_eq!(group.len(), 2);
            }
            other => panic!("expected ToolCalls, got {other:?}"),
        }
        assert!(matches!(
            items[3],
            TranscriptDisplayItem::Event(4, AgentEvent::Assistant { .. })
        ));
    }

    #[test]
    fn no_collapse_still_groups_pure_tool_runs() {
        let events = vec![tool(1, "Bash"), tool_result(2, "Bash")];
        let items = transcript_display_items(&events, false, false);
        assert_eq!(items.len(), 1);
        assert!(
            matches!(&items[0], TranscriptDisplayItem::ToolCalls(0, group) if group.len() == 2)
        );
    }

    #[test]
    fn no_collapse_explodes_mixed_thinking_and_tool_runs() {
        let events = vec![thinking(1, "hmm"), tool(2, "Bash")];
        let items = transcript_display_items(&events, false, false);
        assert_eq!(items.len(), 2);
        assert!(matches!(
            items[0],
            TranscriptDisplayItem::Event(0, AgentEvent::Thinking { .. })
        ));
        assert!(matches!(
            items[1],
            TranscriptDisplayItem::Event(1, AgentEvent::Tool { .. })
        ));
    }

    #[test]
    fn single_activity_event_never_groups() {
        let events = vec![tool(1, "Bash")];
        let items = transcript_display_items(&events, true, false);
        assert_eq!(items.len(), 1);
        assert!(matches!(
            items[0],
            TranscriptDisplayItem::Event(0, AgentEvent::Tool { .. })
        ));
    }

    #[test]
    fn transcript_activity_expanded_matches_kotlin_semantics() {
        let mut overrides = HashSet::new();
        // auto_expand on: expanded unless overridden.
        assert!(transcript_activity_expanded(0, &overrides, true));
        overrides.insert(0);
        assert!(!transcript_activity_expanded(0, &overrides, true));
        // auto_expand off: collapsed unless overridden.
        assert!(!transcript_activity_expanded(1, &overrides, false));
        overrides.insert(1);
        assert!(transcript_activity_expanded(1, &overrides, false));
    }
}
