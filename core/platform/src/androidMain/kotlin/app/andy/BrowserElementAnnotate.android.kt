package app.andy

actual fun setBrowserElementInspectEnabled(enabled: Boolean) = Unit

actual fun observeBrowserElementAnnotations(
    onEvent: (BrowserElementAnnotateEvent) -> Unit,
): () -> Unit = {}
