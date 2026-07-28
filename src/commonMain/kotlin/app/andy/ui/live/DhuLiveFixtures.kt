package app.andy.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.andy.service.DhuCheckStatus
import app.andy.service.DhuConsoleState
import app.andy.service.DhuHostKind
import app.andy.service.DhuReadiness
import app.andy.service.DhuReadinessCheck
import app.andy.service.DhuSession
import app.andy.service.DhuSessionPhase
import app.andy.service.UnavailableDhuService
import app.andy.ui.theme.AndyColors
import app.andy.ui.theme.TextPrimary
import app.andy.ui.theme.TextSecondary

/**
 * Desktop UI fixtures for Android Auto DHU (toggle / status / console).
 * Screenshot baselines are recorded only when explicitly requested.
 */
internal object DhuLiveFixtures {
    val readyReadiness = DhuReadiness(
        hostKind = DhuHostKind.MacOs,
        checks = listOf(
            DhuReadinessCheck("host", "Desktop host", DhuCheckStatus.Ok, "MacOs"),
            DhuReadinessCheck("sdk", "Android SDK", DhuCheckStatus.Ok, "/Android/sdk"),
            DhuReadinessCheck("auto_extra", "SDK extras/google/auto", DhuCheckStatus.Ok, "/Android/sdk/extras/google/auto"),
            DhuReadinessCheck("executable", "DHU executable", DhuCheckStatus.Ok, "/Android/sdk/extras/google/auto/desktop-head-unit"),
            DhuReadinessCheck("libusb", "Colocated libusb", DhuCheckStatus.Ok, "/Android/sdk/extras/google/auto/libusb-1.0.dylib"),
            DhuReadinessCheck("capture_permission", "DHU display", DhuCheckStatus.Ok, "Separate desktop-head-unit window (not embedded in Andy)"),
            DhuReadinessCheck("adb", "ADB", DhuCheckStatus.Ok, "/Android/sdk/platform-tools/adb"),
            DhuReadinessCheck("device", "Selected device", DhuCheckStatus.Ok, "emulator-5554"),
            DhuReadinessCheck("link", "DHU link", DhuCheckStatus.Ok, "ADB transport; requires Head Unit Server on TCP 5277"),
            DhuReadinessCheck("head_unit_server", "Head Unit Server", DhuCheckStatus.Ok, "Device listening on TCP 5277"),
        ),
        autoDir = "/Android/sdk/extras/google/auto",
        executablePath = "/Android/sdk/extras/google/auto/desktop-head-unit",
        adbPath = "/Android/sdk/platform-tools/adb",
        serial = "emulator-5554",
    )

    val missingExtraReadiness = readyReadiness.copy(
        checks = readyReadiness.checks.map {
            if (it.id == "auto_extra") {
                it.copy(
                    status = DhuCheckStatus.Missing,
                    detail = "extras/google/auto not found",
                    remediation = "Install extras;google;auto via sdkmanager.",
                )
            } else {
                it
            }
        },
        autoDir = null,
        executablePath = null,
    )

    val runningSession = DhuSession(
        serial = "emulator-5554",
        localPort = 19001,
        phase = DhuSessionPhase.Running,
        message = "DHU running over ADB in its own window",
        captureAvailable = false,
        processAlive = true,
    )

    val failedSession = runningSession.copy(
        phase = DhuSessionPhase.Failed,
        message = "DHU exited",
        processAlive = false,
    )

    val consoleState = DhuConsoleState(
        lines = listOf(
            "\$ desktop-head-unit --config=/tmp/andy-dhu.ini --input=touch --adb=19001",
            "Connecting to Android Auto…",
            "> day",
            "day mode",
        ),
        history = listOf("day", "night"),
    )
}

@Composable
internal fun DhuToggleFixture(
    enabled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .width(480.dp)
            .background(AndyColors.Neutral900)
            .padding(16.dp),
    ) {
        AndroidAutoToggle(
            enabled = enabled,
            onEnabledChange = {},
            readyHint = if (enabled) "DHU running in its own window — interact there; console below" else null,
        )
    }
}

@Composable
internal fun DhuStatusFixture(modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(480.dp)
            .height(220.dp)
            .background(AndyColors.Neutral850)
            .padding(12.dp),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).fillMaxSize().background(Color.Black).padding(12.dp)) {
                Text("Phone mirror", color = TextPrimary, fontSize = 12.sp)
            }
            Text("DHU runs in a separate desktop-head-unit window", color = TextSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
internal fun DhuErrorFixture(modifier: Modifier = Modifier) {
    Column(
        modifier
            .width(520.dp)
            .background(AndyColors.Neutral900)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Android Auto not ready", color = TextPrimary, fontWeight = FontWeight.SemiBold)
        DhuLiveFixtures.missingExtraReadiness.blocking.forEach { check ->
            Text("• ${check.label}: ${check.detail}", color = TextSecondary, fontSize = 12.sp)
            check.remediation?.let { Text("  $it", color = TextSecondary, fontSize = 11.sp) }
        }
        DhuConsolePanel(
            dhu = UnavailableDhuService,
            console = DhuConsoleState(lines = listOf("ADB forward failed")),
            session = DhuLiveFixtures.failedSession,
            readiness = DhuLiveFixtures.missingExtraReadiness,
        )
    }
}

@Composable
internal fun DhuConsoleFixture(modifier: Modifier = Modifier) {
    DhuConsolePanel(
        dhu = UnavailableDhuService,
        console = DhuLiveFixtures.consoleState,
        session = DhuLiveFixtures.runningSession,
        readiness = DhuLiveFixtures.readyReadiness,
        modifier = modifier.width(560.dp),
    )
}
