package app.andy.service

/** Conservative stream defaults when Live runs over an SSH-tunneled adb/scrcpy path. */
object RemoteMirrorTuning {
    const val MAX_SIZE = 720
    const val BIT_RATE = 4_000_000
    const val MAX_FPS = 30
}

/**
 * Caps mirror quality for high-RTT remote tunnels. Native (0) is treated as uncapped local
 * resolution and is replaced with [RemoteMirrorTuning.MAX_SIZE] so SSH is not asked to carry
 * full-device bitstreams.
 */
fun MirrorVideoConfig.forRemoteTunnel(): MirrorVideoConfig {
    val cappedMaxSize = when {
        maxSize == 0 -> RemoteMirrorTuning.MAX_SIZE
        maxSize > RemoteMirrorTuning.MAX_SIZE -> RemoteMirrorTuning.MAX_SIZE
        else -> maxSize
    }
    return copy(
        maxSize = cappedMaxSize,
        bitRate = bitRate.coerceAtMost(RemoteMirrorTuning.BIT_RATE),
        maxFps = maxFps.coerceAtMost(RemoteMirrorTuning.MAX_FPS),
    )
}
