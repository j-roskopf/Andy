package app.andy.model

import kotlinx.serialization.Serializable

/** Host platform for computer-use capability reporting. */
enum class ComputerUsePlatform {
    MacOs,
    Windows,
    LinuxX11,
    LinuxWayland,
    Unsupported,
}

/** Permission / readiness state for a single OS grant. */
enum class ComputerUsePermissionStatus {
    Granted,
    Denied,
    NotDetermined,
    Unavailable,
    NotRequired,
}

@Serializable
data class ComputerUseCapabilities(
    val platform: ComputerUsePlatform,
    val available: Boolean,
    /** Human-readable reason when [available] is false. */
    val unavailableReason: String? = null,
    val accessibility: ComputerUsePermissionStatus = ComputerUsePermissionStatus.Unavailable,
    val screenRecording: ComputerUsePermissionStatus = ComputerUsePermissionStatus.Unavailable,
    /** True when Accessibility is granted to the app bundle but not this process (launchd case). */
    val processOutsideAppChain: Boolean = false,
    val masterSwitchEnabled: Boolean = false,
    val armed: Boolean = false,
    val sessionId: String? = null,
    val scopeAppNames: List<String> = emptyList(),
    val attended: Boolean = true,
    val accessibilitySettingsUrl: String? = null,
    val screenRecordingSettingsUrl: String? = null,
)

/** App set a computer-use session may drive. */
@Serializable
data class ComputerUseScope(
    val appNames: List<String> = emptyList(),
    /** When true, scope is the whole desktop (explicit opt-in). */
    val wholeDesktop: Boolean = false,
)

@Serializable
data class ComputerUseGrantProfile(
    val id: String,
    val name: String,
    val scope: ComputerUseScope,
    val attended: Boolean = true,
    /** Wall-clock ceiling in seconds. */
    val wallClockCapSeconds: Int = 600,
    /** Max actions per minute. */
    val rateCapPerMinute: Int = 120,
    /** Extra high-consequence label substrings (case-insensitive). */
    val highConsequenceLabels: List<String> = emptyList(),
)

@Serializable
data class HostElementBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    fun packed(): String = "$x,$y,$width,$height"

    companion object {
        fun parse(packed: String): HostElementBounds? {
            val parts = packed.split(',')
            if (parts.size != 4) return null
            val nums = parts.map { it.toIntOrNull() ?: return null }
            return HostElementBounds(nums[0], nums[1], nums[2], nums[3])
        }
    }
}

/**
 * Compact host accessibility element for agent payloads.
 * Short-key JSON is produced by the dump pipeline; this is the typed form.
 */
@Serializable
data class HostAccessibilityElement(
    val id: String,
    val role: String,
    val label: String? = null,
    val bounds: HostElementBounds? = null,
    val pressable: Boolean = false,
    val secure: Boolean = false,
    val kind: HostElementKind = HostElementKind.Other,
    val subrole: String? = null,
    val value: String? = null,
)

enum class HostElementKind {
    Control,
    Text,
    Other,
}

@Serializable
data class HostAccessibilityTree(
    val appName: String,
    val elements: List<HostAccessibilityElement>,
    val truncated: Boolean = false,
    val payloadBytes: Int = 0,
    val windowCount: Int = 0,
    /** True when CGWindowList shows windows but AX reported none (or vice versa). */
    val windowsOpenOnScreen: Boolean = true,
    val untrusted: Boolean = true,
)

@Serializable
data class HostScreenshotResult(
    val pngBase64: String,
    val scaleFactor: Double,
    val originX: Int,
    val originY: Int,
    val width: Int,
    val height: Int,
    val untrusted: Boolean = true,
)

enum class ComputerActionVerdict {
    Succeeded,
    Failed,
    Unverified,
    Denied,
    NeedsConfirmation,
}

@Serializable
data class ComputerActionResult(
    val verdict: ComputerActionVerdict,
    val message: String,
    val axErrorCode: Int? = null,
    val elementId: String? = null,
    val needsConfirmation: Boolean = false,
    val confirmationReason: String? = null,
)

sealed interface ComputerAction {
    data class Tap(
        val elementId: String? = null,
        val x: Int? = null,
        val y: Int? = null,
        val forceSynthetic: Boolean = false,
    ) : ComputerAction

    data class InputText(
        val text: String,
        val elementId: String? = null,
    ) : ComputerAction

    data class PressKey(
        val key: String,
        val modifiers: List<String> = emptyList(),
    ) : ComputerAction

    data class Scroll(
        val elementId: String? = null,
        val x: Int? = null,
        val y: Int? = null,
        val deltaX: Int = 0,
        val deltaY: Int = 0,
    ) : ComputerAction

    data class Drag(
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int,
        val durationMs: Int = 300,
    ) : ComputerAction
}

/** Live HUD / session snapshot for the always-on-top overlay. */
data class ComputerUseHudState(
    val sessionId: String,
    val scopeAppNames: List<String>,
    val attended: Boolean,
    val startedAtEpochMs: Long,
    val actions: List<ComputerUseActionLogEntry> = emptyList(),
    val lastError: String? = null,
)

@Serializable
data class ComputerUseActionLogEntry(
    val atEpochMs: Long,
    val summary: String,
    val verdict: ComputerActionVerdict,
)
