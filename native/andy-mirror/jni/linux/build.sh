#!/usr/bin/env bash
set -euo pipefail

OUT="${1:?output .so path}"
JAVA_HOME="${2:-${JAVA_HOME:-}}"
if [[ -z "${JAVA_HOME}" ]]; then
  echo "JAVA_HOME is required to build the Andy Linux mirror JNI library" >&2
  exit 1
fi

ROOT="$(cd "$(dirname "$0")" && pwd)"
GEN="$(dirname "$OUT")/generated"
mkdir -p "$(dirname "$OUT")" "$GEN"

compile_shader() {
  local src="$1"
  local spv="$2"
  if command -v glslangValidator >/dev/null 2>&1; then
    glslangValidator -V "$src" -o "$spv"
  elif command -v glslc >/dev/null 2>&1; then
    glslc -fshader-stage="${src##*.}" "$src" -o "$spv"
  else
    return 1
  fi
}

generate_shader_header() {
  local vert_spv="$1"
  local frag_spv="$2"
  local dest="$3"
  python3 - "$vert_spv" "$frag_spv" "$dest" <<'PY'
import pathlib, struct, sys
vert, frag, dest = map(pathlib.Path, sys.argv[1:])

def blob(path):
    data = path.read_bytes()
    if len(data) % 4:
        raise SystemExit(f"{path} is not aligned SPIR-V")
    words = struct.unpack("<" + "I" * (len(data) // 4), data)
    body = ",\n    ".join(f"0x{w:08x}" for w in words)
    return len(data), body

vert_len, vert_body = blob(vert)
frag_len, frag_body = blob(frag)
dest.write_text(
    "#pragma once\n"
    "#include <stddef.h>\n"
    "#include <stdint.h>\n"
    "// Generated from overlay.vert / overlay.frag — regenerate with glslangValidator/glslc when editing GLSL.\n"
    f"static const uint32_t overlay_vert_spv[] = {{\n    {vert_body}\n}};\n"
    f"static const size_t overlay_vert_spv_len = {vert_len};\n"
    f"static const uint32_t overlay_frag_spv[] = {{\n    {frag_body}\n}};\n"
    f"static const size_t overlay_frag_spv_len = {frag_len};\n"
)
PY
}

VENDORED_HEADER="$ROOT/shaders/overlay_shaders.h"
if compile_shader "$ROOT/shaders/overlay.vert" "$GEN/overlay.vert.spv" \
  && compile_shader "$ROOT/shaders/overlay.frag" "$GEN/overlay.frag.spv"; then
  generate_shader_header "$GEN/overlay.vert.spv" "$GEN/overlay.frag.spv" "$GEN/overlay_shaders.h"
elif [[ -f "$VENDORED_HEADER" ]]; then
  cp "$VENDORED_HEADER" "$GEN/overlay_shaders.h"
else
  echo "Need glslangValidator or glslc to compile overlay shaders (or commit shaders/overlay_shaders.h)" >&2
  exit 1
fi

# Headers only: linking libavcodec would DT_NEEDED Ubuntu's SONAME and break Fedora/CachyOS.
PKGS="vulkan x11 xfixes xext"
AV_CFLAGS="$(pkg-config --cflags libavcodec libavutil)"
CFLAGS="$(pkg-config --cflags $PKGS) $AV_CFLAGS"
LIBS="$(pkg-config --libs $PKGS)"
VK_HEADERS="$ROOT/../../third_party"

cc -shared -fPIC -O2 -std=c11 -DVK_USE_PLATFORM_XLIB_KHR \
  -I"$JAVA_HOME/include" \
  -I"$JAVA_HOME/include/linux" \
  -I"$ROOT" \
  -I"$GEN" \
  -I"$VK_HEADERS" \
  $CFLAGS \
  "$ROOT"/andy_mirror_frame.c \
  "$ROOT"/andy_mirror_x11.c \
  "$ROOT"/andy_mirror_av.c \
  "$ROOT"/andy_mirror_nvdec.c \
  "$ROOT"/andy_mirror_vk.c \
  "$ROOT"/andy_mirror_hub_linux.c \
  "$ROOT"/andy_mirror_jni_linux.c \
  $LIBS \
  -ldl -lm -lpthread \
  -Wl,--as-needed \
  -o "$OUT"

if readelf -d "$OUT" | grep -E 'NEEDED.*libav(codec|util)'; then
  echo "andy-mirror: libandy-mirror-jni.so must not DT_NEEDED libavcodec/libavutil" >&2
  exit 1
fi
