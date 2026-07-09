package app.andy.desktop.service

import app.andy.model.ProxyRule
import app.andy.model.WorkspaceState
import app.andy.service.WorkspaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties

class DesktopWorkspaceStore : WorkspaceStore {
    private val file = File(System.getProperty("user.home"), ".andy/workspace.properties")

    override suspend fun load(): WorkspaceState = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext WorkspaceState()
        val props = Properties().apply { file.inputStream().use(::load) }
        WorkspaceState(
            selectedSdkPath = props.getProperty("selectedSdkPath")?.takeIf { it.isNotBlank() },
            selectedDeviceSerial = props.getProperty("selectedDeviceSerial")?.takeIf { it.isNotBlank() },
            logSearch = props.getProperty("logSearch").orEmpty(),
            proxyPort = props.getProperty("proxyPort")?.toIntOrNull() ?: 9099,
            proxyStartOnLaunch = props.getProperty("proxyStartOnLaunch")?.toBooleanStrictOrNull() ?: false,
            mcpServerEnabled = props.getProperty("mcpServerEnabled")?.toBooleanStrictOrNull() ?: false,
            mcpServerPort = props.getProperty("mcpServerPort")?.toIntOrNull() ?: 8565,
            workspaceSidebarExpanded = props.getProperty("workspaceSidebarExpanded")?.toBooleanStrictOrNull() ?: true,
            proxyRules = loadProxyRules(props),
            liveDevicePaneWidth = props.getProperty("liveDevicePaneWidth")?.toFloatOrNull() ?: 390f,
            liveControlsPaneHeight = props.getProperty("liveControlsPaneHeight")?.toFloatOrNull() ?: 230f,
            appsListPaneWidth = props.getProperty("appsListPaneWidth")?.toFloatOrNull() ?: 520f,
            appsDetailsPaneHeight = props.getProperty("appsDetailsPaneHeight")?.toFloatOrNull() ?: 350f,
            performanceProcessesPaneWidth = props.getProperty("performanceProcessesPaneWidth")?.toFloatOrNull() ?: 760f,
            performanceLivePaneWidth = props.getProperty("performanceLivePaneWidth")?.toFloatOrNull() ?: 320f,
            designDevicePaneWidth = props.getProperty("designDevicePaneWidth")?.toFloatOrNull() ?: 520f,
            accessibilityTreePaneWidth = props.getProperty("accessibilityTreePaneWidth")?.toFloatOrNull() ?: 760f,
            hostFileRoots = props.getProperty("hostFileRoots").orEmpty().lines().filter { it.isNotBlank() },
            lastHostFilePath = props.getProperty("lastHostFilePath")?.takeIf { it.isNotBlank() },
            recentHostFiles = props.getProperty("recentHostFiles").orEmpty().lines().filter { it.isNotBlank() },
            hostFileTreePaneWidth = props.getProperty("hostFileTreePaneWidth")?.toFloatOrNull() ?: 320f,
            hostFileSearchPaneWidth = props.getProperty("hostFileSearchPaneWidth")?.toFloatOrNull() ?: 430f,
            selectedPackage = props.getProperty("selectedPackage")?.takeIf { it.isNotBlank() },
        )
    }

    override suspend fun save(state: WorkspaceState) = withContext(Dispatchers.IO) {
        file.parentFile.mkdirs()
        val props = Properties().apply {
            setProperty("selectedSdkPath", state.selectedSdkPath.orEmpty())
            setProperty("selectedDeviceSerial", state.selectedDeviceSerial.orEmpty())
            setProperty("logSearch", state.logSearch)
            setProperty("proxyPort", state.proxyPort.toString())
            setProperty("proxyStartOnLaunch", state.proxyStartOnLaunch.toString())
            setProperty("mcpServerEnabled", state.mcpServerEnabled.toString())
            setProperty("mcpServerPort", state.mcpServerPort.toString())
            setProperty("workspaceSidebarExpanded", state.workspaceSidebarExpanded.toString())
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
            setProperty("liveDevicePaneWidth", state.liveDevicePaneWidth.toString())
            setProperty("liveControlsPaneHeight", state.liveControlsPaneHeight.toString())
            setProperty("appsListPaneWidth", state.appsListPaneWidth.toString())
            setProperty("appsDetailsPaneHeight", state.appsDetailsPaneHeight.toString())
            setProperty("performanceProcessesPaneWidth", state.performanceProcessesPaneWidth.toString())
            setProperty("performanceLivePaneWidth", state.performanceLivePaneWidth.toString())
            setProperty("designDevicePaneWidth", state.designDevicePaneWidth.toString())
            setProperty("accessibilityTreePaneWidth", state.accessibilityTreePaneWidth.toString())
            setProperty("hostFileRoots", state.hostFileRoots.joinToString("\n"))
            setProperty("lastHostFilePath", state.lastHostFilePath.orEmpty())
            setProperty("recentHostFiles", state.recentHostFiles.joinToString("\n"))
            setProperty("hostFileTreePaneWidth", state.hostFileTreePaneWidth.toString())
            setProperty("hostFileSearchPaneWidth", state.hostFileSearchPaneWidth.toString())
            setProperty("selectedPackage", state.selectedPackage.orEmpty())
        }
        file.outputStream().use { props.store(it, "Andy workspace") }
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

    private fun encodeHeaderMap(headers: Map<String, String>): String {
        return headers.entries.joinToString("\n") { "${it.key}:${it.value}" }
    }

    private fun decodeHeaderMap(value: String): Map<String, String> {
        return value.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && ":" in it }
            .associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
    }
}
