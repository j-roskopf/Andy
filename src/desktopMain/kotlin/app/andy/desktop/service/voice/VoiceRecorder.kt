package app.andy.desktop.service.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.io.StringWriter
import java.time.Instant
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.TargetDataLine

interface VoiceRecorder {
    suspend fun startRecording(): Boolean
    /** Stops capture; returns temp WAV or null when under [MIN_DURATION_MS]. */
    suspend fun stopRecording(): File?
}

/** Appends timestamped diagnostics to ~/.andy/voice/debug.log for capture-path troubleshooting. */
internal fun voiceDebugLog(message: String) {
    runCatching {
        val log = File(File(System.getProperty("user.home"), ".andy/voice"), "debug.log")
        log.parentFile?.mkdirs()
        log.appendText("${Instant.now()} $message\n")
    }
}

private fun Throwable.stackString(): String {
    val sw = StringWriter()
    printStackTrace(PrintWriter(sw))
    return sw.toString()
}

class JavaxSoundVoiceRecorder(
    private val tempDir: File = File(System.getProperty("java.io.tmpdir")),
    private val clock: () -> Long = System::currentTimeMillis,
    private val ensureMicPermission: () -> MacOsMicPermission? = {
        if (!MacOsMicrophoneAccess.isSupported()) null
        else MacOsMicrophoneAccess.requestAccess()
    },
) : VoiceRecorder {
    private var line: TargetDataLine? = null
    private var startedAtMs: Long = 0L
    private var captureThread: Thread? = null
    private val pcmBuffer = ByteArrayOutputStream()
    private val lock = Any()

    /** Set when stop drops a silent clip so the dictation service can surface a mic-access hint. */
    @Volatile
    var lastStopWasSilent: Boolean = false
        private set

    override suspend fun startRecording(): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (line != null) return@withContext false
            pcmBuffer.reset()
            lastStopWasSilent = false

            when (val permission = ensureMicPermission()) {
                null -> Unit // non-macOS / bridge unavailable — fall through to Java Sound
                MacOsMicPermission.Granted -> voiceDebugLog("startRecording: macOS mic permission granted")
                MacOsMicPermission.Denied, MacOsMicPermission.Restricted -> {
                    voiceDebugLog("startRecording: macOS mic permission=$permission")
                    return@withContext false
                }
                MacOsMicPermission.NotDetermined, MacOsMicPermission.Unavailable -> {
                    voiceDebugLog("startRecording: macOS mic permission unresolved ($permission); attempting capture anyway")
                }
            }

            val format = AudioFormat(
                /* sampleRate = */ SAMPLE_RATE,
                /* sampleSizeInBits = */ 16,
                /* channels = */ 1,
                /* signed = */ true,
                /* bigEndian = */ false,
            )
            val info = DataLine.Info(TargetDataLine::class.java, format)
            val mixers = AudioSystem.getMixerInfo().toList()
            voiceDebugLog("startRecording: available mixers=[${mixers.joinToString("; ") { it.name }}]")
            val selected = selectCaptureMixer(mixers, format)
            if (selected == null && !AudioSystem.isLineSupported(info)) {
                voiceDebugLog("startRecording: no capture mixer supports $info")
                return@withContext false
            }
            val target = try {
                if (selected != null) {
                    voiceDebugLog("startRecording: opening mixer='${selected.info.name}'")
                    selected.mixer.getLine(info) as TargetDataLine
                } else {
                    voiceDebugLog("startRecording: opening AudioSystem default TargetDataLine")
                    AudioSystem.getLine(info) as TargetDataLine
                }
            } catch (t: Exception) {
                voiceDebugLog("startRecording: getLine threw ${t.stackString()}")
                return@withContext false
            }
            try {
                target.open(format)
                target.start()
            } catch (t: Exception) {
                voiceDebugLog("startRecording: open/start threw ${t.stackString()}")
                runCatching { target.close() }
                return@withContext false
            }
            voiceDebugLog(
                "startRecording: opened line mixer=${target.lineInfo} " +
                    "bufferSize=${target.bufferSize} format=${target.format}",
            )
            line = target
            startedAtMs = clock()
            captureThread = Thread({
                val buf = ByteArray(4096)
                var totalRead = 0
                while (true) {
                    val active = synchronized(lock) { line }
                    if (active == null) break
                    val read = try {
                        active.read(buf, 0, buf.size)
                    } catch (t: Exception) {
                        voiceDebugLog("captureThread: read threw ${t.stackString()}")
                        break
                    }
                    if (read > 0) {
                        totalRead += read
                        synchronized(lock) { pcmBuffer.write(buf, 0, read) }
                    } else if (read < 0) {
                        voiceDebugLog("captureThread: read returned $read (EOF) after $totalRead bytes")
                        break
                    }
                }
            }, "andy-voice-capture").also {
                it.isDaemon = true
                it.start()
            }
            true
        }
    }

    override suspend fun stopRecording(): File? = withContext(Dispatchers.IO) {
        val elapsed: Long
        val pcm: ByteArray
        synchronized(lock) {
            val active = line ?: return@withContext null
            elapsed = clock() - startedAtMs
            runCatching {
                active.stop()
                active.close()
            }
            line = null
            captureThread?.join(500)
            captureThread = null
            pcm = pcmBuffer.toByteArray()
            pcmBuffer.reset()
        }
        val peak = peakAmplitude(pcm)
        voiceDebugLog(
            "stopRecording: elapsedMs=$elapsed bytes=${pcm.size} peakAmplitude=$peak " +
                "(silenceThreshold=$SILENCE_PEAK_THRESHOLD, minDurationMs=$MIN_DURATION_MS)",
        )
        if (elapsed < MIN_DURATION_MS || pcm.isEmpty() || peak < SILENCE_PEAK_THRESHOLD) {
            lastStopWasSilent = peak < SILENCE_PEAK_THRESHOLD && elapsed >= MIN_DURATION_MS && pcm.isNotEmpty()
            voiceDebugLog(
                "stopRecording: dropped clip (too short or effectively silent) lastStopWasSilent=$lastStopWasSilent",
            )
            return@withContext null
        }
        lastStopWasSilent = false
        val wav = File(tempDir, "andy-voice-${clock()}.wav")
        writeWav(wav, pcm, SAMPLE_RATE, channels = 1, bitsPerSample = 16)
        wav
    }

    companion object {
        const val SAMPLE_RATE = 16_000f
        const val MIN_DURATION_MS = 300L
        /**
         * Peak 16-bit sample magnitude below this is treated as silence. Whisper hallucinates
         * filler text (commonly "you") when fed true silence, so a captured clip with no signal
         * above the noise floor — e.g. mic permission silently denied, wrong input device, muted
         * hardware — must be dropped here rather than handed to the transcriber.
         */
        const val SILENCE_PEAK_THRESHOLD = 400

        /** PCM is little-endian 16-bit signed mono; scan every sample for the peak magnitude. */
        internal fun peakAmplitude(pcm: ByteArray): Int {
            var peak = 0
            var i = 0
            while (i + 1 < pcm.size) {
                val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toInt()
                val magnitude = kotlin.math.abs(sample)
                if (magnitude > peak) peak = magnitude
                i += 2
            }
            return peak
        }
    }
}

internal data class CaptureMixerSelection(val info: Mixer.Info, val mixer: Mixer)

/**
 * Prefer a real microphone capture device over Port-mixers / speakers / the silent default.
 * Returns null when nothing looks usable so the caller can fall back to AudioSystem.getLine.
 */
internal fun selectCaptureMixer(
    mixers: List<Mixer.Info>,
    format: AudioFormat,
): CaptureMixerSelection? {
    val info = DataLine.Info(TargetDataLine::class.java, format)
    data class Candidate(val selection: CaptureMixerSelection, val score: Int)

    val candidates = mixers.mapNotNull { mixerInfo ->
        val lower = mixerInfo.name.lowercase()
        if (lower.startsWith("port ")) return@mapNotNull null
        if (lower.contains("speaker") || lower.contains("headphones") || lower.contains("output")) {
            return@mapNotNull null
        }
        val mixer = runCatching { AudioSystem.getMixer(mixerInfo) }.getOrNull() ?: return@mapNotNull null
        if (!mixer.isLineSupported(info)) return@mapNotNull null
        val score = when {
            lower.contains("microphone") || Regex("""\bmic\b""").containsMatchIn(lower) -> 100
            lower.contains("built-in") || lower.contains("macbook") -> 80
            lower.contains("usb") || lower.contains("brio") || lower.contains("webcam") -> 60
            lower.contains("default") -> 20
            else -> 40
        }
        Candidate(CaptureMixerSelection(mixerInfo, mixer), score)
    }.sortedByDescending { it.score }

    return candidates.firstOrNull()?.selection
}

internal fun writeWav(
    file: File,
    pcm: ByteArray,
    sampleRate: Float,
    channels: Int,
    bitsPerSample: Int,
) {
    val byteRate = (sampleRate * channels * bitsPerSample / 8).toInt()
    val blockAlign = (channels * bitsPerSample / 8).toShort()
    RandomAccessFile(file, "rw").use { raf ->
        raf.setLength(0)
        raf.writeBytes("RIFF")
        raf.writeIntLE(36 + pcm.size)
        raf.writeBytes("WAVE")
        raf.writeBytes("fmt ")
        raf.writeIntLE(16)
        raf.writeShortLE(1) // PCM
        raf.writeShortLE(channels)
        raf.writeIntLE(sampleRate.toInt())
        raf.writeIntLE(byteRate)
        raf.writeShortLE(blockAlign.toInt())
        raf.writeShortLE(bitsPerSample)
        raf.writeBytes("data")
        raf.writeIntLE(pcm.size)
        raf.write(pcm)
    }
}

private fun RandomAccessFile.writeIntLE(value: Int) {
    write(value and 0xff)
    write((value ushr 8) and 0xff)
    write((value ushr 16) and 0xff)
    write((value ushr 24) and 0xff)
}

private fun RandomAccessFile.writeShortLE(value: Int) {
    write(value and 0xff)
    write((value ushr 8) and 0xff)
}
