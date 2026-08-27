package app.andy.ui.inspector

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.andy.model.AccessibilityNode
import app.andy.model.HierarchySnapshot

/**
 * Mutable state for [InspectorScreen] — unified hierarchy inspector (view tree, a11y details,
 * mirror overlay, accessibility checks, merged/unmerged capture, and structural diff.
 */
class InspectorState {
    var snapshot by mutableStateOf<HierarchySnapshot?>(null)
    var status by mutableStateOf("No capture loaded")
    var hoveredBounds by mutableStateOf<String?>(null)
    var selectedNode by mutableStateOf<AccessibilityNode?>(null)
    var interactionMode by mutableStateOf(false)
    var isInitialCaptureDone by mutableStateOf(false)
    var isLoading by mutableStateOf(false)
    var lastSerial by mutableStateOf<String?>(null)
    var includeInvisible by mutableStateOf(false)
    var unmergedSemantics by mutableStateOf(false)
    var searchQuery by mutableStateOf("")
    val collapsedNodes = mutableStateMapOf<String, Boolean>()

    var interestingOnly by mutableStateOf(false)
    var layoutBounds by mutableStateOf(false)

    /** Structural snapshot diff (§D.4): [baseline] is pinned by the user, diffed against [snapshot]. */
    var baseline by mutableStateOf<HierarchySnapshot?>(null)
    var showDiff by mutableStateOf(false)
}
