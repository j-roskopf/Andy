package app.andy

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image

private const val MaxRemoteImageBytes = 15L * 1024L * 1024L

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

actual suspend fun fetchRemoteBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
    runCatching {
        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build()
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .GET()
            .header("Accept", "image/*,*/*;q=0.8")
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) return@runCatching null
        response.headers().firstValue("Content-Length").orElse(null)?.toLongOrNull()?.let { length ->
            if (length > MaxRemoteImageBytes) return@runCatching null
        }
        response.body().use { input ->
            val output = java.io.ByteArrayOutputStream(minOf(64 * 1024, MaxRemoteImageBytes.toInt()))
            val chunk = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                total += read
                if (total > MaxRemoteImageBytes) return@runCatching null
                output.write(chunk, 0, read)
            }
            output.toByteArray()
        }
    }.getOrNull()
}
