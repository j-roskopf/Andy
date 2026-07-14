package app.andy.desktop.service.mirror

import app.andy.service.MirrorInput
import app.andy.service.MirrorVideoConfig
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AndyMirrorProcessTest {
    @Test
    fun stagedCompanionReceivesOnlyAndyOwnedTransportDependencies() = runBlocking {
        if (System.getProperty("os.name").contains("windows", ignoreCase = true)) return@runBlocking
        val executable = Files.createTempFile("andy-mirror-environment", ".sh").toFile().apply {
            writeText(
                """
                #!/bin/sh
                while IFS= read -r line; do
                  case "${'$'}line" in
                    *'"type":"start"'*)
                      if [ "${'$'}ANDY_MIRROR_ADB_PATH" != "/andy/adb" ] || [ "${'$'}ANDY_MIRROR_SCRCPY_SERVER_PATH" != "/andy/scrcpy-server" ]; then
                        echo '{"type":"failure","failureReason":"missing Andy-owned transport inputs"}'
                        exit 1
                      fi
                      echo '{"type":"ready","decoder":"Fixture H264","renderer":"Fixture Metal","decoderHardwareBacked":true,"rendererHardwareBacked":true,"hardwareBacked":true}'
                      ;;
                    *'"type":"stop"'*) exit 0 ;;
                  esac
                done
                """.trimIndent(),
            )
            setExecutable(true)
        }
        val process = AndyMirrorProcess(
            executable = executable,
            onEvent = {},
            environment = mapOf(
                "ANDY_MIRROR_ADB_PATH" to "/andy/adb",
                "ANDY_MIRROR_SCRCPY_SERVER_PATH" to "/andy/scrcpy-server",
            ),
        )
        try {
            assertTrue(process.start("device-1", MirrorVideoConfig())?.isVerifiedHardwareReady() == true)
        } finally {
            process.close()
            executable.delete()
        }
    }

    @Test
    fun stagedCompanionUsesLineProtocolForReadyStatsInputAndStop() = runBlocking {
        if (System.getProperty("os.name").contains("windows", ignoreCase = true)) return@runBlocking
        val executable = Files.createTempFile("andy-mirror-protocol", ".sh").toFile().apply {
            writeText(
                """
                #!/bin/sh
                while IFS= read -r line; do
                  case "${'$'}line" in
                    *'"type":"start"'*) echo '{"type":"ready","decoder":"Fixture H264","renderer":"Fixture Metal","decoderHardwareBacked":true,"rendererHardwareBacked":true,"hardwareBacked":true,"width":720,"height":1280}' ;;
                    *'"type":"attach"'*) echo '{"type":"stats","framesPresented":7}' ;;
                    *'"type":"input"'*) echo '{"type":"stats","displayedFps":60,"decodedFps":60,"droppedFrames":0,"framesPresented":42,"p95InputToPresentMillis":17.5}' ;;
                    *'"type":"stop"'*) echo '{"type":"stopped"}'; exit 0 ;;
                  esac
                done
                """.trimIndent(),
            )
            setExecutable(true)
        }
        val events = CopyOnWriteArrayList<AndyMirrorEvent>()
        val process = AndyMirrorProcess(executable, events::add)
        try {
            val ready = process.start("device-1", MirrorVideoConfig())
            assertNotNull(ready)
            assertEquals("ready", ready.type)
            assertTrue(ready.decoderHardwareBacked == true)
            assertTrue(ready.rendererHardwareBacked == true)
            assertTrue(ready.hardwareBacked == true)

            assertTrue(process.attach("popout"))
            repeat(50) {
                if (events.any { it.framesPresented == 7L }) return@repeat
                delay(10)
            }
            assertTrue(events.any { it.framesPresented == 7L }, "Expected pop-out attach command")

            assertTrue(process.input(MirrorInput.Tap(10, 12)))
            repeat(50) {
                if (events.any { it.displayedFps != null }) return@repeat
                delay(10)
            }
            assertEquals(60f, events.first { it.displayedFps != null }.displayedFps)
        } finally {
            process.close()
            executable.delete()
        }
    }
}
