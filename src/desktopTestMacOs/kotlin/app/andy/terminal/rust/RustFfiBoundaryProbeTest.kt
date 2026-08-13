package app.andy.terminal.rust

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import uniffi.andy_terminal_engine.uniffiRoundTripAdd

/**
 * Phase-0 FFI technology probe: prove both JNI and UniFFI can do a trivial
 * Kotlin → Rust → Kotlin round-trip before picking one for the engine API.
 */
class RustFfiBoundaryProbeTest {
    @Test
    fun jniRoundTripAdd() {
        assertTrue(RustTerminalNative.isAvailable(), "native library must load")
        assertEquals(7, JniRoundTrip.add(3, 4))
        assertEquals(Int.MAX_VALUE, JniRoundTrip.add(Int.MAX_VALUE, 1))
    }

    @Test
    fun uniffiRoundTripAdd() {
        // Loads the same cdylib via JNA (UniFFI's desktop Kotlin backend).
        assertTrue(RustTerminalNative.isAvailable(), "native library must load before UniFFI")
        assertEquals(7, uniffiRoundTripAdd(3, 4))
        assertEquals(Int.MAX_VALUE, uniffiRoundTripAdd(Int.MAX_VALUE, 1))
    }
}
