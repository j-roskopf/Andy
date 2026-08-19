package app.andy.ui.agents

import app.andy.model.AgentKind
import app.andy.model.AgentModelCatalog
import app.andy.model.AgentModelOption

internal data class ComposerModelSelection(
    val modelId: String?,
    val customModel: String,
)

internal fun savedProviderModel(agent: AgentKind, savedModel: String?): String? =
    savedModel?.takeUnless { agent == AgentKind.Pi && '/' !in it }

internal fun composerModelSelection(
    agent: AgentKind,
    savedModel: String?,
    discovered: Map<AgentKind, List<AgentModelOption>> = emptyMap(),
): ComposerModelSelection {
    val model = savedProviderModel(agent, savedModel)
    val catalogModel = AgentModelCatalog.option(agent, model, discovered)
    return when {
        model == null -> ComposerModelSelection(modelId = null, customModel = "")
        catalogModel != null -> ComposerModelSelection(modelId = catalogModel.id, customModel = "")
        else -> ComposerModelSelection(modelId = ComposerCustomModelId, customModel = model)
    }
}

/**
 * Last-used models that are not in the offline catalog look like "custom" until the
 * provider probe finishes. Promote back to a catalog chip once the id is known, but
 * leave a user-edited custom slug alone.
 */
internal fun composerModelSelectionAfterCatalogUpdate(
    current: ComposerModelSelection,
    agent: AgentKind,
    savedModel: String?,
    discovered: Map<AgentKind, List<AgentModelOption>>,
): ComposerModelSelection {
    if (current.modelId != ComposerCustomModelId) return current
    val model = savedProviderModel(agent, savedModel) ?: return current
    if (current.customModel != model) return current
    val resolved = composerModelSelection(agent, savedModel, discovered)
    return if (resolved.modelId == ComposerCustomModelId) current else resolved
}
