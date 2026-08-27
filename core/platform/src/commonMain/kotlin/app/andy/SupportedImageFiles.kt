package app.andy

val supportedImageExtensions: Set<String> = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "tif", "tiff", "svg", "heic", "heif",
)

fun String.isSupportedImagePath(): Boolean {
    val extension = substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return extension in supportedImageExtensions
}

fun List<String>.filterSupportedImagePaths(): List<String> =
    map { it.trim() }.filter { it.isNotBlank() && it.isSupportedImagePath() }.distinct()

fun List<String>.mergeChatImagePaths(added: List<String>): List<String> =
    (this + added.filterSupportedImagePaths()).distinct()
