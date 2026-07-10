package app.andy.transfer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.andy.service.AppService
import app.andy.service.CommandResult
import app.andy.service.FileService
import app.andy.uniqueLocalPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

enum class LocalDropKind {
    Empty,
    Apks,
    Files,
    Mixed,
}

fun isApkPath(path: String): Boolean {
    val name = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
    return name.endsWith(".apk", ignoreCase = true)
}

fun localPathName(path: String): String =
    path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

fun classifyLocalPaths(paths: List<String>): LocalDropKind {
    if (paths.isEmpty()) return LocalDropKind.Empty
    val apkCount = paths.count(::isApkPath)
    return when {
        apkCount == paths.size -> LocalDropKind.Apks
        apkCount == 0 -> LocalDropKind.Files
        else -> LocalDropKind.Mixed
    }
}

fun joinRemotePath(directory: String, name: String): String {
    val base = if (directory == "/") "" else directory.trimEnd('/')
    return "$base/$name"
}

fun CommandResult.isAlreadyInstalledConflict(): Boolean {
    if (isSuccess) return false
    val text = "$stdout\n$stderr".uppercase()
    return "INSTALL_FAILED_ALREADY_EXISTS" in text ||
        ("ALREADY EXISTS" in text && "INSTALL" in text)
}

class DeviceTransferCoordinator {
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set

    private var activeJob: Job? = null
    private var pendingConfirm: Continuation<Boolean>? = null

    var confirmationTitle by mutableStateOf<String?>(null)
        private set
    var confirmationMessage by mutableStateOf("")
        private set

    fun cancel() {
        pendingConfirm?.resume(false)
        pendingConfirm = null
        confirmationTitle = null
        confirmationMessage = ""
        activeJob?.cancel()
        activeJob = null
        busy = false
        status = "Cancelled"
    }

    fun dismissConfirmation() {
        pendingConfirm?.resume(false)
        pendingConfirm = null
        confirmationTitle = null
        confirmationMessage = ""
    }

    fun acceptConfirmation() {
        pendingConfirm?.resume(true)
        pendingConfirm = null
        confirmationTitle = null
        confirmationMessage = ""
    }

    private suspend fun confirm(title: String, message: String): Boolean {
        return suspendCancellableCoroutine { cont ->
            pendingConfirm?.resume(false)
            pendingConfirm = cont
            confirmationTitle = title
            confirmationMessage = message
            cont.invokeOnCancellation {
                if (pendingConfirm === cont) {
                    pendingConfirm = null
                    confirmationTitle = null
                    confirmationMessage = ""
                }
            }
        }
    }

    fun tryStart(scope: CoroutineScope, initialStatus: String, block: suspend TransferOps.() -> Unit): Boolean {
        if (busy) {
            status = "Wait for the current transfer to finish"
            return false
        }
        busy = true
        status = initialStatus
        activeJob = scope.launch {
            try {
                TransferOps().block()
            } catch (_: kotlinx.coroutines.CancellationException) {
                status = "Cancelled"
            } catch (error: Throwable) {
                status = error.message ?: "Transfer failed"
            } finally {
                busy = false
                activeJob = null
                pendingConfirm?.resume(false)
                pendingConfirm = null
                confirmationTitle = null
                confirmationMessage = ""
            }
        }
        return true
    }

    inner class TransferOps {
        fun setStatus(message: String) {
            status = message
        }

        suspend fun askConfirm(title: String, message: String): Boolean = confirm(title, message)

        suspend fun pullAll(
            files: FileService,
            serial: String,
            remotePaths: List<String>,
            downloadsDir: String,
        ) {
            var completed = 0
            for (remote in remotePaths) {
                val name = localPathName(remote)
                setStatus("Pulling $name…")
                val local = uniqueLocalPath(downloadsDir, name)
                val result = files.pull(serial, remote, local)
                if (!result.isSuccess) {
                    setStatus("Pull failed: ${result.stderr.ifBlank { result.stdout }.ifBlank { name }}")
                    return
                }
                completed++
            }
            setStatus(
                if (completed == 1) "Downloaded ${localPathName(remotePaths.first())} to Downloads"
                else "Downloaded $completed items to Downloads",
            )
        }

        suspend fun pushAll(
            files: FileService,
            serial: String,
            localPaths: List<String>,
            remoteDirectory: String,
            existingNames: Set<String>,
            onSuccess: suspend () -> Unit,
        ) {
            var completed = 0
            val remainingNames = existingNames.toMutableSet()
            for (local in localPaths) {
                val name = localPathName(local)
                if (name in remainingNames) {
                    val replace = askConfirm("Replace existing file?", "$name already exists on the device.")
                    if (!replace) {
                        setStatus("Cancelled")
                        return
                    }
                }
                setStatus("Pushing $name…")
                val remote = joinRemotePath(remoteDirectory, name)
                val result = files.push(serial, local, remote)
                if (!result.isSuccess) {
                    setStatus("Push failed: ${result.stderr.ifBlank { result.stdout }.ifBlank { name }}")
                    return
                }
                remainingNames.add(name)
                completed++
            }
            setStatus(if (completed == 1) "Pushed ${localPathName(localPaths.first())}" else "Pushed $completed items")
            onSuccess()
        }

        suspend fun installAll(
            apps: AppService,
            serial: String,
            apkPaths: List<String>,
        ) {
            var completed = 0
            var replaced = 0
            for (apk in apkPaths) {
                val name = localPathName(apk)
                setStatus("Installing $name…")
                val first = apps.install(serial, apk, replace = false)
                if (first.isSuccess) {
                    completed++
                    continue
                }
                if (first.isAlreadyInstalledConflict()) {
                    val replace = askConfirm("Replace existing app?", "$name is already installed. Replace it?")
                    if (!replace) {
                        setStatus("Cancelled")
                        return
                    }
                    setStatus("Replacing $name…")
                    val second = apps.install(serial, apk, replace = true)
                    if (!second.isSuccess) {
                        setStatus("Install failed: ${second.stderr.ifBlank { second.stdout }.ifBlank { name }}")
                        return
                    }
                    completed++
                    replaced++
                } else {
                    setStatus("Install failed: ${first.stderr.ifBlank { first.stdout }.ifBlank { name }}")
                    return
                }
            }
            setStatus(
                when {
                    completed == 1 && replaced == 1 -> "App replaced: ${localPathName(apkPaths.first())}"
                    completed == 1 -> "App installed: ${localPathName(apkPaths.first())}"
                    replaced == completed -> "Replaced $completed apps"
                    else -> "Installed $completed apps"
                },
            )
        }
    }
}
