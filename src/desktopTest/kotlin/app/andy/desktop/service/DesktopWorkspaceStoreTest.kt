package app.andy.desktop.service

import app.andy.model.AgentNotificationTiming
import app.andy.model.WorkspaceState
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopWorkspaceStoreTest {
    @Test
    fun roundTripsAgentNotificationPreferencesAndFallsBackForUnknownSound() = runBlocking {
        val file = createTempDirectory("andy-workspace").toFile().resolve("workspace.properties")
        val saved = WorkspaceState(
            agentOsNotificationsEnabled = false,
            agentNotificationSoundEnabled = false,
            agentIconBadgeEnabled = false,
            agentNotificationTiming = AgentNotificationTiming.Always,
            agentNotificationSoundId = "ping",
            tintId = "violet",
        )
        DesktopWorkspaceStore(file).save(saved)
        assertEquals(saved, DesktopWorkspaceStore(file).load())

        file.writeText(file.readText().replace("agentNotificationSoundId=ping", "agentNotificationSoundId=unknown"))
        assertEquals("chime", DesktopWorkspaceStore(file).load().agentNotificationSoundId)

        file.writeText(file.readText().replace("tintId=violet", "tintId=not-a-tint"))
        assertEquals("andy-blue", DesktopWorkspaceStore(file).load().tintId)
    }
}
