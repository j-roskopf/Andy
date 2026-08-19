package app.andy

internal actual fun setBrowserElementInspectEnabled(enabled: Boolean) = Unit

internal actual fun observeBrowserElementAnnotations(
    onEvent: (BrowserElementAnnotateEvent) -> Unit,
): () -> Unit = {}
