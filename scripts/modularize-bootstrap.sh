#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

mk_module_src() {
  local module_path="$1"
  mkdir -p "$module_path/src/commonMain/kotlin"
  mkdir -p "$module_path/src/commonTest/kotlin"
  mkdir -p "$module_path/src/desktopMain/kotlin"
  mkdir -p "$module_path/src/androidMain/kotlin"
  mkdir -p "$module_path/src/wasmJsMain/kotlin"
}

write_domain_build() {
  cat > domain/build.gradle.kts <<'EOF'
plugins {
    id("andy.domain")
}
EOF
}

write_navigation_build() {
  cat > navigation/build.gradle.kts <<'EOF'
plugins {
    id("andy.compose.library")
    id("andy.metro")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain"))
            implementation(project(":ui:core"))
        }
    }
}
EOF
}

write_core_platform_build() {
  cat > core/platform/build.gradle.kts <<'EOF'
plugins {
    id("andy.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain"))
        }
    }
}
EOF
}

write_core_di_build() {
  cat > core/di/build.gradle.kts <<'EOF'
plugins {
    id("andy.kmp.library")
    id("andy.metro")
}

kotlin {
    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(project(":data:devices"))
                implementation(project(":data:mirror"))
                implementation(project(":data:agents"))
                implementation(project(":data:network"))
                implementation(project(":data:workspace"))
                implementation(project(":data:artifacts"))
                implementation(project(":data:platform-tools"))
                implementation(project(":data:host"))
                implementation(project(":data:updates"))
                implementation(project(":data:remote"))
            }
        }
        commonMain.dependencies {
            implementation(project(":domain"))
            implementation(project(":core:platform"))
        }
    }
}
EOF
}

write_ui_core_build() {
  cat > ui/core/build.gradle.kts <<'EOF'
plugins {
    id("andy.ui")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain"))
        }
    }
}
EOF
}

write_ui_components_build() {
  cat > ui/components/build.gradle.kts <<'EOF'
plugins {
    id("andy.ui")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":ui:core"))
            implementation(project(":domain"))
            implementation(project(":navigation"))
        }
    }
}
EOF
}

write_ui_preview_build() {
  cat > ui/preview/build.gradle.kts <<'EOF'
plugins {
    id("andy.ui")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":ui:core"))
            implementation(project(":ui:components"))
            implementation(project(":domain"))
        }
    }
}
EOF
}

write_ui_shell_build() {
  cat > ui/shell/build.gradle.kts <<'EOF'
plugins {
    id("andy.compose.library")
    id("andy.metro")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain"))
            implementation(project(":navigation"))
            implementation(project(":ui:core"))
            implementation(project(":ui:components"))
            implementation(project(":feature:devices"))
            implementation(project(":feature:catalog"))
            implementation(project(":feature:live"))
            implementation(project(":feature:apps"))
            implementation(project(":feature:logcat"))
            implementation(project(":feature:intents"))
            implementation(project(":feature:files"))
            implementation(project(":feature:computer-files"))
            implementation(project(":feature:network"))
            implementation(project(":feature:actions"))
            implementation(project(":feature:agents"))
            implementation(project(":feature:snapshots"))
            implementation(project(":feature:controls"))
            implementation(project(":feature:performance"))
            implementation(project(":feature:tracing"))
            implementation(project(":feature:design"))
            implementation(project(":feature:inspector"))
            implementation(project(":feature:bugs"))
            implementation(project(":feature:recordings"))
            implementation(project(":feature:settings"))
        }
    }
}
EOF
}

write_feature_build() {
  local name="$1"
  local extra_deps="${2:-}"
  cat > "feature/${name}/build.gradle.kts" <<EOF
plugins {
    id("andy.feature")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":ui:components"))
            ${extra_deps}
        }
    }
}
EOF
}

write_data_build() {
  local name="$1"
  cat > "data/${name}/build.gradle.kts" <<'EOF'
plugins {
    id("andy.data")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:platform"))
        }
    }
}
EOF
  # replace data module name in path - file already written to data/${name}/
  true
}

# Create module directories
for m in domain navigation; do mk_module_src "$m"; done
for m in core/platform core/di; do mk_module_src "$m"; done
for m in ui/core ui/components ui/preview ui/shell; do mk_module_src "$m"; done
for m in devices mirror agents network workspace artifacts platform-tools host updates remote; do mk_module_src "data/$m"; done
for m in devices catalog live apps logcat intents files computer-files network actions agents snapshots controls performance tracing design inspector bugs recordings settings; do
  mk_module_src "feature/$m"
done

write_domain_build
write_navigation_build
write_core_platform_build
write_core_di_build
write_ui_core_build
write_ui_components_build
write_ui_preview_build
write_ui_shell_build

for m in devices catalog live apps logcat intents files computer-files network actions agents snapshots controls performance tracing design inspector bugs recordings settings; do
  write_feature_build "$m"
done

for m in devices mirror agents network workspace artifacts platform-tools host updates remote; do
  write_data_build "$m"
done

# --- Source moves from composeApp ---

move_dir() {
  local src="$1"
  local dst="$2"
  if [[ -d "$src" && "$(ls -A "$src" 2>/dev/null)" ]]; then
    mkdir -p "$(dirname "$dst")"
    git mv "$src" "$dst"
  fi
}

move_file() {
  local src="$1"
  local dst="$2"
  if [[ -f "$src" ]]; then
    mkdir -p "$(dirname "$dst")"
    git mv "$src" "$dst"
  fi
}

CA=composeApp/src/commonMain/kotlin/app/andy
CD=composeApp/src/desktopMain/kotlin/app/andy
CW=composeApp/src/wasmJsMain/kotlin/app/andy
CT=composeApp/src/commonTest/kotlin/app/andy
DT=composeApp/src/desktopTest/kotlin/app/andy

# Domain
move_dir "$CA/model" domain/src/commonMain/kotlin/app/andy/model
move_dir "$CA/domain" domain/src/commonMain/kotlin/app/andy/domain
for f in Services.kt UnavailableServices.kt MirrorCaptureSizing.kt IosTargetRegistry.kt \
  AutomationService.kt CliUpdateCheck.kt DhuModels.kt DhuService.kt InvestigationEvidenceService.kt \
  LocalServers.kt OrchestrationPreferences.kt RemoteSession.kt RuntimeBundle.kt \
  RoutingAppDatabaseService.kt RoutingAppService.kt RoutingCrashInspectorService.kt \
  RoutingFileService.kt RoutingIntentService.kt RoutingLogcatService.kt RoutingMirrorEngine.kt \
  RoutingSharedPrefsService.kt; do
  move_file "$CA/service/$f" "domain/src/commonMain/kotlin/app/andy/service/$f"
done
move_dir "$CA/service" domain/src/commonMain/kotlin/app/andy/service 2>/dev/null || true
move_dir "$CA/transfer" domain/src/commonMain/kotlin/app/andy/transfer 2>/dev/null || true

# Navigation - extract AndyDestination from AndyApp.kt later via split; move whole AndyApp extensions file for now
move_file "$CA/AndyApp.kt" navigation/src/commonMain/kotlin/app/andy/AndyApp.kt

# UI core
move_dir "$CA/ui/theme" ui/core/src/commonMain/kotlin/app/andy/ui/theme

# UI components
move_dir "$CA/ui/components" ui/components/src/commonMain/kotlin/app/andy/ui/components

# Features
declare -A FEAT_MAP=(
  [devices]=devices
  [catalog]=catalog
  [live]=live
  [apps]=apps
  [logcat]=logcat
  [intents]=intents
  [files]=files
  [computer-files]=hostfiles
  [network]=network
  [actions]=actions
  [agents]=agents
  [snapshots]=snapshots
  [controls]=controls
  [performance]=performance
  [tracing]=tracing
  [design]=design
  [inspector]=inspector
  [bugs]=bugs
  [recordings]=live
  [settings]=settings
)
for feat in "${!FEAT_MAP[@]}"; do
  pkg="${FEAT_MAP[$feat]}"
  if [[ "$feat" != "recordings" ]]; then
    move_dir "$CA/ui/$pkg" "feature/$feat/src/commonMain/kotlin/app/andy/ui/$pkg"
  fi
done
move_dir "$CA/ui/automations" feature/agents/src/commonMain/kotlin/app/andy/ui/automations
move_dir "$CA/ui/artifacts" feature/bugs/src/commonMain/kotlin/app/andy/ui/artifacts
move_dir "$CA/ui/hierarchy" feature/inspector/src/commonMain/kotlin/app/andy/ui/hierarchy

# UI shell
move_dir "$CA/ui/shell" ui/shell/src/commonMain/kotlin/app/andy/ui/shell

# Core platform - expect/actual declarations at app root
for f in Clipboard.kt ClipboardImages.kt DirectoryPicker.kt EditorSyntaxThemePreview.kt \
  ExternalFileDrop.kt HostCodeEditor.kt ImageDropTarget.kt ResizeCursors.kt SnapshotImageLoader.kt \
  SupportedImageFiles.kt PlatformText.kt BrowserSurface.kt BrowserElementAnnotate.kt \
  BugLogcatTextSurface.kt MirrorVideoSurface.kt MirrorSurfaceReady.kt NewChatBackgroundUri.kt; do
  move_file "$CA/$f" "core/platform/src/commonMain/kotlin/app/andy/$f"
done

# Data - desktop implementations
move_dir "$CD/desktop/service/devices" data/devices/src/desktopMain/kotlin/app/andy/desktop/service/devices 2>/dev/null || true
move_file "$CD/desktop/service/DesktopDeviceService.kt" data/devices/src/desktopMain/kotlin/app/andy/desktop/service/DesktopDeviceService.kt
move_file "$CD/desktop/service/DesktopAvdService.kt" data/devices/src/desktopMain/kotlin/app/andy/desktop/service/DesktopAvdService.kt
move_file "$CD/desktop/service/AvdHomeScanner.kt" data/devices/src/desktopMain/kotlin/app/andy/desktop/service/AvdHomeScanner.kt
move_file "$CD/desktop/service/SdkLocator.kt" data/devices/src/desktopMain/kotlin/app/andy/desktop/service/SdkLocator.kt
move_dir "$CD/desktop/service/ios" data/devices/src/desktopMain/kotlin/app/andy/desktop/service/ios
move_dir "$CD/desktop/service/emulator" data/devices/src/desktopMain/kotlin/app/andy/desktop/service/emulator

move_dir "$CD/desktop/service/mirror" data/mirror/src/desktopMain/kotlin/app/andy/desktop/service/mirror
move_dir "$CD/desktop/service/agents" data/agents/src/desktopMain/kotlin/app/andy/desktop/service/agents
move_file "$CD/desktop/service/McpAgentRunClient.kt" data/agents/src/desktopMain/kotlin/app/andy/desktop/service/McpAgentRunClient.kt
move_file "$CD/desktop/service/McpAgentTools.kt" data/agents/src/desktopMain/kotlin/app/andy/desktop/service/McpAgentTools.kt
move_file "$CD/desktop/service/McpAutomationTools.kt" data/agents/src/desktopMain/kotlin/app/andy/desktop/service/McpAutomationTools.kt
move_file "$CD/desktop/service/McpClientConfig.kt" data/agents/src/desktopMain/kotlin/app/andy/desktop/service/McpClientConfig.kt
move_file "$CD/desktop/service/McpServerService.kt" data/agents/src/desktopMain/kotlin/app/andy/desktop/service/McpServerService.kt
move_file "$CD/desktop/service/McpAgentTaskListSync.kt" data/agents/src/desktopMain/kotlin/app/andy/desktop/service/McpAgentTaskListSync.kt
move_file "$CD/desktop/service/McpUnixSocketServer.kt" data/agents/src/desktopMain/kotlin/app/andy/desktop/service/McpUnixSocketServer.kt
move_file "$CD/desktop/service/ChatSubscribeSupport.kt" data/agents/src/desktopMain/kotlin/app/andy/desktop/service/ChatSubscribeSupport.kt
move_dir "$CD/desktop/service/automations" data/agents/src/desktopMain/kotlin/app/andy/desktop/service/automations

move_dir "$CD/desktop/service/proxy" data/network/src/desktopMain/kotlin/app/andy/desktop/service/proxy

move_file "$CD/desktop/service/DesktopActionConfigStore.kt" data/workspace/src/desktopMain/kotlin/app/andy/desktop/service/DesktopActionConfigStore.kt
move_file "$CD/desktop/service/DesktopActionRunService.kt" data/workspace/src/desktopMain/kotlin/app/andy/desktop/service/DesktopActionRunService.kt
move_file "$CD/desktop/service/WorkspaceStore.kt" data/workspace/src/desktopMain/kotlin/app/andy/desktop/service/WorkspaceStore.kt
move_file "$CD/desktop/service/KanbanService.kt" data/workspace/src/desktopMain/kotlin/app/andy/desktop/service/KanbanService.kt

move_file "$CD/desktop/service/DesktopBugService.kt" data/artifacts/src/desktopMain/kotlin/app/andy/desktop/service/DesktopBugService.kt
move_file "$CD/desktop/service/BugJson.kt" data/artifacts/src/desktopMain/kotlin/app/andy/desktop/service/BugJson.kt
move_file "$CD/desktop/service/DesktopArtifactService.kt" data/artifacts/src/desktopMain/kotlin/app/andy/desktop/service/DesktopArtifactService.kt
move_file "$CD/desktop/service/InvestigationEvidenceService.kt" data/artifacts/src/desktopMain/kotlin/app/andy/desktop/service/InvestigationEvidenceService.kt 2>/dev/null || true
move_file "$CD/desktop/service/InvestigationCapture.kt" data/artifacts/src/desktopMain/kotlin/app/andy/desktop/service/InvestigationCapture.kt
move_file "$CD/desktop/service/InvestigationBundle.kt" data/artifacts/src/desktopMain/kotlin/app/andy/desktop/service/InvestigationBundle.kt
move_file "$CD/desktop/service/InvestigationJson.kt" data/artifacts/src/desktopMain/kotlin/app/andy/desktop/service/InvestigationJson.kt
move_file "$CD/desktop/service/EvidenceJson.kt" data/artifacts/src/desktopMain/kotlin/app/andy/desktop/service/EvidenceJson.kt
move_file "$CD/desktop/service/DesktopHeapDumpService.kt" data/artifacts/src/desktopMain/kotlin/app/andy/desktop/service/DesktopHeapDumpService.kt
move_file "$CD/desktop/service/RecordingExportService.kt" data/artifacts/src/desktopMain/kotlin/app/andy/desktop/service/RecordingExportService.kt 2>/dev/null || true
move_file "$CD/desktop/service/ProjectArtifactCatalogService.kt" data/artifacts/src/desktopMain/kotlin/app/andy/desktop/service/ProjectArtifactCatalogService.kt 2>/dev/null || true

move_file "$CD/desktop/service/DesktopHostFileService.kt" data/host/src/desktopMain/kotlin/app/andy/desktop/service/DesktopHostFileService.kt

move_dir "$CD/desktop/service/remote" data/remote/src/desktopMain/kotlin/app/andy/desktop/service/remote
move_dir "$CD/desktop/service/webchat" data/remote/src/desktopMain/kotlin/app/andy/desktop/service/webchat 2>/dev/null || true

# Remaining desktop services -> platform-tools
for f in DesktopAccessibilityService DesktopAppService DesktopCrashInspectorService DesktopFileService \
  DesktopIntentService DesktopLogcatService DesktopMetricsService DesktopViewHierarchyService \
  CommandRunner JavaHomeLocator NotificationServices AgentAttentionCoordinator AgentNotificationDedup \
  AndydProcess GifEncoder LoginShellEnvironment MacOsNotificationBridge; do
  move_file "$CD/desktop/service/${f}.kt" "data/platform-tools/src/desktopMain/kotlin/app/andy/desktop/service/${f}.kt"
done
move_dir "$CD/desktop/service/inspector" data/platform-tools/src/desktopMain/kotlin/app/andy/desktop/service/inspector
move_dir "$CD/desktop/service/tracing" data/platform-tools/src/desktopMain/kotlin/app/andy/desktop/service/tracing
move_dir "$CD/desktop/service/dhu" data/platform-tools/src/desktopMain/kotlin/app/andy/desktop/service/dhu
move_dir "$CD/desktop/service/voice" data/platform-tools/src/desktopMain/kotlin/app/andy/desktop/service/voice

# Desktop actuals for platform
move_dir "$CD/desktop" composeApp/src/desktopMain/kotlin/app/andy/desktop 2>/dev/null || true

# Move desktop actuals for platform to core/platform
find composeApp/src/desktopMain/kotlin/app/andy -maxdepth 1 -name '*.desktop.kt' -exec git mv {} core/platform/src/desktopMain/kotlin/app/andy/ \; 2>/dev/null || true

echo "Bootstrap complete."
