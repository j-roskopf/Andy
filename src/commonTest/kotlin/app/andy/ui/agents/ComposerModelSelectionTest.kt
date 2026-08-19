package app.andy.ui.agents

import app.andy.model.AgentKind
import app.andy.model.AgentModelOption
import app.andy.model.AgentReasoningEffort
import kotlin.test.Test
import kotlin.test.assertEquals

class ComposerModelSelectionTest {
    @Test
    fun unknownCursorModelIsCustomUntilTheLiveCatalogArrives() {
        val saved = "cursor-unreleased-99"
        val before = composerModelSelection(AgentKind.Cursor, saved, discovered = emptyMap())
        assertEquals(ComposerCustomModelId, before.modelId)
        assertEquals(saved, before.customModel)

        val grok = AgentModelOption(
            id = saved,
            label = "Unreleased 99",
            efforts = listOf(AgentReasoningEffort.Low, AgentReasoningEffort.Medium, AgentReasoningEffort.High),
            supportsFastMode = true,
        )
        val after = composerModelSelectionAfterCatalogUpdate(
            current = before,
            agent = AgentKind.Cursor,
            savedModel = saved,
            discovered = mapOf(AgentKind.Cursor to listOf(grok)),
        )
        assertEquals(saved, after.modelId)
        assertEquals("", after.customModel)
    }

    @Test
    fun doesNotOverwriteAUserEditedCustomModel() {
        val saved = "cursor-grok-4.6"
        val current = ComposerModelSelection(ComposerCustomModelId, "my-finetune")
        val grok = AgentModelOption(id = saved, label = "Grok 4.6", efforts = emptyList())
        val after = composerModelSelectionAfterCatalogUpdate(
            current = current,
            agent = AgentKind.Cursor,
            savedModel = saved,
            discovered = mapOf(AgentKind.Cursor to listOf(grok)),
        )
        assertEquals(current, after)
    }

    @Test
    fun catalogIdsStayOnTheNamedChip() {
        val selected = composerModelSelection(AgentKind.Cursor, "composer-2.5")
        assertEquals("composer-2.5", selected.modelId)
        assertEquals("", selected.customModel)
    }

    @Test
    fun lastUsedGrok46IsACatalogChipEvenBeforeTheLiveProbe() {
        val selected = composerModelSelection(AgentKind.Cursor, "cursor-grok-4.6")
        assertEquals("cursor-grok-4.6", selected.modelId)
        assertEquals("", selected.customModel)
    }

    @Test
    fun dropsBarePiProviderNames() {
        val selected = composerModelSelection(AgentKind.Pi, "openai-codex")
        assertEquals(null, selected.modelId)
        assertEquals("", selected.customModel)
    }
}
