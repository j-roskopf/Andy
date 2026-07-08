package app.andy

import androidx.compose.ui.graphics.ImageBitmap

expect fun loadImageBitmap(path: String): ImageBitmap?
expect fun loadImageBitmap(bytes: ByteArray): ImageBitmap?
