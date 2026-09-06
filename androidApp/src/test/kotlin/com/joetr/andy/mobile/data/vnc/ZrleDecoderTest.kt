package com.joetr.andy.mobile.data.vnc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.Deflater
import kotlin.math.min

/**
 * Round-trips ZRLE rectangles through a minimal reference encoder, one subencoding at a
 * time, so every tile path the decoder can take is exercised without a live VNC server.
 */
class ZrleDecoderTest {
    @Test
    fun decodesSolidTiles() {
        val expected = IntArray(64 * 64) { argb(0x12, 0x34, 0x56) }
        assertDecodes(expected, 64, 64) { tile, _, _, _, _ ->
            tile.writeByte(1)
            tile.writeCPixel(argb(0x12, 0x34, 0x56))
        }
    }

    @Test
    fun decodesRawTiles() {
        val expected = IntArray(40 * 24) { argb(it and 0xFF, (it * 3) and 0xFF, (it * 7) and 0xFF) }
        assertDecodes(expected, 40, 24) { tile, tx, ty, tw, th ->
            tile.writeByte(0)
            forEachTilePixel(40, tx, ty, tw, th) { tile.writeCPixel(expected[it]) }
        }
    }

    @Test
    fun decodesPackedPaletteTiles() {
        // Two colours -> 1 bit per pixel, with each tile row padded to a byte boundary.
        val a = argb(0xFF, 0x00, 0x00)
        val b = argb(0x00, 0xFF, 0x00)
        val width = 70 // forces a 64px tile plus a 6px tile, so row padding matters
        val height = 5
        val expected = IntArray(width * height) { if ((it % 3) == 0) a else b }
        assertDecodes(expected, width, height) { tile, tx, ty, tw, th ->
            tile.writeByte(2)
            tile.writeCPixel(a)
            tile.writeCPixel(b)
            for (row in 0 until th) {
                var packed = 0
                var filled = 0
                for (col in 0 until tw) {
                    val index = if (expected[(ty + row) * width + tx + col] == a) 0 else 1
                    packed = (packed shl 1) or index
                    filled++
                    if (filled == 8) {
                        tile.writeByte(packed)
                        packed = 0
                        filled = 0
                    }
                }
                if (filled > 0) tile.writeByte(packed shl (8 - filled))
            }
        }
    }

    @Test
    fun decodesPlainRleTilesIncludingRunsOverTwoHundredFiftyFive() {
        val width = 64
        val height = 8
        val color = argb(0x0A, 0x0B, 0x0C)
        val expected = IntArray(width * height) { color }
        assertDecodes(expected, width, height) { tile, _, _, tw, th ->
            tile.writeByte(128)
            tile.writeCPixel(color)
            tile.writeRunLength(tw * th) // 512 -> needs a 255 continuation byte
        }
    }

    @Test
    fun decodesPaletteRleTiles() {
        val a = argb(0x11, 0x22, 0x33)
        val b = argb(0x44, 0x55, 0x66)
        val width = 64
        val height = 4
        // Left half colour a, right half colour b, so runs wrap across rows.
        val expected = IntArray(width * height) { if (it % width < 32) a else b }
        assertDecodes(expected, width, height) { tile, tx, ty, tw, th ->
            tile.writeByte(130) // palette RLE, 2 entries
            tile.writeCPixel(a)
            tile.writeCPixel(b)
            var offset = 0
            val count = tw * th
            while (offset < count) {
                val row = offset / tw
                val col = offset % tw
                val color = expected[(ty + row) * width + tx + col]
                var run = 0
                while (offset + run < count) {
                    val r = (offset + run) / tw
                    val c = (offset + run) % tw
                    if (expected[(ty + r) * width + tx + c] != color) break
                    run++
                }
                val index = if (color == a) 0 else 1
                if (run == 1) {
                    tile.writeByte(index)
                } else {
                    tile.writeByte(index or 0x80)
                    tile.writeRunLength(run)
                }
                offset += run
            }
        }
    }

    /** The zlib stream spans rectangles; decoding rect 2 depends on rect 1's history. */
    @Test
    fun keepsTheZlibStreamAcrossRectangles() {
        val first = IntArray(16 * 16) { argb(0x01, 0x02, 0x03) }
        val second = IntArray(16 * 16) { argb(0x04, 0x05, 0x06) }
        val deflater = Deflater()
        val stream = ByteArrayOutputStream()
        val out = DataOutputStream(stream)
        writeRect(out, deflater, 16, 16) { tile, _, _, _, _ ->
            tile.writeByte(1)
            tile.writeCPixel(first[0])
        }
        writeRect(out, deflater, 16, 16) { tile, _, _, _, _ ->
            tile.writeByte(1)
            tile.writeCPixel(second[0])
        }
        deflater.end()

        val decoder = ZrleDecoder()
        val inp = DataInputStream(ByteArrayInputStream(stream.toByteArray()))
        val dst = IntArray(16 * 16)
        decoder.decodeRect(inp, dst, 16, 0, 0, 16, 16)
        assertArrayEquals(first, dst)
        decoder.decodeRect(inp, dst, 16, 0, 0, 16, 16)
        assertArrayEquals(second, dst)
        assertEquals(0, inp.available())
        decoder.end()
    }

    /** Rects decode straight into the framebuffer, so origin and stride must be honoured. */
    @Test
    fun writesAtTheRectOriginAndLeavesTheRestOfTheFramebufferAlone() {
        val fbWidth = 32
        val fbHeight = 16
        val untouched = argb(0x00, 0x00, 0x00)
        val fill = argb(0x77, 0x88, 0x99)
        val fb = IntArray(fbWidth * fbHeight) { untouched }

        val deflater = Deflater()
        val stream = ByteArrayOutputStream()
        writeRect(DataOutputStream(stream), deflater, 8, 4) { tile, _, _, _, _ ->
            tile.writeByte(1)
            tile.writeCPixel(fill)
        }
        deflater.end()

        val decoder = ZrleDecoder()
        decoder.decodeRect(
            DataInputStream(ByteArrayInputStream(stream.toByteArray())),
            fb,
            fbWidth,
            /* originX = */ 20,
            /* originY = */ 9,
            /* width = */ 8,
            /* height = */ 4,
        )
        decoder.end()

        for (y in 0 until fbHeight) {
            for (x in 0 until fbWidth) {
                val inside = x in 20 until 28 && y in 9 until 13
                assertEquals("pixel $x,$y", if (inside) fill else untouched, fb[y * fbWidth + x])
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun assertDecodes(
        expected: IntArray,
        width: Int,
        height: Int,
        writeTile: TileWriter,
    ) {
        val deflater = Deflater()
        val stream = ByteArrayOutputStream()
        writeRect(DataOutputStream(stream), deflater, width, height, writeTile)
        deflater.end()

        val decoder = ZrleDecoder()
        val dst = IntArray(width * height)
        decoder.decodeRect(
            DataInputStream(ByteArrayInputStream(stream.toByteArray())),
            dst,
            width,
            0,
            0,
            width,
            height,
        )
        decoder.end()
        assertArrayEquals(expected, dst)
    }

    private fun writeRect(
        out: DataOutputStream,
        deflater: Deflater,
        width: Int,
        height: Int,
        writeTile: TileWriter,
    ) {
        val tiles = ByteArrayOutputStream()
        val tileOut = DataOutputStream(tiles)
        var ty = 0
        while (ty < height) {
            val th = min(64, height - ty)
            var tx = 0
            while (tx < width) {
                writeTile(tileOut, tx, ty, min(64, width - tx), th)
                tx += 64
            }
            ty += 64
        }
        val compressed = syncFlush(deflater, tiles.toByteArray())
        out.writeInt(compressed.size)
        out.write(compressed)
        out.flush()
    }

    /** Mirrors what a real server does: deflate the rect, then Z_SYNC_FLUSH. */
    private fun syncFlush(deflater: Deflater, data: ByteArray): ByteArray {
        deflater.setInput(data)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.needsInput()) {
            val n = deflater.deflate(buf, 0, buf.size, Deflater.SYNC_FLUSH)
            if (n == 0) break
            out.write(buf, 0, n)
        }
        while (true) {
            val n = deflater.deflate(buf, 0, buf.size, Deflater.SYNC_FLUSH)
            if (n == 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun forEachTilePixel(stride: Int, tx: Int, ty: Int, tw: Int, th: Int, block: (Int) -> Unit) {
        for (row in 0 until th) {
            for (col in 0 until tw) block((ty + row) * stride + tx + col)
        }
    }

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun DataOutputStream.writeCPixel(color: Int) {
        writeByte(color and 0xFF) // blue
        writeByte((color shr 8) and 0xFF) // green
        writeByte((color shr 16) and 0xFF) // red
    }

    private fun DataOutputStream.writeRunLength(run: Int) {
        var remaining = run - 1
        while (remaining >= 255) {
            writeByte(255)
            remaining -= 255
        }
        writeByte(remaining)
    }
}

private typealias TileWriter = (DataOutputStream, Int, Int, Int, Int) -> Unit
