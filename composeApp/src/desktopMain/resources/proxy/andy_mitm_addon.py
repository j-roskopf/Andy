import asyncio
import gzip
import hashlib
import json
import os
import queue
import re
import socket
import sys
import threading
import time
import uuid
import zlib
from mitmproxy import http

RULES_PATH = os.environ.get("ANDY_RULES_PATH")
# Keep previews small: each flow is one JSON line on stdout. Dumping full
# bodies (256KB+) fills the OS pipe and blocks mitmdump mid-response.
PREVIEW_LIMIT = 8192
EVENT_QUEUE_MAX = 2000
ADDON_VERSION = 3

# mitmproxy calls asyncio.open_connection without Happy Eyeballs (RFC 8305), so
# dual-stack hosts with a dead AAAA hang ~15–20s before IPv4. Browsers, curl,
# Charles/Proxyman (via OS stacks) race families instead. Patch open_connection
# for this mitmdump process; delay is the standard ~250ms first-family lead.
# Override with ANDY_HAPPY_EYEBALLS_DELAY (seconds); set 0 to disable.
try:
    _HE_DELAY = float(os.environ.get("ANDY_HAPPY_EYEBALLS_DELAY", "0.25"))
except ValueError:
    _HE_DELAY = 0.25
_he_installed = False
_real_open_connection = asyncio.open_connection

_event_queue = queue.Queue(maxsize=EVENT_QUEUE_MAX)
_dropped_count = 0
_dropped_lock = threading.Lock()
_writer_started = False
_writer_lock = threading.Lock()

_rules_cache = []
_rules_mtime_ns = None

# Hosts that rejected Andy's CA (pinning / missing trust). Subsequent
# ClientHellos for these SNIs are raw-tunneled so the app keeps working.
_passthrough_hosts = set()
_passthrough_lock = threading.Lock()


async def _open_connection_happy_eyeballs(*args, happy_eyeballs_delay=_HE_DELAY, **kwargs):
    # Default delay enables HE; callers that pass an explicit value keep it.
    return await _real_open_connection(
        *args, happy_eyeballs_delay=happy_eyeballs_delay, **kwargs
    )


def _install_happy_eyeballs():
    global _he_installed
    if _he_installed or _HE_DELAY <= 0:
        return
    asyncio.open_connection = _open_connection_happy_eyeballs
    _he_installed = True


def _addon_sha256():
    path = os.path.abspath(__file__)
    try:
        with open(path, "rb") as handle:
            return hashlib.sha256(handle.read()).hexdigest()
    except Exception:
        return None


def _is_ipv4_literal(host):
    try:
        socket.inet_pton(socket.AF_INET, host)
        return True
    except OSError:
        return False


def _is_ipv6_literal(host):
    try:
        socket.inet_pton(socket.AF_INET6, str(host).strip("[]"))
        return True
    except OSError:
        return False


def _lookup_hostname(server, host):
    """Hostname to A-resolve. Prefer SNI when address is already an IP literal."""
    if host and not _is_ipv4_literal(host) and not _is_ipv6_literal(host):
        return host
    for attr in ("sni", "server_name"):
        value = getattr(server, attr, None)
        if isinstance(value, str) and value and not _is_ipv4_literal(value) and not _is_ipv6_literal(value):
            return value
    return None


def _write_stdout_bytes(data):
    """Best-effort non-blocking write so a slow Andy reader cannot stall mitmdump."""
    if not data:
        return True
    try:
        fd = sys.stdout.fileno()
    except Exception:
        try:
            sys.stdout.write(data.decode("utf-8", errors="replace"))
            sys.stdout.flush()
            return True
        except Exception:
            return False
    try:
        os.set_blocking(fd, False)
    except Exception:
        pass
    view = memoryview(data)
    offset = 0
    idle_spins = 0
    while offset < len(view):
        try:
            written = os.write(fd, view[offset:])
            if written:
                offset += written
                idle_spins = 0
                continue
        except BlockingIOError:
            pass
        except BrokenPipeError:
            return False
        except Exception:
            return False
        idle_spins += 1
        if idle_spins > 40:
            # Pipe stayed full — drop remaining bytes rather than blocking forever.
            return False
        time.sleep(0.001)
    return True


def _writer_loop():
    global _dropped_count
    while True:
        payload = _event_queue.get()
        if payload is None:
            break
        try:
            line = json.dumps(payload, separators=(",", ":")).encode("utf-8") + b"\n"
        except Exception:
            continue
        if not _write_stdout_bytes(line):
            with _dropped_lock:
                _dropped_count += 1
            continue
        with _dropped_lock:
            dropped = _dropped_count
            if dropped:
                _dropped_count = 0
            else:
                dropped = 0
        if dropped:
            drop_line = (
                json.dumps({"type": "events_dropped", "count": dropped}, separators=(",", ":")).encode("utf-8")
                + b"\n"
            )
            _write_stdout_bytes(drop_line)


def _ensure_writer():
    global _writer_started
    with _writer_lock:
        if _writer_started:
            return
        thread = threading.Thread(target=_writer_loop, name="andy-mitm-writer", daemon=True)
        thread.start()
        _writer_started = True


def _emit_event(payload):
    global _dropped_count
    _ensure_writer()
    # Under backpressure, strip bulky fields before enqueue so hooks stay cheap.
    if _event_queue.qsize() >= EVENT_QUEUE_MAX // 2:
        payload = dict(payload)
        payload["requestBodyPreview"] = None
        payload["responseBodyPreview"] = None
        if len(payload.get("requestHeaders") or {}) > 20:
            payload["requestHeaders"] = {}
        if len(payload.get("responseHeaders") or {}) > 20:
            payload["responseHeaders"] = {}
    try:
        _event_queue.put_nowait(payload)
    except queue.Full:
        with _dropped_lock:
            _dropped_count += 1


def load(loader):
    _install_happy_eyeballs()
    _ensure_writer()
    _emit_event(
        {
            "type": "addon_hello",
            "sha256": _addon_sha256(),
            "version": ADDON_VERSION,
            "startedAtMillis": int(time.time() * 1000),
            "happyEyeballsDelay": _HE_DELAY if _HE_DELAY > 0 else None,
        }
    )


async def server_connect(data):
    # If mitmproxy already resolved to a single IP, Happy Eyeballs cannot race
    # families. Restore the hostname from SNI when available so open_connection
    # gets a name and can try AAAA then A (same as browsers / OS stacks).
    try:
        server = getattr(data, "server", None)
        address = getattr(server, "address", None) if server is not None else None
        if not address or len(address) < 2:
            return
        host, port = address[0], address[1]
        if not host:
            return
        if not _is_ipv4_literal(host) and not _is_ipv6_literal(host):
            return
        hostname = _lookup_hostname(server, host)
        if hostname:
            server.address = (hostname, port)
    except Exception:
        return


def _load_rules():
    global _rules_cache, _rules_mtime_ns
    if not RULES_PATH or not os.path.exists(RULES_PATH):
        _rules_cache = []
        _rules_mtime_ns = None
        return _rules_cache
    try:
        mtime_ns = os.stat(RULES_PATH).st_mtime_ns
        if _rules_mtime_ns == mtime_ns:
            return _rules_cache
        with open(RULES_PATH, "r", encoding="utf-8") as handle:
            _rules_cache = json.load(handle).get("rules", [])
        _rules_mtime_ns = mtime_ns
        return _rules_cache
    except Exception:
        return _rules_cache if _rules_cache is not None else []


def _preview(content):
    if not content:
        return None
    if isinstance(content, str):
        return content[:PREVIEW_LIMIT]
    data = content[:PREVIEW_LIMIT]
    try:
        return data.decode("utf-8", errors="replace")
    except Exception:
        return repr(data)


def _try_decompress_bounded(snippet, encoding):
    """Best-effort decompress of a bounded snippet — never touches the full body."""
    if not snippet or not encoding:
        return None
    enc = encoding.lower().strip()
    try:
        if enc in ("gzip", "x-gzip"):
            return gzip.decompress(snippet)
        if enc == "deflate":
            try:
                return zlib.decompress(snippet)
            except zlib.error:
                return zlib.decompress(snippet, -zlib.MAX_WBITS)
        if enc in ("br", "brotli"):
            try:
                import brotli  # type: ignore

                return brotli.decompress(snippet)
            except Exception:
                return None
    except Exception:
        return None
    return None


def _message_preview(message):
    """Cheap body preview: never call the decompressed content property (stalls the event loop)."""
    if not message:
        return None
    raw = getattr(message, "raw_content", None)
    if raw is None:
        return None
    if len(raw) == 0:
        return None
    snippet = raw[:PREVIEW_LIMIT]
    encoding = None
    try:
        headers = getattr(message, "headers", None)
        if headers is not None:
            encoding = headers.get("content-encoding")
    except Exception:
        encoding = None
    if encoding:
        decompressed = _try_decompress_bounded(snippet, encoding)
        if decompressed is not None:
            return _preview(decompressed)
    if len(raw) > PREVIEW_LIMIT:
        preview = _preview(snippet)
        return f"{preview}\n…[truncated; {len(raw)} raw bytes]" if preview else f"[body {len(raw)} bytes; preview truncated]"
    return _preview(snippet)


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


def _peer_address(conn):
    peer = getattr(conn, "peername", None)
    if isinstance(peer, (list, tuple)) and peer:
        host = peer[0]
        port = peer[1] if len(peer) > 1 else None
        return f"{host}:{port}" if port is not None else str(host)
    return str(peer) if peer else None


def request(flow: http.HTTPFlow):
    try:
        flow.metadata["andy_started_at"] = int(time.time() * 1000)
        # In-flight marker without bodies — keeps stdout small under parallel fetches.
        _emit(flow, is_request=True, include_bodies=False)
    except Exception as e:
        _emit_event(
            {
                "type": "addon_error",
                "id": getattr(flow, "id", None) or str(uuid.uuid4()),
                "startedAtMillis": int(time.time() * 1000),
                "error": f"request hook: {e}",
            }
        )


def response(flow: http.HTTPFlow):
    try:
        if not flow.response:
            return
        matched_rule_id = None
        error_msg = None
        try:
            for rule in _load_rules():
                if not _match(rule, flow):
                    continue
                matched_rule_id = rule.get("id")
                if rule.get("statusCode") is not None:
                    flow.response.status_code = int(rule["statusCode"])
                for header, value in (rule.get("setHeaders") or {}).items():
                    flow.response.headers[header] = value
                for header in rule.get("removeHeaders") or []:
                    _remove_header(flow.response.headers, header)
                if rule.get("responseBody") is not None:
                    for name in list(flow.response.headers.keys()):
                        if name.lower() in ("content-encoding", "content-length", "transfer-encoding"):
                            flow.response.headers.pop(name, None)
                    body = rule["responseBody"]
                    flow.response.content = body.encode("utf-8") if isinstance(body, str) else body
                break
        except Exception as e:
            error_msg = f"Rule error: {e}"
        _emit(flow, matched_rule_id, error_msg)
    except Exception as e:
        _emit_event(
            {
                "type": "addon_error",
                "id": getattr(flow, "id", None) or str(uuid.uuid4()),
                "startedAtMillis": int(time.time() * 1000),
                "error": f"response hook: {e}",
            }
        )


def error(flow: http.HTTPFlow):
    try:
        _emit(flow, None, str(flow.error) if flow.error else "proxy error")
    except Exception as e:
        _emit_event(
            {
                "type": "addon_error",
                "id": getattr(flow, "id", None) or str(uuid.uuid4()),
                "startedAtMillis": int(time.time() * 1000),
                "error": f"error hook: {e}",
            }
        )


def client_connected(client):
    try:
        _emit_event(
            {
                "type": "client_connected",
                "id": getattr(client, "id", None) or str(uuid.uuid4()),
                "startedAtMillis": int(time.time() * 1000),
                "peer": _peer_address(client),
            }
        )
    except Exception:
        pass


def client_disconnected(client):
    try:
        _emit_event(
            {
                "type": "client_disconnected",
                "id": getattr(client, "id", None) or str(uuid.uuid4()),
                "startedAtMillis": int(time.time() * 1000),
                "peer": _peer_address(client),
            }
        )
    except Exception:
        pass


def tls_failed_client(data):
    try:
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
        if sni:
            with _passthrough_lock:
                _passthrough_hosts.add(str(sni).lower())
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
    except Exception as e:
        _emit_event(
            {
                "type": "addon_error",
                "id": str(uuid.uuid4()),
                "startedAtMillis": int(time.time() * 1000),
                "error": f"tls_failed_client hook: {e}",
            }
        )


def tls_clienthello(data):
    """Raw-tunnel hosts that previously rejected Andy's CA so apps keep working."""
    try:
        client_hello = getattr(data, "client_hello", None)
        sni = getattr(client_hello, "sni", None) if client_hello is not None else None
        if not sni:
            return
        host = str(sni).lower()
        with _passthrough_lock:
            should_passthrough = host in _passthrough_hosts
        if not should_passthrough:
            return
        data.ignore_connection = True
        now = int(time.time() * 1000)
        _emit_event(
            {
                "type": "tls_passthrough",
                "id": f"tls-passthrough-{host}-{now}",
                "startedAtMillis": now,
                "completedAtMillis": now,
                "durationMillis": 0,
                "method": "PASS",
                "url": f"https://{sni}/",
                "statusCode": None,
                "contentType": None,
                "sizeBytes": None,
                "requestHeaders": {},
                "responseHeaders": {},
                "requestBodyPreview": None,
                "responseBodyPreview": "Not decrypted — CA not trusted / pinned",
                "error": "Not decrypted — CA not trusted / pinned",
                "tlsStatus": "passthrough",
                "matchedRuleId": None,
                "sni": sni,
                "peer": None,
                "reason": "passthrough after client CA rejection",
            }
        )
    except Exception as e:
        _emit_event(
            {
                "type": "addon_error",
                "id": str(uuid.uuid4()),
                "startedAtMillis": int(time.time() * 1000),
                "error": f"tls_clienthello hook: {e}",
            }
        )


def _emit(flow, matched_rule_id=None, error=None, is_request=False, include_bodies=True):
    response = flow.response if (hasattr(flow, "response") and flow.response) else None
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

    size_bytes = None
    if response is not None:
        raw = getattr(response, "raw_content", None)
        if raw is not None:
            size_bytes = len(raw)
        else:
            # Avoid full decompress on the hot path for size.
            size_bytes = None

    busy = _event_queue.qsize() >= EVENT_QUEUE_MAX // 2
    include_bodies = include_bodies and not busy
    include_headers = not busy
    payload = {
        "type": "flow",
        "id": flow.id,
        "startedAtMillis": started,
        "completedAtMillis": completed,
        "durationMillis": duration,
        "method": flow.request.method,
        "url": flow.request.pretty_url,
        "statusCode": response.status_code if response else None,
        "contentType": response.headers.get("content-type") if response and include_headers else None,
        "sizeBytes": size_bytes,
        "requestHeaders": _headers(flow.request.headers) if include_headers else {},
        "responseHeaders": _headers(response.headers) if response and include_headers else {},
        "requestBodyPreview": _message_preview(flow.request) if include_bodies and not is_request else None,
        "responseBodyPreview": _message_preview(response) if include_bodies and response and not is_request else None,
        "error": error,
        "tlsStatus": "tls" if flow.request.scheme == "https" else "plain",
        "matchedRuleId": matched_rule_id,
    }
    _emit_event(payload)
