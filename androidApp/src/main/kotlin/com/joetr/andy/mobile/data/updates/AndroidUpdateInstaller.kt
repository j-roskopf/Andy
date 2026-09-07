package com.joetr.andy.mobile.data.updates

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import app.andy.service.AvailableUpdate
import app.andy.service.ReleaseAsset
import app.andy.service.UpdateInstallPhase
import app.andy.service.UpdateInstallProgress
import app.andy.updates.AndyBuildInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class AndroidUpdateInstaller(
    private val appContext: Context,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
) {
    suspend fun install(
        update: AvailableUpdate,
        onProgress: (UpdateInstallProgress) -> Unit = {},
    ): UpdateInstallResult = withContext(Dispatchers.IO) {
        val asset = update.asset ?: return@withContext openReleasePage(update)
        if (!appContext.packageManager.canRequestPackageInstalls()) {
            openUnknownAppSourcesSettings(appContext)
            return@withContext UpdateInstallResult.RequiresUserAction(
                "Allow Andy to install unknown apps, then tap Update again.",
            )
        }

        val apk = downloadApk(asset, onProgress)
        try {
            onProgress(
                UpdateInstallProgress(
                    phase = UpdateInstallPhase.Installing,
                    message = "Opening Android installer...",
                ),
            )
            commitPackageInstallerSession(appContext, apk, update.versionName)
            UpdateInstallResult.Started("Andy downloaded the update and opened Android's installer.")
        } catch (error: Throwable) {
            Log.d(TAG, "PackageInstaller session failed: ${error.message}")
            openInstallIntentFallback(appContext, apk)
            UpdateInstallResult.Started("Andy downloaded the update and opened Android's installer.")
        }
    }

    private fun openReleasePage(update: AvailableUpdate): UpdateInstallResult {
        val intent = Intent(Intent.ACTION_VIEW, update.releasePageUrl.toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
        return UpdateInstallResult.OpenedReleasePage("Andy opened the latest release page.")
    }

    private fun downloadApk(
        asset: ReleaseAsset,
        onProgress: (UpdateInstallProgress) -> Unit,
    ): File {
        val target = File(
            appContext.cacheDir,
            "updates/${asset.name.replace(Regex("""[^A-Za-z0-9._-]"""), "_")}",
        )
        target.parentFile?.mkdirs()
        val expectedSha256 = asset.sha256Digest.normalizedSha256Digest()
        val digest = if (expectedSha256 != null) MessageDigest.getInstance("SHA-256") else null
        val request = Request.Builder()
            .url(asset.downloadUrl)
            .header("User-Agent", "Andy/${AndyBuildInfo.versionName}")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                target.delete()
                error("Update download failed: HTTP ${response.code}")
            }
            val body = response.body ?: run {
                target.delete()
                error("Update download failed: empty body")
            }
            val totalBytes = body.contentLength().takeIf { it > 0 } ?: asset.sizeBytes.takeIf { it > 0 }
            var downloadedBytes = 0L
            onProgress(
                UpdateInstallProgress(
                    phase = UpdateInstallPhase.Downloading,
                    message = "Downloading Andy update...",
                    fraction = progressFraction(downloadedBytes, totalBytes),
                ),
            )
            target.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DownloadBufferSize)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        digest?.update(buffer, 0, read)
                        downloadedBytes += read
                        onProgress(
                            UpdateInstallProgress(
                                phase = UpdateInstallPhase.Downloading,
                                message = "Downloading Andy update...",
                                fraction = progressFraction(downloadedBytes, totalBytes),
                            ),
                        )
                    }
                }
            }
        }
        if (expectedSha256 != null) {
            onProgress(
                UpdateInstallProgress(
                    phase = UpdateInstallPhase.ReadyToInstall,
                    message = "Verifying update...",
                ),
            )
            val actual = digest?.digest()?.joinToString("") { byte -> "%02x".format(byte) }
            check(actual == expectedSha256) { "Downloaded update failed SHA-256 verification." }
        }
        return target
    }
}

private fun openUnknownAppSourcesSettings(context: Context) {
    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
        .setData("package:${context.packageName}".toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

private fun progressFraction(downloadedBytes: Long, totalBytes: Long?): Float? =
    totalBytes
        ?.takeIf { it > 0L }
        ?.let { total -> (downloadedBytes.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f) }

private const val DownloadBufferSize = 256 * 1024
private const val TAG = "AndyUpdate"

private fun commitPackageInstallerSession(
    context: Context,
    apk: File,
    versionName: String,
) {
    val packageInstaller = context.packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
        setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
    }
    val sessionId = packageInstaller.createSession(params)
    packageInstaller.openSession(sessionId).use { session ->
        session.openWrite("andy-$versionName.apk", 0L, apk.length()).use { output ->
            apk.inputStream().use { input ->
                input.copyTo(output)
            }
            session.fsync(output)
        }
        val callback = Intent(context, AndroidUpdateInstallReceiver::class.java)
            .setAction(AndroidUpdateInstallReceiver.ActionInstallStatus)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val sender = PendingIntent.getBroadcast(context, sessionId, callback, flags).intentSender
        session.commit(sender)
    }
}

private fun openInstallIntentFallback(context: Context, apk: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
    @Suppress("DEPRECATION")
    val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
        .setData(uri)
        .putExtra(Intent.EXTRA_RETURN_RESULT, false)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

class AndroidUpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirmation)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                val launch = context.packageManager
                    .getLaunchIntentForPackage(context.packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (launch != null) context.startActivity(launch)
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.d(TAG, "Android update install failed: $status ${message.orEmpty()}")
            }
        }
    }

    companion object {
        const val ActionInstallStatus = "com.joetr.andy.UPDATE_INSTALL_STATUS"
    }
}
