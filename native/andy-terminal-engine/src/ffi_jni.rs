//! Hand-rolled JNI boundary for the spike engine + trivial round-trip probe.
//!
//! Package: `app.andy.terminal.rust`
//! Classes: `JniRoundTrip`, `RustTerminalEngine`

use std::collections::HashMap;
use std::sync::atomic::{AtomicI64, Ordering};

use jni::objects::{JByteArray, JClass, JIntArray};
use jni::sys::{jboolean, jint, jlong, jstring};
use jni::JNIEnv;
use parking_lot::Mutex;

use crate::color::{
    ColorPalette, ATTR_BOLD, ATTR_DIM, ATTR_INVERSE, ATTR_ITALIC, ATTR_STRIKE, ATTR_UNDERLINE,
};
use crate::TerminalEngine;

static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);
static ENGINES: Mutex<Option<HashMap<i64, TerminalEngine>>> = Mutex::new(None);

fn with_engines<R>(f: impl FnOnce(&mut HashMap<i64, TerminalEngine>) -> R) -> R {
    let mut guard = ENGINES.lock();
    if guard.is_none() {
        *guard = Some(HashMap::new());
    }
    f(guard.as_mut().unwrap())
}

/// JNI trivial round-trip (compare with UniFFI's `uniffi_round_trip_add`).
#[no_mangle]
pub extern "system" fn Java_app_andy_terminal_rust_JniRoundTrip_nativeAdd(
    _env: JNIEnv,
    _class: JClass,
    a: jint,
    b: jint,
) -> jint {
    a.saturating_add(b)
}

#[no_mangle]
pub extern "system" fn Java_app_andy_terminal_rust_RustTerminalEngine_nativeCreate(
    _env: JNIEnv,
    _class: JClass,
    columns: jint,
    rows: jint,
) -> jlong {
    let columns = columns.max(1) as usize;
    let rows = rows.max(1) as usize;
    let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
    with_engines(|map| {
        map.insert(handle, TerminalEngine::new(columns, rows));
    });
    handle
}

#[no_mangle]
pub extern "system" fn Java_app_andy_terminal_rust_RustTerminalEngine_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    with_engines(|map| {
        map.remove(&handle);
    });
}

#[no_mangle]
pub extern "system" fn Java_app_andy_terminal_rust_RustTerminalEngine_nativeAdvance<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    bytes: JByteArray<'local>,
) {
    let Ok(data) = env.convert_byte_array(&bytes) else {
        return;
    };
    with_engines(|map| {
        if let Some(engine) = map.get_mut(&handle) {
            engine.advance(&data);
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_app_andy_terminal_rust_RustTerminalEngine_nativeResize(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    columns: jint,
    rows: jint,
) {
    let columns = columns.max(1) as usize;
    let rows = rows.max(1) as usize;
    with_engines(|map| {
        if let Some(engine) = map.get_mut(&handle) {
            engine.resize(columns, rows);
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_app_andy_terminal_rust_RustTerminalEngine_nativeIsAltScreen(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    with_engines(|map| {
        map.get(&handle)
            .map(|e| e.is_alt_screen() as jboolean)
            .unwrap_or(0)
    })
}

#[no_mangle]
pub extern "system" fn Java_app_andy_terminal_rust_RustTerminalEngine_nativeSyncBufferedBytes(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    with_engines(|map| {
        map.get(&handle)
            .map(|e| e.sync_buffered_bytes() as jint)
            .unwrap_or(0)
    })
}

#[no_mangle]
pub extern "system" fn Java_app_andy_terminal_rust_RustTerminalEngine_nativeViewportText<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    let text = with_engines(|map| {
        map.get(&handle)
            .map(|e| e.viewport_text())
            .unwrap_or_default()
    });
    env.new_string(text)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Returns a Java String of exactly `rows * columns` chars (row-major, spaces for empties).
#[no_mangle]
pub extern "system" fn Java_app_andy_terminal_rust_RustTerminalEngine_nativeGridChars<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    let text = with_engines(|map| {
        map.get(&handle)
            .map(|e| {
                let snap = e.snapshot();
                snap.cells.iter().map(|c| c.ch).collect::<String>()
            })
            .unwrap_or_default()
    });
    env.new_string(text)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_app_andy_terminal_rust_RustTerminalEngine_nativeCursorRow(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    with_engines(|map| {
        map.get(&handle)
            .map(|e| e.snapshot().cursor.row as jint)
            .unwrap_or(0)
    })
}

#[no_mangle]
pub extern "system" fn Java_app_andy_terminal_rust_RustTerminalEngine_nativeCursorCol(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    with_engines(|map| {
        map.get(&handle)
            .map(|e| e.snapshot().cursor.col as jint)
            .unwrap_or(0)
    })
}

#[no_mangle]
pub extern "system" fn Java_app_andy_terminal_rust_RustTerminalEngine_nativeColumns(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    with_engines(|map| {
        map.get(&handle)
            .map(|e| e.size().columns as jint)
            .unwrap_or(0)
    })
}

#[no_mangle]
pub extern "system" fn Java_app_andy_terminal_rust_RustTerminalEngine_nativeRows(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    with_engines(|map| {
        map.get(&handle)
            .map(|e| e.size().rows as jint)
            .unwrap_or(0)
    })
}

/// Returns whether cell at (row,col) is bold (spike attr probe).
#[no_mangle]
pub extern "system" fn Java_app_andy_terminal_rust_RustTerminalEngine_nativeCellBold(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    row: jint,
    col: jint,
) -> jboolean {
    with_engines(|map| {
        let Some(engine) = map.get(&handle) else {
            return 0;
        };
        let snap = engine.snapshot();
        let row = row as usize;
        let col = col as usize;
        if row >= snap.rows || col >= snap.columns {
            return 0;
        }
        snap.cells[row * snap.columns + col].attrs.bold as jboolean
    })
}

#[no_mangle]
pub extern "system" fn Java_app_andy_terminal_rust_RustTerminalEngine_nativeStopSync(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    with_engines(|map| {
        if let Some(engine) = map.get_mut(&handle) {
            engine.stop_sync();
        }
    });
}

/// Fills preallocated arrays with a full viewport snapshot.
///
/// `meta` length ≥ 8:
/// `[columns, rows, cursorRow, cursorCol, altScreen, syncBufferedBytes, displayOffset, historySize]`.
/// `codePoints` / `fgArgb` / `bgArgb` / `attrs` length ≥ `columns * rows`.
/// `codePoints` stores Unicode scalar values (supports supplementary-plane glyphs).
///
/// Returns `0` on success, `-1` on error (bad handle / short buffers).
#[no_mangle]
pub extern "system" fn Java_app_andy_terminal_rust_RustTerminalEngine_nativeFillSnapshot<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    code_points: JIntArray<'local>,
    fg_argb: JIntArray<'local>,
    bg_argb: JIntArray<'local>,
    attrs: JByteArray<'local>,
    meta: JIntArray<'local>,
) -> jint {
    let snap = with_engines(|map| map.get(&handle).map(|e| e.snapshot()));
    let Some(snap) = snap else {
        return -1;
    };
    let cell_count = snap.columns * snap.rows;
    let Ok(code_points_len) = env.get_array_length(&code_points) else {
        return -1;
    };
    let Ok(fg_len) = env.get_array_length(&fg_argb) else {
        return -1;
    };
    let Ok(bg_len) = env.get_array_length(&bg_argb) else {
        return -1;
    };
    let Ok(attrs_len) = env.get_array_length(&attrs) else {
        return -1;
    };
    let Ok(meta_len) = env.get_array_length(&meta) else {
        return -1;
    };
    if code_points_len < cell_count as i32
        || fg_len < cell_count as i32
        || bg_len < cell_count as i32
        || attrs_len < cell_count as i32
        || meta_len < 8
    {
        return -1;
    }

    let mut code_point_buf = vec![0i32; cell_count];
    let mut fg_buf = vec![0i32; cell_count];
    let mut bg_buf = vec![0i32; cell_count];
    let mut attr_buf = vec![0i8; cell_count];

    for (i, cell) in snap.cells.iter().enumerate() {
        code_point_buf[i] = cell.ch as u32 as i32;
        fg_buf[i] = cell.fg_argb as i32;
        bg_buf[i] = cell.bg_argb as i32;
        let mut a = 0u8;
        if cell.attrs.bold {
            a |= ATTR_BOLD;
        }
        if cell.attrs.italic {
            a |= ATTR_ITALIC;
        }
        if cell.attrs.underline {
            a |= ATTR_UNDERLINE;
        }
        if cell.attrs.inverse {
            a |= ATTR_INVERSE;
        }
        if cell.attrs.dim {
            a |= ATTR_DIM;
        }
        if cell.attrs.strikethrough {
            a |= ATTR_STRIKE;
        }
        attr_buf[i] = a as i8;
    }

    if env.set_int_array_region(&code_points, 0, &code_point_buf).is_err() {
        return -1;
    }
    if env.set_int_array_region(&fg_argb, 0, &fg_buf).is_err() {
        return -1;
    }
    if env.set_int_array_region(&bg_argb, 0, &bg_buf).is_err() {
        return -1;
    }
    if env.set_byte_array_region(&attrs, 0, &attr_buf).is_err() {
        return -1;
    }
    let meta_buf = [
        snap.columns as i32,
        snap.rows as i32,
        snap.cursor.row,
        snap.cursor.col as i32,
        if snap.alt_screen { 1 } else { 0 },
        snap.sync_buffered_bytes as i32,
        snap.display_offset as i32,
        snap.history_size as i32,
    ];
    if env.set_int_array_region(&meta, 0, &meta_buf).is_err() {
        return -1;
    }
    0
}

/// `palette` length ≥ 19: `[fg, bg, cursor, ansi0..ansi15]` as opaque ARGB ints.
#[no_mangle]
pub extern "system" fn Java_app_andy_terminal_rust_RustTerminalEngine_nativeSetPalette<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    palette: JIntArray<'local>,
) -> jint {
    let Ok(len) = env.get_array_length(&palette) else {
        return -1;
    };
    if len < 19 {
        return -1;
    }
    let mut buf = [0i32; 19];
    if env.get_int_array_region(&palette, 0, &mut buf).is_err() {
        return -1;
    }
    let mut ansi16 = [0u32; 16];
    for i in 0..16 {
        ansi16[i] = buf[3 + i] as u32;
    }
    let color_palette = ColorPalette {
        foreground: buf[0] as u32,
        background: buf[1] as u32,
        cursor: buf[2] as u32,
        ansi16,
    };
    with_engines(|map| {
        if let Some(engine) = map.get_mut(&handle) {
            engine.set_palette(color_palette);
            0
        } else {
            -1
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_app_andy_terminal_rust_RustTerminalEngine_nativeScrollDisplay(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    delta: jint,
) {
    with_engines(|map| {
        if let Some(engine) = map.get_mut(&handle) {
            engine.scroll_display_delta(delta);
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_app_andy_terminal_rust_RustTerminalEngine_nativeScrollToBottom(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    with_engines(|map| {
        if let Some(engine) = map.get_mut(&handle) {
            engine.scroll_display_bottom();
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_app_andy_terminal_rust_RustTerminalEngine_nativeBracketedPasteEnabled(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    with_engines(|map| {
        map.get(&handle)
            .map(|e| e.bracketed_paste_enabled() as jboolean)
            .unwrap_or(0)
    })
}

#[no_mangle]
pub extern "system" fn Java_app_andy_terminal_rust_RustTerminalEngine_nativeMouseFlags(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    with_engines(|map| {
        map.get(&handle)
            .map(|e| e.mouse_flags() as jint)
            .unwrap_or(0)
    })
}

#[no_mangle]
pub extern "system" fn Java_app_andy_terminal_rust_RustTerminalEngine_nativeDisplayOffset(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    with_engines(|map| {
        map.get(&handle)
            .map(|e| e.display_offset() as jint)
            .unwrap_or(0)
    })
}
