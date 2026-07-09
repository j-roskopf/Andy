import json
import os
import re
import time
import uuid
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


def _remove_header(headers, target):
    target_lower = target.lower()
    for name in list(headers.keys()):
        if name.lower() == target_lower:
            headers.pop(name, None)


def _match(rule, flow):
    if not rule.get("enabled", True):
        return False
    pattern = (rule.get("urlPattern") or "").strip()
    if pattern:
        url = flow.request.pretty_url
        if "*" in pattern:
            regex = re.escape(pattern).replace(r"\*", ".*")
            if not re.fullmatch(regex, url, re.IGNORECASE):
                return False
        else:
            if pattern.lower() not in url.lower():
                return False
    method = rule.get("method")
    if method and method.upper() != flow.request.method.upper():
        return False
    return True


def _emit_event(payload):
    print(json.dumps(payload, separators=(",", ":")), flush=True)


def _peer_address(conn):
    peer = getattr(conn, "peername", None)
    if isinstance(peer, (list, tuple)) and peer:
        host = peer[0]
        port = peer[1] if len(peer) > 1 else None
        return f"{host}:{port}" if port is not None else str(host)
    return str(peer) if peer else None


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
                _remove_header(flow.response.headers, header)
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


def client_connected(client):
    _emit_event(
        {
            "type": "client_connected",
            "id": getattr(client, "id", None) or str(uuid.uuid4()),
            "startedAtMillis": int(time.time() * 1000),
            "peer": _peer_address(client),
        }
    )


def client_disconnected(client):
    _emit_event(
        {
            "type": "client_disconnected",
            "id": getattr(client, "id", None) or str(uuid.uuid4()),
            "startedAtMillis": int(time.time() * 1000),
            "peer": _peer_address(client),
        }
    )


def tls_failed_client(data):
    conn = getattr(data, "conn", None)
    context = getattr(data, "context", None)
    server = getattr(context, "server", None) if context is not None else None
    sni = getattr(conn, "sni", None) if conn is not None else None
    if not sni and server is not None:
        address = getattr(server, "address", None)
        if isinstance(address, (list, tuple)) and address:
            sni = address[0]
        elif address:
            sni = str(address)
    reason = None
    if conn is not None:
        reason = getattr(conn, "error", None)
    if not reason:
        reason = "client TLS handshake failed"
    host = sni or "unknown-host"
    now = int(time.time() * 1000)
    flow_id = getattr(conn, "id", None) or str(uuid.uuid4())
    _emit_event(
        {
            "type": "tls_failed",
            "id": f"tls-failed-{flow_id}",
            "startedAtMillis": now,
            "completedAtMillis": now,
            "durationMillis": 0,
            "method": "TLS",
            "url": f"https://{host}/",
            "statusCode": None,
            "contentType": None,
            "sizeBytes": None,
            "requestHeaders": {},
            "responseHeaders": {},
            "requestBodyPreview": None,
            "responseBodyPreview": None,
            "error": f"Client rejected Andy's CA for {host}: {reason}",
            "tlsStatus": "tls",
            "matchedRuleId": None,
            "sni": sni,
            "peer": _peer_address(conn) if conn is not None else None,
            "reason": str(reason),
        }
    )


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
    _emit_event(payload)
