package com.joetr.andy.mobile.data.updates

import android.content.Context
import app.andy.service.AppUpdateService
import app.andy.service.AppUpdateState
import app.andy.service.AvailableUpdate
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.util.concurrent.TimeUnit

class AndroidAppUpdateService(
    context: Context,
    httpClient: HttpClient? = null,
    private val repository: GitHubReleaseUpdateRepository? = null,
    private val installer: AndroidUpdateInstaller = AndroidUpdateInstaller(context.applicationContext),
) : AppUpdateService, Closeable {

    private val ownsHttpClient = httpClient == null
    private val client = httpClient ?: defaultHttpClient()
    private val updateRepository = repository ?: GitHubReleaseUpdateRepository(client)

    private val mutableState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    override val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()

    private val mutablePendingInstallConfirmation = MutableStateFlow<AvailableUpdate?>(null)
    override val pendingInstallConfirmation: StateFlow<AvailableUpdate?> =
        mutablePendingInstallConfirmation.asStateFlow()

    override suspend fun checkForUpdates(onFailure: (Throwable) -> Unit) {
        if (mutableState.value is AppUpdateState.Installing) return
        mutableState.value = AppUpdateState.Checking
        runCatching {
            updateRepository.checkForUpdate()
        }.onSuccess { update ->
            mutableState.value = if (update == null) {
                AppUpdateState.Current
            } else {
                AppUpdateState.Available(update)
            }
        }.onFailure { error ->
            onFailure(error)
            mutableState.value = AppUpdateState.Failed(error.message ?: "Couldn't check for updates.")
        }
    }

    override suspend fun installAvailableUpdate(onMessage: (String) -> Unit) {
        val update = when (val state = mutableState.value) {
            is AppUpdateState.Available -> state.update
            is AppUpdateState.Failed -> state.lastKnownUpdate
            is AppUpdateState.Installing -> return
            else -> null
        } ?: return

        val initialMessage = "Downloading Andy ${update.versionName}..."
        mutableState.value = AppUpdateState.Installing(update, initialMessage)
        onMessage(initialMessage)
        runCatching {
            installer.install(
                update = update,
                onProgress = { progress ->
                    mutableState.value = AppUpdateState.Installing(
                        update = update,
                        message = progress.message,
                        progress = progress.fraction,
                    )
                },
            )
        }.onSuccess { result ->
            onMessage(result.message)
            mutableState.value = when (result) {
                is UpdateInstallResult.Started -> AppUpdateState.Installing(update, result.message)
                is UpdateInstallResult.OpenedReleasePage,
                is UpdateInstallResult.RequiresUserAction,
                -> AppUpdateState.Available(update)
            }
        }.onFailure { error ->
            val message = error.message ?: "Couldn't install the update."
            onMessage(message)
            mutableState.value = AppUpdateState.Failed(message, update)
        }
    }

    override fun respondToInstallConfirmation(install: Boolean) {
        // Android uses the system installer UI; no in-app confirmation gate.
    }

    override fun close() {
        if (ownsHttpClient) client.close()
    }

    companion object {
        fun defaultHttpClient(): HttpClient =
            HttpClient(OkHttp) {
                expectSuccess = false
                engine {
                    config {
                        connectTimeout(15, TimeUnit.SECONDS)
                        readTimeout(30, TimeUnit.SECONDS)
                    }
                }
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        },
                    )
                }
            }
    }
}
