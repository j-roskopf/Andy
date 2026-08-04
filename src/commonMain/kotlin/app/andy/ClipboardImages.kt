package app.andy

/** Returns local paths for image content on the system clipboard, if any. */
expect suspend fun readClipboardImagePaths(): List<String>
