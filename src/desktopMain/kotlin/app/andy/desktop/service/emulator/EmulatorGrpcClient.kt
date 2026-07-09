package app.andy.desktop.service.emulator

import app.andy.desktop.service.mirror.EmulatorDisplaySize
import app.andy.desktop.service.mirror.scaledEmulatorTouchPoint
import app.andy.service.CommandResult
import app.andy.service.MirrorFrame
import app.andy.service.MirrorInput
import app.andy.service.MirrorTouchAction
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ClientInterceptors
import io.grpc.ForwardingClientCall
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.stub.ClientCalls
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

internal class EmulatorGrpcClient(
    host: String,
    port: Int,
    token: String?,
    initialDisplaySize: EmulatorDisplaySize?,
) {
    var displaySize: EmulatorDisplaySize? = initialDisplaySize
        private set
    private val managedChannel: ManagedChannel = ManagedChannelBuilder.forAddress(host, port)
        .usePlaintext()
        .build()
    private val channel: Channel = token
        ?.let { ClientInterceptors.intercept(managedChannel, EmulatorGrpcAuthInterceptor(it)) }
        ?: managedChannel

    fun streamScreenshots(maxSize: Int, mmapHandle: String? = null): Iterator<EmulatorImage> {
        return ClientCalls.blockingServerStreamingCall(
            channel,
            STREAM_SCREENSHOT_METHOD,
            CallOptions.DEFAULT,
            EmulatorImageFormat(maxSize, mmapHandle),
        )
    }

    fun sendInput(input: MirrorInput, frame: MirrorFrame): CommandResult? {
        return when (input) {
            is MirrorInput.Touch -> sendTouch(
                touch = EmulatorTouch(
                    x = scaleX(input.x, frame),
                    y = scaleY(input.y, frame),
                    pressure = if (input.action == MirrorTouchAction.Up) 0 else 1,
                ),
            )
            is MirrorInput.Tap -> {
                val x = scaleX(input.x, frame)
                val y = scaleY(input.y, frame)
                sendTouch(EmulatorTouch(x, y, pressure = 1))
                sendTouch(EmulatorTouch(x, y, pressure = 0))
            }
            is MirrorInput.Swipe -> {
                val startX = scaleX(input.startX, frame)
                val startY = scaleY(input.startY, frame)
                val endX = scaleX(input.endX, frame)
                val endY = scaleY(input.endY, frame)
                sendTouch(EmulatorTouch(startX, startY, pressure = 1))
                sendTouch(EmulatorTouch(endX, endY, pressure = 1))
                sendTouch(EmulatorTouch(endX, endY, pressure = 0))
            }
            is MirrorInput.Text -> sendText(input.value)
            // Named keys go over gRPC for snappy typing; anything unmapped (volume,
            // etc.) returns null so the caller falls back to adb keyevent.
            is MirrorInput.Key -> domKeyName(input.keyCode)?.let { sendNamedKey(it) }
            MirrorInput.Back,
            MirrorInput.Home,
            MirrorInput.Recents,
            MirrorInput.Power -> null
        }
    }

    // Returns null when the gRPC keyboard call cannot be delivered so the caller
    // falls back to adb `input text`/`keyevent` instead of surfacing an error.
    private fun sendText(text: String): CommandResult? {
        if (text.isEmpty()) return null
        return sendKey(EmulatorKeyEvent(eventType = KEY_EVENT_KEYPRESS, text = text))
    }

    private fun sendNamedKey(key: String): CommandResult? {
        val down = sendKey(EmulatorKeyEvent(eventType = KEY_EVENT_KEYDOWN, key = key)) ?: return null
        return sendKey(EmulatorKeyEvent(eventType = KEY_EVENT_KEYUP, key = key)) ?: down
    }

    private fun sendKey(event: EmulatorKeyEvent): CommandResult? {
        return runCatching {
            ClientCalls.blockingUnaryCall(channel, SEND_KEY_METHOD, CallOptions.DEFAULT, event)
            CommandResult.success("Input sent")
        }.getOrNull()
    }

    // Android key codes (KeyEvent.KEYCODE_*) → DOM-style key names the emulator understands.
    private fun domKeyName(androidKeyCode: Int): String? = when (androidKeyCode) {
        66 -> "Enter"
        67 -> "Backspace"
        112 -> "Delete"
        61 -> "Tab"
        111 -> "Escape"
        19 -> "ArrowUp"
        20 -> "ArrowDown"
        21 -> "ArrowLeft"
        22 -> "ArrowRight"
        122 -> "Home"
        123 -> "End"
        92 -> "PageUp"
        93 -> "PageDown"
        else -> null
    }

    private fun scaleX(x: Int, frame: MirrorFrame): Int {
        return scaledEmulatorTouchPoint(x, 0, frame, displaySize).x
    }

    private fun scaleY(y: Int, frame: MirrorFrame): Int {
        return scaledEmulatorTouchPoint(0, y, frame, displaySize).y
    }

    fun close() {
        runCatching {
            managedChannel.shutdownNow()
            managedChannel.awaitTermination(500, TimeUnit.MILLISECONDS)
        }
    }

    private fun sendTouch(touch: EmulatorTouch): CommandResult {
        return runCatching {
            ClientCalls.blockingUnaryCall(
                channel,
                SEND_TOUCH_METHOD,
                CallOptions.DEFAULT,
                EmulatorTouchEvent(listOf(touch)),
            )
            CommandResult.success("Input sent")
        }.getOrElse { error ->
            CommandResult.failure("Emulator gRPC touch failed: ${error.message ?: error::class.simpleName}")
        }
    }

    fun updateDisplaySize(next: EmulatorDisplaySize) {
        displaySize = next
    }

    private class EmulatorGrpcAuthInterceptor(private val token: String) : ClientInterceptor {
        override fun <ReqT : Any?, RespT : Any?> interceptCall(
            method: MethodDescriptor<ReqT, RespT>,
            callOptions: CallOptions,
            next: Channel,
        ): ClientCall<ReqT, RespT> {
            return object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
                override fun start(responseListener: Listener<RespT>, headers: Metadata) {
                    headers.put(AUTHORIZATION, "Bearer $token")
                    super.start(responseListener, headers)
                }
            }
        }
    }

    private companion object {
        private val AUTHORIZATION: Metadata.Key<String> =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
        private val STREAM_SCREENSHOT_METHOD: MethodDescriptor<EmulatorImageFormat, EmulatorImage> =
            MethodDescriptor.newBuilder<EmulatorImageFormat, EmulatorImage>()
                .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
                .setFullMethodName(
                    MethodDescriptor.generateFullMethodName(
                        "android.emulation.control.EmulatorController",
                        "streamScreenshot",
                    ),
                )
                .setRequestMarshaller(EmulatorImageFormatMarshaller)
                .setResponseMarshaller(EmulatorImageMarshaller)
                .build()
        private val SEND_TOUCH_METHOD: MethodDescriptor<EmulatorTouchEvent, Unit> =
            MethodDescriptor.newBuilder<EmulatorTouchEvent, Unit>()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(
                    MethodDescriptor.generateFullMethodName(
                        "android.emulation.control.EmulatorController",
                        "sendTouch",
                    ),
                )
                .setRequestMarshaller(EmulatorTouchEventMarshaller)
                .setResponseMarshaller(EmulatorEmptyMarshaller)
                .build()
        private val SEND_KEY_METHOD: MethodDescriptor<EmulatorKeyEvent, Unit> =
            MethodDescriptor.newBuilder<EmulatorKeyEvent, Unit>()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(
                    MethodDescriptor.generateFullMethodName(
                        "android.emulation.control.EmulatorController",
                        "sendKey",
                    ),
                )
                .setRequestMarshaller(EmulatorKeyEventMarshaller)
                .setResponseMarshaller(EmulatorEmptyMarshaller)
                .build()
    }
}

private const val KEY_EVENT_KEYDOWN = 0
private const val KEY_EVENT_KEYUP = 1
private const val KEY_EVENT_KEYPRESS = 2

internal data class EmulatorImageFormat(val maxSize: Int, val mmapHandle: String? = null)
internal data class EmulatorImage(
    val width: Int,
    val height: Int,
    val pixels: ByteArray,
    val seq: Long = 0,
    val timestampUs: Long = 0,
)
internal data class EmulatorTouch(val x: Int, val y: Int, val pressure: Int)
internal data class EmulatorTouchEvent(val touches: List<EmulatorTouch>, val display: Int = 0)
internal data class EmulatorKeyEvent(val eventType: Int = KEY_EVENT_KEYDOWN, val key: String = "", val text: String = "")

private object EmulatorImageFormatMarshaller : MethodDescriptor.Marshaller<EmulatorImageFormat> {
    override fun stream(value: EmulatorImageFormat): InputStream {
        return ByteArrayInputStream(EmulatorGrpcProto.imageFormat(value.maxSize.coerceAtLeast(1), value.mmapHandle))
    }

    override fun parse(stream: InputStream): EmulatorImageFormat {
        stream.readAllBytes()
        return EmulatorImageFormat(0)
    }
}

private object EmulatorImageMarshaller : MethodDescriptor.Marshaller<EmulatorImage> {
    override fun stream(value: EmulatorImage): InputStream {
        return ByteArrayInputStream(ByteArray(0))
    }

    override fun parse(stream: InputStream): EmulatorImage {
        return EmulatorGrpcProto.parseImage(stream.readAllBytes())
    }
}

private object EmulatorTouchEventMarshaller : MethodDescriptor.Marshaller<EmulatorTouchEvent> {
    override fun stream(value: EmulatorTouchEvent): InputStream {
        return ByteArrayInputStream(EmulatorGrpcProto.touchEvent(value))
    }

    override fun parse(stream: InputStream): EmulatorTouchEvent {
        stream.readAllBytes()
        return EmulatorTouchEvent(emptyList())
    }
}

private object EmulatorKeyEventMarshaller : MethodDescriptor.Marshaller<EmulatorKeyEvent> {
    override fun stream(value: EmulatorKeyEvent): InputStream {
        return ByteArrayInputStream(EmulatorGrpcProto.keyEvent(value))
    }

    override fun parse(stream: InputStream): EmulatorKeyEvent {
        stream.readAllBytes()
        return EmulatorKeyEvent()
    }
}

private object EmulatorEmptyMarshaller : MethodDescriptor.Marshaller<Unit> {
    override fun stream(value: Unit): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun parse(stream: InputStream) {
        stream.readAllBytes()
    }
}

internal object EmulatorGrpcProto {
    fun imageFormat(maxSize: Int, mmapHandle: String? = null): ByteArray {
        val writer = ProtoWriter()
        writer.varint(1, 2) // ImageFormat.RGB888
        writer.varint(3, maxSize.toLong())
        writer.varint(4, maxSize.toLong())
        if (!mmapHandle.isNullOrBlank()) {
            val transport = ProtoWriter()
            transport.varint(1, 1) // ImageTransport.MMAP
            transport.string(2, mmapHandle)
            writer.bytes(6, transport.toByteArray())
        }
        return writer.toByteArray()
    }

    fun touchEvent(event: EmulatorTouchEvent): ByteArray {
        val writer = ProtoWriter()
        event.touches.forEach { touch ->
            val touchWriter = ProtoWriter()
            touchWriter.varint(1, touch.x.coerceAtLeast(0).toLong())
            touchWriter.varint(2, touch.y.coerceAtLeast(0).toLong())
            touchWriter.varint(3, 0)
            touchWriter.varint(4, touch.pressure.coerceAtLeast(0).toLong())
            touchWriter.varint(7, 1) // NEVER_EXPIRE; Andy sends explicit pressure=0 on release.
            writer.bytes(1, touchWriter.toByteArray())
        }
        if (event.display != 0) writer.varint(2, event.display.toLong())
        return writer.toByteArray()
    }

    // KeyboardEvent { codeType=1(Usb, default), eventType=2, keyCode=3, key=4, text=5 }
    fun keyEvent(event: EmulatorKeyEvent): ByteArray {
        val writer = ProtoWriter()
        if (event.eventType != 0) writer.varint(2, event.eventType.toLong())
        if (event.key.isNotEmpty()) writer.string(4, event.key)
        if (event.text.isNotEmpty()) writer.string(5, event.text)
        return writer.toByteArray()
    }

    fun parseImage(bytes: ByteArray): EmulatorImage {
        val reader = ProtoReader(bytes)
        var width = 0
        var height = 0
        var deprecatedWidth = 0
        var deprecatedHeight = 0
        var image = ByteArray(0)
        var seq = 0L
        var timestampUs = 0L
        while (!reader.isAtEnd()) {
            when (val tag = reader.readTag()) {
                10 -> {
                    val format = parseImageFormat(reader.readBytes())
                    width = format.first
                    height = format.second
                }
                16 -> deprecatedWidth = reader.readVarint().toInt()
                24 -> deprecatedHeight = reader.readVarint().toInt()
                34 -> image = reader.readBytes()
                40 -> seq = reader.readVarint()
                48 -> timestampUs = reader.readVarint()
                else -> reader.skip(tag)
            }
        }
        return EmulatorImage(
            width = width.takeIf { it > 0 } ?: deprecatedWidth,
            height = height.takeIf { it > 0 } ?: deprecatedHeight,
            pixels = image,
            seq = seq,
            timestampUs = timestampUs,
        )
    }

    private fun parseImageFormat(bytes: ByteArray): Pair<Int, Int> {
        val reader = ProtoReader(bytes)
        var width = 0
        var height = 0
        while (!reader.isAtEnd()) {
            when (val tag = reader.readTag()) {
                8 -> reader.readVarint()
                24 -> width = reader.readVarint().toInt()
                32 -> height = reader.readVarint().toInt()
                else -> reader.skip(tag)
            }
        }
        return width to height
    }

    class ProtoWriter {
        private val output = ByteArrayOutputStream()

        fun varint(field: Int, value: Long) {
            tag(field, 0)
            writeVarint(value)
        }

        fun bytes(field: Int, bytes: ByteArray) {
            tag(field, 2)
            writeVarint(bytes.size.toLong())
            output.write(bytes)
        }

        fun string(field: Int, value: String) {
            bytes(field, value.encodeToByteArray())
        }

        fun toByteArray(): ByteArray = output.toByteArray()

        private fun tag(field: Int, wireType: Int) {
            writeVarint(((field shl 3) or wireType).toLong())
        }

        private fun writeVarint(value: Long) {
            var remaining = value
            while ((remaining and 0x7f.inv().toLong()) != 0L) {
                output.write(((remaining and 0x7f) or 0x80).toInt())
                remaining = remaining ushr 7
            }
            output.write(remaining.toInt())
        }
    }

    private class ProtoReader(private val bytes: ByteArray) {
        private var offset = 0

        fun isAtEnd(): Boolean = offset >= bytes.size

        fun readTag(): Int = readVarint().toInt()

        fun readVarint(): Long {
            var shift = 0
            var result = 0L
            while (shift < 64 && offset < bytes.size) {
                val b = bytes[offset++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
            }
            return result
        }

        fun readBytes(): ByteArray {
            val size = readVarint().toInt().coerceAtLeast(0)
            val end = (offset + size).coerceAtMost(bytes.size)
            return bytes.copyOfRange(offset, end).also { offset = end }
        }

        fun skip(tag: Int) {
            when (tag and 0x7) {
                0 -> readVarint()
                1 -> offset = (offset + 8).coerceAtMost(bytes.size)
                2 -> {
                    val size = readVarint().toInt().coerceAtLeast(0)
                    offset = (offset + size).coerceAtMost(bytes.size)
                }
                5 -> offset = (offset + 4).coerceAtMost(bytes.size)
                else -> offset = bytes.size
            }
        }
    }
}
