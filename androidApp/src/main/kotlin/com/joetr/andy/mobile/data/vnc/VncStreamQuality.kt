package com.joetr.andy.mobile.data.vnc

/**
 * Client-advertised VNC stream knobs. Compression level is an RFB pseudo-encoding
 * (`-256 + level`); JPEG quality (`-32 + level`) applies when the server picks Tight.
 *
 * Lower compression / JPEG values favor encode speed and lower latency over Tailscale;
 * higher values shrink the bitstream at the cost of server CPU.
 */
enum class VncStreamQuality(
    val label: String,
    val compressionLevel: Int,
    val jpegQuality: Int,
) {
    /** Fastest encode — best when the phone feels a frame behind. */
    Smooth("Smooth", compressionLevel = 2, jpegQuality = 4),

    /** Default balance of size vs latency. */
    Balanced("Balanced", compressionLevel = 6, jpegQuality = 6),

    /** Smallest updates — better on tight links, more encode cost. */
    Compact("Compact", compressionLevel = 9, jpegQuality = 8),
    ;

    /** RFB CompressionLevelN pseudo-encoding. */
    val compressionEncoding: Int get() = -256 + compressionLevel.coerceIn(0, 9)

    /** RFB QualityLevelN pseudo-encoding (Tight JPEG). */
    val jpegQualityEncoding: Int get() = -32 + jpegQuality.coerceIn(0, 9)

    companion object {
        fun fromId(id: String?): VncStreamQuality =
            entries.firstOrNull { it.name.equals(id, ignoreCase = true) } ?: Balanced
    }
}
