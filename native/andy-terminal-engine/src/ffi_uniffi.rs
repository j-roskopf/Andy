//! UniFFI boundary probe (trivial round-trip only).
//!
//! Used to compare UniFFI's generated Kotlin/JNA path against hand-rolled JNI
//! before choosing a technology for the full engine API. Not used by the
//! production terminal path.

/// Trivial round-trip used by the UniFFI vs JNI boundary probe.
#[uniffi::export]
pub fn uniffi_round_trip_add(a: i32, b: i32) -> i32 {
    a.saturating_add(b)
}
