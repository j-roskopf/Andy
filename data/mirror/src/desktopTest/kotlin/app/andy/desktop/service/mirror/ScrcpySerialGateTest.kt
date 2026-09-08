package app.andy.desktop.service.mirror

import app.andy.service.CommandResult
import app.andy.service.MirrorEngine
import app.andy.service.MirrorFrame
import app.andy.service.MirrorInput
import app.andy.service.MirrorSession
import app.andy.service.MirrorVideoConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
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
