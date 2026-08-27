package app.andy.desktop.service

import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.FileImageOutputStream

/**
 * Self-contained animated GIF encoder. `FFmpegFrameRecorder`'s built-in GIF muxer has no
 * palette-generation filter wired up through JavaCV and produces poor colors (§E.4 of the
 * missing-features plan), so this quantizes frames to a shared palette in Kotlin — a
 * frequency-weighted median-cut over a coarse color histogram, with an O(1) per-pixel
 * nearest-color lookup table — then writes the sequence with the standard `javax.imageio` GIF
 * writer (NETSCAPE2.0 looping + per-frame delay via metadata).
 */
object GifEncoder {
    private const val CHANNEL_BITS = 5
    private const val CHANNEL_SHIFT = 8 - CHANNEL_BITS
    private const val LEVELS = 1 shl CHANNEL_BITS
    private const val GRID_SIZE = LEVELS * LEVELS * LEVELS
    private const val DEQUANTIZE_SCALE = 255 / (LEVELS - 1)
    /** Sample every Nth pixel per axis when building the histogram; full image when indexing. */
    private const val HISTOGRAM_STRIDE = 3

    fun write(target: File, frames: List<BufferedImage>, delaysCentiseconds: List<Int>, loopForever: Boolean) {
        require(frames.isNotEmpty()) { "No frames to encode" }
        val palette = buildPalette(frames)
        val lookup = nearestPaletteLookup(palette)
        val colorModel = toIndexColorModel(palette)
        val indexedFrames = frames.map { toIndexedImage(it, colorModel, lookup) }
        writeSequence(target, indexedFrames, delaysCentiseconds, loopForever)
    }

    private fun quantizedIndex(rgb: Int): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return ((r shr CHANNEL_SHIFT) shl (2 * CHANNEL_BITS)) or ((g shr CHANNEL_SHIFT) shl CHANNEL_BITS) or (b shr CHANNEL_SHIFT)
    }

    private fun dequantize(index: Int): Triple<Int, Int, Int> {
        val r = (index shr (2 * CHANNEL_BITS)) and (LEVELS - 1)
        val g = (index shr CHANNEL_BITS) and (LEVELS - 1)
        val b = index and (LEVELS - 1)
        return Triple(r * DEQUANTIZE_SCALE, g * DEQUANTIZE_SCALE, b * DEQUANTIZE_SCALE)
    }

    private class Cell(val r: Int, val g: Int, val b: Int, val weight: Long)

    private fun buildPalette(frames: List<BufferedImage>, maxColors: Int = 256): List<IntArray> {
        val counts = LongArray(GRID_SIZE)
        frames.forEach { image ->
            var y = 0
            while (y < image.height) {
                var x = 0
                while (x < image.width) {
                    counts[quantizedIndex(image.getRGB(x, y))]++
                    x += HISTOGRAM_STRIDE
                }
                y += HISTOGRAM_STRIDE
            }
        }
        val cells = ArrayList<Cell>()
        for (index in 0 until GRID_SIZE) {
            val weight = counts[index]
            if (weight > 0) {
                val (r, g, b) = dequantize(index)
                cells += Cell(r, g, b, weight)
            }
        }
        if (cells.isEmpty()) return listOf(intArrayOf(0, 0, 0))
        if (cells.size <= maxColors) return cells.map { intArrayOf(it.r, it.g, it.b) }

        val buckets: MutableList<MutableList<Cell>> = mutableListOf(cells)
        while (buckets.size < maxColors) {
            val splitAt = buckets.indices.maxByOrNull { channelRange(buckets[it]) } ?: break
            val bucket = buckets[splitAt]
            if (bucket.size <= 1) break
            val channel = widestChannelSelector(bucket)
            val sorted = bucket.sortedBy(channel)
            val totalWeight = sorted.sumOf { it.weight }
            var cumulative = 0L
            var cut = sorted.size / 2
            for ((position, cell) in sorted.withIndex()) {
                cumulative += cell.weight
                if (cumulative >= totalWeight / 2) {
                    cut = (position + 1).coerceIn(1, sorted.size - 1)
                    break
                }
            }
            buckets[splitAt] = sorted.subList(0, cut).toMutableList()
            buckets += sorted.subList(cut, sorted.size).toMutableList()
        }
        return buckets.map { bucket ->
            var rSum = 0.0
            var gSum = 0.0
            var bSum = 0.0
            var weightSum = 0.0
            bucket.forEach { cell ->
                rSum += cell.r * cell.weight
                gSum += cell.g * cell.weight
                bSum += cell.b * cell.weight
                weightSum += cell.weight
            }
            intArrayOf((rSum / weightSum).toInt(), (gSum / weightSum).toInt(), (bSum / weightSum).toInt())
        }
    }

    private fun channelRange(bucket: List<Cell>): Int {
        if (bucket.size <= 1) return 0
        val rRange = bucket.maxOf { it.r } - bucket.minOf { it.r }
        val gRange = bucket.maxOf { it.g } - bucket.minOf { it.g }
        val bRange = bucket.maxOf { it.b } - bucket.minOf { it.b }
        return maxOf(rRange, gRange, bRange)
    }

    private fun widestChannelSelector(bucket: List<Cell>): (Cell) -> Int {
        val rRange = bucket.maxOf { it.r } - bucket.minOf { it.r }
        val gRange = bucket.maxOf { it.g } - bucket.minOf { it.g }
        val bRange = bucket.maxOf { it.b } - bucket.minOf { it.b }
        return when (maxOf(rRange, gRange, bRange)) {
            rRange -> { cell -> cell.r }
            gRange -> { cell -> cell.g }
            else -> { cell -> cell.b }
        }
    }

    private fun nearestPaletteLookup(palette: List<IntArray>): IntArray {
        val lookup = IntArray(GRID_SIZE)
        for (index in 0 until GRID_SIZE) {
            val (r, g, b) = dequantize(index)
            var best = 0
            var bestDistance = Int.MAX_VALUE
            for (paletteIndex in palette.indices) {
                val entry = palette[paletteIndex]
                val dr = r - entry[0]
                val dg = g - entry[1]
                val db = b - entry[2]
                val distance = dr * dr + dg * dg + db * db
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = paletteIndex
                }
            }
            lookup[index] = best
        }
        return lookup
    }

    private fun toIndexColorModel(palette: List<IntArray>): IndexColorModel {
        val reds = ByteArray(palette.size)
        val greens = ByteArray(palette.size)
        val blues = ByteArray(palette.size)
        palette.forEachIndexed { index, entry ->
            reds[index] = entry[0].toByte()
            greens[index] = entry[1].toByte()
            blues[index] = entry[2].toByte()
        }
        return IndexColorModel(8, palette.size, reds, greens, blues)
    }

    private fun toIndexedImage(source: BufferedImage, colorModel: IndexColorModel, lookup: IntArray): BufferedImage {
        val indexed = BufferedImage(source.width, source.height, BufferedImage.TYPE_BYTE_INDEXED, colorModel)
        val raster = indexed.raster
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                raster.setSample(x, y, 0, lookup[quantizedIndex(source.getRGB(x, y))])
            }
        }
        return indexed
    }

    private fun writeSequence(target: File, frames: List<BufferedImage>, delaysCentiseconds: List<Int>, loopForever: Boolean) {
        val writer = ImageIO.getImageWritersBySuffix("gif").next()
        val params = writer.defaultWriteParam
        val imageType = ImageTypeSpecifier.createFromRenderedImage(frames.first())
        target.parentFile?.mkdirs()
        FileImageOutputStream(target).use { output ->
            writer.output = output
            writer.prepareWriteSequence(null)
            frames.forEachIndexed { index, frame ->
                val metadata = writer.getDefaultImageMetadata(imageType, params)
                val format = metadata.nativeMetadataFormatName
                val root = metadata.getAsTree(format) as IIOMetadataNode
                applyGraphicControlExtension(root, delaysCentiseconds.getOrElse(index) { delaysCentiseconds.lastOrNull() ?: 10 })
                if (index == 0) applyLoopingExtension(root, loopForever)
                metadata.setFromTree(format, root)
                writer.writeToSequence(IIOImage(frame, null, metadata), params)
            }
            writer.endWriteSequence()
        }
        writer.dispose()
    }

    private fun applyGraphicControlExtension(root: IIOMetadataNode, delayCentiseconds: Int) {
        val node = childNode(root, "GraphicControlExtension")
        node.setAttribute("disposalMethod", "none")
        node.setAttribute("userInputFlag", "FALSE")
        node.setAttribute("transparentColorFlag", "FALSE")
        node.setAttribute("delayTime", delayCentiseconds.coerceIn(2, 500).toString())
        node.setAttribute("transparentColorIndex", "0")
    }

    private fun applyLoopingExtension(root: IIOMetadataNode, loopForever: Boolean) {
        val extensions = childNode(root, "ApplicationExtensions")
        val extension = IIOMetadataNode("ApplicationExtension")
        extension.setAttribute("applicationID", "NETSCAPE")
        extension.setAttribute("authenticationCode", "2.0")
        val loopCount = if (loopForever) 0 else 1
        extension.userObject = byteArrayOf(0x1, (loopCount and 0xFF).toByte(), ((loopCount shr 8) and 0xFF).toByte())
        extensions.appendChild(extension)
    }

    private fun childNode(root: IIOMetadataNode, name: String): IIOMetadataNode {
        for (i in 0 until root.length) {
            val node = root.item(i)
            if (node.nodeName == name) return node as IIOMetadataNode
        }
        val node = IIOMetadataNode(name)
        root.appendChild(node)
        return node
    }
}
