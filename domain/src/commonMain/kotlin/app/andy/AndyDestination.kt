package app.andy

import app.andy.service.TargetCapabilities

enum class AndyDestination(val label: String) {
    Devices("Devices"),
    Catalog("Catalog"),
    Live("Live"),
    Apps("Apps"),
    Logcat("Logcat"),
    Intents("Intents"),
    Files("Files & data"),
    ComputerFiles("Computer Files"),
    Network("Network"),
    Actions("Projects"),
    Agents("Agents"),
    Snapshots("Snapshots"),
    Controls("Controls"),
    Performance("Performance"),
    Tracing("Tracing"),
    Design("Design"),
    Inspector("Inspector"),
    Bugs("Bugs"),
    Recordings("Recordings"),
    Settings("Settings"),
}

val AndyDestination.showsSideChat: Boolean
    get() = this == AndyDestination.Actions || this == AndyDestination.Agents

fun AndyDestination.availableWithIosTarget(
    capabilities: TargetCapabilities = TargetCapabilities.Simulator,
): Boolean = capabilities.destinationAvailable(this)

fun AndyDestination.isToggleableInSidebar(): Boolean = this != AndyDestination.Settings

fun AndyDestination.availableWhileRemote(): Boolean = when (this) {
    AndyDestination.Devices,
    AndyDestination.Live,
    AndyDestination.Apps,
    AndyDestination.Logcat,
    AndyDestination.Intents,
    AndyDestination.Files,
    AndyDestination.Actions,
    AndyDestination.Agents,
    AndyDestination.Controls,
    AndyDestination.Settings,
    -> true
    AndyDestination.Catalog,
    AndyDestination.ComputerFiles,
    AndyDestination.Network,
    AndyDestination.Snapshots,
    AndyDestination.Performance,
    AndyDestination.Tracing,
    AndyDestination.Design,
    AndyDestination.Inspector,
    AndyDestination.Bugs,
    AndyDestination.Recordings,
    -> false
}
