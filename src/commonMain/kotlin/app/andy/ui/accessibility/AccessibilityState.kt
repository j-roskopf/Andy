package app.andy.ui.accessibility

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.andy.model.AccessibilityNode

internal class AccessibilityState {
    var root by mutableStateOf<AccessibilityNode?>(null)
    var status by mutableStateOf("No dump loaded")
    var hoveredBounds by mutableStateOf<String?>(null)
    var selectedNode by mutableStateOf<AccessibilityNode?>(null)
    var interactionMode by mutableStateOf(false)
    var isInitialDumpDone by mutableStateOf(false)
    var isLoading by mutableStateOf(false)
    var lastSerial by mutableStateOf<String?>(null)
    var layoutBounds by mutableStateOf(false)
    var interestingOnly by mutableStateOf(false)
    val collapsedNodes = mutableStateMapOf<String, Boolean>()
}
