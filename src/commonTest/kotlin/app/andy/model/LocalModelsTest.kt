package app.andy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalModelsTest {
    @Test
    fun pickerExpandsEachBackendAcrossOpenCodePiGoose() {
        val options = agentPickerOptions()
        val local = options.filter { it.agent.isLocalModelBackend }
        assertEquals(6, local.size)
        assertEquals(
            listOf(
                "Ollama · OpenCode",
                "Ollama · Pi",
                "Ollama · Goose",
                "LM Studio · OpenCode",
                "LM Studio · Pi",
                "LM Studio · Goose",
            ),
            local.map { it.label },
        )
        assertTrue(options.none { it.agent.hasVendorCli && it.localRuntime != null })
    }

    @Test
    fun prefixesBareModelIdsAndStripsV1FromHost() {
        assertEquals("ollama/llama3.2", prefixedLocalModelId(AgentKind.Ollama, "llama3.2"))
        assertEquals("lmstudio/qwen", prefixedLocalModelId(AgentKind.LMStudio, "lmstudio/qwen"))
        assertEquals(
            "lmstudio/qwen/qwen3.8-27b",
            prefixedLocalModelId(AgentKind.LMStudio, "qwen/qwen3.8-27b"),
        )
        assertEquals(
            "lmstudio/qwen/qwen3.8-27b",
            prefixedLocalModelId(AgentKind.LMStudio, "lmstudio/qwen/qwen3.8-27b"),
        )
        assertEquals(
            "qwen/qwen3.8-27b",
            localModelIdWithoutProviderPrefix(AgentKind.LMStudio, "lmstudio/qwen/qwen3.8-27b"),
        )
        assertEquals(
            "qwen/qwen3.8-27b",
            localModelIdWithoutProviderPrefix(AgentKind.LMStudio, "qwen/qwen3.8-27b"),
        )
        assertEquals(
            "http://127.0.0.1:11434",
            openaiCompatUrlToProviderHost("http://127.0.0.1:11434/v1/"),
        )
        assertEquals("http://127.0.0.1:1234", openaiCompatUrlToProviderHost("http://127.0.0.1:1234"))
    }

    @Test
    fun parsesOpenAiCompatModelLists() {
        val options = parseOpenAiCompatModels(
            """{"data":[{"id":"llama3.2:latest"},{"id":"qwen2.5-coder"}]}""",
            AgentKind.Ollama,
        )
        assertEquals(listOf("ollama/llama3.2:latest", "ollama/qwen2.5-coder"), options.map { it.id })
        val lmStudio = parseOpenAiCompatModels(
            """{"data":[{"id":"qwen/qwen3.8-27b"},{"id":"lmstudio/already-prefixed"}]}""",
            AgentKind.LMStudio,
        )
        assertEquals(
            listOf("lmstudio/qwen/qwen3.8-27b", "lmstudio/already-prefixed"),
            lmStudio.map { it.id },
        )
    }

    @Test
    fun gooseRuntimeIsReadyWhenBinaryExistsEvenIfUnconfigured() {
        val goose = AgentCliStatus(
            kind = AgentKind.Goose,
            binaryPath = "/usr/local/bin/goose",
            issue = AgentCliIssue(
                title = "Goose needs a provider",
                detail = "Run goose configure",
            ),
        )
        assertTrue(goose.readyForLocalRuntime())
        assertTrue(localModelComboReady(backendReachable = true, runtimeStatus = goose))
        assertFalse(localModelComboReady(backendReachable = false, runtimeStatus = goose))
        assertFalse(localModelComboReady(backendReachable = true, runtimeStatus = null))
        val missingOpenCode = AgentCliStatus(kind = AgentKind.OpenCode)
        assertFalse(localModelComboReady(true, missingOpenCode))
    }

    @Test
    fun launchRequiresRuntimeAndModel() {
        val draft = AgentTaskDraft(
            title = "t",
            prompt = "hi",
            agent = AgentKind.Ollama,
            projectId = null,
        )
        assertEquals("runtime is required for Ollama (OpenCode, Pi, or Goose)", draft.localModelLaunchError())
        assertEquals(
            "a model is required for Ollama",
            draft.copy(localRuntime = LocalAgentRuntime.Goose).localModelLaunchError(),
        )
        assertNull(
            draft.copy(localRuntime = LocalAgentRuntime.Goose, model = "llama3.2").localModelLaunchError(),
        )
        assertNull(
            AgentTaskDraft(title = "t", prompt = "hi", agent = AgentKind.Codex, projectId = null)
                .localModelLaunchError(),
        )
    }

    @Test
    fun comboReadyUsesRuntimeStatusNotTheBackendCliName() {
        val option = AgentPickerOption(AgentKind.Ollama, LocalAgentRuntime.Goose)
        val statuses = listOf(
            AgentCliStatus(kind = AgentKind.Goose, binaryPath = "/bin/goose"),
            AgentCliStatus(kind = AgentKind.OpenCode),
        )
        assertTrue(option.comboReady(statuses, mapOf(AgentKind.Ollama to true)))
        assertFalse(option.comboReady(statuses, mapOf(AgentKind.Ollama to false)))
        val openCodeRow = AgentPickerOption(AgentKind.Ollama, LocalAgentRuntime.OpenCode)
        assertFalse(openCodeRow.comboReady(statuses, mapOf(AgentKind.Ollama to true)))
    }
}
