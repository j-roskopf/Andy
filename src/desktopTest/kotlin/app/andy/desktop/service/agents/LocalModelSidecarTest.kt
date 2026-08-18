package app.andy.desktop.service.agents

import app.andy.model.AgentKind
import app.andy.model.AgentTask
import app.andy.model.LocalAgentRuntime
import app.andy.model.WorkspaceState
import app.andy.model.hasVendorCli
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalModelSidecarTest {
    @Test
    fun gooseEnvSetsHostWithoutV1AndDoesNotWriteUserGooseConfig() {
        val home = File.createTempFile("andy-local-models-home", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            val task = AgentTask(
                id = "t1",
                title = "local",
                prompt = "hi",
                agent = AgentKind.Ollama,
                localRuntime = LocalAgentRuntime.Goose,
                model = "llama3.2",
                createdAtMillis = 0,
            )
            val env = LocalModelSidecar.envFor(
                task,
                WorkspaceState(ollamaBaseUrl = "http://127.0.0.1:11434/v1", ollamaBearerToken = "secret"),
                home,
            )
            assertEquals("http://127.0.0.1:11434", env["OLLAMA_HOST"])
            assertEquals("secret", env["GOOSE_PROVIDER__API_KEY"])
            assertFalse(File(home, ".config/goose/config.yaml").exists())
            assertTrue(LocalModelSidecar.rootDir(home).listFiles().isNullOrEmpty())
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun openCodeSidecarWritesAndyOwnedFileNotGlobalConfig() {
        val home = File.createTempFile("andy-opencode-sidecar", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            val task = AgentTask(
                id = "t1",
                title = "local",
                prompt = "hi",
                agent = AgentKind.LMStudio,
                localRuntime = LocalAgentRuntime.OpenCode,
                model = "qwen",
                createdAtMillis = 0,
            )
            val env = LocalModelSidecar.envFor(task, WorkspaceState(), home)
            val sidecar = File(env.getValue("OPENCODE_CONFIG"))
            assertTrue(sidecar.isFile)
            assertTrue(sidecar.path.startsWith(LocalModelSidecar.rootDir(home).path))
            val body = sidecar.readText()
            assertTrue("lmstudio" in body)
            assertTrue("http://127.0.0.1:1234/v1" in body)
            assertTrue("\"model\": \"lmstudio/qwen\"" in body || "\"model\":\"lmstudio/qwen\"" in body)
            assertTrue("qwen" in body)
            assertEquals(body, env["OPENCODE_CONFIG_CONTENT"])
            assertFalse(File(home, ".config/opencode/opencode.json").exists())
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun piSidecarWritesAndyOwnedFileNotUserPiConfig() {
        val home = File.createTempFile("andy-pi-sidecar", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            val task = AgentTask(
                id = "t1",
                title = "local",
                prompt = "hi",
                agent = AgentKind.Ollama,
                localRuntime = LocalAgentRuntime.Pi,
                model = "llama3.2",
                createdAtMillis = 0,
            )
            val env = LocalModelSidecar.envFor(
                task,
                WorkspaceState(ollamaBaseUrl = "http://127.0.0.1:11434/v1"),
                home,
            )
            val sidecar = File(env.getValue("PI_CODING_AGENT_DIR"), "models.json")
            assertTrue(sidecar.isFile)
            assertTrue(sidecar.path.startsWith(LocalModelSidecar.rootDir(home).path))
            assertEquals("http://127.0.0.1:11434", env["OLLAMA_HOST"])
            assertEquals("andy-local", env["OPENAI_API_KEY"])
            assertFalse("PI_MODELS_PATH" in env)
            assertFalse(File(home, ".pi/agent/models.json").exists())
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun piSidecarKeepsSlashedLmStudioModelIds() {
        val home = File.createTempFile("andy-pi-lmstudio-sidecar", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            val task = AgentTask(
                id = "t1",
                title = "local",
                prompt = "hi",
                agent = AgentKind.LMStudio,
                localRuntime = LocalAgentRuntime.Pi,
                model = "lmstudio/qwen/qwen3.8-27b",
                createdAtMillis = 0,
            )
            val env = LocalModelSidecar.envFor(task, WorkspaceState(), home)
            val body = File(env.getValue("PI_CODING_AGENT_DIR"), "models.json").readText()
            assertTrue("qwen/qwen3.8-27b" in body)
            assertTrue("http://127.0.0.1:1234/v1" in body)
            assertTrue("andy-local" in body)
            assertFalse("\"id\": \"qwen3.8-27b\"" in body)
            assertEquals("andy-local", env["OPENAI_API_KEY"])
            assertFalse(File(home, ".pi/agent/models.json").exists())
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun piSidecarWritesLocalDefaultsInsteadOfSymlinkingUserSettings() {
        val home = File.createTempFile("andy-pi-settings-overlay", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            val userAgent = File(home, ".pi/agent").apply { mkdirs() }
            val userSettings = File(userAgent, "settings.json")
            userSettings.writeText(
                """{"theme":"dark","defaultProvider":"openai-codex","defaultModel":"gpt-5.3-codex-spark"}""",
            )
            val overlay = File(home, ".andy/local-models/pi-lmstudio-agent").apply { mkdirs() }
            val staleLink = File(overlay, "settings.json")
            java.nio.file.Files.createSymbolicLink(staleLink.toPath(), userSettings.toPath())

            LocalModelSidecar.envFor(
                AgentTask(
                    id = "t1",
                    title = "local",
                    prompt = "hi",
                    agent = AgentKind.LMStudio,
                    localRuntime = LocalAgentRuntime.Pi,
                    model = "lmstudio/qwen/qwen3.8-27b",
                    createdAtMillis = 0,
                ),
                WorkspaceState(),
                home,
            )

            assertFalse(java.nio.file.Files.isSymbolicLink(staleLink.toPath()))
            val overlayBody = staleLink.readText()
            assertTrue("\"defaultProvider\": \"lmstudio\"" in overlayBody || "\"defaultProvider\":\"lmstudio\"" in overlayBody)
            assertTrue("qwen/qwen3.8-27b" in overlayBody)
            assertTrue("dark" in overlayBody)
            assertFalse("openai-codex" in overlayBody)
            assertEquals(
                """{"theme":"dark","defaultProvider":"openai-codex","defaultModel":"gpt-5.3-codex-spark"}""",
                userSettings.readText(),
            )
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun openCodeSidecarPinsSlashedLmStudioModelAsDefault() {
        val home = File.createTempFile("andy-opencode-lmstudio-model", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            val task = AgentTask(
                id = "t1",
                title = "local",
                prompt = "hi",
                agent = AgentKind.LMStudio,
                localRuntime = LocalAgentRuntime.OpenCode,
                model = "qwen/qwen3.8-27b",
                createdAtMillis = 0,
            )
            val env = LocalModelSidecar.envFor(task, WorkspaceState(), home)
            val body = File(env.getValue("OPENCODE_CONFIG")).readText()
            assertTrue("lmstudio/qwen/qwen3.8-27b" in body)
            assertTrue("\"qwen/qwen3.8-27b\"" in body)
            assertEquals(body, env["OPENCODE_CONFIG_CONTENT"])
        } finally {
            home.deleteRecursively()
        }
    }
}

class AgentCliLocatorLocalModelsTest {
    @Test
    fun locateAllDoesNotEmitOllamaOrLmStudioRows() {
        val statuses = AgentCliLocator().locateAll(emptyMap())
        assertTrue(statuses.none { !it.kind.hasVendorCli })
        assertTrue(statuses.none { it.kind == AgentKind.Ollama || it.kind == AgentKind.LMStudio })
    }
}
