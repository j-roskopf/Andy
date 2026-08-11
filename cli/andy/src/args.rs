use anyhow::{bail, Result};
use serde_json::{json, Map, Value};

/// Merge positionals/base object, `--json-args`, and repeated `--arg key=value`.
/// Precedence (later wins): base < json_args < arg flags.
pub fn merge_tool_args(
    base: Map<String, Value>,
    json_args: Option<&str>,
    arg_flags: &[String],
) -> Result<Value> {
    let mut map = base;
    if let Some(raw) = json_args {
        let parsed: Value =
            serde_json::from_str(raw).map_err(|e| anyhow::anyhow!("invalid --json-args: {e}"))?;
        let Value::Object(obj) = parsed else {
            bail!("--json-args must be a JSON object");
        };
        for (k, v) in obj {
            map.insert(k, v);
        }
    }
    for flag in arg_flags {
        let (key, value) = parse_arg_kv(flag)?;
        map.insert(key, value);
    }
    Ok(Value::Object(map))
}

pub fn parse_arg_kv(flag: &str) -> Result<(String, Value)> {
    let Some((key, raw)) = flag.split_once('=') else {
        bail!("--arg expects key=value, got {flag:?}");
    };
    if key.is_empty() {
        bail!("--arg key must not be empty");
    }
    Ok((key.to_string(), coerce_arg_value(raw)))
}

/// Prefer JSON literals; otherwise treat as a string.
pub fn coerce_arg_value(raw: &str) -> Value {
    if let Ok(v) = serde_json::from_str::<Value>(raw) {
        return v;
    }
    json!(raw)
}

/// Resolve serial from explicit flag, else `ANDY_SERIAL`. Does not overwrite an
/// existing `serial` key in `args`.
pub fn inject_serial(args: &mut Value, serial_flag: Option<&str>) {
    if args
        .as_object()
        .map(|o| o.contains_key("serial"))
        .unwrap_or(false)
    {
        return;
    }
    let serial = serial_flag
        .map(str::to_string)
        .or_else(|| std::env::var("ANDY_SERIAL").ok())
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty());
    if let Some(serial) = serial {
        if let Some(obj) = args.as_object_mut() {
            obj.insert("serial".into(), json!(serial));
        }
    }
}

/// When an Andy agent shell calls `chat.start`, inject `ANDY_TASK_ID` as
/// `callerTaskId` so omitted autonomy inherits the parent's dial. Does not
/// overwrite an explicit `callerTaskId`.
pub fn inject_caller_task_id(args: &mut Value) {
    if args
        .as_object()
        .map(|o| o.contains_key("callerTaskId"))
        .unwrap_or(false)
    {
        return;
    }
    let task_id = std::env::var("ANDY_TASK_ID")
        .ok()
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty());
    if let Some(task_id) = task_id {
        if let Some(obj) = args.as_object_mut() {
            obj.insert("callerTaskId".into(), json!(task_id));
        }
    }
}

pub fn resolve_serial_flag(serial_flag: Option<&str>) -> Option<String> {
    serial_flag
        .map(str::to_string)
        .or_else(|| std::env::var("ANDY_SERIAL").ok())
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn merge_precedence_json_then_arg_flags() {
        let mut base = Map::new();
        base.insert("name".into(), json!("from-base"));
        base.insert("keep".into(), json!(1));
        let merged = merge_tool_args(
            base,
            Some(r#"{"name":"from-json","port":8080}"#),
            &["name=from-arg".into(), "enabled=true".into()],
        )
        .unwrap();
        assert_eq!(merged["name"], json!("from-arg"));
        assert_eq!(merged["port"], json!(8080));
        assert_eq!(merged["enabled"], json!(true));
        assert_eq!(merged["keep"], json!(1));
    }

    #[test]
    fn inject_serial_does_not_overwrite() {
        let mut args = json!({"serial": "already"});
        inject_serial(&mut args, Some("flag"));
        assert_eq!(args["serial"], json!("already"));
    }

    #[test]
    fn inject_serial_from_flag() {
        let mut args = json!({});
        inject_serial(&mut args, Some("emulator-5554"));
        assert_eq!(args["serial"], json!("emulator-5554"));
    }

    #[test]
    fn inject_caller_task_id_does_not_overwrite() {
        let mut args = json!({"callerTaskId": "already"});
        // Even if ANDY_TASK_ID is set in the environment, explicit wins.
        inject_caller_task_id(&mut args);
        assert_eq!(args["callerTaskId"], json!("already"));
    }

    #[test]
    fn coerce_json_and_string() {
        assert_eq!(coerce_arg_value("true"), json!(true));
        assert_eq!(coerce_arg_value("42"), json!(42));
        assert_eq!(coerce_arg_value("hello"), json!("hello"));
        assert_eq!(coerce_arg_value(r#"{"a":1}"#), json!({"a": 1}));
    }
}
