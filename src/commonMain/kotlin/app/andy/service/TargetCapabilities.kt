package app.andy.service

import app.andy.AndyDestination
import app.andy.model.IosTarget
import app.andy.model.IosTargetKind

/**
 * Per-target feature surface. Replaces ad-hoc `!isIosTarget` / `availableWithIosTarget()` gates
 * so simulator vs physical divergence is expressible without a second boolean pile.
 */
data class TargetCapabilities(
    val hardwareButtons: Boolean = true,
    val navButtons: Boolean = true,
    val input: Boolean = true,
    val logs: Boolean = true,
    val fileSystem: Boolean = true,
    val appManagement: Boolean = true,
    val intents: Boolean = true,
    /** Activity / service / broadcast intent modes (Android). iOS URL schemes hide these. */
    val androidIntentModes: Boolean = true,
    val network: Boolean = true,
    val snapshots: Boolean = true,
    val controls: Boolean = true,
    val performance: Boolean = true,
    val tracing: Boolean = true,
    val design: Boolean = true,
    val inspector: Boolean = true,
    val bugs: Boolean = true,
    val recordings: Boolean = true,
    val catalog: Boolean = true,
    val mirrorStreamControls: Boolean = true,
    val foldable: Boolean = true,
    val androidAuto: Boolean = true,
    val chromeControls: Boolean = true,
    /** Physical iOS: show Developer Mode guidance instead of empty tool screens. */
    val requiresDeveloperMode: Boolean = false,
) {
    fun destinationAvailable(destination: AndyDestination): Boolean = when (destination) {
        AndyDestination.Live,
        AndyDestination.Devices,
        AndyDestination.Settings,
        AndyDestination.ComputerFiles,
        AndyDestination.Agents,
        AndyDestination.Actions,
        -> true
        AndyDestination.Catalog -> catalog
        AndyDestination.Apps -> appManagement
        AndyDestination.Logcat -> logs
        AndyDestination.Intents -> intents
        AndyDestination.Files -> fileSystem
        AndyDestination.Network -> network
        AndyDestination.Snapshots -> snapshots
        AndyDestination.Controls -> controls
        AndyDestination.Performance -> performance
        AndyDestination.Tracing -> tracing
        AndyDestination.Design -> design
        AndyDestination.Inspector -> inspector
        AndyDestination.Bugs -> bugs
        AndyDestination.Recordings -> recordings
    }

    companion object {
        val Android = TargetCapabilities()

        /**
         * Destinations reachable with no device selected (toolbar empty). Matches the historical
         * Android-default sidebar minus nothing — selection gates happen per-screen.
         */
        val None = Android

        fun of(target: IosTarget): TargetCapabilities = when (target.kind) {
            IosTargetKind.Simulator -> Simulator
            IosTargetKind.Physical -> Physical
        }

        /** Simulator surface after phases 0–5 (Inspector stays deferred until Phase 7 spike). */
        val Simulator = TargetCapabilities(
            hardwareButtons = false,
            navButtons = false,
            input = true,
            logs = true,
            fileSystem = true,
            appManagement = true,
            intents = true,
            androidIntentModes = false,
            network = false,
            snapshots = false,
            controls = true,
            performance = false,
            tracing = false,
            design = true,
            inspector = false,
            bugs = true,
            recordings = true,
            catalog = true,
            mirrorStreamControls = false,
            foldable = false,
            androidAuto = false,
            chromeControls = false,
            requiresDeveloperMode = false,
        )

        /**
         * Physical devices: Live mirroring + Devices/Settings only until Developer Mode unlocks
         * more. Input is never available without an on-device runner (out of scope).
         */
        val Physical = TargetCapabilities(
            hardwareButtons = false,
            navButtons = false,
            input = false,
            logs = false,
            fileSystem = false,
            appManagement = false,
            intents = false,
            androidIntentModes = false,
            network = false,
            snapshots = false,
            controls = false,
            performance = false,
            tracing = false,
            design = true,
            inspector = false,
            bugs = true,
            recordings = true,
            catalog = false,
            mirrorStreamControls = false,
            foldable = false,
            androidAuto = false,
            chromeControls = false,
            requiresDeveloperMode = true,
        )
    }
}
