package app.andy.desktop.service.plugins

import app.andy.model.InstalledPluginRecord
import app.andy.model.PluginSourceInfo
import app.andy.model.PluginSourceKind
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginRegistryStoreTest {
    @Test
    fun concurrentLoadsDoNotThrowOverlappingFileLock() {
        val home = File.createTempFile("andy-plugins-home", null).apply {
            delete()
            mkdirs()
        }
        val store = PluginRegistryStore(andyHome = home)
        store.upsert(
            InstalledPluginRecord(
                pluginId = "examples.test",
                enabled = true,
                source = PluginSourceInfo(PluginSourceKind.Local, path = home.absolutePath),
                manifestPath = File(home, "andy-plugin.toml").absolutePath,
                pluginRoot = home.absolutePath,
            ),
        )

        val threads = 8
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val failures = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            pool.execute {
                try {
                    start.await()
                    repeat(40) { store.load() }
                } catch (t: Throwable) {
                    failures.incrementAndGet()
                    t.printStackTrace()
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(0, failures.get())
        home.deleteRecursively()
    }
}
