package app.andy.service

import app.andy.model.ComputerAction
import app.andy.model.ComputerActionResult
import app.andy.model.ComputerActionVerdict
import app.andy.model.ComputerUseActionLogEntry
import app.andy.model.ComputerUseArmRequest
import app.andy.model.ComputerUseCapabilities
import app.andy.model.ComputerUseHudState
import app.andy.model.ComputerUsePermissionStatus
import app.andy.model.ComputerUsePlatform
import app.andy.model.ComputerUseScope
import app.andy.model.HostAccessibilityElement
import app.andy.model.HostAccessibilityTree
import app.andy.model.HostScreenshotResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object UnavailableComputerUseService : ComputerUseService {
    private val _hud = MutableStateFlow<ComputerUseHudState?>(null)
    override val hud: StateFlow<ComputerUseHudState?> = _hud.asStateFlow()
    private val _actionLog = MutableStateFlow<List<ComputerUseActionLogEntry>>(emptyList())
    override val actionLog: StateFlow<List<ComputerUseActionLogEntry>> = _actionLog.asStateFlow()
    override val pendingArm: StateFlow<ComputerUseArmRequest?> =
        MutableStateFlow<ComputerUseArmRequest?>(null).asStateFlow()

    override suspend fun capabilities() = ComputerUseCapabilities(
        platform = ComputerUsePlatform.Unsupported,
        available = false,
        unavailableReason = "Computer use is unavailable in this runtime.",
        accessibility = ComputerUsePermissionStatus.Unavailable,
        screenRecording = ComputerUsePermissionStatus.Unavailable,
    )

    override suspend fun requestControl(
        scope: ComputerUseScope,
        attended: Boolean,
        profileId: String?,
        wallClockCapSeconds: Int?,
        ownerTaskId: String?,
    ) = ComputerUseArmResult.Denied("Computer use is unavailable in this runtime.")

    override fun decideArm(requestId: String, accepted: Boolean) = Unit

    override suspend fun releaseControl(sessionId: String?) =
        ComputerActionResult(ComputerActionVerdict.Denied, "Computer use unavailable")

    override fun panic(reason: String) = Unit

    override suspend fun dump(appName: String?, menus: Boolean): HostAccessibilityTree =
        error("Computer use unavailable")

    override suspend fun findElements(query: String, role: String?, limit: Int): List<HostAccessibilityElement> =
        emptyList()

    override suspend fun screenshot(appName: String?): HostScreenshotResult =
        error("Computer use unavailable")

    override suspend fun act(action: ComputerAction, confirmHighConsequence: Boolean) =
        ComputerActionResult(ComputerActionVerdict.Denied, "Computer use unavailable")

    override suspend fun confirmPendingAction() =
        ComputerActionResult(ComputerActionVerdict.Denied, "Computer use unavailable")

    override suspend fun discardPendingAction() =
        ComputerActionResult(ComputerActionVerdict.Denied, "Computer use unavailable")
}
