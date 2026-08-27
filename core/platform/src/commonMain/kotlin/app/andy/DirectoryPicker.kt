package app.andy

expect suspend fun pickDirectory(initialDir: String? = null): String?

expect suspend fun pickFiles(initialDir: String? = null, allowMultiple: Boolean = true): List<String>

suspend fun pickImageFiles(initialDir: String? = null): List<String> =
    pickFiles(initialDir, allowMultiple = true).filterSupportedImagePaths()

expect suspend fun pickSavePath(suggestedName: String, initialDir: String? = null): String?

expect fun downloadsDirectory(): String

expect fun uniqueLocalPath(directory: String, fileName: String): String
