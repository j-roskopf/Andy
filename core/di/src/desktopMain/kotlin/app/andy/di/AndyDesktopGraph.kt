package app.andy.di

import app.andy.desktop.service.DesktopRuntime
import app.andy.desktop.service.RuntimeMode
import app.andy.desktop.service.createDesktopRuntime
import app.andy.service.AndyServices
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraphFactory

/**
 * Compile-time DI entry for the desktop host. Initially delegates to [createDesktopRuntime];
 * leaf services migrate here incrementally via [@ContributesBinding][dev.zacsweers.metro.ContributesBinding].
 */
@DependencyGraph(AndyScope::class)
interface AndyDesktopGraph {
    val runtime: DesktopRuntime
    val services: AndyServices

    @Provides
    fun provideRuntime(mode: RuntimeMode): DesktopRuntime = createDesktopRuntime(mode)

    @Provides
    fun provideServices(runtime: DesktopRuntime): AndyServices = runtime.services

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides mode: RuntimeMode): AndyDesktopGraph
    }
}

fun openAndyDesktopGraph(mode: RuntimeMode): AndyDesktopGraph =
    createGraphFactory<AndyDesktopGraph.Factory>().create(mode)
