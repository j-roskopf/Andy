package app.andy

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.File
import javax.imageio.ImageIO
import org.jetbrains.skia.Image

actual fun loadImageBitmap(path: String): ImageBitmap? {
    return try {
        val file = File(path)
        if (file.exists() && file.isFile) {
            ImageIO.read(file)?.toComposeImageBitmap()
        } else null
    } catch (e: Exception) {
        null
    }
}

actual fun loadImageBitmap(bytes: ByteArray): ImageBitmap? {
    return try {
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    } catch (e: Exception) {
        null
    }
}
