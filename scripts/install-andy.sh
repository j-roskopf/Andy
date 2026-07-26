#!/usr/bin/env bash
# Install the Andy CLI (+ status hook helper) into ~/.andy/bin
# Usage:
#   curl -fsSL https://github.com/j-roskopf/Andy/releases/latest/download/install-andy.sh | bash
set -euo pipefail

REPO="${ANDY_INSTALL_REPO:-j-roskopf/Andy}"
BIN_DIR="${ANDY_BIN_DIR:-${HOME}/.andy/bin}"
API_URL="${ANDY_RELEASES_API:-https://api.github.com/repos/${REPO}/releases/latest}"

log() { printf '%s\n' "$*" >&2; }
die() { log "error: $*"; exit 1; }

need() {
  command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"
}

detect_target() {
  local os arch
  os="$(uname -s | tr '[:upper:]' '[:lower:]')"
  arch="$(uname -m)"
  case "${os}" in
    darwin)
      case "${arch}" in
        arm64|aarch64) echo "macos-arm64" ;;
        *)
          die "unsupported macOS architecture '${arch}' (release builds are macos-arm64 only). Build from source with ./gradlew installAndyCli"
          ;;
      esac
      ;;
    linux)
      case "${arch}" in
        x86_64|amd64) echo "linux-x86_64" ;;
        *)
          die "unsupported Linux architecture '${arch}' (release builds are linux-x86_64 only)"
          ;;
      esac
      ;;
    mingw*|msys*|cygwin*|windows*)
      die "Windows: download andy-<version>-windows-x86_64.exe from https://github.com/${REPO}/releases/latest (this installer is bash/macOS/Linux only)"
      ;;
    *)
      die "unsupported OS '${os}'"
      ;;
  esac
}

need curl
need uname
need mkdir
need chmod
need mktemp

TARGET="$(detect_target)"
log "Installing Andy CLI for ${TARGET}…"

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

# Prefer jq when present; otherwise parse lightly with sed/tr.
RELEASE_JSON="${TMP}/release.json"
curl -fsSL \
  -H "Accept: application/vnd.github+json" \
  -H "User-Agent: andy-install" \
  "${API_URL}" >"${RELEASE_JSON}"

ASSET_NAME=""
DOWNLOAD_URL=""
if command -v jq >/dev/null 2>&1; then
  ASSET_NAME="$(jq -r --arg t "${TARGET}" '
    .assets[]
    | select(.name | test("^andy-.+-" + $t + "$"))
    | .name
  ' "${RELEASE_JSON}" | head -n1)"
  DOWNLOAD_URL="$(jq -r --arg n "${ASSET_NAME}" '
    .assets[] | select(.name == $n) | .browser_download_url
  ' "${RELEASE_JSON}")"
else
  # Match andy-<version>-<target> (not .exe)
  ASSET_NAME="$(
    tr '"' '\n' <"${RELEASE_JSON}" \
      | grep -E "^andy-.+-${TARGET}$" \
      | head -n1 || true
  )"
  if [[ -n "${ASSET_NAME}" ]]; then
    DOWNLOAD_URL="$(
      tr '"' '\n' <"${RELEASE_JSON}" \
        | grep -E "https://.*/${ASSET_NAME}$" \
        | head -n1 || true
    )"
  fi
fi

[[ -n "${ASSET_NAME}" && -n "${DOWNLOAD_URL}" ]] || \
  die "could not find release asset andy-<version>-${TARGET} in latest GitHub release for ${REPO}"

log "Downloading ${ASSET_NAME}…"
curl -fsSL "${DOWNLOAD_URL}" -o "${TMP}/andy"
chmod +x "${TMP}/andy"

mkdir -p "${BIN_DIR}"
# Atomic-ish replace
mv -f "${TMP}/andy" "${BIN_DIR}/andy"
chmod +x "${BIN_DIR}/andy"

# Ad-hoc codesign: Gradle/curl downloads can leave an invalid signature on macOS.
if [[ "$(uname -s)" == "Darwin" ]] && command -v codesign >/dev/null 2>&1; then
  codesign --force --sign - "${BIN_DIR}/andy" >/dev/null 2>&1 || true
fi

# Install status hook helper (same contract as the desktop app / andyd).
HOOK_DEST="${BIN_DIR}/andy-status-hook.sh"
cat >"${HOOK_DEST}" <<'HOOK'
#!/bin/sh
# Andy-managed status hook — do not edit.
# Installed to ~/.andy/bin/andy-status-hook.sh by the Andy desktop app, andyd, or install-andy.sh.
# Usage: andy-status-hook.sh <working|done|blocked|error> [respond]
# respond: none (default) | empty | allow | stop
#
# Resolves the active task via $ANDY_PROJECT_ROOT/.andy/active-task (default: $PWD).
# No-ops when the pointer is missing so user-level / shared hooks are safe.
status="${1:-done}"
respond="${2:-none}"
ROOT="${ANDY_PROJECT_ROOT:-$PWD}"
ACTIVE="$ROOT/.andy/active-task"
if [ ! -f "$ACTIVE" ]; then
  case "$respond" in
    empty) printf '%s\n' '{}' ;;
    allow) printf '%s\n' '{"decision":"allow"}' ;;
    stop) printf '%s\n' '{"decision":"stop"}' ;;
  esac
  exit 0
fi
task_id=$(tr -d '[:space:]' < "$ACTIVE")
if [ -z "$task_id" ]; then
  case "$respond" in
    empty) printf '%s\n' '{}' ;;
    allow) printf '%s\n' '{"decision":"allow"}' ;;
    stop) printf '%s\n' '{"decision":"stop"}' ;;
  esac
  exit 0
fi
dir="$ROOT/.andy/$task_id"
mkdir -p "$dir"
printf '{"status":"%s","at":%s}\n' "$status" "$(date +%s)" >> "$dir/status.json"
case "$respond" in
  empty) printf '%s\n' '{}' ;;
  allow) printf '%s\n' '{"decision":"allow"}' ;;
  stop) printf '%s\n' '{"decision":"stop"}' ;;
esac
exit 0
HOOK
chmod +x "${HOOK_DEST}"

log "Installed ${BIN_DIR}/andy"
log "Installed ${HOOK_DEST}"
if ! command -v andy >/dev/null 2>&1 || [[ "$(command -v andy)" != "${BIN_DIR}/andy" ]]; then
  log "Add to PATH if needed:"
  log "  export PATH=\"\$HOME/.andy/bin:\$PATH\""
fi
log "Try: andy --help"
