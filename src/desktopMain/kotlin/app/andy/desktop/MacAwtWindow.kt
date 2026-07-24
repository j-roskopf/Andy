package app.andy.desktop

import java.awt.Window

/** Cocoa window number for an AWT [Window], used to parent the inline Metal presenter. */
internal fun Window.nsWindowNumber(): Int {
    val peer = awtPeer() ?: return 0
    val platformWindow = runCatching {
        peer.javaClass.methods.firstOrNull { it.name == "getPlatformWindow" && it.parameterCount == 0 }
            ?.invoke(peer)
    }.getOrNull() ?: return 0

    // JetBrains Runtime / newer AWT expose getNSWindowPtr or getNativeWindow; older had getNSWindow.
    val nsWindow = runCatching {
        val clazz = platformWindow.javaClass
        clazz.methods.firstOrNull { it.name == "getNSWindow" && it.parameterCount == 0 }?.invoke(platformWindow)
            ?: clazz.methods.firstOrNull { it.name == "getWindow" && it.parameterCount == 0 }?.invoke(platformWindow)
    }.getOrNull()

    if (nsWindow != null) {
        val number = runCatching {
            nsWindow.javaClass.methods.firstOrNull { it.name == "windowNumber" && it.parameterCount == 0 }
                ?.invoke(nsWindow) as? Int
        }.getOrNull()
        if (number != null && number != 0) return number
    }

    // Some JREs return the Cocoa window number directly from the platform window.
    return runCatching {
        platformWindow.javaClass.methods.firstOrNull { it.name == "getNSWindowPtr" && it.parameterCount == 0 }
            ?.invoke(platformWindow) as? Long
    }.getOrNull()?.toInt() ?: 0
}

private fun Window.awtPeer(): Any? = runCatching {
    val accessor = Class.forName("sun.awt.AWTAccessor")
    val componentAccessor = accessor.getMethod("getComponentAccessor").invoke(null)
    componentAccessor.javaClass.getMethod("getPeer", java.awt.Component::class.java)
        .invoke(componentAccessor, this)
}.getOrNull() ?: runCatching {
    java.awt.Component::class.java.getDeclaredMethod("getPeer").apply { isAccessible = true }.invoke(this)
}.getOrNull()
