package app.andy.terminal.rust

/**
 * Trivial JNI round-trip probe for the Phase-0 FFI decision.
 * Compare with UniFFI's generated [uniffi.andy_terminal_engine.uniffiRoundTripAdd].
 */
object JniRoundTrip {
    init {
        RustTerminalNative.ensureLoaded()
    }

    @JvmStatic
    external fun nativeAdd(a: Int, b: Int): Int

    fun add(a: Int, b: Int): Int = nativeAdd(a, b)
}
