package app.andy.desktop

import java.awt.Window

/** Native window id for an AWT [Window]: Cocoa window number on macOS, X11 XID on Linux. */
fun Window.nsWindowNumber(): Int {
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
        nativeIdFrom(nsWindow)?.let { if (it != 0) return it }
    }

    // Some JREs return the Cocoa window number directly from the platform window.
    runCatching {
        platformWindow.javaClass.methods.firstOrNull { it.name == "getNSWindowPtr" && it.parameterCount == 0 }
            ?.invoke(platformWindow) as? Long
    }.getOrNull()?.toInt()?.takeIf { it != 0 }?.let { return it }

    nativeIdFrom(platformWindow)?.let { if (it != 0) return it }
    nativeIdFrom(peer)?.let { if (it != 0) return it }
    return 0
}

private fun nativeIdFrom(target: Any): Int? {
    val names = listOf("getWindow", "getContentWindow", "getNativeWindow", "getXWindow")
    for (name in names) {
        val value = runCatching {
            target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(target)
        }.getOrNull() ?: continue
        when (value) {
            is Int -> if (value != 0) return value
            is Long -> if (value != 0L) return value.toInt()
        }
    }
    return generateSequence(target.javaClass as Class<*>?) { it.superclass }
        .flatMap { it.declaredFields.asSequence() }
        .firstOrNull { it.name == "window" || it.name == "contentWindow" }
        ?.let { field ->
            runCatching {
                field.isAccessible = true
                when (val value = field.get(target)) {
                    is Int -> value
                    is Long -> value.toInt()
                    else -> 0
                }
            }.getOrNull()
        }
        ?.takeIf { it != 0 }
}

private fun Window.awtPeer(): Any? = runCatching {
    val accessor = Class.forName("sun.awt.AWTAccessor")
    val componentAccessor = accessor.getMethod("getComponentAccessor").invoke(null)
    componentAccessor.javaClass.getMethod("getPeer", java.awt.Component::class.java)
        .invoke(componentAccessor, this)
}.getOrNull() ?: runCatching {
    java.awt.Component::class.java.getDeclaredMethod("getPeer").apply { isAccessible = true }.invoke(this)
}.getOrNull()
