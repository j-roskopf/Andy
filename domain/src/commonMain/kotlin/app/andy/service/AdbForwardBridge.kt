package app.andy.service

/** Optional hook so [app.andy.desktop.service.mirror.DesktopMirrorEngine] can SSH-bridge adb forwards. */
interface AdbForwardBridge {
    fun afterForwardOpened(port: Int): Boolean
    fun beforeForwardClosed(port: Int) {}
}
