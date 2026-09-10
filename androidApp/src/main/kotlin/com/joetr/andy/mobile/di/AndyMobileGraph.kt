package com.joetr.andy.mobile.di

import android.app.Application
import android.content.Context
import com.joetr.andy.mobile.data.HostRepository
import com.joetr.andy.mobile.data.VoiceDefaultsStore
import com.joetr.andy.mobile.data.attention.AndroidChatNotificationService
import com.joetr.andy.mobile.data.secrets.KeystoreSecretStore
import com.joetr.andy.mobile.data.updates.AndroidAppUpdateService
import com.joetr.andy.mobile.data.updates.AndroidUpdateInstaller
import com.joetr.andy.mobile.data.updates.GitHubReleaseUpdateRepository
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraphFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@DependencyGraph(MobileScope::class)
interface AndyMobileGraph : SessionGraph.Factory {
    val hostRepository: HostRepository
    val voiceDefaultsStore: VoiceDefaultsStore
    val secretStore: KeystoreSecretStore
    val sessionManager: SessionManager
    val updateService: AndroidAppUpdateService
    val notificationService: AndroidChatNotificationService

    @InteractiveOkHttp
    val interactiveOkHttp: OkHttpClient

    @LongLivedOkHttp
    val longLivedOkHttp: OkHttpClient

    @Provides
    @SingleIn(MobileScope::class)
    fun provideContext(application: Application): Context = application.applicationContext

    @Provides
    @SingleIn(MobileScope::class)
    @InteractiveOkHttp
    fun provideInteractiveOkHttp(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()

    @Provides
    @SingleIn(MobileScope::class)
    @LongLivedOkHttp
    fun provideLongLivedOkHttp(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

    @Provides
    @SingleIn(MobileScope::class)
    fun provideInteractiveHttpClient(
        @InteractiveOkHttp okHttp: OkHttpClient,
    ): HttpClient =
        HttpClient(OkHttp) {
            expectSuccess = false
            engine { preconfigured = okHttp }
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    },
                )
            }
        }

    @Provides
    @SingleIn(MobileScope::class)
    fun provideUpdateRepository(httpClient: HttpClient): GitHubReleaseUpdateRepository =
        GitHubReleaseUpdateRepository(httpClient)

    @Provides
    @SingleIn(MobileScope::class)
    fun provideUpdateInstaller(
        context: Context,
        @InteractiveOkHttp okHttp: OkHttpClient,
    ): AndroidUpdateInstaller = AndroidUpdateInstaller(context, okHttp)

    @Provides
    @SingleIn(MobileScope::class)
    fun provideUpdateService(
        context: Context,
        httpClient: HttpClient,
        repository: GitHubReleaseUpdateRepository,
        installer: AndroidUpdateInstaller,
    ): AndroidAppUpdateService =
        AndroidAppUpdateService(
            context = context,
            httpClient = httpClient,
            repository = repository,
            installer = installer,
        )

    @Provides
    @SingleIn(MobileScope::class)
    fun provideNotificationService(context: Context): AndroidChatNotificationService =
        AndroidChatNotificationService(context)

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides application: Application): AndyMobileGraph
    }
}

fun openAndyMobileGraph(application: Application): AndyMobileGraph =
    createGraphFactory<AndyMobileGraph.Factory>().create(application)
