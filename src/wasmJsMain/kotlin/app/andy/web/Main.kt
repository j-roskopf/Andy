package app.andy.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import app.andy.AndyApp
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val root = document.getElementById("root") ?: error("Missing #root")
    // Connection and mirror services own browser/ADB state and must survive recomposition.
    val services = createWebServices()
    ComposeViewport(root) {
        AndyApp(services)
    }
}
