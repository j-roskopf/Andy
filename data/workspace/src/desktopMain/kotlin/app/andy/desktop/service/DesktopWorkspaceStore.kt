package app.andy.desktop.service

import app.andy.model.IntentDraft
import app.andy.model.PairedWifiDevice
import app.andy.model.ProxyRule
import app.andy.model.WorkspaceState
import app.andy.model.AgentMessageDeliveryMode
import app.andy.model.AgentNotificationSound
import app.andy.model.AgentNotificationTiming
import app.andy.model.EditorSyntaxTheme
import app.andy.model.FilesTab
import app.andy.model.PerformanceTab
import app.andy.model.TerminalFontFamily
import app.andy.model.TerminalThemePreset
import app.andy.model.normalizeTerminalHex
import app.andy.ui.theme.AndySurfaceMode
import app.andy.ui.theme.AndyTint
import app.andy.service.WorkspaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Properties

class DesktopWorkspaceStore(
    private val file: File = File(System.getProperty("user.home"), ".andy/workspace.properties"),
) : WorkspaceStore {
    private val mutableState = MutableStateFlow(WorkspaceState())
    override val state: StateFlow<WorkspaceState> = mutableState

    override suspend fun load(): WorkspaceState = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext WorkspaceState()
        val props = Properties().apply { file.inputStream().use(::load) }
        WorkspaceState(
            selectedSdkPath = props.getProperty("selectedSdkPath")?.takeIf { it.isNotBlank() },
            selectedDeviceSerial = props.getProperty("selectedDeviceSerial")?.takeIf { it.isNotBlank() },
            // Indexed key/value properties, not a `key:value:...` line format — device serials
            // for Wi‑Fi-paired devices are themselves `host:port` and would break a colon split.
            deviceLabels = loadIndexedStringMap(props, "deviceLabel"),
            deviceNotes = loadIndexedStringMap(props, "deviceNote"),
            savedIntents = decodeSavedIntents(props.getProperty("savedIntents").orEmpty()),
            logSearch = props.getProperty("logSearch").orEmpty(),
            proxyPort = props.getProperty("proxyPort")?.toIntOrNull() ?: 9099,
            proxyStartOnLaunch = props.getProperty("proxyStartOnLaunch")?.toBooleanStrictOrNull() ?: false,
            proxySslInsecure = props.getProperty("proxySslInsecure")?.toBooleanStrictOrNull() ?: false,
            proxyUpstreamTrustedCaPath = props.getProperty("proxyUpstreamTrustedCaPath")?.takeIf { it.isNotBlank() },
            mcpServerEnabled = props.getProperty("mcpServerEnabled")?.toBooleanStrictOrNull() ?: false,
            mcpServerPort = props.getProperty("mcpServerPort")?.toIntOrNull() ?: 8565,
            networkAccessEnabled = props.getProperty("networkAccessEnabled")?.toBooleanStrictOrNull() ?: false,
            networkAccessTailscaleOnly =
                props.getProperty("networkAccessTailscaleOnly")?.toBooleanStrictOrNull() ?: true,
            networkAccessToken = props.getProperty("networkAccessToken").orEmpty(),
            vapidPublicKey = props.getProperty("vapidPublicKey").orEmpty(),
            vapidPrivateKey = props.getProperty("vapidPrivateKey").orEmpty(),
            tintId = AndyTint.fromId(props.getProperty("tintId").orEmpty()).id,
            surfaceModeId = AndySurfaceMode.fromId(props.getProperty("surfaceModeId").orEmpty()).id,
            newChatBackgroundUri = props.getProperty("newChatBackgroundUri").orEmpty(),
            editorSyntaxThemeId = EditorSyntaxTheme.fromId(props.getProperty("editorSyntaxThemeId").orEmpty()).id,
            // Legacy Andy hex theme ids coerce to BossTerm built-ins; hex fields are ignored at runtime.
            terminalThemeId = TerminalThemePreset.fromId(
                props.getProperty("terminalThemeId").orEmpty(),
            ).id,
            terminalForegroundHex = normalizeTerminalHex(
                props.getProperty("terminalForegroundHex").orEmpty(),
                WorkspaceState().terminalForegroundHex,
            ),
            terminalBackgroundHex = normalizeTerminalHex(
                props.getProperty("terminalBackgroundHex").orEmpty(),
                WorkspaceState().terminalBackgroundHex,
            ),
            terminalSelectionFgHex = normalizeTerminalHex(
                props.getProperty("terminalSelectionFgHex").orEmpty(),
                WorkspaceState().terminalSelectionFgHex,
            ),
            terminalSelectionBgHex = normalizeTerminalHex(
                props.getProperty("terminalSelectionBgHex").orEmpty(),
                WorkspaceState().terminalSelectionBgHex,
            ),
            terminalFoundFgHex = normalizeTerminalHex(
                props.getProperty("terminalFoundFgHex").orEmpty(),
                WorkspaceState().terminalFoundFgHex,
            ),
            terminalFoundBgHex = normalizeTerminalHex(
                props.getProperty("terminalFoundBgHex").orEmpty(),
                WorkspaceState().terminalFoundBgHex,
            ),
            terminalHyperlinkFgHex = normalizeTerminalHex(
                props.getProperty("terminalHyperlinkFgHex").orEmpty(),
                WorkspaceState().terminalHyperlinkFgHex,
            ),
            terminalHyperlinkBgHex = normalizeTerminalHex(
                props.getProperty("terminalHyperlinkBgHex").orEmpty(),
                WorkspaceState().terminalHyperlinkBgHex,
            ),
            terminalUseInverseSelection = props.getProperty("terminalUseInverseSelection")
                ?.toBooleanStrictOrNull()
                ?: false,
            terminalColorPaletteId = props.getProperty("terminalColorPaletteId")?.takeIf { it.isNotBlank() }
                ?: WorkspaceState().terminalColorPaletteId,
            terminalFontFamilyId = TerminalFontFamily.fromId(
                props.getProperty("terminalFontFamilyId").orEmpty(),
            ).id,
            terminalFontSize = TerminalThemePreset.coerceFontSize(
                props.getProperty("terminalFontSize")?.toFloatOrNull()
                    ?: TerminalThemePreset.DefaultFontSize,
            ),
            workspaceSidebarExpanded = props.getProperty("workspaceSidebarExpanded")?.toBooleanStrictOrNull() ?: true,
            workspaceStatusExpanded = props.getProperty("workspaceStatusExpanded")?.toBooleanStrictOrNull() ?: false,
            projectsIntroductionCompleted = props.getProperty("projectsIntroductionCompleted")?.toBooleanStrictOrNull() ?: false,
            projectListPaneVisible = props.getProperty("projectListPaneVisible")?.toBooleanStrictOrNull() ?: true,
            projectListPaneWidth = props.getProperty("projectListPaneWidth")?.toFloatOrNull()?.coerceIn(220f, 600f) ?: 300f,
            proxyRules = loadProxyRules(props),
            pairedWifiDevices = loadPairedWifi(props),
            liveDevicePaneWidth = props.getProperty("liveDevicePaneWidth")?.toFloatOrNull() ?: 720f,
            liveControlsPaneHeight = props.getProperty("liveControlsPaneHeight")?.toFloatOrNull() ?: 320f,
            appsListPaneWidth = props.getProperty("appsListPaneWidth")?.toFloatOrNull() ?: 520f,
            appsDetailsPaneHeight = props.getProperty("appsDetailsPaneHeight")?.toFloatOrNull() ?: 350f,
            performanceProcessesPaneWidth = props.getProperty("performanceProcessesPaneWidth")?.toFloatOrNull() ?: 760f,
            performanceLivePaneWidth = props.getProperty("performanceLivePaneWidth")?.toFloatOrNull() ?: 320f,
            performanceTab = props.getProperty("performanceTab")?.takeIf { tab ->
                PerformanceTab.entries.any { it.name == tab }
            } ?: PerformanceTab.Metrics.name,
            filesTab = props.getProperty("filesTab")?.takeIf { tab ->
                FilesTab.entries.any { it.name == tab }
            } ?: FilesTab.Files.name,
            tracingPresetId = props.getProperty("tracingPresetId")?.takeIf { it.isNotBlank() } ?: "default",
            tracingDurationSeconds = props.getProperty("tracingDurationSeconds")?.toIntOrNull() ?: 10,
            tracingBufferSizeMb = props.getProperty("tracingBufferSizeMb")?.toIntOrNull() ?: 64,
            retentionCleanupEnabled = props.getProperty("retentionCleanupEnabled")?.toBooleanStrictOrNull() ?: true,
            retentionCompressArchiveAfterDays = props.getProperty("retentionCompressArchiveAfterDays")?.toIntOrNull() ?: 30,
            retentionPermanentDeleteAfterDays = props.getProperty("retentionPermanentDeleteAfterDays")?.toIntOrNull() ?: 90,
            tracingPresetsPaneWidth = props.getProperty("tracingPresetsPaneWidth")?.toFloatOrNull() ?: 320f,
            tracingLibraryPaneHeight = props.getProperty("tracingLibraryPaneHeight")?.toFloatOrNull() ?: 240f,
            designDevicePaneWidth = props.getProperty("designDevicePaneWidth")?.toFloatOrNull() ?: 520f,
            inspectorTreePaneWidth = props.getProperty("inspectorTreePaneWidth")?.toFloatOrNull()
                ?: props.getProperty("accessibilityTreePaneWidth")?.toFloatOrNull()
                ?: 760f,
            hostFileRoots = props.getProperty("hostFileRoots").orEmpty().lines().filter { it.isNotBlank() },
            lastHostFilePath = props.getProperty("lastHostFilePath")?.takeIf { it.isNotBlank() },
            recentHostFiles = props.getProperty("recentHostFiles").orEmpty().lines().filter { it.isNotBlank() },
            hostFileTreePaneWidth = props.getProperty("hostFileTreePaneWidth")?.toFloatOrNull() ?: 320f,
            hostFileSearchPaneWidth = props.getProperty("hostFileSearchPaneWidth")?.toFloatOrNull() ?: 430f,
            selectedPackage = props.getProperty("selectedPackage")?.takeIf { it.isNotBlank() },
            lastActionProjectId = props.getProperty("lastActionProjectId")?.takeIf { it.isNotBlank() },
            lastActionId = props.getProperty("lastActionId")?.takeIf { it.isNotBlank() },
            lastActionIdsByProject = loadLastActionIdsByProject(props),
            agentOsNotificationsEnabled = props.getProperty("agentOsNotificationsEnabled")?.toBooleanStrictOrNull() ?: true,
            agentNotificationSoundEnabled = props.getProperty("agentNotificationSoundEnabled")?.toBooleanStrictOrNull() ?: true,
            agentIconBadgeEnabled = props.getProperty("agentIconBadgeEnabled")?.toBooleanStrictOrNull() ?: true,
            voiceDictationShortcut = props.getProperty("voiceDictationShortcut")?.takeIf { it.isNotBlank() },
            keepAgentSessionsOnShutdown = props.getProperty("keepAgentSessionsOnShutdown")?.toBooleanStrictOrNull() ?: false,
            agentNotificationTiming = props.getProperty("agentNotificationTiming")?.let { value -> AgentNotificationTiming.entries.firstOrNull { it.name == value } } ?: AgentNotificationTiming.BackgroundOnly,
            agentNotificationSoundId = props.getProperty("agentNotificationSoundId")?.takeIf { id -> AgentNotificationSound.entries.any { it.id == id } } ?: AgentNotificationSound.Chime.id,
            agentTranscriptAutoExpandThinking = props.booleanOrLegacy(
                key = "agentTranscriptAutoExpandThinking",
                legacyKey = "agentTranscriptAutoExpandActivity",
            ),
            agentTranscriptAutoExpandTools = props.booleanOrLegacy(
                key = "agentTranscriptAutoExpandTools",
                legacyKey = "agentTranscriptAutoExpandActivity",
            ),
            agentTranscriptCollapseActivityBlocks = props.getProperty("agentTranscriptCollapseActivityBlocks")?.toBooleanStrictOrNull() ?: false,
            agentMessageDeliveryMode = props.getProperty("agentMessageDeliveryMode")?.let { value ->
                AgentMessageDeliveryMode.entries.firstOrNull { it.name == value }
            } ?: AgentMessageDeliveryMode.Immediate,
            agentPinPriorityChats = props.getProperty("agentPinPriorityChats")?.toBooleanStrictOrNull() ?: false,
            disabledDestinations = props.getProperty("disabledDestinations").orEmpty().lines().filter { it.isNotBlank() }.toSet(),
            collapsedProjectChatIds = props.getProperty("collapsedProjectChatIds").orEmpty().lines().filter { it.isNotBlank() }.toSet(),
            ollamaBaseUrl = props.getProperty("ollamaBaseUrl")?.takeIf { it.isNotBlank() } ?: WorkspaceState().ollamaBaseUrl,
            ollamaBearerToken = props.getProperty("ollamaBearerToken").orEmpty(),
            lmStudioBaseUrl = props.getProperty("lmStudioBaseUrl")?.takeIf { it.isNotBlank() } ?: WorkspaceState().lmStudioBaseUrl,
            lmStudioBearerToken = props.getProperty("lmStudioBearerToken").orEmpty(),
            savedSshTargets = props.getProperty("savedSshTargets").orEmpty().lines().filter { it.isNotBlank() },
            iosCmioIds = loadIndexedStringMap(props, "iosCmioId"),
        )
    }.also { mutableState.value = it }

    override suspend fun save(state: WorkspaceState) = withContext(Dispatchers.IO) {
        file.parentFile.mkdirs()
        // Daemon-owned VAPID keys may be generated after the GUI loaded ShellState.
        // Preserve non-blank on-disk keys when the incoming state still has empties so a
        // later GUI workspace save does not wipe them and break existing push subscriptions.
        val onDisk = Properties().apply {
            if (file.exists()) {
                file.inputStream().use { load(it) }
            }
        }
        val vapidPublic = state.vapidPublicKey.ifBlank {
            onDisk.getProperty("vapidPublicKey").orEmpty()
        }
        val vapidPrivate = state.vapidPrivateKey.ifBlank {
            onDisk.getProperty("vapidPrivateKey").orEmpty()
        }
        val props = Properties().apply {
            setProperty("selectedSdkPath", state.selectedSdkPath.orEmpty())
            setProperty("selectedDeviceSerial", state.selectedDeviceSerial.orEmpty())
            saveIndexedStringMap(this, "deviceLabel", state.deviceLabels)
            saveIndexedStringMap(this, "deviceNote", state.deviceNotes)
            setProperty("savedIntents", encodeSavedIntents(state.savedIntents))
            setProperty("logSearch", state.logSearch)
            setProperty("proxyPort", state.proxyPort.toString())
            setProperty("proxyStartOnLaunch", state.proxyStartOnLaunch.toString())
            setProperty("proxySslInsecure", state.proxySslInsecure.toString())
            setProperty("proxyUpstreamTrustedCaPath", state.proxyUpstreamTrustedCaPath.orEmpty())
            setProperty("mcpServerEnabled", state.mcpServerEnabled.toString())
            setProperty("mcpServerPort", state.mcpServerPort.toString())
            setProperty("networkAccessEnabled", state.networkAccessEnabled.toString())
            setProperty("networkAccessTailscaleOnly", state.networkAccessTailscaleOnly.toString())
            setProperty("networkAccessToken", state.networkAccessToken)
            setProperty("vapidPublicKey", vapidPublic)
            setProperty("vapidPrivateKey", vapidPrivate)
            setProperty("tintId", state.tintId)
            setProperty("surfaceModeId", state.surfaceModeId)
            setProperty("newChatBackgroundUri", state.newChatBackgroundUri)
            setProperty("editorSyntaxThemeId", state.editorSyntaxThemeId)
            setProperty("terminalThemeId", state.terminalThemeId)
            setProperty("terminalForegroundHex", state.terminalForegroundHex)
            setProperty("terminalBackgroundHex", state.terminalBackgroundHex)
            setProperty("terminalSelectionFgHex", state.terminalSelectionFgHex)
            setProperty("terminalSelectionBgHex", state.terminalSelectionBgHex)
            setProperty("terminalFoundFgHex", state.terminalFoundFgHex)
            setProperty("terminalFoundBgHex", state.terminalFoundBgHex)
            setProperty("terminalHyperlinkFgHex", state.terminalHyperlinkFgHex)
            setProperty("terminalHyperlinkBgHex", state.terminalHyperlinkBgHex)
            setProperty("terminalUseInverseSelection", state.terminalUseInverseSelection.toString())
            setProperty("terminalColorPaletteId", state.terminalColorPaletteId)
            setProperty("terminalFontFamilyId", state.terminalFontFamilyId)
            setProperty("terminalFontSize", state.terminalFontSize.toString())
            setProperty("workspaceSidebarExpanded", state.workspaceSidebarExpanded.toString())
            setProperty("workspaceStatusExpanded", state.workspaceStatusExpanded.toString())
            setProperty("projectsIntroductionCompleted", state.projectsIntroductionCompleted.toString())
            setProperty("projectListPaneVisible", state.projectListPaneVisible.toString())
            setProperty("projectListPaneWidth", state.projectListPaneWidth.toString())
            setProperty("proxyRuleCount", state.proxyRules.size.toString())
            state.proxyRules.forEachIndexed { index, rule ->
                val prefix = "proxyRule.$index."
                setProperty(prefix + "id", rule.id)
                setProperty(prefix + "name", rule.name)
                setProperty(prefix + "enabled", rule.enabled.toString())
                setProperty(prefix + "urlPattern", rule.urlPattern)
                setProperty(prefix + "method", rule.method.orEmpty())
                setProperty(prefix + "statusCode", rule.statusCode?.toString().orEmpty())
                setProperty(prefix + "setHeaders", encodeHeaderMap(rule.setHeaders))
                setProperty(prefix + "removeHeaders", rule.removeHeaders.joinToString("\n"))
                setProperty(prefix + "responseBody", rule.responseBody.orEmpty())
            }
            setProperty("pairedWifiCount", state.pairedWifiDevices.size.toString())
            state.pairedWifiDevices.forEachIndexed { index, device ->
                val prefix = "pairedWifi.$index."
                setProperty(prefix + "id", device.id)
                setProperty(prefix + "displayName", device.displayName)
                setProperty(prefix + "mdnsInstanceName", device.mdnsInstanceName.orEmpty())
                setProperty(prefix + "lastEndpoint", device.lastEndpoint.orEmpty())
                setProperty(prefix + "pairedAtMillis", device.pairedAtMillis.toString())
            }
            setProperty("liveDevicePaneWidth", state.liveDevicePaneWidth.toString())
            setProperty("liveControlsPaneHeight", state.liveControlsPaneHeight.toString())
            setProperty("appsListPaneWidth", state.appsListPaneWidth.toString())
            setProperty("appsDetailsPaneHeight", state.appsDetailsPaneHeight.toString())
            setProperty("performanceProcessesPaneWidth", state.performanceProcessesPaneWidth.toString())
            setProperty("performanceLivePaneWidth", state.performanceLivePaneWidth.toString())
            setProperty("performanceTab", state.performanceTab)
            setProperty("filesTab", state.filesTab)
            setProperty("tracingPresetId", state.tracingPresetId)
            setProperty("tracingDurationSeconds", state.tracingDurationSeconds.toString())
            setProperty("tracingBufferSizeMb", state.tracingBufferSizeMb.toString())
            setProperty("retentionCleanupEnabled", state.retentionCleanupEnabled.toString())
            setProperty("retentionCompressArchiveAfterDays", state.retentionCompressArchiveAfterDays.toString())
            setProperty("retentionPermanentDeleteAfterDays", state.retentionPermanentDeleteAfterDays.toString())
            setProperty("tracingPresetsPaneWidth", state.tracingPresetsPaneWidth.toString())
            setProperty("tracingLibraryPaneHeight", state.tracingLibraryPaneHeight.toString())
            setProperty("designDevicePaneWidth", state.designDevicePaneWidth.toString())
            setProperty("inspectorTreePaneWidth", state.inspectorTreePaneWidth.toString())
            setProperty("hostFileRoots", state.hostFileRoots.joinToString("\n"))
            setProperty("lastHostFilePath", state.lastHostFilePath.orEmpty())
            setProperty("recentHostFiles", state.recentHostFiles.joinToString("\n"))
            setProperty("hostFileTreePaneWidth", state.hostFileTreePaneWidth.toString())
            setProperty("hostFileSearchPaneWidth", state.hostFileSearchPaneWidth.toString())
            setProperty("selectedPackage", state.selectedPackage.orEmpty())
            setProperty("lastActionProjectId", state.lastActionProjectId.orEmpty())
            setProperty("lastActionId", state.lastActionId.orEmpty())
            saveIndexedStringMap(this, "lastActionIdByProject", state.lastActionIdsByProject)
            setProperty("agentOsNotificationsEnabled", state.agentOsNotificationsEnabled.toString())
            setProperty("agentNotificationSoundEnabled", state.agentNotificationSoundEnabled.toString())
            setProperty("agentIconBadgeEnabled", state.agentIconBadgeEnabled.toString())
            setProperty("voiceDictationShortcut", state.voiceDictationShortcut.orEmpty())
            setProperty("keepAgentSessionsOnShutdown", state.keepAgentSessionsOnShutdown.toString())
            setProperty("agentNotificationTiming", state.agentNotificationTiming.name)
            setProperty("agentNotificationSoundId", state.agentNotificationSoundId)
            setProperty("agentTranscriptAutoExpandThinking", state.agentTranscriptAutoExpandThinking.toString())
            setProperty("agentTranscriptAutoExpandTools", state.agentTranscriptAutoExpandTools.toString())
            setProperty("agentTranscriptCollapseActivityBlocks", state.agentTranscriptCollapseActivityBlocks.toString())
            setProperty("agentMessageDeliveryMode", state.agentMessageDeliveryMode.name)
            setProperty("agentPinPriorityChats", state.agentPinPriorityChats.toString())
            setProperty("disabledDestinations", state.disabledDestinations.joinToString("\n"))
            setProperty("collapsedProjectChatIds", state.collapsedProjectChatIds.joinToString("\n"))
            setProperty("ollamaBaseUrl", state.ollamaBaseUrl)
            setProperty("ollamaBearerToken", state.ollamaBearerToken)
            setProperty("lmStudioBaseUrl", state.lmStudioBaseUrl)
            setProperty("lmStudioBearerToken", state.lmStudioBearerToken)
            setProperty("savedSshTargets", state.savedSshTargets.joinToString("\n"))
            saveIndexedStringMap(this, "iosCmioId", state.iosCmioIds)
        }
        file.outputStream().use { props.store(it, "Andy workspace") }
        mutableState.value = state
    }

    private fun encodeSavedIntents(intents: List<IntentDraft>): String {
        if (intents.isEmpty()) return ""
        return WorkspaceJson.encodeToString(intents)
    }

    private fun decodeSavedIntents(value: String): List<IntentDraft> {
        if (value.isBlank()) return emptyList()
        return runCatching {
            WorkspaceJson.decodeFromString(ListSerializer(IntentDraft.serializer()), value)
        }.getOrDefault(emptyList())
    }

    private fun loadProxyRules(props: Properties): List<ProxyRule> {
        val count = props.getProperty("proxyRuleCount")?.toIntOrNull() ?: return emptyList()
        return (0 until count).mapNotNull { index ->
            val prefix = "proxyRule.$index."
            val id = props.getProperty(prefix + "id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ProxyRule(
                id = id,
                name = props.getProperty(prefix + "name").orEmpty().ifBlank { id },
                enabled = props.getProperty(prefix + "enabled")?.toBooleanStrictOrNull() ?: true,
                urlPattern = props.getProperty(prefix + "urlPattern").orEmpty(),
                method = props.getProperty(prefix + "method")?.takeIf { it.isNotBlank() },
                statusCode = props.getProperty(prefix + "statusCode")?.toIntOrNull(),
                setHeaders = decodeHeaderMap(props.getProperty(prefix + "setHeaders").orEmpty()),
                removeHeaders = props.getProperty(prefix + "removeHeaders").orEmpty().lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList(),
                responseBody = props.getProperty(prefix + "responseBody")?.takeIf { it.isNotBlank() },
            )
        }
    }

    private fun loadPairedWifi(props: Properties): List<PairedWifiDevice> {
        val count = props.getProperty("pairedWifiCount")?.toIntOrNull() ?: return emptyList()
        return (0 until count).mapNotNull { index ->
            val prefix = "pairedWifi.$index."
            val id = props.getProperty(prefix + "id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PairedWifiDevice(
                id = id,
                displayName = props.getProperty(prefix + "displayName").orEmpty().ifBlank { id },
                mdnsInstanceName = props.getProperty(prefix + "mdnsInstanceName")?.takeIf { it.isNotBlank() },
                lastEndpoint = props.getProperty(prefix + "lastEndpoint")?.takeIf { it.isNotBlank() },
                pairedAtMillis = props.getProperty(prefix + "pairedAtMillis")?.toLongOrNull() ?: 0L,
            )
        }
    }

    private fun loadLastActionIdsByProject(props: Properties): Map<String, String> {
        val stored = loadIndexedStringMap(props, "lastActionIdByProject")
        if (stored.isNotEmpty()) return stored
        val projectId = props.getProperty("lastActionProjectId")?.takeIf { it.isNotBlank() } ?: return emptyMap()
        val actionId = props.getProperty("lastActionId")?.takeIf { it.isNotBlank() } ?: return emptyMap()
        return mapOf(projectId to actionId)
    }

    private fun loadIndexedStringMap(props: Properties, prefix: String): Map<String, String> {
        val count = props.getProperty("${prefix}Count")?.toIntOrNull() ?: return emptyMap()
        return (0 until count).mapNotNull { index ->
            val key = props.getProperty("$prefix.$index.key")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            key to props.getProperty("$prefix.$index.value").orEmpty()
        }.toMap()
    }

    private fun saveIndexedStringMap(props: Properties, prefix: String, map: Map<String, String>) {
        props.setProperty("${prefix}Count", map.size.toString())
        map.entries.forEachIndexed { index, (key, value) ->
            props.setProperty("$prefix.$index.key", key)
            props.setProperty("$prefix.$index.value", value)
        }
    }

    private fun encodeHeaderMap(headers: Map<String, String>): String {
        return headers.entries.joinToString("\n") { "${it.key}:${it.value}" }
    }

    private fun decodeHeaderMap(value: String): Map<String, String> {
        return value.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && ":" in it }
            .associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
    }

    /** Prefer [key]; if unset, fall back to a pre-split legacy boolean (defaults false). */
    private fun Properties.booleanOrLegacy(key: String, legacyKey: String): Boolean =
        getProperty(key)?.toBooleanStrictOrNull()
            ?: getProperty(legacyKey)?.toBooleanStrictOrNull()
            ?: false

    private companion object {
        val WorkspaceJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
