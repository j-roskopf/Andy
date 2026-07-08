import json
import os
import time
from mitmproxy import http

RULES_PATH = os.environ.get("ANDY_RULES_PATH")
PREVIEW_LIMIT = 262144


def _load_rules():
    if not RULES_PATH or not os.path.exists(RULES_PATH):
        return []
    try:
        with open(RULES_PATH, "r", encoding="utf-8") as handle:
            return json.load(handle).get("rules", [])
    except Exception:
        return []


def _preview(content):
    if not content:
        return None
    data = content[:PREVIEW_LIMIT]
    try:
        return data.decode("utf-8", errors="replace")
    except Exception:
        return repr(data)


def _message_preview(message):
    if not message or not message.raw_content:
        return None
    try:
        text = message.get_text(strict=False)
        if text is not None:
            return text[:PREVIEW_LIMIT]
    except Exception:
        pass
    return _preview(message.raw_content)


def _headers(headers):
    return {key: value for key, value in headers.items()}


def _match(rule, flow):
    if not rule.get("enabled", True):
        return False
    pattern = (rule.get("urlPattern") or "").lower()
    if pattern and pattern not in flow.request.pretty_url.lower():
        return False
    method = rule.get("method")
    if method and method.upper() != flow.request.method.upper():
        return False
    return True


def request(flow: http.HTTPFlow):
    flow.metadata["andy_started_at"] = int(time.time() * 1000)
    _emit(flow, is_request=True)


def response(flow: http.HTTPFlow):
    if not flow.response:
        return
    matched_rule_id = None
    error_msg = None
    try:
        for rule in _load_rules():
            if not _match(rule, flow):
                continue
            matched_rule_id = rule.get("id")
            print(f"[Proxy addon] Rule matched: {matched_rule_id}")
            if rule.get("statusCode") is not None:
                flow.response.status_code = int(rule["statusCode"])
            for header, value in (rule.get("setHeaders") or {}).items():
                flow.response.headers[header] = value
            for header in rule.get("removeHeaders") or []:
                flow.response.headers.pop(header, None)
            if rule.get("responseBody") is not None:
                # Remove any encoding/length/transfer headers case-insensitively
                for name in list(flow.response.headers.keys()):
                    if name.lower() in ("content-encoding", "content-length", "transfer-encoding"):
                        flow.response.headers.pop(name, None)
                body = rule["responseBody"]
                flow.response.content = body.encode("utf-8") if isinstance(body, str) else body
                print(f"[Proxy addon] Set response content, size: {len(flow.response.content)}")
            break
    except Exception as e:
        error_msg = f"Rule error: {e}"
        print(f"[Proxy addon] Exception in response: {e}")
    _emit(flow, matched_rule_id, error_msg)


def error(flow: http.HTTPFlow):
    _emit(flow, None, str(flow.error) if flow.error else "proxy error")


def _emit(flow, matched_rule_id=None, error=None, is_request=False):
    response = flow.response if (hasattr(flow, 'response') and flow.response) else None
    started = flow.metadata.get("andy_started_at")
    if started is None:
        started = int(time.time() * 1000)
        flow.metadata["andy_started_at"] = started

    if is_request:
        completed = None
        duration = None
    else:
        completed = int(time.time() * 1000)
        duration = max(0, completed - started)

    payload = {
        "type": "flow",
        "id": flow.id,
        "startedAtMillis": started,
        "completedAtMillis": completed,
        "durationMillis": duration,
        "method": flow.request.method,
        "url": flow.request.pretty_url,
        "statusCode": response.status_code if response else None,
        "contentType": response.headers.get("content-type") if response else None,
        "sizeBytes": len(response.raw_content or b"") if response else None,
        "requestHeaders": _headers(flow.request.headers),
        "responseHeaders": _headers(response.headers) if response else {},
        "requestBodyPreview": _message_preview(flow.request),
        "responseBodyPreview": _message_preview(response) if response else None,
        "error": error,
        "tlsStatus": "tls" if flow.request.scheme == "https" else "plain",
        "matchedRuleId": matched_rule_id,
    }
    print(json.dumps(payload, separators=(",", ":")), flush=True)
