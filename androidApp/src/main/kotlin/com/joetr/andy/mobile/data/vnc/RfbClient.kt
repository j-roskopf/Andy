package com.joetr.andy.mobile.data.vnc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max
import kotlin.math.min

/** ARGB_8888 pixels for the whole remote desktop. Mutated in place by the reader thread. */
class Framebuffer(val width: Int, val height: Int) {
    val pixels = IntArray(width * height)
}

/**
 * Minimal RFB 3.8 client for Tailscale/LAN VNC on :5900.
 * Intentionally pure Kotlin/JVM — no GPL LibVNCClient dependency.
 *
 * Encodings: ZRLE, zlib, CopyRect, Raw. ZRLE is what makes this usable over a
 * phone link — a 6896x2346 desktop is 64 MB per frame as Raw, and single-digit
 * MB or less as ZRLE.
 */
class RfbClient : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var readerJob: Job? = null
    private var senderJob: Job? = null

    @Volatile
    private var inputQueue: Channel<InputEvent>? = null

    private val _state = MutableStateFlow<VncConnectionState>(VncConnectionState.Disconnected)
    val state: StateFlow<VncConnectionState> = _state.asStateFlow()

    /** Bumped once per decoded framebuffer update; read from the draw phase only. */
    private val _frameVersion = MutableStateFlow(0L)
    val frameVersion: StateFlow<Long> = _frameVersion.asStateFlow()

    /** Identity changes only on connect and desktop resize, so composition rarely reruns. */
    private val _framebuffer = MutableStateFlow<Framebuffer?>(null)
    val framebuffer: StateFlow<Framebuffer?> = _framebuffer.asStateFlow()

    // Decoder scratch, reused across rects to keep the reader allocation-free.
    private var rawBuf = ByteArray(0)
    private var copyScratch = IntArray(0)
    private var zlibIn = ByteArray(0)
    private val zlibInflater = Inflater()
    private val zrle = ZrleDecoder()

    /** Set when the server resizes; the next update request asks for a full refresh. */
    @Volatile
    private var needsFullUpdate = false

    suspend fun connect(
        host: String,
        port: Int,
        password: String?,
        username: String? = null,
        shared: Boolean = true,
        quality: VncStreamQuality = VncStreamQuality.Balanced,
    ) = withContext(Dispatchers.IO) {
        disconnectInternal()
        _state.value = VncConnectionState.Connecting("$host:$port")
        try {
            val sock = Socket()
            sock.tcpNoDelay = true
            runCatching { sock.receiveBufferSize = 512 * 1024 }
            sock.connect(InetSocketAddress(host, port), 8_000)
            sock.soTimeout = 0
            socket = sock
            input = DataInputStream(BufferedInputStream(sock.getInputStream(), 256 * 1024))
            output = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 16 * 1024))

            handshake(password, username.orEmpty())
            clientInit(shared)
            val init = readServerInit()
            zrle.reset()
            zlibInflater.reset()
            needsFullUpdate = false
            _framebuffer.value = Framebuffer(init.width, init.height)
            setPixelFormat()
            setEncodings(quality)
            requestFramebufferUpdate(incremental = false)
            _state.value = VncConnectionState.Connected(init.name.ifBlank { host })
            val queue = Channel<InputEvent>(Channel.UNLIMITED)
            inputQueue = queue
            startSender(queue)
            startReader()
        } catch (t: Throwable) {
            disconnectInternal()
            val message = t.message?.takeIf { it.isNotBlank() } ?: t::class.simpleName.orEmpty()
            _state.value = VncConnectionState.Error(message)
            throw t
        }
    }

    fun disconnect() {
        scope.launch { disconnectInternal() }
    }

    private fun disconnectInternal() {
        inputQueue?.close()
        inputQueue = null
        senderJob?.cancel()
        senderJob = null
        readerJob?.cancel()
        readerJob = null
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
        // Do not recycle/zero the framebuffer: the UI may still be sampling it.
        _framebuffer.value = null
        if (_state.value !is VncConnectionState.Error) {
            _state.value = VncConnectionState.Disconnected
        }
    }

    // ---------------------------------------------------------------- input

    private sealed interface InputEvent {
        data class Pointer(val x: Int, val y: Int, val mask: Int) : InputEvent
        data class Key(val down: Boolean, val keysym: Int) : InputEvent
    }

    /**
     * Queue input instead of launching a coroutine per event.
     *
     * Every event previously got its own `launch { }`, so a drag's button-down could
     * reach the socket after its own motion events and typing came out scrambled.
     * A single ordered queue with one consumer makes delivery order match input order.
     */
    fun postPointer(x: Int, y: Int, mask: Int) {
        inputQueue?.trySend(InputEvent.Pointer(x, y, mask))
    }

    fun postKey(down: Boolean, keysym: Int) {
        inputQueue?.trySend(InputEvent.Key(down, keysym))
    }

    fun postKeyTap(keysym: Int) {
        postKey(true, keysym)
        postKey(false, keysym)
    }

    fun postText(text: String) {
        for (ch in text) {
            val keysym = charToKeysym(ch)
            val shift = charRequiresShift(ch)
            if (shift) postKey(true, Keysym.ShiftL)
            postKeyTap(keysym)
            if (shift) postKey(false, Keysym.ShiftL)
        }
    }

    private fun startSender(queue: Channel<InputEvent>) {
        senderJob = scope.launch {
            var lastMask = 0
            var pending: InputEvent? = null
            try {
                while (isActive) {
                    var event = pending ?: queue.receive()
                    pending = null
                    // Coalesce backed-up motion so a fast drag never queues behind stale
                    // points, but never drop the event that changes button state.
                    if (event is InputEvent.Pointer && event.mask == lastMask) {
                        while (true) {
                            val next = queue.tryReceive().getOrNull() ?: break
                            if (next is InputEvent.Pointer && next.mask == lastMask) {
                                event = next
                            } else {
                                pending = next
                                break
                            }
                        }
                    }
                    when (val e = event) {
                        is InputEvent.Pointer -> {
                            lastMask = e.mask
                            writePointer(e.x, e.y, e.mask)
                        }
                        is InputEvent.Key -> writeKey(e.down, e.keysym)
                    }
                }
            } catch (_: Throwable) {
                // Channel closed or socket gone — disconnect handling owns the state.
            }
        }
    }

    private suspend fun writePointer(x: Int, y: Int, mask: Int) {
        val fb = _framebuffer.value ?: return
        writeMutex.withLock {
            val out = output ?: return
            out.writeByte(5)
            out.writeByte(mask and 0xFF)
            out.writeShort(x.coerceIn(0, fb.width - 1))
            out.writeShort(y.coerceIn(0, fb.height - 1))
            out.flush()
        }
    }

    private suspend fun writeKey(down: Boolean, keysym: Int) {
        writeMutex.withLock {
            val out = output ?: return
            out.writeByte(4)
            out.writeByte(if (down) 1 else 0)
            out.writeShort(0)
            out.writeInt(keysym)
            out.flush()
        }
    }

    // ------------------------------------------------------------- protocol

    private suspend fun requestFramebufferUpdate(incremental: Boolean) {
        val fb = _framebuffer.value ?: return
        writeMutex.withLock {
            val out = output ?: return
            out.writeByte(3)
            out.writeByte(if (incremental) 1 else 0)
            out.writeShort(0)
            out.writeShort(0)
            out.writeShort(fb.width)
            out.writeShort(fb.height)
            out.flush()
        }
    }

    private fun startReader() {
        readerJob = scope.launch {
            val inp = input ?: return@launch
            try {
                while (isActive) {
                    when (val type = inp.readUnsignedByte()) {
                        0 -> handleFramebufferUpdate(inp)
                        1 -> handleSetColourMap(inp)
                        2 -> Unit // Bell
                        3 -> handleServerCutText(inp)
                        else -> throw IllegalStateException("Unknown RFB message type $type")
                    }
                }
            } catch (t: Throwable) {
                if (isActive) {
                    _state.value = VncConnectionState.Error(t.message ?: "Connection lost")
                    disconnectInternal()
                }
            }
        }
    }

    private suspend fun handshake(password: String?, username: String) {
        val inp = input!!
        val out = output!!
        val serverVersion = ByteArray(12).also { inp.readFully(it) }.decodeToString()
        if (!serverVersion.startsWith("RFB ")) {
            throw IllegalStateException("Not a VNC server")
        }
        out.writeBytes("RFB 003.008\n")
        out.flush()

        val typeCount = inp.readUnsignedByte()
        if (typeCount == 0) {
            val reasonLen = inp.readInt()
            val reason = ByteArray(reasonLen.coerceAtLeast(0)).also { inp.readFully(it) }.decodeToString()
            throw IllegalStateException(reason.ifBlank { "VNC security handshake failed" })
        }
        val types = IntArray(typeCount) { inp.readUnsignedByte() }
        val chosen = when {
            types.contains(1) -> 1 // None
            types.contains(2) -> 2 // Classic VNC Auth
            types.contains(ArdAuthentication.SecurityType) -> ArdAuthentication.SecurityType
            else -> throw IllegalStateException(
                "No supported VNC security type (need None, VNC Auth, or macOS ARD). Got: ${types.toList()}",
            )
        }
        out.writeByte(chosen)
        out.flush()

        when (chosen) {
            2 -> {
                val challenge = ByteArray(16).also { inp.readFully(it) }
                val pass = password.orEmpty()
                if (pass.isEmpty()) {
                    throw IllegalStateException(
                        "This host requires a VNC password. On macOS: enable “VNC viewers may control screen with password”.",
                    )
                }
                out.write(vncEncryptChallenge(challenge, pass))
                out.flush()
            }
            ArdAuthentication.SecurityType -> {
                val pass = password.orEmpty()
                if (pass.isEmpty()) {
                    throw IllegalStateException(
                        "macOS Screen Sharing requires a password. " +
                            "Enable “VNC viewers may control screen with password”, or use a Mac user password, " +
                            "and save it under Hosts → Edit.",
                    )
                }
                val generator = ByteArray(2).also { inp.readFully(it) }
                val keyLength = inp.readUnsignedShort()
                val prime = ByteArray(keyLength).also { inp.readFully(it) }
                val peerKey = ByteArray(keyLength).also { inp.readFully(it) }
                val (ciphertext, publicKey) = ArdAuthentication.authenticate(
                    generatorBytes = generator,
                    keyLength = keyLength,
                    prime = prime,
                    peerPublicKey = peerKey,
                    username = username,
                    password = pass,
                )
                out.write(ciphertext)
                out.write(publicKey)
                out.flush()
            }
        }

        val securityResult = inp.readInt()
        if (securityResult != 0) {
            val reasonLen = if (serverVersion.contains("003.008")) inp.readInt() else 0
            val reason = if (reasonLen > 0) {
                ByteArray(reasonLen).also { inp.readFully(it) }.decodeToString()
            } else {
                "Authentication failed"
            }
            throw IllegalStateException(reason)
        }
    }

    private fun clientInit(shared: Boolean) {
        val out = output!!
        out.writeByte(if (shared) 1 else 0)
        out.flush()
    }

    private fun readServerInit(): ServerInit {
        val inp = input!!
        val width = inp.readUnsignedShort()
        val height = inp.readUnsignedShort()
        inp.skipBytes(16) // pixel format — we set our own next
        val nameLen = inp.readInt()
        val name = ByteArray(nameLen.coerceAtLeast(0)).also { inp.readFully(it) }.decodeToString()
        if (width <= 0 || height <= 0) throw IllegalStateException("Server reported an empty desktop")
        return ServerInit(width, height, name)
    }

    private fun setPixelFormat() {
        val out = output!!
        // ClientSetPixelFormat — 32bpp little-endian RGB888 (true-colour)
        out.writeByte(0)
        out.writeByte(0)
        out.writeByte(0)
        out.writeByte(0)
        out.writeByte(32) // bits-per-pixel
        out.writeByte(24) // depth
        out.writeByte(0) // big-endian-flag
        out.writeByte(1) // true-colour-flag
        out.writeShort(255) // red-max
        out.writeShort(255)
        out.writeShort(255)
        out.writeByte(16) // red-shift
        out.writeByte(8)
        out.writeByte(0)
        out.writeByte(0)
        out.writeByte(0)
        out.writeByte(0)
        out.flush()
    }

    private fun setEncodings(quality: VncStreamQuality) {
        val out = output!!
        // Order is preference order. Cursor (-239) is deliberately absent so the server
        // composites the pointer into the framebuffer and it stays visible on the phone.
        val encodings = intArrayOf(
            ENCODING_ZRLE,
            ENCODING_ZLIB,
            ENCODING_COPY_RECT,
            ENCODING_RAW,
            ENCODING_DESKTOP_SIZE,
            ENCODING_LAST_RECT,
            quality.compressionEncoding,
            quality.jpegQualityEncoding,
        )
        out.writeByte(2)
        out.writeByte(0)
        out.writeShort(encodings.size)
        encodings.forEach { out.writeInt(it) }
        out.flush()
    }

    private suspend fun handleFramebufferUpdate(inp: DataInputStream) {
        inp.readByte() // padding
        val rectCount = inp.readUnsignedShort()

        // Ask for the next update before decoding this one, so the server's render and
        // the network transfer overlap our decode instead of serialising behind it.
        // Exactly one request is outstanding at any time, so this cannot stampede.
        requestFramebufferUpdate(incremental = !needsFullUpdate)
        needsFullUpdate = false

        var index = 0
        while (rectCount == LAST_RECT_SENTINEL || index < rectCount) {
            val x = inp.readUnsignedShort()
            val y = inp.readUnsignedShort()
            val w = inp.readUnsignedShort()
            val h = inp.readUnsignedShort()
            val encoding = inp.readInt()
            if (encoding == ENCODING_LAST_RECT) break
            decodeRect(inp, x, y, w, h, encoding)
            index++
        }
        _frameVersion.value = _frameVersion.value + 1
    }

    private fun decodeRect(inp: DataInputStream, x: Int, y: Int, w: Int, h: Int, encoding: Int) {
        val fb = _framebuffer.value
        when (encoding) {
            ENCODING_RAW -> readRawRect(inp, fb, x, y, w, h)
            ENCODING_COPY_RECT -> {
                val srcX = inp.readUnsignedShort()
                val srcY = inp.readUnsignedShort()
                if (fb != null) copyRect(fb, srcX, srcY, x, y, w, h)
            }
            ENCODING_ZLIB -> readZlibRect(inp, fb, x, y, w, h)
            ENCODING_ZRLE -> readZrleRect(inp, fb, x, y, w, h)
            ENCODING_DESKTOP_SIZE -> resizeDesktop(w, h)
            ENCODING_CURSOR -> {
                inp.skipFully(w * h * 4)
                inp.skipFully(((w + 7) / 8) * h)
            }
            else -> throw IllegalStateException("Unsupported encoding $encoding")
        }
    }

    private fun resizeDesktop(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val old = _framebuffer.value
        if (old != null && old.width == w && old.height == h) return
        val next = Framebuffer(w, h)
        if (old != null) {
            val rows = min(old.height, h)
            val cols = min(old.width, w)
            for (row in 0 until rows) {
                System.arraycopy(old.pixels, row * old.width, next.pixels, row * w, cols)
            }
        }
        _framebuffer.value = next
        needsFullUpdate = true
    }

    // ------------------------------------------------------------- decoding

    /** Raw is streamed one row at a time — a full-screen rect is 64 MB in one go. */
    private fun readRawRect(inp: DataInputStream, fb: Framebuffer?, x: Int, y: Int, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val rowBytes = w * 4
        if (rawBuf.size < rowBytes) rawBuf = ByteArray(rowBytes)
        for (row in 0 until h) {
            inp.readFully(rawBuf, 0, rowBytes)
            if (fb != null) blitPixelRow(rawBuf, fb, x, y + row, w)
        }
    }

    private fun readZlibRect(inp: DataInputStream, fb: Framebuffer?, x: Int, y: Int, w: Int, h: Int) {
        val length = inp.readInt()
        if (length < 0) throw IllegalStateException("Negative zlib rect length")
        if (zlibIn.size < length) zlibIn = ByteArray(length)
        inp.readFully(zlibIn, 0, length)
        if (w <= 0 || h <= 0) return
        val rowBytes = w * 4
        if (rawBuf.size < rowBytes) rawBuf = ByteArray(rowBytes)
        zlibInflater.setInput(zlibIn, 0, length)
        for (row in 0 until h) {
            var produced = 0
            while (produced < rowBytes) {
                val n = zlibInflater.inflate(rawBuf, produced, rowBytes - produced)
                if (n == 0) throw IllegalStateException("Truncated zlib rect")
                produced += n
            }
            if (fb != null) blitPixelRow(rawBuf, fb, x, y + row, w)
        }
    }

    /** Copy one row of little-endian BGRX bytes into the framebuffer as opaque ARGB. */
    private fun blitPixelRow(buf: ByteArray, fb: Framebuffer, x: Int, y: Int, w: Int) {
        if (y < 0 || y >= fb.height) return
        var src = 0
        var dst = y * fb.width + x
        for (col in 0 until w) {
            val b = buf[src].toInt() and 0xFF
            val g = buf[src + 1].toInt() and 0xFF
            val r = buf[src + 2].toInt() and 0xFF
            src += 4
            if (x + col in 0 until fb.width) {
                fb.pixels[dst] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            dst++
        }
    }

    private fun copyRect(fb: Framebuffer, srcX: Int, srcY: Int, x: Int, y: Int, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (srcX < 0 || srcY < 0 || srcX + w > fb.width || srcY + h > fb.height) return
        if (x < 0 || y < 0 || x + w > fb.width || y + h > fb.height) return
        if (copyScratch.size < w * h) copyScratch = IntArray(w * h)
        for (row in 0 until h) {
            System.arraycopy(fb.pixels, (srcY + row) * fb.width + srcX, copyScratch, row * w, w)
        }
        for (row in 0 until h) {
            System.arraycopy(copyScratch, row * w, fb.pixels, (y + row) * fb.width + x, w)
        }
    }

    private fun readZrleRect(inp: DataInputStream, fb: Framebuffer?, x: Int, y: Int, w: Int, h: Int) {
        if (fb == null) {
            // Torn down mid-update: still decode, because the ZRLE zlib stream is
            // continuous for the session and skipping a rect desynchronises it.
            zrle.decodeRect(inp, IntArray(max(w * h, 1)), max(w, 1), 0, 0, w, h)
            return
        }
        // Decoding into a detached rect buffer would cost a second full-desktop
        // allocation, so ZRLE writes tiles straight into the framebuffer. That means the
        // rect must be in bounds; a server that lies about it fails the connection rather
        // than scribbling over neighbouring rows.
        if (x < 0 || y < 0 || x + w > fb.width || y + h > fb.height) {
            throw IllegalStateException("ZRLE rect ${w}x$h at $x,$y is outside the desktop")
        }
        zrle.decodeRect(inp, fb.pixels, fb.width, x, y, w, h)
    }

    private fun handleSetColourMap(inp: DataInputStream) {
        inp.readByte()
        inp.readUnsignedShort()
        val count = inp.readUnsignedShort()
        inp.skipFully(count * 6)
    }

    private fun handleServerCutText(inp: DataInputStream) {
        inp.skipFully(3)
        val len = inp.readInt()
        inp.skipFully(len.coerceAtLeast(0))
    }

    override fun close() {
        disconnectInternal()
        zrle.end()
        zlibInflater.end()
        scope.cancel()
    }

    private companion object {
        const val ENCODING_RAW = 0
        const val ENCODING_COPY_RECT = 1
        const val ENCODING_ZLIB = 6
        const val ENCODING_ZRLE = 16
        const val ENCODING_DESKTOP_SIZE = -223
        const val ENCODING_LAST_RECT = -224
        const val ENCODING_CURSOR = -239
        const val LAST_RECT_SENTINEL = 0xFFFF
    }
}

/** [DataInputStream.skipBytes] may skip fewer bytes than asked; RFB framing cannot tolerate that. */
private fun DataInputStream.skipFully(count: Int) {
    var remaining = count
    while (remaining > 0) {
        val skipped = skipBytes(remaining)
        if (skipped <= 0) {
            readByte()
            remaining--
        } else {
            remaining -= skipped
        }
    }
}

sealed interface VncConnectionState {
    data object Disconnected : VncConnectionState
    data class Connecting(val target: String) : VncConnectionState
    data class Connected(val desktopName: String) : VncConnectionState
    data class Error(val message: String) : VncConnectionState
}

private data class ServerInit(val width: Int, val height: Int, val name: String)

/**
 * Classic VNC authentication: bit-reverse each password byte, DES-ECB encrypt the
 * 16-byte challenge as two 8-byte blocks.
 */
internal fun vncEncryptChallenge(challenge: ByteArray, password: String): ByteArray {
    val keyBytes = ByteArray(8)
    val passBytes = password.toByteArray(Charsets.ISO_8859_1)
    val n = min(8, passBytes.size)
    for (i in 0 until n) {
        keyBytes[i] = reverseBits(passBytes[i])
    }
    val key = SecretKeySpec(keyBytes, "DES")
    val cipher = Cipher.getInstance("DES/ECB/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key)
    val out = ByteArray(16)
    System.arraycopy(cipher.doFinal(challenge, 0, 8), 0, out, 0, 8)
    System.arraycopy(cipher.doFinal(challenge, 8, 8), 0, out, 8, 8)
    return out
}

private fun reverseBits(b: Byte): Byte {
    var v = b.toInt() and 0xFF
    v = ((v and 0xF0) ushr 4) or ((v and 0x0F) shl 4)
    v = ((v and 0xCC) ushr 2) or ((v and 0x33) shl 2)
    v = ((v and 0xAA) ushr 1) or ((v and 0x55) shl 1)
    return v.toByte()
}
