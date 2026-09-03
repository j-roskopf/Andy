package app.andy.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import app.andy.andy.generated.resources.Res
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.svg.SvgDecoder

private val lucideBytes = mutableMapOf<String, ByteArray>()
private var sharedLoader: ImageLoader? = null

private fun lucideImageLoader(context: PlatformContext): ImageLoader {
    val existing = sharedLoader
    if (existing != null) return existing
    return ImageLoader.Builder(context)
        .components { add(SvgDecoder.Factory()) }
        .build()
        .also { sharedLoader = it }
}

@Composable
fun LucideIcon(
    path: String,
    tint: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val context = LocalPlatformContext.current
    val imageLoader = remember(context) { lucideImageLoader(context) }
    val bytes by produceState(initialValue = lucideBytes[path], key1 = path) {
        value = lucideBytes[path] ?: Res.readBytes(path).also { lucideBytes[path] = it }
    }
    val data = bytes
    if (data == null) {
        Box(modifier = modifier)
        return
    }
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(data)
            .memoryCacheKey(path)
            .decoderFactory(SvgDecoder.Factory())
            .build(),
        contentDescription = contentDescription,
        imageLoader = imageLoader,
        modifier = modifier,
        contentScale = ContentScale.Fit,
        colorFilter = ColorFilter.tint(tint),
    )
}
