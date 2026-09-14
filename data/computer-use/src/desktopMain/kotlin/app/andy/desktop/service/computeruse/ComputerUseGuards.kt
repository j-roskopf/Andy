package app.andy.desktop.service.computeruse

import app.andy.model.ComputerUseScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val guardJson = Json { ignoreUnknownKeys = true }

/** Focused host target reported by the native bridge. */
data class FocusedAppInfo(
    val app: String? = null,
    val bundleId: String? = null,
    val pid: Int? = null,
    val secure: Boolean = false,
)

internal fun parseFocusedAppInfo(raw: String): FocusedAppInfo {
    val obj = runCatching { guardJson.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: return FocusedAppInfo()
    return FocusedAppInfo(
        app = obj["app"]?.jsonPrimitive?.contentOrNull,
        bundleId = obj["bundleId"]?.jsonPrimitive?.contentOrNull,
        pid = obj["pid"]?.jsonPrimitive?.intOrNull,
        secure = obj["secure"]?.jsonPrimitive?.booleanOrNull == true,
    )
}

/**
 * Denial reason when synthetic (coordinate/focus) input would land outside the armed scope,
 * on a denylisted app, or in a secure field. Returns null when the target is allowed.
 *
 * Fails closed for app-scoped sessions when the focus target cannot be identified.
 */
internal fun focusedTargetDenial(scope: ComputerUseScope, info: FocusedAppInfo): String? {
    if (info.secure) {
        return "Focused control is a secure field; refusing synthetic input"
    }
    val app = info.app?.takeIf { it.isNotBlank() } ?: info.bundleId?.takeIf { it.isNotBlank() }
    if (app == null) {
        if (!scope.wholeDesktop) {
            return "Could not identify the focused app; refusing synthetic input outside the armed scope"
        }
        return null
    }
    if (ComputerUseScopeDenylist.isDenied(app)) {
        return ComputerUseScopeDenylist.deniedReason(app)
    }
    val inScope = scope.appNames.any {
        it.equals(app, ignoreCase = true) ||
            (info.bundleId != null && it.equals(info.bundleId, ignoreCase = true))
    }
    if (!scope.wholeDesktop && !inScope) {
        return "Focused app \"$app\" is outside the armed scope ${scope.appNames}"
    }
    return null
}

/** Union of an app's on-screen window frames, in logical screen points (top-left origin). */
data class AppWindowBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

internal fun parseAppWindowBounds(raw: String): AppWindowBounds? {
    val obj = runCatching { guardJson.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
    if (obj["error"] != null) return null
    val x = obj["x"]?.jsonPrimitive?.intOrNull ?: return null
    val y = obj["y"]?.jsonPrimitive?.intOrNull ?: return null
    val w = obj["w"]?.jsonPrimitive?.intOrNull ?: return null
    val h = obj["h"]?.jsonPrimitive?.intOrNull ?: return null
    if (w <= 0 || h <= 0) return null
    return AppWindowBounds(x, y, w, h)
}

/**
 * Opt-in local persistence for attended screenshots. Writes under
 * `~/.andy/computer-use/screenshots` and prunes to the most recent [maxFiles].
 */
internal object ComputerUseScreenshotStore {
    private const val maxFiles = 200

    fun save(png: ByteArray, sessionId: String, atEpochMs: Long): String? = runCatching {
        val dir = File(System.getProperty("user.home"), ".andy/computer-use/screenshots")
        if (!dir.isDirectory && !dir.mkdirs()) return@runCatching null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(atEpochMs))
        val file = File(dir, "$stamp-${sessionId.take(8)}.png")
        file.writeBytes(png)
        prune(dir)
        file.absolutePath
    }.getOrNull()

    private fun prune(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".png") } ?: return
        if (files.size <= maxFiles) return
        files.sortedByDescending { it.lastModified() }
            .drop(maxFiles)
            .forEach { runCatching { it.delete() } }
    }
}
