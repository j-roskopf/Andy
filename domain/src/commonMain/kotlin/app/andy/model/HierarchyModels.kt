package app.andy.model

/** Where a [HierarchySnapshot]'s [HierarchySnapshot.root] tree came from (§D.2/§D.3). */
enum class HierarchySource {
    /** `uiautomator dump` only — the merged accessibility/semantics tree Andy already had. */
    Uiautomator,

    /** `dumpsys activity top`'s unmerged view tree only (uiautomator unavailable, or [HierarchyOptions.unmergedSemantics]). */
    Dumpsys,

    /** `uiautomator dump` enriched with `dumpsys activity top` view attributes, matched by bounds + class. */
    Merged,
}

data class HierarchyOptions(
    /** Keep nodes that are not visible-to-user. Off by default, matching the Accessibility screen. */
    val includeInvisible: Boolean = false,
    /**
     * Show the raw `dumpsys activity top` view tree instead of the uiautomator-merged tree.
     * Unmerged is more granular — it is not collapsed the way Compose semantics are — but loses
     * uiautomator's text/content-description enrichment.
     */
    val unmergedSemantics: Boolean = false,
    /** `uiautomator dump --compressed`: faster, but drops non-interesting nodes. Off by default. */
    val compressed: Boolean = false,
)

/** One entry from `dumpsys window`'s z-ordered window list (§D.3), front-to-back. */
data class WindowLayerInfo(
    /** Position in `dumpsys window`'s listing; 0 is frontmost/topmost in z-order. */
    val index: Int,
    /** Window title, e.g. `"com.example.app/com.example.app.MainActivity"` or a system window name. */
    val title: String,
    val packageName: String? = null,
    val displayId: Int? = null,
    /** `"[left,top][right,bottom]"`, from the window's `Frames:` line, when parseable. */
    val bounds: String? = null,
    /** e.g. `BASE_APPLICATION`, `STATUS_BAR`, `INPUT_METHOD`, from `mAttrs`'s `ty=`. */
    val windowType: String? = null,
    val isVisible: Boolean = false,
    val isOnScreen: Boolean = false,
)

/** A captured tier-1/tier-2 hierarchy (§D.3), reusing [AccessibilityNode] rather than a parallel node type. */
data class HierarchySnapshot(
    val root: AccessibilityNode,
    val capturedAtMillis: Long,
    val displayWidth: Int,
    val displayHeight: Int,
    val source: HierarchySource,
    /** Window z-order from `dumpsys window`, front-to-back; empty when unavailable. */
    val windows: List<WindowLayerInfo> = emptyList(),
)
