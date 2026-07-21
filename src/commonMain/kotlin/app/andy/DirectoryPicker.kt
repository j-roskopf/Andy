package app.andy

expect suspend fun pickDirectory(initialDir: String? = null): String?

expect suspend fun pickFiles(initialDir: String? = null, allowMultiple: Boolean = true): List<String>

expect suspend fun pickSavePath(suggestedName: String, initialDir: String? = null): String?

expect fun downloadsDirectory(): String

expect fun uniqueLocalPath(directory: String, fileName: String): String
