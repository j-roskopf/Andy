package app.andy.desktop.service.mirror

import java.awt.Canvas
import java.awt.Rectangle
import java.awt.Robot

/**
 * Screen-capture checks that Metal actually painted into the host bounds.
 * Command-buffer completion alone cannot catch a black Canvas winning z-order.
 */
internal fun mirrorHostContainsNonBlackPixels(
    host: Canvas,
    minChannel: Int = 80,
    minChroma: Int = 25,
): Boolean {
    if (!host.isDisplayable || host.width <= 0 || host.height <= 0) return false
    val location = host.locationOnScreen
    val image = Robot().createScreenCapture(Rectangle(location, host.size))
    val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    return pixels.any { pixel ->
        val red = pixel ushr 16 and 0xff
        val green = pixel ushr 8 and 0xff
        val blue = pixel and 0xff
        maxOf(red, green, blue) > minChannel &&
            maxOf(red, green, blue) - minOf(red, green, blue) > minChroma
    }
}

internal fun awaitGpuMirrorHost(timeoutMs: Long = 5_000): Canvas? {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
    var host = GpuMirrorHostRegistry.current()
    while (host == null && System.nanoTime() < deadline) {
        Thread.sleep(20)
        host = GpuMirrorHostRegistry.current()
    }
    return host
}

internal fun isMacArm64(): Boolean {
    if (!System.getProperty("os.name").lowercase().contains("mac")) return false
    return System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")
}
