package app.andy.desktop.service.mirror

import app.andy.model.MirrorTarget
import app.andy.service.CommandResult
import app.andy.service.MirrorEngine
import app.andy.service.MirrorFrame
import app.andy.service.MirrorInput
import app.andy.service.MirrorSession
import app.andy.service.MirrorVideoConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ScrcpySerialGateTest {
    @Test
    fun killOrphanedScrcpyServerCommandTargetsScrcpyServerProcess() {
        assertEquals(
            listOf("adb", "-s", "SM-S921U", "shell", "pkill", "-f", "com.genymobile.scrcpy.Server"),
            killOrphanedScrcpyServerCommand("adb", "SM-S921U"),
        )
    }

    @Test
    fun resolveDockLiveMirrorPrefersWarmPrimarySession() {
        val primary = StubMirrorEngine()
        val pooled = StubMirrorEngine()
        assertSame(
            primary,
            resolveDockLiveMirror("device-1", primarySerial = "device-1", primary = primary, pooled = pooled),
        )
        assertSame(
            pooled,
            resolveDockLiveMirror("device-1", primarySerial = "device-2", primary = primary, pooled = pooled),
        )
        assertNull(
            resolveDockLiveMirror("device-1", primarySerial = "device-2", primary = primary, pooled = null),
        )
    }

    @Test
    fun withExclusiveSerializesOverlappingSessions() = runBlocking {
        val serial = "gate-serialize-${System.nanoTime()}"
        val order = mutableListOf<String>()
        val secondStarted = Mutex(locked = true)
        val first = async {
            ScrcpySerialGate.withExclusive(serial) {
                order += "first-enter"
                secondStarted.unlock()
                delay(50)
                order += "first-exit"
            }
        }
        secondStarted.lock()
        val second = async {
            ScrcpySerialGate.withExclusive(
                serial,
                onWaiting = { order += "second-waiting" },
            ) {
                order += "second-enter"
            }
        }
        first.await()
        second.await()
        assertEquals(
            listOf("first-enter", "second-waiting", "first-exit", "second-enter"),
            order,
        )
        assertTrue(ScrcpySerialGate.isAvailable(serial))
    }

    @Test
    fun differentDisplaysOfOneSerialCaptureConcurrently() = runBlocking {
        val serial = "gate-dual-${System.nanoTime()}"
        val primaryKey = MirrorTarget.id(serial, 0)
        val secondaryKey = MirrorTarget.id(serial, 2)
        val primaryEntered = CompletableDeferred<Unit>()
        val secondaryEntered = CompletableDeferred<Unit>()
        val first = async {
            ScrcpySerialGate.withExclusive(primaryKey, serial) {
                primaryEntered.complete(Unit)
                secondaryEntered.await()
            }
        }
        val second = async {
            ScrcpySerialGate.withExclusive(secondaryKey, serial) {
                secondaryEntered.complete(Unit)
                primaryEntered.await()
            }
        }
        withTimeout(2_000) {
            first.await()
            second.await()
        }
        assertTrue(ScrcpySerialGate.isAvailable(primaryKey))
        assertTrue(ScrcpySerialGate.isAvailable(secondaryKey))
    }

    @Test
    fun concurrentDisplayStartupsCleanUpOrphansExactlyOnce() = runBlocking {
        val serial = "gate-cleanup-${System.nanoTime()}"
        val primaryKey = MirrorTarget.id(serial, 0)
        val secondaryKey = MirrorTarget.id(serial, 2)
        val cleanups = AtomicInteger(0)
        val first = async {
            ScrcpySerialGate.withExclusive(primaryKey, serial) {
                ScrcpySerialGate.withDeviceStartup(primaryKey, serial) { cleanups.incrementAndGet() }
                delay(20)
            }
        }
        val second = async {
            ScrcpySerialGate.withExclusive(secondaryKey, serial) {
                ScrcpySerialGate.withDeviceStartup(secondaryKey, serial) { cleanups.incrementAndGet() }
            }
        }
        first.await()
        second.await()
        assertEquals(1, cleanups.get())
        // Once both streams end, the next startup must clean up again.
        ScrcpySerialGate.withExclusive(primaryKey, serial) {
            ScrcpySerialGate.withDeviceStartup(primaryKey, serial) { cleanups.incrementAndGet() }
        }
        assertEquals(2, cleanups.get())
    }

    @Test
    fun differentSerialsDoNotBlockEachOther() = runBlocking {
        val serialA = "gate-a-${System.nanoTime()}"
        val serialB = "gate-b-${System.nanoTime()}"
        val aEntered = Mutex(locked = true)
        val bEntered = Mutex(locked = true)
        val first = async {
            ScrcpySerialGate.withExclusive(serialA) {
                aEntered.unlock()
                bEntered.lock()
            }
        }
        val second = async {
            aEntered.lock()
            ScrcpySerialGate.withExclusive(serialB) {
                bEntered.unlock()
            }
        }
        first.await()
        second.await()
        assertTrue(ScrcpySerialGate.isAvailable(serialA))
        assertTrue(ScrcpySerialGate.isAvailable(serialB))
    }
}

private class StubMirrorEngine : MirrorEngine {
    override val session = MutableStateFlow<MirrorSession?>(null)
    override val frames = MutableStateFlow(MirrorFrame(1, 1, intArrayOf(0xff000000.toInt())))
    override val status = MutableStateFlow("ready")
    override suspend fun connect(serial: String, config: MirrorVideoConfig) = CommandResult.success("ok")
    override suspend fun disconnect(immediate: Boolean) = Unit
    override suspend fun sendInput(input: MirrorInput) = CommandResult.success()
    override suspend fun screenshot(serial: String): ByteArray? = null
}
