package app.andy.service

import app.andy.model.IosTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tracks the latest iOS discovery results so mirror routing can identify iOS UDIDs. */
object IosTargetRegistry {
    private val targetsFlow = MutableStateFlow<List<IosTarget>>(emptyList())

    val targets: StateFlow<List<IosTarget>> = targetsFlow.asStateFlow()

    fun update(targets: List<IosTarget>) {
        targetsFlow.value = targets
    }

    fun isIosTarget(udid: String?): Boolean {
        if (udid.isNullOrBlank()) return false
        return targetsFlow.value.any { it.udid == udid }
    }

    fun target(udid: String?): IosTarget? = targetsFlow.value.firstOrNull { it.udid == udid }
}
