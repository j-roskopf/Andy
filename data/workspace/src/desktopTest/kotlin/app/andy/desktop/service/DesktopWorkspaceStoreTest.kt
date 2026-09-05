package app.andy.desktop.service

import app.andy.model.AgentMessageDeliveryMode
import app.andy.model.AgentNotificationTiming
import app.andy.model.IntentDraft
import app.andy.model.IntentMode
import app.andy.model.SavedDockLayout
import app.andy.model.SavedDockPane
import app.andy.model.SavedDockTab
import app.andy.model.SavedDockTabKind
import app.andy.model.SavedLiveNode
import app.andy.model.SavedSplitAxis
import app.andy.model.SavedTerminalNode
import app.andy.model.SavedTerminalSession
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

        val withVoiceShortcut = saved.copy(voiceDictationShortcut = "69|0|1|0|0")
        DesktopWorkspaceStore(file).save(withVoiceShortcut)
        assertEquals("69|0|1|0|0", DesktopWorkspaceStore(file).load().voiceDictationShortcut)

        DesktopWorkspaceStore(file).save(withVoiceShortcut.copy(voiceDictationShortcut = null))
        assertEquals(null, DesktopWorkspaceStore(file).load().voiceDictationShortcut)

        DesktopWorkspaceStore(file).save(saved.copy(keepAgentSessionsOnShutdown = true))
        assertEquals(true, DesktopWorkspaceStore(file).load().keepAgentSessionsOnShutdown)

        DesktopWorkspaceStore(file).save(saved.copy(autoBugCaptureEnabled = true))
        assertEquals(true, DesktopWorkspaceStore(file).load().autoBugCaptureEnabled)

        val messaging = saved.copy(agentMessageDeliveryMode = AgentMessageDeliveryMode.Queue)
        DesktopWorkspaceStore(file).save(messaging)
        assertEquals(AgentMessageDeliveryMode.Queue, DesktopWorkspaceStore(file).load().agentMessageDeliveryMode)

        DesktopWorkspaceStore(file).save(saved.copy(agentPinPriorityChats = true))
        assertEquals(true, DesktopWorkspaceStore(file).load().agentPinPriorityChats)

        DesktopWorkspaceStore(file).save(saved.copy(agentAdoptProviderSessionTitles = false))
        assertEquals(false, DesktopWorkspaceStore(file).load().agentAdoptProviderSessionTitles)
        assertEquals(true, DesktopWorkspaceStore(createTempDirectory("andy-workspace-adopt-default").toFile().resolve("missing.properties")).load().agentAdoptProviderSessionTitles)

        DesktopWorkspaceStore(file).save(
            saved.copy(
                agentTranscriptAutoExpandThinking = true,
                agentTranscriptAutoExpandTools = false,
            ),
        )
        val transcriptExpand = DesktopWorkspaceStore(file).load()
        assertEquals(true, transcriptExpand.agentTranscriptAutoExpandThinking)
        assertEquals(false, transcriptExpand.agentTranscriptAutoExpandTools)

        val retention = saved.copy(
            retentionCleanupEnabled = false,
            retentionCompressArchiveAfterDays = 12,
            retentionPermanentDeleteAfterDays = 45,
        )
        DesktopWorkspaceStore(file).save(retention)
        val loadedRetention = DesktopWorkspaceStore(file).load()
        assertEquals(false, loadedRetention.retentionCleanupEnabled)
        assertEquals(12, loadedRetention.retentionCompressArchiveAfterDays)
        assertEquals(45, loadedRetention.retentionPermanentDeleteAfterDays)

        val defaults = DesktopWorkspaceStore(createTempDirectory("andy-workspace-defaults").toFile().resolve("missing.properties")).load()
        assertEquals(true, defaults.retentionCleanupEnabled)
        assertEquals(30, defaults.retentionCompressArchiveAfterDays)
        assertEquals(90, defaults.retentionPermanentDeleteAfterDays)

        val withIntents = saved.copy(
            savedIntents = listOf(
                IntentDraft(
                    mode = IntentMode.DeepLink,
                    action = "android.intent.action.VIEW",
                    dataUri = "app://open?id=1",
                ),
                IntentDraft(
                    mode = IntentMode.Activity,
                    action = "android.intent.action.MAIN",
                    component = "com.example/.MainActivity",
                ),
            ),
        )
        DesktopWorkspaceStore(file).save(withIntents)
        assertEquals(withIntents.savedIntents, DesktopWorkspaceStore(file).load().savedIntents)

        file.writeText(file.readText().replace("agentNotificationSoundId=ping", "agentNotificationSoundId=unknown"))
        assertEquals("chime", DesktopWorkspaceStore(file).load().agentNotificationSoundId)

        file.writeText(file.readText().replace("tintId=violet", "tintId=not-a-tint"))
        assertEquals("andy-blue", DesktopWorkspaceStore(file).load().tintId)

        DesktopWorkspaceStore(file).save(saved.copy(surfaceModeId = "pitch-black"))
        assertEquals("pitch-black", DesktopWorkspaceStore(file).load().surfaceModeId)

        DesktopWorkspaceStore(file).save(saved.copy(surfaceModeId = "light"))
        assertEquals("light", DesktopWorkspaceStore(file).load().surfaceModeId)

        file.writeText(file.readText().replace("surfaceModeId=light", "surfaceModeId=not-a-mode"))
        assertEquals("tinted", DesktopWorkspaceStore(file).load().surfaceModeId)

        DesktopWorkspaceStore(file).save(
            saved.copy(newChatBackgroundUri = "/Users/me/Pictures/chat-bg.png"),
        )
        assertEquals(
            "/Users/me/Pictures/chat-bg.png",
            DesktopWorkspaceStore(file).load().newChatBackgroundUri,
        )
        DesktopWorkspaceStore(file).save(saved.copy(newChatBackgroundUri = ""))
        assertEquals("", DesktopWorkspaceStore(file).load().newChatBackgroundUri)

        DesktopWorkspaceStore(file).save(saved.copy(editorSyntaxThemeId = "monokai"))
        assertEquals("monokai", DesktopWorkspaceStore(file).load().editorSyntaxThemeId)

        file.writeText(file.readText().replace("editorSyntaxThemeId=monokai", "editorSyntaxThemeId=not-a-theme"))
        assertEquals("andy", DesktopWorkspaceStore(file).load().editorSyntaxThemeId)

        DesktopWorkspaceStore(file).save(
            saved.copy(
                tracingPresetId = "battery",
                tracingDurationSeconds = 0,
                tracingBufferSizeMb = 32,
                tracingPresetsPaneWidth = 360f,
                tracingLibraryPaneHeight = 300f,
                workspaceStatusExpanded = true,
                projectListPaneVisible = false,
                projectListPaneWidth = 418f,
                performanceTab = "Tracing",
                filesTab = "Database",
                lastActionProjectId = "garden",
                lastActionId = "test",
            ),
        )
        val tracing = DesktopWorkspaceStore(file).load()
        assertEquals("battery", tracing.tracingPresetId)
        assertEquals(0, tracing.tracingDurationSeconds)
        assertEquals(32, tracing.tracingBufferSizeMb)
        assertEquals(360f, tracing.tracingPresetsPaneWidth)
        assertEquals(300f, tracing.tracingLibraryPaneHeight)
        assertEquals(true, tracing.workspaceStatusExpanded)
        assertEquals(false, tracing.projectListPaneVisible)
        assertEquals(418f, tracing.projectListPaneWidth)
        assertEquals("Tracing", tracing.performanceTab)
        assertEquals("Database", tracing.filesTab)
        assertEquals("garden", tracing.lastActionProjectId)
        assertEquals("test", tracing.lastActionId)
        assertEquals(mapOf("garden" to "test"), tracing.lastActionIdsByProject)

        DesktopWorkspaceStore(file).save(
            tracing.copy(
                lastActionProjectId = "mobile",
                lastActionId = "install",
                lastActionIdsByProject = mapOf(
                    "garden" to "test",
                    "mobile" to "install",
                ),
            ),
        )
        val perProjectActions = DesktopWorkspaceStore(file).load()
        assertEquals("mobile", perProjectActions.lastActionProjectId)
        assertEquals("install", perProjectActions.lastActionId)
        assertEquals(
            mapOf("garden" to "test", "mobile" to "install"),
            perProjectActions.lastActionIdsByProject,
        )

        file.writeText(file.readText().replace("performanceTab=Tracing", "performanceTab=Nope"))
        assertEquals("Metrics", DesktopWorkspaceStore(file).load().performanceTab)
        file.writeText(
            DesktopWorkspaceStore(file).load().let { state ->
                // reload after reset default, then poison filesTab
                DesktopWorkspaceStore(file).save(state.copy(filesTab = "SharedPreferences"))
                file.readText().replace("filesTab=SharedPreferences", "filesTab=Nope")
            },
        )
        assertEquals("Files", DesktopWorkspaceStore(file).load().filesTab)

        DesktopWorkspaceStore(file).save(saved.copy(disabledDestinations = setOf("Logcat", "Network")))
        assertEquals(setOf("Logcat", "Network"), DesktopWorkspaceStore(file).load().disabledDestinations)

        DesktopWorkspaceStore(file).save(saved.copy(disabledDestinations = emptySet()))
        assertEquals(emptySet(), DesktopWorkspaceStore(file).load().disabledDestinations)

        DesktopWorkspaceStore(file).save(saved.copy(collapsedProjectChatIds = setOf("project-a", "project-b")))
        assertEquals(setOf("project-a", "project-b"), DesktopWorkspaceStore(file).load().collapsedProjectChatIds)

        DesktopWorkspaceStore(file).save(saved.copy(collapsedProjectChatIds = emptySet()))
        assertEquals(emptySet(), DesktopWorkspaceStore(file).load().collapsedProjectChatIds)

        DesktopWorkspaceStore(file).save(saved.copy(collapsedWorkflowTaskIds = setOf("spec-a", "spec-b")))
        assertEquals(setOf("spec-a", "spec-b"), DesktopWorkspaceStore(file).load().collapsedWorkflowTaskIds)

        DesktopWorkspaceStore(file).save(saved.copy(collapsedWorkflowTaskIds = emptySet()))
        assertEquals(emptySet(), DesktopWorkspaceStore(file).load().collapsedWorkflowTaskIds)

        DesktopWorkspaceStore(file).save(saved.copy(savedSshTargets = listOf("alice@box", "HostAlias")))
        assertEquals(listOf("alice@box", "HostAlias"), DesktopWorkspaceStore(file).load().savedSshTargets)

        DesktopWorkspaceStore(file).save(saved.copy(hostScreenshotEnabled = true))
        assertEquals(true, DesktopWorkspaceStore(file).load().hostScreenshotEnabled)
        DesktopWorkspaceStore(file).save(saved.copy(hostScreenshotEnabled = false))
        assertEquals(false, DesktopWorkspaceStore(file).load().hostScreenshotEnabled)

        // Concurrent ShellState-style save must not wipe targets written via update().
        val store = DesktopWorkspaceStore(file)
        store.update { it.copy(savedSshTargets = listOf("user@remote")) }
        store.update { current ->
            // Mimic ShellState: UI transform that did not touch savedSshTargets, but was
            // built from a stale snapshot that still had an empty list.
            current.copy(
                workspaceSidebarExpanded = false,
                savedSshTargets = listOf("user@remote"),
            )
        }
        assertEquals(listOf("user@remote"), store.load().savedSshTargets)
    }

    @Test
    fun migratesLegacyAutoExpandActivityToThinkingAndTools() = runBlocking {
        val file = createTempDirectory("andy-workspace-legacy-expand").toFile().resolve("workspace.properties")
        file.parentFile.mkdirs()
        file.writeText(
            """
            agentTranscriptAutoExpandActivity=true
            """.trimIndent() + "\n",
        )
        val loaded = DesktopWorkspaceStore(file).load()
        assertEquals(true, loaded.agentTranscriptAutoExpandThinking)
        assertEquals(true, loaded.agentTranscriptAutoExpandTools)

        DesktopWorkspaceStore(file).save(
            loaded.copy(
                agentTranscriptAutoExpandThinking = true,
                agentTranscriptAutoExpandTools = false,
            ),
        )
        val roundTripped = file.readText()
        assertEquals(true, "agentTranscriptAutoExpandThinking=true" in roundTripped)
        assertEquals(true, "agentTranscriptAutoExpandTools=false" in roundTripped)
        assertEquals(false, "agentTranscriptAutoExpandActivity=" in roundTripped)
    }

    @Test
    fun roundTripsTerminalAppearanceAndCoercesLegacyThemeIds() = runBlocking {
        val file = createTempDirectory("andy-workspace-terminal").toFile().resolve("workspace.properties")
        val saved = WorkspaceState(
            terminalThemeId = "nord",
            terminalForegroundHex = "#1A1814",
            terminalBackgroundHex = "#F7F4EC",
            terminalSelectionFgHex = "#1A1814",
            terminalSelectionBgHex = "#B8D0F0",
            terminalFoundFgHex = "#1A1814",
            terminalFoundBgHex = "#FFE066",
            terminalHyperlinkFgHex = "#0B57D0",
            terminalHyperlinkBgHex = "#F7F4EC",
            terminalUseInverseSelection = false,
            terminalColorPaletteId = "windows",
            terminalFontFamilyId = "jetbrains-mono",
            terminalFontSize = 16f,
        )
        DesktopWorkspaceStore(file).save(saved)
        assertEquals(saved, DesktopWorkspaceStore(file).load())

        // Legacy / unknown theme ids coerce to One Dark on load.
        file.writeText(file.readText().replace("terminalThemeId=nord", "terminalThemeId=andy"))
        assertEquals("one-dark", DesktopWorkspaceStore(file).load().terminalThemeId)

        DesktopWorkspaceStore(file).save(
            saved.copy(
                terminalThemeId = "custom",
                terminalForegroundHex = "garbage",
                terminalFontFamilyId = "comic",
                terminalFontSize = 15.6f,
            ),
        )
        val coerced = DesktopWorkspaceStore(file).load()
        assertEquals("one-dark", coerced.terminalThemeId)
        assertEquals("#ABB2BF", coerced.terminalForegroundHex)
        assertEquals("default", coerced.terminalFontFamilyId)
        assertEquals(16f, coerced.terminalFontSize)
    }

    @Test
    fun roundTripsSavedDockLayouts() = runBlocking {
        val file = createTempDirectory("andy-workspace-layouts").toFile().resolve("workspace.properties")
        val layout1 = SavedDockLayout(
            id = "layout-1",
            name = "Layout 1",
            savedAtMillis = 1000L,
            right = SavedDockPane(
                tabs = listOf(
                    SavedDockTab(
                        kind = SavedDockTabKind.Terminal,
                        title = "Nested Terminal",
                        terminalTree = SavedTerminalNode.Split(
                            axis = SavedSplitAxis.Row,
                            children = listOf(
                                SavedTerminalNode.Leaf(
                                    sessions = listOf(SavedTerminalSession("proj-1", "S1")),
                                    activeSessionIndex = 0,
                                ),
                                SavedTerminalNode.Leaf(
                                    sessions = listOf(SavedTerminalSession("proj-2", "S2")),
                                    activeSessionIndex = 0,
                                ),
                            ),
                            weights = listOf(0.5f, 0.5f),
                        ),
                        focusedLeafIndex = 0,
                    ),
                    SavedDockTab(
                        kind = SavedDockTabKind.Live,
                        title = "Live",
                        liveTree = SavedLiveNode.Leaf("dev-1", "Device 1"),
                    ),
                ),
                activeTabIndex = 0,
                visible = true,
            ),
            bottom = SavedDockPane(
                tabs = listOf(
                    SavedDockTab(kind = SavedDockTabKind.Logs),
                    SavedDockTab(kind = SavedDockTabKind.Browser, browserUrl = "https://andy.app"),
                ),
                activeTabIndex = 1,
                visible = true,
            ),
            rightPaneWidth = 500f,
            bottomPaneHeight = 280f,
        )
        val layout2 = SavedDockLayout(
            id = "layout-2",
            name = "Layout 2",
            savedAtMillis = 2000L,
        )

        val state = WorkspaceState(
            logSearch = "testing-saved-layouts",
            savedDockLayouts = listOf(layout1, layout2),
        )
        DesktopWorkspaceStore(file).save(state)
        val loaded = DesktopWorkspaceStore(file).load()
        assertEquals(state.savedDockLayouts, loaded.savedDockLayouts)
        assertEquals("testing-saved-layouts", loaded.logSearch)

        // Corrupt / garbage JSON in savedDockLayouts falls back to empty list while other properties still parse.
        val fileContent = file.readText()
        val corrupt = fileContent.lines().map { line ->
            if (line.startsWith("savedDockLayouts=")) "savedDockLayouts={not:valid-json..."
            else line
        }.joinToString("\n")
        file.writeText(corrupt)

        val loadedAfterCorrupt = DesktopWorkspaceStore(file).load()
        assertEquals(emptyList(), loadedAfterCorrupt.savedDockLayouts)
        assertEquals("testing-saved-layouts", loadedAfterCorrupt.logSearch)
    }
}
