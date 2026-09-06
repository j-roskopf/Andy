package com.joetr.andy.mobile.data.vnc

import java.io.DataInputStream
import java.util.zip.Inflater
import kotlin.math.max
import kotlin.math.min

/**
 * ZRLE (RFB encoding 16) rectangle decoder.
 *
 * ZRLE is the reason a remote desktop is usable over a phone link at all: Raw ships
 * `w * h * 4` bytes per changed region, which is 64 MB for a 6896x2346 desktop, while
 * ZRLE zlib-compresses 64x64 tiles that are each solid, palettised, or run-length coded.
 *
 * The zlib stream is continuous for the whole session — one [Inflater] for the life of
 * the connection, never reset mid-session — and the server flushes after every
 * rectangle, so inflating all pending input yields exactly one rectangle's tile data.
 *
 * Pixels are CPIXELs: with our 32bpp/depth-24 little-endian RGB888 format the padding
 * byte is dropped, leaving three bytes per pixel in B, G, R order.
 */
internal class ZrleDecoder {
    private val inflater = Inflater()
    private var compressed = ByteArray(0)
    private var out = ByteArray(0)
    private var length = 0
    private var position = 0
    private val palette = IntArray(128)

    fun reset() = inflater.reset()

    fun end() = inflater.end()

    /**
     * Read one length-prefixed ZRLE rectangle from [inp] and write [width] x [height]
     * ARGB pixels into [dst] at ([originX], [originY]), where rows are [stride] apart.
     *
     * Writing straight into the destination matters: a full-screen rect on a 6896x2346
     * desktop would otherwise need a second 64 MB staging array.
     */
    @Suppress("LongParameterList")
    fun decodeRect(
        inp: DataInputStream,
        dst: IntArray,
        stride: Int,
        originX: Int,
        originY: Int,
        width: Int,
        height: Int,
    ) {
        val chunk = inp.readInt()
        if (chunk < 0) throw IllegalStateException("Negative ZRLE rect length")
        if (compressed.size < chunk) compressed = ByteArray(chunk)
        inp.readFully(compressed, 0, chunk)
        if (width <= 0 || height <= 0) return

        inflater.setInput(compressed, 0, chunk)
        length = 0
        while (true) {
            if (length == out.size) out = out.copyOf(max(INITIAL_OUT, out.size * 2))
            val produced = inflater.inflate(out, length, out.size - length)
            if (produced == 0) break
            length += produced
        }
        position = 0

        var tileY = 0
        while (tileY < height) {
            val th = min(TILE, height - tileY)
            var tileX = 0
            while (tileX < width) {
                decodeTile(
                    dst = dst,
                    stride = stride,
                    tx = originX + tileX,
                    ty = originY + tileY,
                    tw = min(TILE, width - tileX),
                    th = th,
                )
                tileX += TILE
            }
            tileY += TILE
        }
    }

    @Suppress("LongParameterList")
    private fun decodeTile(dst: IntArray, stride: Int, tx: Int, ty: Int, tw: Int, th: Int) {
        val count = tw * th
        when (val sub = readByte()) {
            0 -> for (row in 0 until th) {
                var i = (ty + row) * stride + tx
                repeat(tw) { dst[i++] = readCPixel() }
            }
            1 -> {
                val color = readCPixel()
                for (row in 0 until th) {
                    var i = (ty + row) * stride + tx
                    repeat(tw) { dst[i++] = color }
                }
            }
            in 2..16 -> {
                for (i in 0 until sub) palette[i] = readCPixel()
                val bits = when {
                    sub == 2 -> 1
                    sub <= 4 -> 2
                    else -> 4
                }
                val mask = (1 shl bits) - 1
                for (row in 0 until th) {
                    // Each tile row restarts on a byte boundary.
                    var packed = 0
                    var bitsLeft = 0
                    var i = (ty + row) * stride + tx
                    repeat(tw) {
                        if (bitsLeft == 0) {
                            packed = readByte()
                            bitsLeft = 8
                        }
                        bitsLeft -= bits
                        dst[i++] = palette[(packed shr bitsLeft) and mask]
                    }
                }
            }
            128 -> {
                var written = 0
                while (written < count) {
                    val color = readCPixel()
                    val run = min(readRunLength(), count - written)
                    written = fill(dst, stride, tx, ty, tw, written, run, color)
                }
            }
            in 130..255 -> {
                for (i in 0 until sub - 128) palette[i] = readCPixel()
                var written = 0
                while (written < count) {
                    val raw = readByte()
                    val run = if (raw and 0x80 != 0) readRunLength() else 1
                    written = fill(
                        dst,
                        stride,
                        tx,
                        ty,
                        tw,
                        written,
                        min(run, count - written),
                        palette[raw and 0x7F],
                    )
                }
            }
            else -> throw IllegalStateException("Invalid ZRLE subencoding $sub")
        }
    }

    /** Write [run] pixels in tile-scan order starting at flat tile offset [from]. */
    @Suppress("LongParameterList")
    private fun fill(
        dst: IntArray,
        stride: Int,
        tx: Int,
        ty: Int,
        tw: Int,
        from: Int,
        run: Int,
        color: Int,
    ): Int {
        var offset = from
        var remaining = run
        while (remaining > 0) {
            val row = offset / tw
            val col = offset % tw
            val n = min(remaining, tw - col)
            var i = (ty + row) * stride + tx + col
            repeat(n) { dst[i++] = color }
            offset += n
            remaining -= n
        }
        return offset
    }

    /** Run lengths are `1 + sum(bytes)`, with 255 meaning "keep reading". */
    private fun readRunLength(): Int {
        var run = 1
        var b = readByte()
        while (b == 255) {
            run += 255
            b = readByte()
        }
        return run + b
    }

    private fun readByte(): Int {
        if (position >= length) throw IllegalStateException("Truncated ZRLE tile data")
        return out[position++].toInt() and 0xFF
    }

    private fun readCPixel(): Int {
        if (position + 2 >= length) throw IllegalStateException("Truncated ZRLE pixel data")
        val b = out[position].toInt() and 0xFF
        val g = out[position + 1].toInt() and 0xFF
        val r = out[position + 2].toInt() and 0xFF
        position += 3
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private companion object {
        const val TILE = 64
        const val INITIAL_OUT = 1 shl 18
    }
}
