package app.andy.desktop.service.computeruse

import app.andy.model.ComputerUsePlatform

fun detectComputerUsePlatform(): ComputerUsePlatform {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        os.contains("mac") || os.contains("darwin") -> ComputerUsePlatform.MacOs
        os.contains("win") -> ComputerUsePlatform.Windows
        os.contains("linux") || os.contains("bsd") -> {
            val session = System.getenv("XDG_SESSION_TYPE")?.lowercase().orEmpty()
            val waylandDisplay = System.getenv("WAYLAND_DISPLAY")
            val isWayland = session == "wayland" || !waylandDisplay.isNullOrBlank()
            val hasX11 = !System.getenv("DISPLAY").isNullOrBlank()
            when {
                isWayland && !hasX11 -> ComputerUsePlatform.LinuxWayland
                hasX11 -> ComputerUsePlatform.LinuxX11
                isWayland -> ComputerUsePlatform.LinuxWayland
                else -> ComputerUsePlatform.Unsupported
            }
        }
        else -> ComputerUsePlatform.Unsupported
    }
}

fun isHeadlessJvm(): Boolean =
    runCatching { java.awt.GraphicsEnvironment.isHeadless() }.getOrDefault(false) ||
        System.getProperty("java.awt.headless")?.equals("true", ignoreCase = true) == true
