package app.andy.desktop

import app.andy.service.IosTargetRegistry

internal data class PopOutMirrorWindow(
    val targetId: String,
    val preferPrimaryMirror: Boolean = false,
)

/**
 * Picks the single pop-out that may use the shared primary mirror / Metal presenter.
 * iOS requires Metal; when it is open every other pop-out must use a CPU mirror session.
 */
internal fun gpuPopOutTargetId(
    windows: Collection<PopOutMirrorWindow>,
    primaryMirrorSerial: String?,
): String? {
    if (windows.isEmpty()) return null
    windows.firstOrNull { IosTargetRegistry.isIosTarget(it.targetId) }?.let { return it.targetId }
    if (windows.size != 1) return null
    val only = windows.first()
    return only.targetId.takeIf { only.preferPrimaryMirror && only.targetId == primaryMirrorSerial }
}
