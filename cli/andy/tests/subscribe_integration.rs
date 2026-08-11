//! Mock-MCP integration coverage for chat.subscribe / imagePaths wire protocol.

mod common;

use andy_cli::events::AgentEvent;
use andy_cli::mcp::{McpClient, SubscribeMessage, SUBSCRIBE_MISSING_ERROR};
use common::{calls_for, collect_subscribe, MockMcpServer, MockState};
use serde_json::json;

#[tokio::test]
async fn subscribe_delivers_backlog_then_live_events() {
    let mut state = MockState::with_subscribe();
    state.backlog = vec![json!({
        "type": "user",
        "atMillis": 1,
        "text": "hello",
        "images": []
    })];
    state.live_events = vec![json!({
        "type": "assistant",
        "atMillis": 2,
        "text": "world",
        "stream": false
    })];
    state.subscribe_finish_reason = Some("terminal".into());

    let server = MockMcpServer::spawn(state).await;
    let mut client = McpClient::new(server.socket.clone());

    let msgs = collect_subscribe(&mut client, "task-1").await;
    let mut events = Vec::new();
    let mut finished = None;
    for msg in msgs {
        match msg {
            SubscribeMessage::Batch(batch) => {
                for raw in batch.events {
                    events.push(AgentEvent::from_wire(&raw));
                }
            }
            SubscribeMessage::Finished { reason } => finished = Some(reason),
            SubscribeMessage::Disconnected(err) => panic!("unexpected disconnect: {err}"),
        }
    }

    assert!(
        events
            .iter()
            .any(|e| matches!(e, AgentEvent::User { text, .. } if text == "hello")),
        "backlog user message missing: {events:?}"
    );
    assert!(
        events
            .iter()
            .any(|e| matches!(e, AgentEvent::Assistant { text, .. } if text == "world")),
        "live assistant event missing: {events:?}"
    );
    assert_eq!(finished.as_deref(), Some("terminal"));
}

#[tokio::test]
async fn missing_chat_subscribe_hard_fails_with_upgrade_message() {
    let server = MockMcpServer::spawn(MockState::without_subscribe()).await;
    let mut client = McpClient::new(server.socket.clone());

    let err = client
        .requires_chat_subscribe()
        .await
        .expect_err("expected upgrade-needed error");
    assert!(
        err.to_string().contains("chat.subscribe"),
        "unexpected error: {err:#}"
    );
    assert_eq!(err.to_string(), SUBSCRIBE_MISSING_ERROR);

    // Direct subscribe path must also hard-fail (no hang / silent polling).
    let (tx, _rx) = tokio::sync::mpsc::channel(4);
    let sub_err = client
        .subscribe_chat_events("task-1", tx)
        .await
        .expect_err("subscribe against old daemon");
    assert!(
        sub_err.to_string().contains("chat.subscribe")
            || sub_err.to_string().contains(SUBSCRIBE_MISSING_ERROR),
        "unexpected subscribe error: {sub_err:#}"
    );
}

#[tokio::test]
async fn start_resume_and_queue_follow_up_carry_image_paths() {
    let server = MockMcpServer::spawn(MockState::with_subscribe()).await;
    let mut client = McpClient::new(server.socket.clone());

    client
        .call_tool(
            "chat.start",
            json!({
                "agent": "ClaudeCode",
                "prompt": "first message",
                "imagePaths": ["/tmp/start.png"]
            }),
        )
        .await
        .expect("chat.start");

    client
        .call_tool(
            "chat.resume",
            json!({
                "taskId": "task-1",
                "followUp": "look at this",
                "imagePaths": ["/tmp/a.png", "/tmp/b.webp"]
            }),
        )
        .await
        .expect("chat.resume");

    client
        .call_tool(
            "chat.queue_follow_up",
            json!({
                "taskId": "task-1",
                "followUp": "queued",
                "imagePaths": ["/tmp/c.jpg"]
            }),
        )
        .await
        .expect("chat.queue_follow_up");

    let st = server.state();
    let guard = st.lock().unwrap();
    let start = &calls_for(&guard, "chat.start")[0];
    assert_eq!(start.get("imagePaths"), Some(&json!(["/tmp/start.png"])));
    let resume = &calls_for(&guard, "chat.resume")[0];
    assert_eq!(
        resume.get("imagePaths"),
        Some(&json!(["/tmp/a.png", "/tmp/b.webp"]))
    );
    let queued = &calls_for(&guard, "chat.queue_follow_up")[0];
    assert_eq!(queued.get("imagePaths"), Some(&json!(["/tmp/c.jpg"])));
}

#[tokio::test]
async fn subscribe_backlog_is_renderable_as_agent_events() {
    // Mirrors the "backlog rendered on open" requirement: wire payloads round-trip
    // through AgentEvent::from_wire the same way acp_view applies Batch messages.
    let mut state = MockState::with_subscribe();
    state.backlog = vec![
        json!({"type": "user", "atMillis": 1, "text": "hi", "images": ["/x.png"]}),
        json!({"type": "assistant", "atMillis": 2, "text": "**ok**", "stream": false}),
        json!({
            "type": "tool",
            "atMillis": 3,
            "toolName": "edit",
            "toolCallId": "c1",
            "summary": "patch",
            "detail": "@@ -1 +1 @@\n-a\n+b\n",
            "kind": "edit",
            "state": "completed"
        }),
    ];
    state.live_events.clear();
    state.subscribe_finish_reason = Some("done".into());

    let server = MockMcpServer::spawn(state).await;
    let mut client = McpClient::new(server.socket.clone());
    let msgs = collect_subscribe(&mut client, "task-1").await;

    let batch = msgs
        .into_iter()
        .find_map(|m| match m {
            SubscribeMessage::Batch(b) if !b.events.is_empty() => Some(b),
            _ => None,
        })
        .expect("expected backlog batch");

    let rendered: Vec<_> = batch.events.iter().map(AgentEvent::from_wire).collect();
    assert_eq!(rendered.len(), 3);
    match &rendered[0] {
        AgentEvent::User { text, images, .. } => {
            assert_eq!(text, "hi");
            assert_eq!(images, &vec!["/x.png".to_string()]);
        }
        other => panic!("expected user, got {other:?}"),
    }
    assert!(matches!(
        &rendered[1],
        AgentEvent::Assistant { text, .. } if text == "**ok**"
    ));
    assert!(matches!(
        &rendered[2],
        AgentEvent::Tool { tool_name, .. } if tool_name == "edit"
    ));
}
