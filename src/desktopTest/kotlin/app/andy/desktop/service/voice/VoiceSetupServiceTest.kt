package app.andy.desktop.service.voice

import app.andy.service.VoiceSetupState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class VoiceSetupServiceTest {
    @Test
    fun modelUrlIsPinnedToImmutableRevisionNotMain() {
        assertTrue(
            VoiceArtifacts.MODEL_URL.contains("/resolve/80da2d8bfee42b0e836fc3a9890373e5defc00a6/"),
            VoiceArtifacts.MODEL_URL,
        )
        assertTrue(!VoiceArtifacts.MODEL_URL.contains("/resolve/main/"), VoiceArtifacts.MODEL_URL)
        assertTrue(VoiceArtifacts.MODEL_SHA256.length == 64)
    }

    @Test
    fun macOsPackagesPinLibompAndGgmlBottles() {
        val arm = VoiceArtifacts.binaryPackage(VoiceArtifacts.Platform.MacOsArm64)
        assertTrue(arm.secondary != null, "ggml bottle required")
        assertTrue(arm.tertiary != null, "libomp bottle required")
        assertTrue(arm.tertiary!!.url.contains("/libomp/"))
        val x64 = VoiceArtifacts.binaryPackage(VoiceArtifacts.Platform.MacOsX64)
        assertTrue(x64.tertiary != null, "libomp bottle required")
        assertTrue(x64.tertiary!!.sha256.length == 64)
    }

    @Test
    fun extractZipRejectsPathTraversalEntries() {
        val dir = File.createTempFile("andy-zip-trav", null).also { it.delete(); it.mkdirs() }
        val zip = File(dir, "evil.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("../escape.txt"))
            zos.write("nope".toByteArray())
            zos.closeEntry()
        }
        val dest = File(dir, "out").also { it.mkdirs() }
        val error = runCatching { extractZip(zip, dest) }.exceptionOrNull()
        assertTrue(error is IllegalStateException || error is IllegalArgumentException || error != null, "$error")
        assertTrue(error!!.message.orEmpty().contains("escapes"), error.message)
        assertTrue(!File(dir, "escape.txt").exists())
        dir.deleteRecursively()
    }

    @Test
    fun preferredGgmlBackendPrefersAppleCpuOverMetal() {
        val dir = File.createTempFile("andy-backends", null).also { it.delete(); it.mkdirs() }
        File(dir, "libggml-metal.so").writeText("m")
        File(dir, "libggml-cpu-apple_m4.so").writeText("c")
        assertEquals("libggml-cpu-apple_m4.so", preferredGgmlBackend(dir)?.name)
        dir.deleteRecursively()
    }

    /**
     * Live macOS bottle install + whisper-cli --help smoke. Opt-in via ANDY_VOICE_LIVE_SMOKE=1
     * (downloads ~tens of MB of Homebrew bottles; uses a tiny fake model so the 148MB model is skipped).
     */
    @Test
    fun macOsLiveBinaryInstallReachesReadyWithSmokeCheck() = runBlocking {
        val live = System.getProperty("andy.voice.live.smoke") == "1" ||
            System.getenv("ANDY_VOICE_LIVE_SMOKE") == "1"
        if (!live) return@runBlocking
        val plat = VoiceArtifacts.detectPlatform()
        assertTrue(
            plat == VoiceArtifacts.Platform.MacOsArm64 || plat == VoiceArtifacts.Platform.MacOsX64,
            "live smoke requires macOS, got $plat",
        )
        val fixedHome = System.getProperty("andy.voice.live.home")?.takeIf { it.isNotBlank() }?.let(::File)
        val home = fixedHome ?: tempHome()
        val modelBytes = byteArrayOf(9, 9, 9, 9)
        val realModel = File(home, ".andy/voice/models/${VoiceArtifacts.MODEL_NAME}")
        val useRealModel = realModel.isFile && realModel.length() == VoiceArtifacts.MODEL_BYTES
        val service = DesktopVoiceSetupService(
            home = home,
            platform = plat,
            modelUrl = if (useRealModel) VoiceArtifacts.MODEL_URL else "https://example.test/model",
            modelSha256 = if (useRealModel) VoiceArtifacts.MODEL_SHA256 else sha256(modelBytes),
            modelBytes = if (useRealModel) VoiceArtifacts.MODEL_BYTES else modelBytes.size.toLong(),
            downloader = VoiceHttpDownloader { url, dest, headers, onProgress ->
                if (url.contains("example.test/model")) {
                    dest.writeBytes(modelBytes)
                    onProgress(1f)
                } else {
                    DefaultVoiceHttpDownloader.download(url, dest, headers, onProgress)
                }
            },
        )
        service.enable()
        assertIs<VoiceSetupState.Ready>(service.state.value, service.state.value.toString())
        assertTrue(File(home, ".andy/voice/lib/libomp.dylib").isFile)
        assertTrue(service.preferredBackendFile()?.isFile == true)
        // Relocated runtime must not still point at the Homebrew placeholder prefix.
        val otool = ProcessBuilder("otool", "-L", service.binaryFile().absolutePath)
            .redirectErrorStream(true).start()
        val deps = otool.inputStream.bufferedReader().readText()
        otool.waitFor()
        assertTrue(!deps.contains("@@HOMEBREW_PREFIX@@"), deps)
        if (fixedHome == null) home.deleteRecursively()
        Unit
    }

    @Test
    fun enableDownloadsBinaryAndModelInOrderAndReachesReady() = runBlocking {
        val home = tempHome()
        val order = mutableListOf<String>()
        val modelBytes = byteArrayOf(9, 9, 9, 9)
        val binBytes = byteArrayOf(1, 2, 3)
        val service = DesktopVoiceSetupService(
            home = home,
            platform = VoiceArtifacts.Platform.LinuxX64,
            downloader = recordingDownloader(order, mapOf("binary" to binBytes, "model" to modelBytes)),
            binaryInstaller = VoiceBinaryInstaller { _, _, _ ->
                File(home, ".andy/voice/bin/whisper-cli").apply {
                    parentFile?.mkdirs()
                    writeText("bin")
                    setExecutable(true)
                }
            },
            binaryPackageFor = {
                VoiceArtifacts.BinaryPackage(
                    it,
                    VoiceArtifacts.Download(
                        url = "https://example.test/binary",
                        sha256 = sha256(binBytes),
                        label = "binary",
                    ),
                )
            },
            modelUrl = "https://example.test/model",
            modelSha256 = sha256(modelBytes),
            modelBytes = modelBytes.size.toLong(),
        )
        service.enable()
        assertIs<VoiceSetupState.Ready>(service.state.value)
        assertEquals(listOf("binary", "model"), order)
        assertTrue(service.binaryFile().isFile)
        assertTrue(service.modelFile().isFile)
        home.deleteRecursively()
        Unit
    }

    @Test
    fun enableWithOnlyModelMissingDownloadsOnlyModel() = runBlocking {
        val home = tempHome()
        val order = mutableListOf<String>()
        val modelBytes = byteArrayOf(4, 5, 6)
        File(home, ".andy/voice/bin/whisper-cli").apply {
            parentFile?.mkdirs()
            writeText("bin")
            setExecutable(true)
        }
        File(home, ".andy/voice/state.json").apply {
            parentFile?.mkdirs()
            writeText(
                """{"binaryVersion":"${VoiceArtifacts.BINARY_VERSION}","model":"${VoiceArtifacts.MODEL_NAME}","enabled":false}""",
            )
        }
        val service = DesktopVoiceSetupService(
            home = home,
            platform = VoiceArtifacts.Platform.LinuxX64,
            downloader = recordingDownloader(order, mapOf("model" to modelBytes)),
            binaryInstaller = VoiceBinaryInstaller { _, _, _ -> error("should not install binary") },
            binaryPackageFor = {
                VoiceArtifacts.BinaryPackage(
                    it,
                    VoiceArtifacts.Download(
                        url = "https://example.test/binary",
                        sha256 = "x".repeat(64),
                        label = "binary",
                    ),
                )
            },
            modelUrl = "https://example.test/model",
            modelSha256 = sha256(modelBytes),
            modelBytes = modelBytes.size.toLong(),
        )
        service.enable()
        assertIs<VoiceSetupState.Ready>(service.state.value)
        assertEquals(listOf("model"), order)
        home.deleteRecursively()
        Unit
    }

    @Test
    fun checksumMismatchFailsWithoutDeletingGoodPeerFile() = runBlocking {
        val home = tempHome()
        File(home, ".andy/voice/bin/whisper-cli").apply {
            parentFile?.mkdirs()
            writeText("good-bin")
            setExecutable(true)
        }
        File(home, ".andy/voice/state.json").apply {
            parentFile?.mkdirs()
            writeText(
                """{"binaryVersion":"${VoiceArtifacts.BINARY_VERSION}","model":"${VoiceArtifacts.MODEL_NAME}","enabled":false}""",
            )
        }
        val service = DesktopVoiceSetupService(
            home = home,
            platform = VoiceArtifacts.Platform.LinuxX64,
            downloader = VoiceHttpDownloader { _, dest, _, onProgress ->
                dest.writeBytes(byteArrayOf(1, 2, 3))
                onProgress(1f)
            },
            binaryInstaller = VoiceBinaryInstaller { _, _, _ -> error("unused") },
            modelUrl = "https://example.test/model",
            modelSha256 = "0".repeat(64),
            modelBytes = 3,
        )
        service.enable()
        val failed = assertIs<VoiceSetupState.Failed>(service.state.value)
        assertEquals("model", failed.what)
        assertTrue(File(home, ".andy/voice/bin/whisper-cli").isFile)
        home.deleteRecursively()
        Unit
    }

    @Test
    fun modelFailureAfterBinaryInstallDoesNotRedownloadBinaryOnRetry() = runBlocking {
        val home = tempHome()
        val order = mutableListOf<String>()
        val binBytes = byteArrayOf(1, 2, 3)
        val goodModel = byteArrayOf(9, 9, 9, 9)
        var modelAttempts = 0
        val service = DesktopVoiceSetupService(
            home = home,
            platform = VoiceArtifacts.Platform.LinuxX64,
            downloader = VoiceHttpDownloader { url, dest, _, onProgress ->
                val key = if (url.contains("model")) "model" else "binary"
                order += key
                if (key == "binary") {
                    dest.writeBytes(binBytes)
                } else {
                    modelAttempts++
                    if (modelAttempts == 1) {
                        dest.writeBytes(byteArrayOf(0, 0, 0, 0))
                    } else {
                        dest.writeBytes(goodModel)
                    }
                }
                onProgress(1f)
            },
            binaryInstaller = VoiceBinaryInstaller { _, _, _ ->
                File(home, ".andy/voice/bin/whisper-cli").apply {
                    parentFile?.mkdirs()
                    writeText("bin")
                    setExecutable(true)
                }
            },
            binaryPackageFor = {
                VoiceArtifacts.BinaryPackage(
                    it,
                    VoiceArtifacts.Download(
                        url = "https://example.test/binary",
                        sha256 = sha256(binBytes),
                        label = "binary",
                    ),
                )
            },
            modelUrl = "https://example.test/model",
            modelSha256 = sha256(goodModel),
            modelBytes = goodModel.size.toLong(),
        )
        service.enable()
        val failed = assertIs<VoiceSetupState.Failed>(service.state.value)
        assertEquals("model", failed.what)
        assertEquals(listOf("binary", "model"), order)

        service.enable()
        assertIs<VoiceSetupState.Ready>(service.state.value)
        assertEquals(listOf("binary", "model", "model"), order)
        home.deleteRecursively()
        Unit
    }

    @Test
    fun sameSizeCorruptModelIsNotTreatedAsReady() = runBlocking {
        val home = tempHome()
        val order = mutableListOf<String>()
        val goodModel = byteArrayOf(1, 1, 1, 1)
        val corruptModel = byteArrayOf(2, 2, 2, 2)
        File(home, ".andy/voice/bin/whisper-cli").apply {
            parentFile?.mkdirs()
            writeText("bin")
            setExecutable(true)
        }
        File(home, ".andy/voice/models/${VoiceArtifacts.MODEL_NAME}").apply {
            parentFile?.mkdirs()
            writeBytes(corruptModel)
        }
        File(home, ".andy/voice/state.json").apply {
            parentFile?.mkdirs()
            writeText(
                """{"binaryVersion":"${VoiceArtifacts.BINARY_VERSION}","model":"${VoiceArtifacts.MODEL_NAME}","enabled":true}""",
            )
        }
        val service = DesktopVoiceSetupService(
            home = home,
            platform = VoiceArtifacts.Platform.LinuxX64,
            downloader = recordingDownloader(order, mapOf("model" to goodModel)),
            binaryInstaller = VoiceBinaryInstaller { _, _, _ -> error("should not install binary") },
            modelUrl = "https://example.test/model",
            modelSha256 = sha256(goodModel),
            modelBytes = goodModel.size.toLong(),
        )
        // Corrupt same-size file must not restore Ready from persisted enabled=true.
        assertIs<VoiceSetupState.NotEnabled>(service.state.value)
        service.enable()
        assertIs<VoiceSetupState.Ready>(service.state.value)
        assertEquals(listOf("model"), order)
        assertTrue(service.modelFile().readBytes().contentEquals(goodModel))
        home.deleteRecursively()
        Unit
    }

    @Test
    fun disableThenEnableDoesNotRedownload() = runBlocking {
        val home = tempHome()
        var downloads = 0
        val modelBytes = byteArrayOf(7, 7, 7)
        File(home, ".andy/voice/bin/whisper-cli").apply {
            parentFile?.mkdirs()
            writeText("bin")
            setExecutable(true)
        }
        File(home, ".andy/voice/models/${VoiceArtifacts.MODEL_NAME}").apply {
            parentFile?.mkdirs()
            writeBytes(modelBytes)
        }
        File(home, ".andy/voice/state.json").apply {
            parentFile?.mkdirs()
            writeText(
                """{"binaryVersion":"${VoiceArtifacts.BINARY_VERSION}","model":"${VoiceArtifacts.MODEL_NAME}","enabled":true}""",
            )
        }
        val service = DesktopVoiceSetupService(
            home = home,
            platform = VoiceArtifacts.Platform.LinuxX64,
            downloader = VoiceHttpDownloader { _, dest, _, onProgress ->
                downloads++
                dest.writeBytes(byteArrayOf(0))
                onProgress(1f)
            },
            modelBytes = modelBytes.size.toLong(),
            modelSha256 = sha256(modelBytes),
        )
        assertIs<VoiceSetupState.Ready>(service.state.value)
        service.disable()
        assertIs<VoiceSetupState.NotEnabled>(service.state.value)
        service.enable()
        assertIs<VoiceSetupState.Ready>(service.state.value)
        assertEquals(0, downloads)
        home.deleteRecursively()
        Unit
    }

    @Test
    fun deleteDownloadsRemovesOnDemandArtifactsButKeepsPackagedNative() = runBlocking {
        val home = tempHome()
        val modelBytes = byteArrayOf(3, 3, 3)
        File(home, ".andy/voice/bin/whisper-cli").apply {
            parentFile?.mkdirs()
            writeText("bin")
            setExecutable(true)
        }
        File(home, ".andy/voice/lib/libomp.dylib").apply {
            parentFile?.mkdirs()
            writeText("lib")
        }
        File(home, ".andy/voice/libexec/ggml-cpu.so").apply {
            parentFile?.mkdirs()
            writeText("backend")
        }
        File(home, ".andy/voice/models/${VoiceArtifacts.MODEL_NAME}").apply {
            parentFile?.mkdirs()
            writeBytes(modelBytes)
        }
        File(home, ".andy/voice/runtime/tmp").apply {
            parentFile?.mkdirs()
            writeText("scratch")
        }
        File(home, ".andy/voice/state.json").apply {
            parentFile?.mkdirs()
            writeText(
                """{"binaryVersion":"${VoiceArtifacts.BINARY_VERSION}","model":"${VoiceArtifacts.MODEL_NAME}","enabled":true}""",
            )
        }
        File(home, ".andy/voice/debug.log").apply {
            parentFile?.mkdirs()
            writeText("log")
        }
        val nativeBridge = File(home, ".andy/voice/native/andy-voice/macos-arm64/andy-voice-jni.dylib").apply {
            parentFile?.mkdirs()
            writeText("packaged")
        }
        val service = DesktopVoiceSetupService(
            home = home,
            platform = VoiceArtifacts.Platform.LinuxX64,
            modelBytes = modelBytes.size.toLong(),
            modelSha256 = sha256(modelBytes),
        )
        assertTrue(service.hasDownloads())
        assertIs<VoiceSetupState.Ready>(service.state.value)

        service.deleteDownloads()

        assertIs<VoiceSetupState.NotEnabled>(service.state.value)
        assertFalse(service.hasDownloads())
        assertFalse(File(home, ".andy/voice/bin").exists())
        assertFalse(File(home, ".andy/voice/lib").exists())
        assertFalse(File(home, ".andy/voice/libexec").exists())
        assertFalse(File(home, ".andy/voice/models").exists())
        assertFalse(File(home, ".andy/voice/runtime").exists())
        assertFalse(File(home, ".andy/voice/state.json").exists())
        assertFalse(File(home, ".andy/voice/debug.log").exists())
        assertTrue(nativeBridge.isFile, "packaged native bridge must survive deleteDownloads")
        home.deleteRecursively()
        Unit
    }

    @Test
    fun deleteDownloadsReportsFailureWhenArtifactsRemainLocked() = runBlocking {
        // macOS uchg makes File.delete() fail the same way a locked whisper-cli.exe does on Windows.
        assumeTrue(
            "immutable-file fixture uses macOS chflags",
            System.getProperty("os.name").contains("mac", ignoreCase = true),
        )
        val home = tempHome()
        val locked = File(home, ".andy/voice/bin/whisper-cli").apply {
            parentFile?.mkdirs()
            writeText("bin")
            setExecutable(true)
        }
        val chflags = ProcessBuilder("chflags", "uchg", locked.absolutePath).start()
        assertEquals(0, chflags.waitFor(), "chflags uchg failed")
        try {
            val service = DesktopVoiceSetupService(
                home = home,
                platform = VoiceArtifacts.Platform.MacOsArm64,
            )
            assertTrue(service.hasDownloads())
            service.deleteDownloads()
            val failed = assertIs<VoiceSetupState.Failed>(service.state.value)
            assertEquals("delete", failed.what)
            assertTrue(service.hasDownloads(), "locked binary must remain after failed delete")
            assertTrue(locked.isFile)
        } finally {
            ProcessBuilder("chflags", "nouchg", locked.absolutePath).start().waitFor()
            home.deleteRecursively()
        }
        Unit
    }

    @Test
    fun disableWhileDownloaderBlockedKeepsDisabled() = runBlocking {
        val home = tempHome()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val binBytes = byteArrayOf(1, 2, 3)
        val modelBytes = byteArrayOf(9, 9, 9, 9)
        val service = DesktopVoiceSetupService(
            home = home,
            platform = VoiceArtifacts.Platform.LinuxX64,
            downloader = VoiceHttpDownloader { url, dest, _, onProgress ->
                started.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS), "release latch timed out")
                val payload = if (url.contains("model")) modelBytes else binBytes
                dest.writeBytes(payload)
                onProgress(1f)
            },
            binaryInstaller = VoiceBinaryInstaller { _, _, _ ->
                File(home, ".andy/voice/bin/whisper-cli").apply {
                    parentFile?.mkdirs()
                    writeText("bin")
                    setExecutable(true)
                }
            },
            binaryPackageFor = {
                VoiceArtifacts.BinaryPackage(
                    it,
                    VoiceArtifacts.Download(
                        url = "https://example.test/binary",
                        sha256 = sha256(binBytes),
                        label = "binary",
                    ),
                )
            },
            modelUrl = "https://example.test/model",
            modelSha256 = sha256(modelBytes),
            modelBytes = modelBytes.size.toLong(),
        )

        // enable() must run off the runBlocking event loop so the blocked download
        // does not starve the test thread waiting on `started`.
        val enableJob = async(Dispatchers.IO) { service.enable() }
        assertTrue(started.await(5, TimeUnit.SECONDS), "download never started")
        assertIs<VoiceSetupState.Downloading>(service.state.value)

        service.disable()
        assertIs<VoiceSetupState.NotEnabled>(service.state.value)

        release.countDown()
        withTimeout(10_000) { enableJob.await() }

        assertIs<VoiceSetupState.NotEnabled>(service.state.value)
        val persisted = Json.parseToJsonElement(File(home, ".andy/voice/state.json").readText()).jsonObject
        assertFalse(persisted["enabled"]?.jsonPrimitive?.booleanOrNull == true)
        home.deleteRecursively()
        Unit
    }

    private fun recordingDownloader(
        order: MutableList<String>,
        payloads: Map<String, ByteArray>,
    ): VoiceHttpDownloader = VoiceHttpDownloader { url, dest, _, onProgress ->
        val key = if (url.contains("model")) "model" else "binary"
        order += key
        dest.writeBytes(payloads.getValue(key))
        onProgress(1f)
    }

    private fun tempHome(): File =
        File.createTempFile("andy-voice-home", null).also {
            it.delete()
            it.mkdirs()
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
