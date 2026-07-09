package app.andy.desktop.service.emulator

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

internal const val EMULATOR_IMAGE_BYTES_PER_PIXEL = 3

internal class EmulatorMappedFramebuffer private constructor(
    private val file: File,
    private val channel: FileChannel,
    private val buffer: MappedByteBuffer,
) : AutoCloseable {
    val handle: String = file.toPath().toUri().toASCIIString()

    fun frameBytes(byteCount: Int): ByteBuffer? {
        if (byteCount <= 0 || byteCount > buffer.capacity()) return null
        val frame = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        frame.position(0)
        frame.limit(byteCount)
        return frame.slice().order(ByteOrder.LITTLE_ENDIAN)
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { file.delete() }
    }

    companion object {
        fun create(maxSize: Int): EmulatorMappedFramebuffer {
            val boundedMaxSize = maxSize.coerceIn(1, 4096)
            val byteCount = boundedMaxSize.toLong() * boundedMaxSize * EMULATOR_IMAGE_BYTES_PER_PIXEL + 4096L
            val file = File.createTempFile("andy-emulator-framebuffer-", ".rgb")
            file.deleteOnExit()
            val channel = FileChannel.open(
                file.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            try {
                channel.truncate(byteCount)
                val buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, byteCount)
                return EmulatorMappedFramebuffer(file, channel, buffer)
            } catch (error: Throwable) {
                runCatching { channel.close() }
                runCatching { file.delete() }
                throw error
            }
        }
    }
}
