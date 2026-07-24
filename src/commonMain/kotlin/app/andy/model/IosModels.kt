package app.andy.model

enum class IosTargetKind { Simulator, Physical }

enum class IosTargetState {
    Booted,
    Shutdown,
    Unavailable,
    Unknown,
}

enum class IosTransport {
    Usb,
    Network,
    Unknown,
}

data class IosTarget(
    val udid: String,
    val displayName: String,
    val kind: IosTargetKind,
    val state: IosTargetState,
    val runtime: String? = null,
    val model: String? = null,
    val transport: IosTransport = IosTransport.Unknown,
    /** CoreDevice identifier from devicectl; AVCapture may use this instead of the hardware UDID. */
    val coreDeviceIdentifier: String? = null,
) {
    val isMirrorable: Boolean
        get() = when (kind) {
            IosTargetKind.Simulator -> state == IosTargetState.Booted || state == IosTargetState.Shutdown
            IosTargetKind.Physical -> state != IosTargetState.Unavailable && transport == IosTransport.Usb
        }

    /** Whether Live should auto-connect to this target (paired USB iPhones, booted simulators). */
    val isLiveReady: Boolean
        get() = when (kind) {
            IosTargetKind.Physical -> isMirrorable
            IosTargetKind.Simulator -> state == IosTargetState.Booted
        }

    val supportsInput: Boolean
        get() = kind == IosTargetKind.Simulator && state == IosTargetState.Booted
}

/** Converts device-pixel coordinates from the mirror surface into normalized 0..1 touch space. */
fun iosNormalizedTouchCoordinates(
    pixelX: Int,
    pixelY: Int,
    widthPoints: Int,
    heightPoints: Int,
): Pair<Float, Float> {
    val width = widthPoints.coerceAtLeast(1)
    val height = heightPoints.coerceAtLeast(1)
    return pixelX.toFloat() / width to pixelY.toFloat() / height
}
