use serde_json::Value;

/// Wire-format agent transcript event (mirrors Kotlin `AgentEvent.toWire()`).
#[derive(Debug, Clone, PartialEq)]
pub enum AgentEvent {
    Session {
        at_millis: i64,
        session_id: String,
        model: String,
    },
    User {
        at_millis: i64,
        text: String,
        images: Vec<String>,
    },
    Assistant {
        at_millis: i64,
        text: String,
        stream: bool,
    },
    Thinking {
        at_millis: i64,
        text: String,
        stream: bool,
    },
    Tool {
        at_millis: i64,
        tool_name: String,
        tool_call_id: String,
        summary: String,
        detail: String,
        kind: String,
        state: String,
        locations: Vec<String>,
    },
    ToolResult {
        at_millis: i64,
        tool_name: String,
        summary: String,
        detail: String,
        is_error: bool,
    },
    Error {
        at_millis: i64,
        text: String,
    },
    Result {
        at_millis: i64,
        success: bool,
        final_text: String,
    },
    Usage {
        at_millis: i64,
        used_tokens: i64,
        window_tokens: i64,
    },
    Plan {
        at_millis: i64,
        entries: Vec<(String, String)>,
        markdown: Option<String>,
    },
    Mode {
        at_millis: i64,
        mode_id: String,
    },
    Commands {
        at_millis: i64,
        commands: Vec<(String, String)>,
    },
    Modes {
        at_millis: i64,
        current_mode_id: String,
        modes: Vec<(String, String)>,
    },
    Permission {
        at_millis: i64,
        request_id: String,
        tool_name: String,
        question: String,
        options: Vec<(String, String)>,
    },
    PermissionResolved {
        at_millis: i64,
        request_id: String,
        option_id: String,
        allowed: bool,
        note: Option<String>,
    },
    Raw {
        at_millis: i64,
        line: String,
    },
    Unknown {
        at_millis: i64,
        raw: Value,
    },
}

impl AgentEvent {
    pub fn from_wire(value: &Value) -> Self {
        let at = value.get("atMillis").and_then(|v| v.as_i64()).unwrap_or(0);
        let ty = value.get("type").and_then(|v| v.as_str()).unwrap_or("");
        match ty {
            "session" => Self::Session {
                at_millis: at,
                session_id: string_field(value, "sessionId"),
                model: string_field(value, "model"),
            },
            "user" => Self::User {
                at_millis: at,
                text: string_field(value, "text"),
                images: string_array(value, "images"),
            },
            "assistant" => Self::Assistant {
                at_millis: at,
                text: string_field(value, "text"),
                stream: value
                    .get("stream")
                    .and_then(|v| v.as_bool())
                    .unwrap_or(false),
            },
            "thinking" => Self::Thinking {
                at_millis: at,
                text: string_field(value, "text"),
                stream: value
                    .get("stream")
                    .and_then(|v| v.as_bool())
                    .unwrap_or(false),
            },
            "tool" => Self::Tool {
                at_millis: at,
                tool_name: string_field(value, "toolName"),
                tool_call_id: string_field(value, "toolCallId"),
                summary: string_field(value, "summary"),
                detail: string_field(value, "detail"),
                kind: string_field(value, "kind"),
                state: string_field(value, "state"),
                locations: string_array(value, "locations"),
            },
            "tool-result" => Self::ToolResult {
                at_millis: at,
                tool_name: string_field(value, "toolName"),
                summary: string_field(value, "summary"),
                detail: string_field(value, "detail"),
                is_error: value
                    .get("isError")
                    .and_then(|v| v.as_bool())
                    .unwrap_or(false),
            },
            "error" => Self::Error {
                at_millis: at,
                text: string_field(value, "text"),
            },
            "result" => Self::Result {
                at_millis: at,
                success: value
                    .get("success")
                    .and_then(|v| v.as_bool())
                    .unwrap_or(false),
                final_text: string_field(value, "finalText"),
            },
            "usage" => Self::Usage {
                at_millis: at,
                used_tokens: value
                    .get("usedTokens")
                    .and_then(|v| v.as_i64())
                    .unwrap_or(0),
                window_tokens: value
                    .get("windowTokens")
                    .and_then(|v| v.as_i64())
                    .unwrap_or(0),
            },
            "plan" => Self::Plan {
                at_millis: at,
                entries: value
                    .get("entries")
                    .and_then(|v| v.as_array())
                    .map(|arr| {
                        arr.iter()
                            .map(|e| (string_field(e, "content"), string_field(e, "status")))
                            .collect()
                    })
                    .unwrap_or_default(),
                markdown: value
                    .get("markdown")
                    .and_then(|v| v.as_str())
                    .map(|s| s.to_string()),
            },
            "mode" => Self::Mode {
                at_millis: at,
                mode_id: string_field(value, "modeId"),
            },
            "commands" => Self::Commands {
                at_millis: at,
                commands: value
                    .get("commands")
                    .and_then(|v| v.as_array())
                    .map(|arr| {
                        arr.iter()
                            .map(|e| (string_field(e, "name"), string_field(e, "description")))
                            .collect()
                    })
                    .unwrap_or_default(),
            },
            "modes" => Self::Modes {
                at_millis: at,
                current_mode_id: string_field(value, "currentModeId"),
                modes: value
                    .get("modes")
                    .and_then(|v| v.as_array())
                    .map(|arr| {
                        arr.iter()
                            .map(|e| (string_field(e, "id"), string_field(e, "name")))
                            .collect()
                    })
                    .unwrap_or_default(),
            },
            "permission" => Self::Permission {
                at_millis: at,
                request_id: string_field(value, "requestId"),
                tool_name: string_field(value, "toolName"),
                question: string_field(value, "question"),
                options: value
                    .get("options")
                    .and_then(|v| v.as_array())
                    .map(|arr| {
                        arr.iter()
                            .map(|e| (string_field(e, "label"), string_field(e, "description")))
                            .collect()
                    })
                    .unwrap_or_default(),
            },
            "permission-resolved" => Self::PermissionResolved {
                at_millis: at,
                request_id: string_field(value, "requestId"),
                option_id: string_field(value, "optionId"),
                allowed: value
                    .get("allowed")
                    .and_then(|v| v.as_bool())
                    .unwrap_or(false),
                note: value
                    .get("note")
                    .and_then(|v| v.as_str())
                    .map(|s| s.to_string()),
            },
            "raw" => Self::Raw {
                at_millis: at,
                line: string_field(value, "line"),
            },
            _ => Self::Unknown {
                at_millis: at,
                raw: value.clone(),
            },
        }
    }

    pub fn parse_list(raw: &str) -> Vec<Self> {
        let Ok(Value::Array(arr)) = serde_json::from_str::<Value>(raw) else {
            return Vec::new();
        };
        arr.iter().map(Self::from_wire).collect()
    }

    /// Merge streamed assistant/thinking deltas into a display-friendly list.
    pub fn coalesce_for_display(events: &[Self]) -> Vec<Self> {
        let mut out: Vec<Self> = Vec::new();
        for event in events {
            match (out.last_mut(), event) {
                (
                    Some(Self::Assistant {
                        text,
                        stream: prev_stream,
                        ..
                    }),
                    Self::Assistant {
                        text: next,
                        stream: true,
                        at_millis,
                        ..
                    },
                ) if *prev_stream => {
                    // Only coalesce into a prior stream bubble — a completed (non-stream)
                    // assistant event is a barrier, matching GUI coalesceAgentStreamDeltas.
                    text.push_str(next);
                    *prev_stream = true;
                    let _ = at_millis;
                }
                (
                    Some(Self::Thinking {
                        text,
                        stream: prev_stream,
                        ..
                    }),
                    Self::Thinking {
                        text: next,
                        stream: true,
                        ..
                    },
                ) if *prev_stream => {
                    text.push_str(next);
                    *prev_stream = true;
                }
                _ => out.push(event.clone()),
            }
        }
        out
    }
}

fn string_field(value: &Value, key: &str) -> String {
    value
        .get(key)
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string()
}

fn string_array(value: &Value, key: &str) -> Vec<String> {
    value
        .get(key)
        .and_then(|v| v.as_array())
        .map(|arr| {
            arr.iter()
                .filter_map(|v| v.as_str().map(|s| s.to_string()))
                .collect()
        })
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn parses_each_event_type() {
        let samples = vec![
            json!({"type":"session","atMillis":1,"sessionId":"s","model":"m"}),
            json!({"type":"user","atMillis":2,"text":"hi","images":["/a.png"]}),
            json!({"type":"assistant","atMillis":3,"text":"yo","stream":true}),
            json!({"type":"thinking","atMillis":4,"text":"...","stream":false}),
            json!({"type":"tool","atMillis":5,"toolName":"Bash","toolCallId":"1","summary":"run","detail":"ls","kind":"Execute","state":"Completed","locations":[]}),
            json!({"type":"tool-result","atMillis":6,"toolName":"Bash","summary":"ok","detail":"out","isError":false}),
            json!({"type":"error","atMillis":7,"text":"boom"}),
            json!({"type":"result","atMillis":8,"success":true,"finalText":"done"}),
            json!({"type":"usage","atMillis":9,"usedTokens":1,"windowTokens":2}),
            json!({"type":"plan","atMillis":10,"entries":[{"content":"a","status":"pending"}],"markdown":"# p"}),
            json!({"type":"mode","atMillis":11,"modeId":"plan"}),
            json!({"type":"commands","atMillis":12,"commands":[{"name":"x","description":"d","inputHint":""}]}),
            json!({"type":"modes","atMillis":13,"currentModeId":"c","modes":[{"id":"c","name":"C","description":""}]}),
            json!({"type":"permission","atMillis":14,"requestId":"r","toolName":"Edit","question":"ok?","options":[{"label":"Allow","description":"allow_once · 1"}]}),
            json!({"type":"permission-resolved","atMillis":15,"requestId":"r","optionId":"1","allowed":true,"note":"n"}),
            json!({"type":"raw","atMillis":16,"line":"x"}),
        ];
        for sample in samples {
            let parsed = AgentEvent::from_wire(&sample);
            assert!(
                !matches!(parsed, AgentEvent::Unknown { .. }),
                "failed to parse {sample}"
            );
        }
    }

    #[test]
    fn coalesce_streams_assistant_deltas() {
        let events = vec![
            AgentEvent::Assistant {
                at_millis: 1,
                text: "Hel".into(),
                stream: true,
            },
            AgentEvent::Assistant {
                at_millis: 2,
                text: "lo".into(),
                stream: true,
            },
        ];
        let coalesced = AgentEvent::coalesce_for_display(&events);
        assert_eq!(coalesced.len(), 1);
        match &coalesced[0] {
            AgentEvent::Assistant { text, .. } => assert_eq!(text, "Hello"),
            other => panic!("unexpected {other:?}"),
        }
    }

    #[test]
    fn coalesce_does_not_merge_stream_onto_non_stream_assistant() {
        let events = vec![
            AgentEvent::Assistant {
                at_millis: 1,
                text: "complete".into(),
                stream: false,
            },
            AgentEvent::Assistant {
                at_millis: 2,
                text: "next".into(),
                stream: true,
            },
        ];
        let coalesced = AgentEvent::coalesce_for_display(&events);
        assert_eq!(coalesced.len(), 2);
        match (&coalesced[0], &coalesced[1]) {
            (
                AgentEvent::Assistant {
                    text: a,
                    stream: false,
                    ..
                },
                AgentEvent::Assistant {
                    text: b,
                    stream: true,
                    ..
                },
            ) => {
                assert_eq!(a, "complete");
                assert_eq!(b, "next");
            }
            other => panic!("unexpected {other:?}"),
        }
    }

    #[test]
    fn coalesce_does_not_merge_stream_onto_non_stream_thinking() {
        let events = vec![
            AgentEvent::Thinking {
                at_millis: 1,
                text: "done thinking".into(),
                stream: false,
            },
            AgentEvent::Thinking {
                at_millis: 2,
                text: "more".into(),
                stream: true,
            },
        ];
        let coalesced = AgentEvent::coalesce_for_display(&events);
        assert_eq!(coalesced.len(), 2);
    }
}
