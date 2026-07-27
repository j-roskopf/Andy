#!/usr/bin/env bash
# Install the Andy CLI, andyd daemon runtime, bundled tmux, and status hook into ~/.andy
# Usage:
#   curl -fsSL https://github.com/j-roskopf/Andy/releases/latest/download/install-andy.sh | bash
set -euo pipefail

REPO="${ANDY_INSTALL_REPO:-j-roskopf/Andy}"
BIN_DIR="${ANDY_BIN_DIR:-${HOME}/.andy/bin}"
ANDY_HOME="${ANDY_HOME:-${HOME}/.andy}"
RUNTIME_DIR="${ANDY_HOME}/andyd"
API_URL="${ANDY_RELEASES_API:-https://api.github.com/repos/${REPO}/releases/latest}"

log() { printf '%s\n' "$*" >&2; }
die() { log "error: $*"; exit 1; }

need() {
  command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"
}

# Show a progress bar for large assets when stderr is a TTY; stay quiet otherwise.
download() {
  local url="$1"
  local dest="$2"
  if [[ -t 2 ]]; then
    curl -fL --progress-bar "${url}" -o "${dest}"
  else
    curl -fsSL "${url}" -o "${dest}"
  fi
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
          die "unsupported macOS architecture '${arch}' (release builds are macos-arm64 only). Build from source with ./gradlew installAndyCli installAndyd"
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
      die "the andy CLI is only supported on macOS and Linux (Unix domain sockets, tmux, andyd). Use the Andy desktop app on Windows."
      ;;
    *)
      die "unsupported OS '${os}'"
      ;;
  esac
}

find_release_asset() {
  local pattern="$1"
  if command -v jq >/dev/null 2>&1; then
    jq -r --arg re "${pattern}" '
      .assets[]
      | select(.name | test($re))
      | .browser_download_url
    ' "${RELEASE_JSON}" | head -n1
    return
  fi
  tr '"' '\n' <"${RELEASE_JSON}" | grep -E "${pattern}" | grep -E '^https://' | head -n1 || true
}

download_release_asset() {
  local pattern="$1"
  local dest="$2"
  local label="${3:-}"
  local url
  url="$(find_release_asset "${pattern}")"
  [[ -n "${url}" ]] || return 1
  [[ -n "${label}" ]] && log "${label}"
  download "${url}" "${dest}"
}

need curl
need uname
need mkdir
need chmod
need mktemp
need java

TARGET="$(detect_target)"
log "Installing Andy CLI for ${TARGET}…"

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

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
download "${DOWNLOAD_URL}" "${TMP}/andy"
chmod +x "${TMP}/andy"

mkdir -p "${BIN_DIR}" "${RUNTIME_DIR}"

mv -f "${TMP}/andy" "${BIN_DIR}/andy"
chmod +x "${BIN_DIR}/andy"

if [[ "$(uname -s)" == "Darwin" ]] && command -v codesign >/dev/null 2>&1; then
  codesign --force --sign - "${BIN_DIR}/andy" >/dev/null 2>&1 || true
fi

# andyd runtime (fat JAR + launcher)
if download_release_asset "^andyd-.+-${TARGET}\\.jar$" "${RUNTIME_DIR}/andyd.jar" "Downloading andyd runtime…"; then
  log "Installed ${RUNTIME_DIR}/andyd.jar"
else
  log "warning: no andyd-<version>-${TARGET}.jar in this release — run ./gradlew installAndyd from source or use the desktop app"
fi

cat >"${BIN_DIR}/andyd" <<'LAUNCHER'
#!/bin/sh
ANDY_HOME="${ANDY_HOME:-$HOME/.andy}"
JAR="${ANDY_ANDYD_JAR:-$ANDY_HOME/andyd/andyd.jar}"
JAVA="${ANDY_JAVA:-java}"
if [ ! -f "$JAR" ]; then
  printf 'andyd runtime missing at %s\n' "$JAR" >&2
  printf 'Re-run install-andy.sh or run ./gradlew installAndyd from a source checkout.\n' >&2
  exit 1
fi
exec "$JAVA" \
  -Djdk.lang.Process.launchMechanism=FORK \
  -Dapple.awt.UIElement=true \
  -Djava.awt.headless=true \
  -jar "$JAR" "$@"
LAUNCHER
chmod +x "${BIN_DIR}/andyd"
log "Installed ${BIN_DIR}/andyd"

# Bundled tmux (Andy-managed, like scrcpy-server)
if download_release_asset "^tmux-.+-${TARGET}$" "${TMP}/tmux" "Downloading bundled tmux…"; then
  mv -f "${TMP}/tmux" "${BIN_DIR}/tmux"
  chmod +x "${BIN_DIR}/tmux"
  if [[ "$(uname -s)" == "Darwin" ]] && command -v codesign >/dev/null 2>&1; then
    codesign --force --sign - "${BIN_DIR}/tmux" >/dev/null 2>&1 || true
  fi
  log "Installed ${BIN_DIR}/tmux"
elif command -v tmux >/dev/null 2>&1; then
  ln -sf "$(command -v tmux)" "${BIN_DIR}/tmux"
  log "Linked system tmux to ${BIN_DIR}/tmux"
else
  log "warning: tmux not bundled in this release and not found on PATH — agent sessions will not work until tmux is installed"
fi

# Status hook helper (same contract as the desktop app / andyd).
HOOK_DEST="${BIN_DIR}/andy-status-hook.sh"
if download_release_asset '^andy-status-hook\\.sh$' "${TMP}/andy-status-hook.sh"; then
  mv -f "${TMP}/andy-status-hook.sh" "${HOOK_DEST}"
else
  cat >"${HOOK_DEST}" <<'HOOK'
#!/bin/sh
# Andy-managed status hook — do not edit.
# Usage: andy-status-hook.sh <working|done|blocked|error> [respond] [gate]
status="${1:-done}"
respond="${2:-none}"
gate="${3:-none}"
payload=$(cat 2>/dev/null || true)

respond_and_exit() {
  case "$respond" in
    empty) printf '%s\n' '{}' ;;
    allow) printf '%s\n' '{"decision":"allow"}' ;;
    stop) printf '%s\n' '{"decision":"stop"}' ;;
  esac
  exit 0
}

case "$gate" in
  fully-idle)
    printf '%s' "$payload" | grep -Eq '"fullyIdle"[[:space:]]*:[[:space:]]*true' || respond_and_exit
    ;;
  completed)
    printf '%s' "$payload" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"(completed|aborted)"' || respond_and_exit
    ;;
esac

ROOT="${ANDY_PROJECT_ROOT:-$PWD}"
ACTIVE="$ROOT/.andy/active-task"
if [ ! -f "$ACTIVE" ]; then
  respond_and_exit
fi
task_id=$(tr -d '[:space:]' < "$ACTIVE")
if [ -z "$task_id" ]; then
  respond_and_exit
fi
dir="$ROOT/.andy/$task_id"
mkdir -p "$dir"
printf '{"status":"%s","at":%s}\n' "$status" "$(date +%s)" >> "$dir/status.json"
respond_and_exit
HOOK
fi
chmod +x "${HOOK_DEST}"

log "Installed ${BIN_DIR}/andy"
log "Installed ${HOOK_DEST}"
if ! command -v andy >/dev/null 2>&1 || [[ "$(command -v andy)" != "${BIN_DIR}/andy" ]]; then
  log "Add ~/.andy/bin to your PATH permanently (pick your shell):"
  log "  echo 'export PATH=\"\$HOME/.andy/bin:\$PATH\"' >> ~/.zshrc   # zsh"
  log "  echo 'export PATH=\"\$HOME/.andy/bin:\$PATH\"' >> ~/.bashrc  # bash"
  log "Then restart your shell or: source ~/.zshrc  # or ~/.bashrc"
fi
log "Try: andy chat list"
