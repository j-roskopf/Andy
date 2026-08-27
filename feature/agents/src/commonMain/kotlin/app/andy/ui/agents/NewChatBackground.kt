package app.andy.ui.agents

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import app.andy.loadNewChatBackgroundBitmap
import app.andy.ui.theme.AndyColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val NewChatBackgroundImageAlpha = 0.45f
private const val NewChatBackgroundEdgeFraction = 0.14f

/**
 * Soft-edged wallpaper behind the empty new-chat composer.
 * Fades into [AndyColors.ContentBg] around the border so the image never hard-cuts.
 */
@Composable
internal fun NewChatBackground(
    uri: String,
    modifier: Modifier = Modifier,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.Default) {
            loadNewChatBackgroundBitmap(uri)
        }
    }
    val image = bitmap ?: return
    val edgeColor = AndyColors.ContentBg
    Box(modifier.fillMaxSize()) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = NewChatBackgroundImageAlpha,
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val edgeX = width * NewChatBackgroundEdgeFraction
            val edgeY = height * NewChatBackgroundEdgeFraction
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(edgeColor, Color.Transparent),
                    startY = 0f,
                    endY = edgeY,
                ),
                size = Size(width, edgeY),
            )
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, edgeColor),
                    startY = height - edgeY,
                    endY = height,
                ),
                topLeft = Offset(0f, height - edgeY),
                size = Size(width, edgeY),
            )
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(edgeColor, Color.Transparent),
                    startX = 0f,
                    endX = edgeX,
                ),
                size = Size(edgeX, height),
            )
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, edgeColor),
                    startX = width - edgeX,
                    endX = width,
                ),
                topLeft = Offset(width - edgeX, 0f),
                size = Size(edgeX, height),
            )
        }
    }
}
