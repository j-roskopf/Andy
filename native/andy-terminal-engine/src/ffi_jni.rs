//! Hand-rolled JNI boundary for the spike engine + trivial round-trip probe.
//!
//! Package: `app.andy.terminal.rust`
//! Classes: `JniRoundTrip`, `RustTerminalEngine`

use std::collections::HashMap;
use std::sync::atomic::{AtomicI64, Ordering};

use jni::objects::{JByteArray, JClass};
use jni::sys::{jboolean, jint, jlong, jstring};
use jni::JNIEnv;
use parking_lot::Mutex;

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
