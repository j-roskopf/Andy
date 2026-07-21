package app.andy

import app.andy.model.*
import app.andy.service.*
import app.andy.ui.screenshots.AndyScreenshotScenario
import app.andy.ui.screenshots.ScreenshotFixture
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory services for visual tests. These implementations intentionally never
 * execute a command, open a socket, read a host directory, or start an agent.
 */
internal object ScreenshotServices {
    private const val serial = ScreenshotFixture.serial
    private const val now = ScreenshotFixture.nowMillis
    private const val gardenPackage = "com.example.garden"

    fun create(scenario: AndyScreenshotScenario? = null): AndyServices {
        val workspace = ScreenshotWorkspaceStore(
            WorkspaceState(
                selectedSdkPath = "/Android/sdk",
                selectedDeviceSerial = serial,
                projectsIntroductionCompleted = true,
                selectedPackage = gardenPackage,
                filesTab = when (scenario) {
                    AndyScreenshotScenario.SharedPreferences -> FilesTab.SharedPreferences.name
                    AndyScreenshotScenario.AppDatabase -> FilesTab.Database.name
                    else -> FilesTab.Files.name
                },
                // Prefer workspace tab over AndyDestination.Tracing so navigateTo cannot
                // race initialize() and persist a default WorkspaceState.
                performanceTab = when (scenario) {
                    AndyScreenshotScenario.TracingPerfetto -> PerformanceTab.Tracing.name
                    else -> PerformanceTab.Metrics.name
                },
                // Leave room above the library so the seeded user config row is visible.
                tracingLibraryPaneHeight = when (scenario) {
                    AndyScreenshotScenario.TracingPerfetto -> 160f
                    else -> 240f
                },
                proxyPort = 9099,
                proxyRules = listOf(
                    ProxyRule("mock-profile", "Mock profile", true, "*/profile*", "GET", 200, responseBody = "{\"name\":\"Ada\"}"),
                    ProxyRule("mock-debug", "Debug header", true, "api.example.test/*", setHeaders = mapOf("X-Andy" to "visual-test")),
                ),
                hostFileRoots = listOf("/workspace/sample-app"),
                lastHostFilePath = "/workspace/sample-app/app/src/main/AndroidManifest.xml",
                recentHostFiles = listOf("/workspace/sample-app/app/src/main/AndroidManifest.xml"),
            ),
        )
        return AndyServices(
            devices = ScreenshotDevices,
            avd = ScreenshotAvds,
            mirror = ScreenshotMirror,
            logcat = ScreenshotLogcat,
            intents = ScreenshotIntents,
            apps = ScreenshotApps,
            files = ScreenshotFiles,
            hostFiles = ScreenshotHostFiles,
            proxy = ScreenshotProxy,
            metrics = ScreenshotMetrics,
            accessibility = ScreenshotAccessibility,
            bugs = ScreenshotBugs,
            artifacts = ScreenshotArtifacts,
            tracing = ScreenshotTracing,
            traceViewer = ScreenshotTraceViewer,
            sharedPrefs = ScreenshotSharedPrefs,
            appDatabase = ScreenshotAppDatabase,
            workspaceStore = workspace,
            updates = ScreenshotUpdates,
            mcp = ScreenshotMcp,
            actionConfig = ScreenshotActionConfig,
            actionRuns = ScreenshotActionRuns,
            agentRuns = ScreenshotAgentRuns,
            projectWorkflows = ScreenshotProjectWorkflows,
        )
    }

    private object ScreenshotDevices : DeviceService {
        private val devices = listOf(
            AndroidDevice(serial, "Pixel 8 API 36", DeviceKind.Emulator, DeviceConnectionState.Online, DeviceTransport.Unknown, "36", "arm64-v8a", "Pixel 8", batteryPercent = 87, screenSize = "1080x2400", storageSummary = "60G free / 112G"),
            AndroidDevice("R3CXB056ZZB", "Galaxy S24", DeviceKind.Physical, DeviceConnectionState.Online, DeviceTransport.Usb, "35", "arm64-v8a", "SM-S921U", batteryPercent = 74, screenSize = "1080x2340", storageSummary = "92G free / 128G"),
            AndroidDevice("192.168.86.47:5555", "Pixel Tablet", DeviceKind.Physical, DeviceConnectionState.Offline, DeviceTransport.Wifi, "34", "arm64-v8a", batteryPercent = 61),
        )
        override suspend fun discoverSdk() = SdkDiscovery("/Android/sdk", "/Android/sdk/platform-tools/adb", "/Android/sdk/emulator/emulator", "/Android/sdk/cmdline-tools/latest/bin/sdkmanager", "/Android/sdk/cmdline-tools/latest/bin/avdmanager")
        override suspend fun listDevices() = devices
        override suspend fun shell(serial: String, command: List<String>) = CommandResult.success("ok")
        override suspend fun pair(host: String, port: Int, code: String) = CommandResult.success("Successfully paired to $host:$port")
        override suspend fun connect(host: String, port: Int) = CommandResult.success("connected to $host:$port")
        override suspend fun disconnect(serial: String) = CommandResult.success("disconnected $serial")
        override suspend fun listMdnsServices() = listOf(MdnsService("adb-PIXEL8", "_adb-tls-connect._tcp", "192.168.86.47", 37123), MdnsService("adb-PAIRING", "_adb-tls-pairing._tcp", "192.168.86.47", 37199))
        override suspend fun mdnsAvailable() = true
        override suspend fun generatePairingQr(content: String) = ByteArray(64) { (it * 17).toByte() }
    }

    private object ScreenshotAvds : AvdService {
        override suspend fun listSystemImages() = listOf(
            SystemImage("system-images;android-36;google_apis;arm64-v8a", "36", "Google APIs", "arm64-v8a", "Android 16 Google APIs", true, 2_600_000_000),
            SystemImage("system-images;android-35;google_apis_playstore;arm64-v8a", "35", "Google Play", "arm64-v8a", "Android 15 Google Play", false),
            SystemImage("system-images;android-34;android-tv;arm64-v8a", "34", "Android TV", "arm64-v8a", "Android TV 14", false),
        )
        override suspend fun listProfiles() = listOf(AvdProfile("34", "Pixel 8", "Google", "phone", "1080 × 2400", "420 dpi", AvdProfileCategory.Phone), AvdProfile("42", "Pixel Tablet", "Google", "tablet", "2560 × 1600", "320 dpi", AvdProfileCategory.Tablet))
        override suspend fun listVirtualDevices() = listOf(VirtualDevice("Pixel_8_API_36", "/Users/andy/.android/avd/Pixel_8_API_36.avd", "android-36", "arm64-v8a", true, 36, VirtualDeviceType.Phone), VirtualDevice("Tablet_API_35", null, "android-35", "arm64-v8a", false, 35, VirtualDeviceType.Tablet))
        override suspend fun createVirtualDevice(name: String, profileId: String, systemImagePackage: String) = CommandResult.success("Created $name")
        override suspend fun createVirtualDevice(config: AvdCreationConfig) = CommandResult.success("Created ${config.name}")
        override suspend fun startVirtualDevice(name: String) = CommandResult.success("Started $name")
        override suspend fun coldBootVirtualDevice(name: String) = CommandResult.success("Cold booted $name")
        override suspend fun stopVirtualDevice(name: String) = CommandResult.success("Stopped $name")
        override suspend fun wipeVirtualDevice(name: String) = CommandResult.success("Wiped $name")
        override suspend fun deleteVirtualDevice(name: String) = CommandResult.success("Deleted $name")
        override suspend fun cloneVirtualDevice(sourceName: String, newName: String) = CommandResult.success("Cloned $sourceName")
        override suspend fun installSystemImage(packageId: String) = CommandResult.success("Installed $packageId")
        override suspend fun uninstallSystemImage(packageId: String) = CommandResult.success("Uninstalled $packageId")
        override suspend fun listSnapshots(avdName: String) = listOf(EmulatorSnapshot("default_boot", avdName, "automatic", "1.2 GB", "Jan 1, 2025"), EmulatorSnapshot("checkout-flow", avdName, "manual", "980 MB", "Jan 1, 2025"))
        override suspend fun saveSnapshot(avdName: String, snapshotName: String) = CommandResult.success()
        override suspend fun restoreSnapshot(avdName: String, snapshotName: String) = CommandResult.success()
        override suspend fun deleteSnapshot(avdName: String, snapshotName: String) = CommandResult.success()
        override suspend fun renameSnapshot(avdName: String, oldName: String, newName: String) = CommandResult.success()
    }

    private object ScreenshotMirror : MirrorEngine {
        private val frame = MirrorFrame(270, 600, IntArray(270 * 600) { index ->
            val x = index % 270; val y = index / 270
            val r = (25 + x / 6).coerceAtMost(255); val g = (35 + y / 5).coerceAtMost(255); val b = 45 + (x + y) % 70
            (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }, frameNumber = 42, decodedFps = 59.8f)
        override val session = kotlinx.coroutines.flow.MutableStateFlow(
            MirrorSession(
                serial = "emulator-5554",
                requestedMode = MirrorRendererMode.Legacy,
                backend = MirrorBackend(MirrorBackendKind.LegacyCpu, "Deterministic test decoder", "Compose"),
                stats = MirrorStats(displayedFps = 59.8f, decodedFps = 59.8f, framesPresented = 42),
                width = frame.width,
                height = frame.height,
            ),
        )
        override val frames: Flow<MirrorFrame> = flowOf(frame)
        override val status: Flow<String> = flowOf("Connected · 59.8 fps · H.264")
        override suspend fun connect(serial: String, config: MirrorVideoConfig) = CommandResult.success("Connected to Pixel 8 API 36")
        override suspend fun disconnect(immediate: Boolean) = Unit
        override suspend fun sendInput(input: MirrorInput) = CommandResult.success()
        override suspend fun screenshot(serial: String) = ByteArray(32) { it.toByte() }
    }

    private object ScreenshotLogcat : LogcatService {
        private val rows = listOf(
            LogcatEntry("01-01 12:00:00.122", "4021", "4048", LogLevel.Info, "Garden", "Loaded 24 plants from local cache"),
            LogcatEntry("01-01 12:00:00.246", "4021", "4051", LogLevel.Debug, "Network", "GET /api/plants → 200 (142 ms)"),
            LogcatEntry("01-01 12:00:01.003", "4021", "4048", LogLevel.Warn, "ImageLoader", "Fallback artwork used for item 19"),
            LogcatEntry("01-01 12:00:02.744", "4021", "4051", LogLevel.Error, "Checkout", "Address validation rejected postal code"),
        )
        override fun stream(serial: String, filter: LogcatFilter): Flow<List<LogcatEntry>> = flowOf(rows.filter { it.level in filter.levels })
        override suspend fun snapshot(serial: String, filter: LogcatFilter, limit: Int) = rows.take(limit)
        override suspend fun clear(serial: String) = Unit
    }

    private object ScreenshotIntents : IntentService {
        override fun buildCommand(draft: IntentDraft) = listOf("am", "start", "-a", draft.action, "-d", draft.dataUri.ifBlank { "garden://plant/monstera" })
        override suspend fun send(serial: String, draft: IntentDraft) = CommandResult.success("Starting: Intent { act=${draft.action} }")
    }

    private object ScreenshotApps : AppService {
        override suspend fun listApps(serial: String) = listOf(AndroidApp("com.example.garden", "Garden", false, true, "2.4.0", "20400"), AndroidApp("com.example.checkout", "Checkout", false, true, "1.8.2", "10802"), AndroidApp("com.android.settings", "Settings", true, true, "36", "36"))
        override suspend fun getAppDetails(serial: String, packageName: String) = AndroidAppDetails("2.4.0", "20400", "26", "36", "v2", true)
        override suspend fun launch(serial: String, packageName: String) = CommandResult.success()
        override suspend fun launchActivity(serial: String, packageName: String, activityName: String) = CommandResult.success()
        override suspend fun stop(serial: String, packageName: String) = CommandResult.success()
        override suspend fun clearData(serial: String, packageName: String) = CommandResult.success()
        override suspend fun resetPermissions(serial: String, packageName: String) = CommandResult.success()
        override suspend fun uninstall(serial: String, packageName: String) = CommandResult.success()
        override suspend fun install(serial: String, apkPath: String, replace: Boolean) = CommandResult.success("Success")
        override suspend fun listPermissions(serial: String, packageName: String) = listOf(AndroidPermission("android.permission.CAMERA", true), AndroidPermission("android.permission.POST_NOTIFICATIONS", false), AndroidPermission("android.permission.ACCESS_FINE_LOCATION", true))
        override suspend fun listActivities(serial: String, packageName: String) = listOf(AndroidActivity(".MainActivity", true), AndroidActivity(".PlantDetailActivity", false))
        override suspend fun getIcon(serial: String, packageName: String): ByteArray? = null
    }

    private object ScreenshotFiles : FileService {
        override suspend fun list(serial: String, path: String) = listOf(DeviceFile("/sdcard/Download", "Download", true, null, "drwxrwx---", "2025-01-01 11:42"), DeviceFile("/sdcard/Download/garden-debug.apk", "garden-debug.apk", false, 18_430_122, "-rw-rw----", "2025-01-01 11:40"), DeviceFile("/sdcard/Pictures", "Pictures", true, null, "drwxrwx---", "2024-12-31 09:00"))
        override suspend fun pull(serial: String, remotePath: String, localPath: String) = CommandResult.success("1 file pulled")
        override suspend fun push(serial: String, localPath: String, remotePath: String) = CommandResult.success("1 file pushed")
        override suspend fun delete(serial: String, remotePath: String) = CommandResult.success()
    }

    private object ScreenshotHostFiles : HostFileService {
        private val document = HostFileDocument("/workspace/sample-app/app/src/main/AndroidManifest.xml", "<manifest package=\"com.example.garden\">\n  <application android:label=\"Garden\" />\n</manifest>", now, 122, languageHint = "XML")
        override suspend fun list(path: String) = listOf(HostFileEntry("/workspace/sample-app/app", "app", true, 0, now), HostFileEntry(document.path, "AndroidManifest.xml", false, 122, now, "xml", "XML"), HostFileEntry("/workspace/sample-app/README.md", "README.md", false, 520, now, "md", "Markdown"))
        override suspend fun read(path: String) = document
        override suspend fun save(path: String, content: String, expectedModifiedMillis: Long) = HostFileSaveResult.Saved(now)
        override suspend fun indexStatus(root: String) = HostIndexStatus(root, 42, 2_400_000, false, "Indexed 42 files", now)
        override fun indexRoot(root: String) = flowOf(HostIndexStatus(root, 42, 2_400_000, false, "Indexed 42 files", now))
        override suspend fun search(query: String, mode: HostSearchMode, roots: List<String>, limit: Int) = listOf(HostSearchResult(document.path, "/workspace/sample-app", HostSearchMatchKind.Content, 2, 3, "<application android:label=\"Garden\" />"))
    }

    private object ScreenshotProxy : ProxyService {
        private val exchange = NetworkExchange("flow-1", now, now + 142, "GET", "https://api.example.test/v1/plants", 200, "application/json", 8420, 142, mapOf("accept" to "application/json"), mapOf("content-type" to "application/json"), null, "{\"plants\":[…]}", null, "tls", "mock-profile", "flow-1")
        override val exchanges = flowOf(listOf(exchange))
        override val status = flowOf("Proxy listening on 127.0.0.1:9099")
        override val warnings = flowOf(listOf(ProxyWarning("warning-1", now, ProxyWarningKind.ClientTlsFailure, "One debug build rejected Andy's CA", "api.example.test")))
        override val clientConnectionCount = flowOf(2)
        override suspend fun detectMitmproxy() = CommandResult.success("mitmproxy 11.0")
        override suspend fun ensureCertificateAuthority() = CommandResult.success()
        override suspend fun certificateAuthorityPath() = "/workspace/andy-ca.pem"
        override suspend fun start(port: Int, rules: List<ProxyRule>, options: ProxyStartOptions) = CommandResult.success()
        override suspend fun updateRules(rules: List<ProxyRule>) = CommandResult.success()
        override suspend fun clearTraffic() = CommandResult.success()
        override suspend fun stop() = CommandResult.success()
        override suspend fun resolveDeviceProxyHost(serial: String) = "10.0.2.2"
        override suspend fun configureDeviceProxy(serial: String, host: String, port: Int) = CommandResult.success()
        override suspend fun clearDeviceProxy(serial: String) = CommandResult.success()
        override suspend fun diagnoseDeviceProxyRoute(serial: String, host: String, port: Int) = NetworkRouteDiagnostics("$host:$port", "$host:$port", true, false, routeSummary = "emulator → 10.0.2.2")
        override suspend fun openVpnSettings(serial: String) = CommandResult.success()
        override suspend fun prepareUserCertificateInstall(serial: String) = CommandResult.success()
        override suspend fun installSystemCertificateAuthority(serial: String) = CommandResult.success()
        override suspend fun activatePersistedCertificateAuthority(serial: String) = CommandResult.success()
        override suspend fun isCertificateInstalled(serial: String) = true
        override suspend fun isDeviceProxyConfigured(serial: String, host: String, port: Int) = true
    }

    private object ScreenshotMetrics : MetricsService {
        override fun stream(serial: String, packageName: String?) = flowOf(PerformanceSample(now, 31f, 412f, 58.7f, 87, "Normal", 128f, 44f, listOf(ProcessMetric("4021", "com.example.garden", 28f, 312f), ProcessMetric("901", "surfaceflinger", 6f, 98f)), listOf(FrameRenderMetric("draw", 8.4f), FrameRenderMetric("layout", 14.7f), FrameRenderMetric("jank", 23.1f, 16.7f))))
    }

    private object ScreenshotTracing : TracingService {
        private val recording = TraceRecording(
            id = "andy-20250101-120000",
            name = "checkout-jank",
            serial = serial,
            deviceLabel = "Pixel 8 API 36",
            presetId = "default",
            recordedAtMillis = now - 45_000,
            durationMs = 10_000,
            sizeBytes = 2_400_000,
            localPath = "/workspace/.andy/traces/checkout-jank.perfetto-trace",
        )
        override val status = MutableStateFlow(TraceRecordingStatus(phase = TracePhase.Idle, message = "API 36 · traced running"))
        override val recordings = MutableStateFlow(listOf(recording))
        override suspend fun checkSupport(serial: String) = CommandResult.success("API 36 · traced running")
        override suspend fun start(serial: String, configTextProto: String, name: String, presetId: String?) = CommandResult.success()
        override suspend fun stop() = CommandResult.success()
        override suspend fun refreshRecordings() = Unit
        override suspend fun deleteRecording(id: String) = true
        override suspend fun revealRecording(id: String) = CommandResult.success()
        override suspend fun importConfig(sourcePath: String) = CommandResult.success()
        override suspend fun listUserConfigs() = listOf(
            TraceUserConfig("user-checkout", "checkout-focus", "/workspace/.andy/trace-configs/checkout-focus.textproto"),
        )
        override suspend fun loadUserConfig(id: String) = "# custom checkout focus\nduration_ms: 10000\n"
        override suspend fun saveUserConfig(name: String, content: String) = CommandResult.success()
        override suspend fun deleteUserConfig(id: String) = true
        override suspend fun retryPull() = CommandResult.success()
    }

    private object ScreenshotTraceViewer : TraceViewerService {
        override suspend fun openExternally(traceId: String) = CommandResult.success()
        override fun shutdown() = Unit
    }

    private object ScreenshotSharedPrefs : SharedPrefsService {
        private val files = listOf("garden_prefs.xml", "com.example.garden_preferences.xml")
        private val entries = mapOf(
            "garden_prefs.xml" to listOf(
                PrefEntry("onboarding_complete", PrefType.Boolean, "true"),
                PrefEntry("plant_count", PrefType.Int, "24"),
                PrefEntry("display_name", PrefType.String, "Ada"),
                PrefEntry("last_sync_ms", PrefType.Long, now.toString()),
                PrefEntry("favorite_tags", PrefType.StringSet, "indoor,tropical"),
            ),
            "com.example.garden_preferences.xml" to listOf(
                PrefEntry("dark_mode", PrefType.Boolean, "false"),
                PrefEntry("grid_density", PrefType.Float, "1.25"),
            ),
        )
        override suspend fun listFiles(serial: String, packageName: String) = Result.success(files)
        override suspend fun read(serial: String, packageName: String, fileName: String) =
            Result.success(entries[fileName].orEmpty())
        override suspend fun upsert(serial: String, packageName: String, fileName: String, entry: PrefEntry) =
            CommandResult.success()
        override suspend fun delete(serial: String, packageName: String, fileName: String, key: String) =
            CommandResult.success()
    }

    private object ScreenshotAppDatabase : AppDatabaseService {
        private val database = AppDatabaseInfo(
            name = "garden.db",
            path = "databases/garden.db",
            hasWal = true,
            hasShm = true,
        )
        private val tableInfo = DbTableInfo(
            name = "plants",
            columns = listOf(
                DbColumnInfo("id", "INTEGER", primaryKey = true),
                DbColumnInfo("name", "TEXT", primaryKey = false),
                DbColumnInfo("species", "TEXT", primaryKey = false),
                DbColumnInfo("watered", "INTEGER", primaryKey = false),
            ),
            hasRowId = true,
        )
        private val browseResult = DbQueryResult(
            columns = listOf("__rowid__", "id", "name", "species", "watered"),
            rows = listOf(
                listOf("1", "1", "Monstera", "M. deliciosa", "1"),
                listOf("2", "2", "Fiddle Leaf", "F. lyrata", "0"),
                listOf("3", "3", "Snake Plant", "S. trifasciata", "1"),
            ),
        )
        override suspend fun listDatabases(serial: String, packageName: String) = Result.success(listOf(database))
        override suspend fun listTables(serial: String, packageName: String, dbName: String) =
            Result.success(listOf("android_metadata", "plants", "rooms"))
        override suspend fun tableRowCounts(
            serial: String,
            packageName: String,
            dbName: String,
            tables: List<String>,
        ) = Result.success(
            // Put plants first so DatabasePane's "first table with rows" selection shows data.
            mapOf(
                "plants" to 24L,
                "rooms" to 3L,
                "android_metadata" to 1L,
            ).filterKeys { tables.isEmpty() || it in tables },
        )
        override suspend fun tableInfo(serial: String, packageName: String, dbName: String, tableName: String) =
            Result.success(if (tableName == "plants") tableInfo else tableInfo.copy(name = tableName, columns = emptyList()))
        override suspend fun browseTable(
            serial: String,
            packageName: String,
            dbName: String,
            tableName: String,
            limit: Int,
            offset: Int,
        ) = Result.success(if (tableName == "plants") browseResult else DbQueryResult(emptyList(), emptyList()))
        override suspend fun query(serial: String, packageName: String, dbName: String, sql: String, limit: Int) =
            Result.success(browseResult)
        override suspend fun updateCell(
            serial: String,
            packageName: String,
            dbName: String,
            tableName: String,
            column: String,
            newValue: String?,
            rowId: Long?,
            primaryKeyColumn: String?,
            primaryKeyValue: String?,
        ) = CommandResult.success()
        override suspend fun pullToHost(serial: String, packageName: String, dbName: String, localPath: String) =
            CommandResult.success()
        override suspend fun listSavedQueries(packageName: String) = listOf(
            SavedSqlQuery(
                id = "q-active-plants",
                name = "Active plants",
                sql = "SELECT id, name, species FROM plants WHERE watered = 1",
                packageName = gardenPackage,
                updatedAtMillis = now - 86_400_000,
            ),
        )
        override suspend fun saveQuery(packageName: String, name: String, sql: String) = CommandResult.success()
        override suspend fun deleteQuery(packageName: String, id: String) = true
    }

    private object ScreenshotAccessibility : AccessibilityService {
        override suspend fun dump(serial: String) = AccessibilityNode("root", "android.widget.FrameLayout", "com.example.garden", "root", null, null, bounds = "[0,0][1080,2400]", clickable = false, focusable = false, enabled = true, children = listOf(AccessibilityNode("title", "android.widget.TextView", "com.example.garden", "com.example.garden:id/title", "My garden", null, bounds = "[48,120][640,210]", clickable = true, focusable = true, enabled = true), AccessibilityNode("fab", "android.widget.Button", "com.example.garden", "com.example.garden:id/add", null, "Add plant", bounds = "[880,2100][1040,2260]", clickable = true, focusable = true, enabled = true)))
    }

    private object ScreenshotBugs : BugService {
        private val report = BugReport("bug-001", "Checkout address validation", "Postal code remains red after correction.", serial, "Pixel 8", "36", "arm64-v8a", "1080x2400", now, now - 20_000, now, listOf(BugAction("tap-1", now - 15_000, "Tap", "Checkout"), BugAction("input-1", now - 8_000, "Text", "Enter postal code", "60601")), listOf(BugArtifact("logcat.txt", "bug-001/logcat.txt", "log", 1024)), now - 20_000, now, 60.0, listOf(now - 20_000, now - 19_983, now - 19_966))
        override val status = flowOf(BugCaptureStatus(true, serial, 2, 18, 3, "Capturing Pixel 8 API 36"))
        override suspend fun startCapture(serial: String, device: AndroidDevice?) = Unit
        override suspend fun stopCapture() = Unit
        override suspend fun beginRecording() = Unit
        override fun recordAction(kind: String, label: String, detail: String?) = Unit
        override suspend fun saveBug(draft: BugCaptureDraft, device: AndroidDevice?) = report
        override suspend fun saveRecording(device: AndroidDevice?) = report.copy(id = "recording-001", title = "Screen recording")
        override suspend fun listBugs() = listOf(report)
        override suspend fun listRecordings() = listOf(report.copy(id = "recording-001", title = "Screen recording"))
        override suspend fun loadBug(id: String) = report.takeIf { it.id == id }
        override suspend fun loadBugLog(id: String) = "01-01 12:00:00 E Checkout: Address validation rejected postal code"
        override suspend fun deleteBug(id: String) = true
        override suspend fun exportBug(id: String) = "/workspace/bug-001.zip"
        override fun playbackFrames(id: String, startFrameIndex: Int) = ScreenshotMirror.frames
        override suspend fun bugVideoFrameCount(id: String) = 3
        override suspend fun loadBugVideoFrame(id: String, frameIndex: Int) =
            MirrorFrame(270, 600, IntArray(270 * 600) { 0xff1b2631.toInt() }, frameNumber = frameIndex.toLong())
    }

    private object ScreenshotArtifacts : ArtifactService {
        override suspend fun saveScreenshot(serial: String, suggestedName: String) = CommandResult.success("Saved $suggestedName")
        override suspend fun saveBugReport(serial: String, suggestedName: String) = CommandResult.success("Saved $suggestedName")
    }

    private class ScreenshotWorkspaceStore(initial: WorkspaceState) : WorkspaceStore {
        private var state = initial
        override suspend fun load() = state
        override suspend fun save(state: WorkspaceState) { this.state = state }
    }

    private object ScreenshotUpdates : AppUpdateService {
        override val state = MutableStateFlow<AppUpdateState>(AppUpdateState.Current)
        override val pendingInstallConfirmation = MutableStateFlow<AvailableUpdate?>(null)
        override suspend fun checkForUpdates(onFailure: (Throwable) -> Unit) = Unit
        override suspend fun installAvailableUpdate(onMessage: (String) -> Unit) = Unit
        override fun respondToInstallConfirmation(install: Boolean) = Unit
    }

    private object ScreenshotMcp : McpServerService {
        override val status = flowOf("running on 127.0.0.1:8565")
        override val running = flowOf(true)
        override suspend fun start(port: Int) = CommandResult.success()
        override suspend fun stop() = CommandResult.success()
        override fun getSnippet(clientName: String, port: Int) = "andy mcp --port $port"
        override fun getClients() = listOf("Codex", "Claude Code")
        override fun isAutoWriteSupported(clientName: String) = true
        override fun writeConfig(clientName: String, port: Int) = true
        override fun getToolNames() = listOf("list_devices", "capture_screenshot", "run_shell")
    }

    private object ScreenshotActionConfig : ActionConfigStore {
        private val config = ActionsConfig(listOf(ActionProject("garden", "Garden Android", "/workspace/sample-app", mapOf("ANDROID_HOME" to "/Android/sdk"), listOf(ProjectAction("test", "Run unit tests", "run", "./gradlew test"), ProjectAction("lint", "Lint", "check", "./gradlew lint")))))
        override suspend fun load() = config
        override suspend fun save(config: ActionsConfig) = Unit
    }

    private object ScreenshotActionRuns : ActionRunService {
        override val running: StateFlow<List<RunningAction>> = MutableStateFlow(listOf(RunningAction("run-1", "garden", "test", "Run unit tests", "run", "./gradlew test", "/workspace/sample-app", ActionRunStatus.Running, startedAtMillis = now - 9_000)))
        override fun openShell(project: ActionProject) = "shell-1"
        override fun run(project: ActionProject, action: ProjectAction) = "run-1"
        override fun stop(runId: String) = Unit
        override fun clear(runId: String) = Unit
    }

    private object ScreenshotAgentRuns : AgentRunService {
        private val task = AgentTask("task-1", "Tighten checkout validation", "Fix the empty postal code validation and add a regression test.", AgentKind.Codex, "garden", "/workspace/sample-app", "/workspace/sample-app", status = AgentTaskStatus.Completed, createdAtMillis = now - 40_000, startedAtMillis = now - 39_000, finishedAtMillis = now - 3_000, totalCostUsd = 0.18, inputTokens = 2_420, outputTokens = 860, contextTokens = 12_400, contextWindowTokens = 128_000, unread = true)
        override val tasks = MutableStateFlow(listOf(task))
        override val cliStatuses = MutableStateFlow(AgentKind.entries.map { AgentCliStatus(it, "/usr/local/bin/${it.cliName}", "1.0.0") })
        override val providerQuotas = MutableStateFlow(mapOf(AgentKind.Codex to AgentProviderQuota(listOf(AgentQuotaWindow("5 hour", 0.64f, now + 10_800_000)), now)))
        override val quotaAccess = MutableStateFlow(AgentQuotaAccess())
        override val providerDefaults = MutableStateFlow(mapOf(AgentKind.Codex to AgentProviderDefaults(model = "gpt-5.2-codex", reasoningEffort = AgentReasoningEffort.High)))
        override val lastUsedAgent = MutableStateFlow<AgentKind?>(AgentKind.Codex)
        override suspend fun refreshProviderQuotas() = Unit
        override fun setQuotaAccess(agent: AgentKind, enabled: Boolean) = Unit
        override fun skills(agent: AgentKind, directory: String?) = MutableStateFlow(listOf(AgentSkill("compose-expert", "Compose UI guidance", "/skills/compose-expert/SKILL.md")))
        override fun refreshSkills(agent: AgentKind, directory: String?) = Unit
        override suspend fun createAndStart(draft: AgentTaskDraft) = task
        override suspend fun startImplementation(taskId: String) = Unit
        override fun stop(taskId: String) = Unit
        override suspend fun retry(taskId: String) = Unit
        override fun resume(taskId: String, followUp: String, imagePaths: List<String>, skills: List<AgentSkill>) = Unit
        override fun respondToUserInput(taskId: String, requestId: String, answers: Map<String, String>) = Unit
        override fun queueFollowUp(taskId: String, followUp: String, imagePaths: List<String>, skills: List<AgentSkill>) = Unit
        override fun removeQueuedFollowUp(taskId: String, queueIndex: Int) = Unit
        override fun updateGoal(taskId: String, goal: String?) = Unit
        override suspend fun delete(taskId: String, removeWorktree: Boolean) = Unit
        override fun markRead(taskId: String) = Unit
        override fun markUnread(taskId: String) = Unit
        override fun archive(taskId: String) = Unit
        override fun unarchive(taskId: String) = Unit
        override fun events(taskId: String) = MutableStateFlow(listOf<AgentEvent>(AgentEvent.UserMessage(now - 39_000, task.prompt), AgentEvent.ToolCall(now - 31_000, "rg", "Find validation reducer"), AgentEvent.AssistantText(now - 5_000, "Updated the reducer and added a focused test."), AgentEvent.TaskResult(now - 3_000, true, "Checkout validation is covered.", 0.18, inputTokens = 2420, outputTokens = 860, durationMs = 36_000)))
        override fun interactiveResumeCommand(taskId: String) = "codex resume task-1"
        override suspend fun openInTerminal(taskId: String) = CommandResult.success()
        override suspend fun openSkill(path: String) = CommandResult.success()
        override suspend fun worktreeDiffSummary(taskId: String) = "2 files changed, 28 insertions(+), 4 deletions(-)"
        override suspend fun changeSummary(taskId: String) = AgentChangeSummary(listOf(AgentFileChange("app/src/main/.../CheckoutReducer.kt", 14, 4), AgentFileChange("app/src/test/.../CheckoutReducerTest.kt", 14, 0)))
        override suspend fun fileDiff(taskId: String, relativePath: String) = AgentFileDiff(relativePath, listOf(DiffLine(DiffLineKind.Context, "fun validatePostalCode(value: String) {", 12, 12), DiffLine(DiffLineKind.Deletion, "  return value.isNotBlank()", 13, null), DiffLine(DiffLineKind.Addition, "  return value.matches(POSTAL_CODE)", null, 13)))
        override suspend fun refreshCliStatuses() = Unit
        override suspend fun isGitRepo(dir: String) = true
    }

    private object ScreenshotProjectWorkflows : ProjectWorkflowService {
        private val specProfile = ProjectAgentProfile(
            agent = AgentKind.Codex,
            model = "gpt-5.6-sol",
            reasoningEffort = AgentReasoningEffort.ExtraHigh,
            autonomy = AgentAutonomy.ReadOnly,
            sandboxMode = AgentSandboxMode.ReadOnly,
        )
        private val buildProfile = ProjectAgentProfile(
            agent = AgentKind.ClaudeCode,
            model = "sonnet",
            reasoningEffort = AgentReasoningEffort.Medium,
            sandboxMode = AgentSandboxMode.WorkspaceWrite,
            useWorktree = true,
        )
        private val reviewProfile = ProjectAgentProfile(
            agent = AgentKind.Codex,
            model = "gpt-5.6-orbit",
            reasoningEffort = AgentReasoningEffort.High,
            autonomy = AgentAutonomy.Standard,
            sandboxMode = AgentSandboxMode.WorkspaceWrite,
            attachAndyMcp = true,
        )
        private val verifyProfile = ProjectAgentProfile(
            agent = AgentKind.Codex,
            model = "gpt-5.6-terra",
            reasoningEffort = AgentReasoningEffort.High,
            sandboxMode = AgentSandboxMode.ReadOnly,
        )
        private val planText = """
            1. Introduce typed Spec, Build, and Verification records with immutable attempt inputs.
            2. Freeze the selected plan when creating each Build/Verification pair.
            3. Alternate fresh build and verifier sessions in one reusable worktree.
            4. Complete only after a structured passing verifier verdict.
        """.trimIndent()
        private val spec = ProjectTask(
            id = "spec-checkout", projectId = "garden", kind = ProjectTaskKind.Spec,
            title = "Checkout resilience", instructions = "Plan a robust checkout validation workflow.",
            profile = specProfile, includeScratchpad = true, state = ProjectTaskState.Completed,
            planVersions = listOf(
                ProjectPlanVersion(1, planText.substringBeforeLast("\n"), "run-spec-1", now - 80_000),
                ProjectPlanVersion(2, planText, "run-spec-2", now - 60_000),
            ),
            grillMeEnabled = true,
            attempts = listOf(
                ProjectTaskAttempt("run-spec-1", ProjectWorkflowStage.Spec, 1, "Plan checkout", specProfile, "Release before Friday", now - 90_000),
                ProjectTaskAttempt("run-spec-2", ProjectWorkflowStage.Spec, 2, "Revise the plan", specProfile, "Release before Friday", now - 70_000),
            ),
            createdAtMillis = now - 100_000, updatedAtMillis = now - 60_000,
        )
        private val build = ProjectTask(
            id = "build-checkout", projectId = "garden", kind = ProjectTaskKind.Build,
            title = "Checkout validation", instructions = "Preserve the public reducer API.",
            profile = buildProfile, includeScratchpad = false, state = ProjectTaskState.NeedsAttention,
            linkedSpecTaskId = spec.id, linkedReviewTaskId = "review-checkout", linkedVerificationTaskId = "verify-checkout",
            planSnapshot = ProjectPlanSnapshot(planText, spec.id, 2, "Checkout resilience · v2"),
            buildNotes = "Keep the existing state shape and add focused regression coverage.",
            reviewEnabled = true,
            reviewInstructions = "Pay special attention to validation bypasses and reducer state compatibility.",
            reviewGeneration = 2,
            verificationInstructions = "Run compileKotlinDesktop and the checkout reducer tests.",
            maxBudgetUsd = 2.0, paused = true, workspacePath = "/workspace/.andy/worktrees/checkout",
            worktreePath = "/workspace/.andy/worktrees/checkout", branchName = "andy/claude/checkout-validation",
            worktreeOwnerRunId = "run-build-1",
            attempts = listOf(
                ProjectTaskAttempt("run-build-1", ProjectWorkflowStage.Build, 1, "Implement frozen plan", buildProfile, null, now - 50_000),
                ProjectTaskAttempt("run-build-2", ProjectWorkflowStage.Build, 2, "Fix verifier findings", buildProfile, null, now - 25_000),
            ),
            lastError = "review requested changes; inspect the blocking validation finding",
            createdAtMillis = now - 58_000, updatedAtMillis = now - 5_000,
        )
        private val review = ProjectTask(
            id = "review-checkout", projectId = "garden", kind = ProjectTaskKind.Review,
            title = "Review Checkout validation", instructions = build.reviewInstructions,
            profile = reviewProfile, includeScratchpad = false, state = ProjectTaskState.Failed,
            linkedSpecTaskId = spec.id, linkedBuildTaskId = build.id, linkedVerificationTaskId = "verify-checkout",
            planSnapshot = build.planSnapshot, reviewEnabled = true, reviewInstructions = build.reviewInstructions,
            reviewGeneration = 2,
            attempts = listOf(
                ProjectTaskAttempt("run-review-1", ProjectWorkflowStage.Review, 1, "Review first build", reviewProfile, null, now - 43_000, "run-build-1", 2),
                ProjectTaskAttempt("run-review-2", ProjectWorkflowStage.Review, 2, "Review rebuilt checkout", reviewProfile, null, now - 10_000, "run-build-2", 2),
            ),
            reviewVerdicts = listOf(
                ProjectReviewVerdict(
                    ProjectReviewStatus.Approved,
                    "The first implementation is maintainable and aligned with the plan.",
                    listOf(ProjectReviewFinding(ProjectReviewFindingSeverity.Nit, "Test naming", "The reducer case name could be more direct.")),
                    "run-review-1", "run-build-1", 2, now - 41_000,
                ),
                ProjectReviewVerdict(
                    ProjectReviewStatus.ChangesRequested,
                    "One validation path can still accept an empty postal code.",
                    listOf(
                        ProjectReviewFinding(
                            ProjectReviewFindingSeverity.Blocking,
                            "Empty postal code bypasses validation",
                            "The fallback branch returns success before the shared address validator runs.",
                            "src/commonMain/kotlin/checkout/CheckoutReducer.kt",
                            184,
                        ),
                        ProjectReviewFinding(ProjectReviewFindingSeverity.Warning, "Missing negative case", "Add coverage for whitespace-only postal codes."),
                    ),
                    "run-review-2", "run-build-2", 2, now - 5_000,
                ),
            ),
            createdAtMillis = now - 58_000, updatedAtMillis = now - 5_000,
        )
        private val verification = ProjectTask(
            id = "verify-checkout", projectId = "garden", kind = ProjectTaskKind.Verification,
            title = "Verify Checkout validation", instructions = "Run compileKotlinDesktop and the checkout reducer tests.",
            profile = verifyProfile, includeScratchpad = false, state = ProjectTaskState.Waiting,
            linkedSpecTaskId = spec.id, linkedBuildTaskId = build.id,
            planSnapshot = build.planSnapshot, verificationInstructions = build.verificationInstructions,
            attempts = listOf(
                ProjectTaskAttempt("run-verify-1", ProjectWorkflowStage.Verification, 1, "Verify attempt one", verifyProfile, null, now - 35_000, "run-build-1", 2),
            ),
            verdicts = listOf(
                ProjectVerificationVerdict(ProjectVerificationStatus.Failed, "The empty postal-code case still regresses.", listOf("Desktop compilation passed", "41 reducer tests passed"), listOf("CheckoutReducerTest.empty postal code failed"), "run-verify-1", now - 30_000, "run-build-1", 2),
            ),
            createdAtMillis = now - 58_000, updatedAtMillis = now - 5_000,
        )
        private val completedBuild = ProjectTask(
            id = "build-search", projectId = "garden", kind = ProjectTaskKind.Build,
            title = "Search indexing", instructions = "", profile = buildProfile.copy(useWorktree = false),
            includeScratchpad = false, state = ProjectTaskState.Completed,
            linkedReviewTaskId = "review-search", linkedVerificationTaskId = "verify-search",
            planSnapshot = ProjectPlanSnapshot("Keep indexing incremental and deterministic."),
            reviewEnabled = false, reviewGeneration = 1, verificationInstructions = "Run search index tests.",
            attempts = listOf(ProjectTaskAttempt("run-build-search", ProjectWorkflowStage.Build, 1, "Build search indexing", buildProfile, null, now - 180_000)),
            createdAtMillis = now - 200_000, updatedAtMillis = now - 120_000,
        )
        private val disabledReview = ProjectTask(
            id = "review-search", projectId = "garden", kind = ProjectTaskKind.Review,
            title = "Review Search indexing", instructions = "Review index invalidation.", profile = reviewProfile,
            includeScratchpad = false, state = ProjectTaskState.Disabled, linkedBuildTaskId = completedBuild.id,
            linkedVerificationTaskId = "verify-search", planSnapshot = completedBuild.planSnapshot,
            reviewEnabled = false, reviewInstructions = "Review index invalidation.", reviewGeneration = 1,
            attempts = listOf(ProjectTaskAttempt("run-review-search", ProjectWorkflowStage.Review, 1, "Review search", reviewProfile, null, now - 160_000, "run-build-search", 1)),
            reviewVerdicts = listOf(ProjectReviewVerdict(ProjectReviewStatus.Approved, "Index invalidation is scoped and deterministic.", emptyList(), "run-review-search", "run-build-search", 1, now - 150_000)),
            createdAtMillis = now - 190_000, updatedAtMillis = now - 100_000,
        )
        private val completedVerification = ProjectTask(
            id = "verify-search", projectId = "garden", kind = ProjectTaskKind.Verification,
            title = "Verify Search indexing", instructions = "Run search index tests.", profile = verifyProfile,
            includeScratchpad = false, state = ProjectTaskState.Completed, linkedBuildTaskId = completedBuild.id,
            planSnapshot = completedBuild.planSnapshot, verificationInstructions = "Run search index tests.",
            createdAtMillis = now - 190_000, updatedAtMillis = now - 100_000,
        )
        override val projects = MutableStateFlow(
            mapOf(
                "garden" to ProjectWorkflowState(
                    projectId = "garden",
                    scratchpad = "Release before Friday.\n\nKeep checkout state source-compatible.\n\nUseful command: ./gradlew desktopTest --tests '*CheckoutReducerTest*'",
                    profiles = mapOf(ProjectTaskKind.Spec to specProfile, ProjectTaskKind.Build to buildProfile, ProjectTaskKind.Review to reviewProfile, ProjectTaskKind.Verification to verifyProfile),
                    tasks = listOf(spec, build, review, verification, completedBuild, disabledReview, completedVerification),
                    legacyNotesMigrated = true,
                ),
            ),
        )
        override suspend fun ensureProject(projectId: String) = Unit
        override suspend fun updateScratchpad(projectId: String, text: String) = Unit
        override suspend fun updateProfile(projectId: String, kind: ProjectTaskKind, profile: ProjectAgentProfile) = Unit
        override suspend fun saveSpec(draft: ProjectSpecDraft) = spec.id
        override suspend fun runSpec(taskId: String, revisionRequest: String?) = Unit
        override suspend fun saveBuildPair(draft: ProjectBuildPairDraft) = build.id
        override suspend fun startBuildPair(buildTaskId: String) = Unit
        override fun pauseBuildPair(buildTaskId: String) = Unit
        override fun stopBuildPair(buildTaskId: String) = Unit
        override suspend fun resumeBuildPair(buildTaskId: String) = Unit
        override suspend fun startRecoveryFollowUp(buildTaskId: String, followUp: String): String? = null
        override suspend fun startRecoveryReview(buildTaskId: String): String? = null
        override suspend fun deleteTask(taskId: String, cascade: Boolean) = Unit
        override suspend fun deleteProject(projectId: String) = Unit
    }
}
