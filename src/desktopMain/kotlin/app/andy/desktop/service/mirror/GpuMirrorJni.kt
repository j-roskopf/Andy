package app.andy.desktop.service.mirror

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** JNI bridge to the multi-decoder / multi-presenter GPU mirror hub. */
internal object GpuMirrorJni {
    private val loadResult: Result<Unit> by lazy(::loadLibrary)

    fun isAvailable(): Boolean = loadResult.isSuccess

    fun ensureLoaded(): Result<Unit> = loadResult

    fun createDecoder(): Long =
        if (!loadResult.isSuccess) 0L else runCatching { nativeCreateDecoder() }.getOrDefault(0L)

    fun destroyDecoder(decoderId: Long) {
        if (!loadResult.isSuccess || decoderId == 0L) return
        runCatching { nativeDestroyDecoder(decoderId) }
    }

    fun createPresenter(decoderId: Long): Long =
        if (!loadResult.isSuccess || decoderId == 0L) 0L
        else runCatching { nativeCreatePresenter(decoderId) }.getOrDefault(0L)

    fun destroyPresenter(presenterId: Long) {
        if (!loadResult.isSuccess || presenterId == 0L) return
        runCatching { nativeDestroyPresenter(presenterId) }
    }

    fun openPresenterOverlay(presenterId: Long): Boolean =
        loadResult.isSuccess && presenterId != 0L &&
            runCatching { nativeOpenPresenterOverlay(presenterId) }.getOrDefault(false)

    fun setPresenterVisible(presenterId: Long, visible: Boolean) {
        if (!loadResult.isSuccess || presenterId == 0L) return
        runCatching { nativeSetPresenterVisible(presenterId, visible) }
    }

    fun updatePresenterGeometry(
        presenterId: Long,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        scale: Double,
        parentWindowNumber: Int,
    ) {
        if (!loadResult.isSuccess || presenterId == 0L) return
        runCatching {
            nativeUpdatePresenterGeometry(presenterId, x, y, width, height, scale, parentWindowNumber)
        }
    }

    fun setPresenterFillHost(presenterId: Long, fillHost: Boolean) {
        if (!loadResult.isSuccess || presenterId == 0L) return
        runCatching { nativeSetPresenterFillHost(presenterId, fillHost) }
    }

    fun setPresenterContentSize(presenterId: Long, width: Int, height: Int) {
        if (!loadResult.isSuccess || presenterId == 0L || width <= 0 || height <= 0) return
        runCatching { nativeSetPresenterContentSize(presenterId, width, height) }
    }

    fun repaintPresenter(presenterId: Long) {
        if (!loadResult.isSuccess || presenterId == 0L) return
        runCatching { nativeRepaintPresenter(presenterId) }
    }

    fun consumeH264(decoderId: Long, packet: ByteArray): Boolean =
        loadResult.isSuccess && decoderId != 0L && packet.isNotEmpty() &&
            runCatching { nativeConsumeH264(decoderId, packet) }.getOrDefault(false)

    /** Presents a solid BGRA color through the hub (used by presentation regression tests). */
    fun presentSolidBgra(
        decoderId: Long,
        width: Int,
        height: Int,
        blue: Int,
        green: Int,
        red: Int,
        alpha: Int = 255,
    ): Boolean =
        loadResult.isSuccess && decoderId != 0L && width > 0 && height > 0 &&
            runCatching {
                nativePresentSolidBgra(decoderId, width, height, blue, green, red, alpha)
            }.getOrDefault(false)

    fun recordInput(decoderId: Long) {
        if (!loadResult.isSuccess || decoderId == 0L) return
        runCatching { nativeRecordInput(decoderId) }
    }

    fun recordTransportIngress(decoderId: Long) {
        if (!loadResult.isSuccess || decoderId == 0L) return
        runCatching { nativeRecordTransportIngress(decoderId) }
    }

    fun framesPresented(decoderId: Long): Long =
        if (!loadResult.isSuccess || decoderId == 0L) 0L
        else runCatching { nativeFramesPresented(decoderId) }.getOrDefault(0L)

    fun isHardwareReady(decoderId: Long): Boolean =
        loadResult.isSuccess && decoderId != 0L &&
            runCatching { nativeIsHardwareReady(decoderId) }.getOrDefault(false)

    fun bindIosDecoder(decoderId: Long) {
        if (!loadResult.isSuccess || decoderId == 0L) return
        runCatching { nativeSetIosDecoder(decoderId) }
    }

    fun clearIosDecoder(decoderId: Long) {
        if (!loadResult.isSuccess || decoderId == 0L) return
        runCatching { nativeClearIosDecoder(decoderId) }
    }

    /** Decoder currently bound to receive iOS capture frames, or 0 when unbound. */
    fun iosDecoder(): Long =
        if (!loadResult.isSuccess) 0L else runCatching { nativeIosDecoder() }.getOrDefault(0L)

    fun updatePresenterOverlay(
        presenterId: Long,
        gridEnabled: Boolean,
        gridStepX: Float,
        gridStepY: Float,
        gridR: Float,
        gridG: Float,
        gridB: Float,
        gridA: Float,
        rulerEnabled: Boolean,
        rulerX: Float,
        rulerY: Float,
        rulerR: Float,
        rulerG: Float,
        rulerB: Float,
        rulerA: Float,
        sourceWidth: Float,
        sourceHeight: Float,
        pickerEnabled: Boolean,
        highlightLeft: Float,
        highlightTop: Float,
        highlightRight: Float,
        highlightBottom: Float,
    ) {
        if (!loadResult.isSuccess || presenterId == 0L) return
        runCatching {
            nativeUpdatePresenterOverlay(
                presenterId,
                gridEnabled, gridStepX, gridStepY, gridR, gridG, gridB, gridA,
                rulerEnabled, rulerX, rulerY, rulerR, rulerG, rulerB, rulerA,
                sourceWidth, sourceHeight,
                pickerEnabled,
                highlightLeft, highlightTop, highlightRight, highlightBottom,
            )
        }
    }

    fun updatePresenterPickerPoint(presenterId: Long, normalizedX: Float?, normalizedY: Float?) {
        if (!loadResult.isSuccess || presenterId == 0L) return
        runCatching {
            nativeUpdatePresenterPickerPoint(
                presenterId,
                normalizedX ?: 0f,
                normalizedY ?: 0f,
                normalizedX != null && normalizedY != null,
            )
        }
    }

    private fun loadLibrary() = runCatching {
        val resourcePath = NativeMirrorJni.resourcePath()
            ?: error("No packaged VideoToolbox/Metal native mirror bridge is available for this desktop platform")
        val target = File(System.getProperty("user.home"), ".andy/mirror/$resourcePath")
        target.parentFile.mkdirs()
        GpuMirrorJni::class.java.classLoader.getResourceAsStream(resourcePath)?.use {
            Files.copy(it, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } ?: error("Missing packaged native mirror bridge: $resourcePath")
        System.load(target.absolutePath)
    }

    private external fun nativeCreateDecoder(): Long
    private external fun nativeDestroyDecoder(decoderId: Long)
    private external fun nativeCreatePresenter(decoderId: Long): Long
    private external fun nativeDestroyPresenter(presenterId: Long)
    private external fun nativeOpenPresenterOverlay(presenterId: Long): Boolean
    private external fun nativeSetPresenterVisible(presenterId: Long, visible: Boolean)
    private external fun nativeUpdatePresenterGeometry(
        presenterId: Long,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        scale: Double,
        parentWindowNumber: Int,
    )
    private external fun nativeSetPresenterFillHost(presenterId: Long, fillHost: Boolean)
    private external fun nativeSetPresenterContentSize(presenterId: Long, width: Int, height: Int)
    private external fun nativeRepaintPresenter(presenterId: Long)
    private external fun nativeConsumeH264(decoderId: Long, packet: ByteArray): Boolean
    private external fun nativePresentSolidBgra(
        decoderId: Long,
        width: Int,
        height: Int,
        blue: Int,
        green: Int,
        red: Int,
        alpha: Int,
    ): Boolean
    private external fun nativeRecordInput(decoderId: Long)
    private external fun nativeRecordTransportIngress(decoderId: Long)
    private external fun nativeFramesPresented(decoderId: Long): Long
    private external fun nativeIsHardwareReady(decoderId: Long): Boolean
    private external fun nativeSetIosDecoder(decoderId: Long)
    private external fun nativeClearIosDecoder(decoderId: Long)
    private external fun nativeIosDecoder(): Long
    private external fun nativeUpdatePresenterOverlay(
        presenterId: Long,
        gridEnabled: Boolean,
        gridStepX: Float,
        gridStepY: Float,
        gridR: Float,
        gridG: Float,
        gridB: Float,
        gridA: Float,
        rulerEnabled: Boolean,
        rulerX: Float,
        rulerY: Float,
        rulerR: Float,
        rulerG: Float,
        rulerB: Float,
        rulerA: Float,
        sourceWidth: Float,
        sourceHeight: Float,
        pickerEnabled: Boolean,
        highlightLeft: Float,
        highlightTop: Float,
        highlightRight: Float,
        highlightBottom: Float,
    )
    private external fun nativeUpdatePresenterPickerPoint(
        presenterId: Long,
        normalizedX: Float,
        normalizedY: Float,
        visible: Boolean,
    )
}
