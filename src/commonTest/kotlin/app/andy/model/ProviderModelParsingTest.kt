package app.andy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderModelParsingTest {
    @Test
    fun parsesHermesAndOpenClawJsonModelLists() {
        val hermes = parseHermesModels("{\"models\":[{\"id\":\"anthropic/claude-sonnet-4\",\"label\":\"Sonnet\"}]}")
        val openClaw = parseOpenClawModels("[{\"model\":\"openai/gpt-5.6-sol\"}]")
        assertEquals("anthropic/claude-sonnet-4", hermes.single().id)
        assertEquals("Sonnet", hermes.single().label)
        assertEquals("openai/gpt-5.6-sol", openClaw.single().id)
    }
    @Test
    fun parsesOpenCodeProviderModelSlugs() {
        val options = parseOpenCodeModels(
            """
            anthropic/claude-sonnet-5 - Claude Sonnet 5
            openai/gpt-5.5 - GPT-5.5
            google/gemini-3.1-pro
            Tip: use --model provider/id
            """.trimIndent(),
        )
        assertEquals("anthropic/claude-sonnet-5", options.single { it.id == "anthropic/claude-sonnet-5" }.id)
        assertEquals("Claude Sonnet 5", options.single { it.id == "anthropic/claude-sonnet-5" }.label)
        assertEquals(AgentModelFamily.Anthropic, options.single { it.id == "anthropic/claude-sonnet-5" }.modelFamily())
        assertEquals(AgentModelFamily.OpenAI, options.single { it.id == "openai/gpt-5.5" }.modelFamily())
        assertEquals(AgentModelFamily.Google, options.single { it.id == "google/gemini-3.1-pro" }.modelFamily())
    }

    @Test
    fun parsesPiListModelsTable() {
        val options = parsePiModels(
            """
            provider      model                context  max-out  thinking  images
            openai-codex  gpt-5.3-codex-spark  128K     128K     yes       no
            openai-codex  gpt-5.4              272K     128K     yes       yes
            openai-codex  gpt-5.5              272K     128K     yes       yes
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                "openai-codex/gpt-5.3-codex-spark",
                "openai-codex/gpt-5.4",
                "openai-codex/gpt-5.5",
            ),
            options.map { it.id },
        )
        // Must never treat the provider column alone as a model id.
        assertTrue(options.none { it.id == "openai-codex" })
        assertTrue(options.single { it.id == "openai-codex/gpt-5.5" }.efforts.isNotEmpty())
    }

    @Test
    fun parsesPiListModelsLegacySlugs() {
        val options = parsePiModels(
            """
            Provider: anthropic
            anthropic/claude-sonnet-4-5
            anthropic/claude-opus-4
            Provider: openai
            openai/gpt-5
            """.trimIndent(),
        )
        assertTrue(options.any { it.id == "anthropic/claude-sonnet-4-5" })
        assertTrue(options.any { it.id == "openai/gpt-5" })
        assertEquals(AgentModelFamily.Anthropic, options.single { it.id == "anthropic/claude-sonnet-4-5" }.modelFamily())
    }

    @Test
    fun parsesAntigravitySlugsIntoBaseModelsWithEfforts() {
        val options = parseAntigravityModels(
            """
            gemini-3.6-flash-high
            gemini-3.6-flash-medium
            gemini-3.6-flash-low
            gemini-3.1-pro-low
            gemini-3.1-pro-high
            claude-sonnet-4-6
            claude-opus-4-6-thinking
            gpt-oss-120b-medium
            """.trimIndent(),
        )

        val flash = options.single { it.id == "gemini-3.6-flash" }
        assertEquals("Gemini 3.6 Flash", flash.label)
        assertEquals(
            listOf(AgentReasoningEffort.Low, AgentReasoningEffort.Medium, AgentReasoningEffort.High),
            flash.efforts,
        )
        assertEquals("high", flash.effortToken(AgentReasoningEffort.High))

        val pro = options.single { it.id == "gemini-3.1-pro" }
        assertEquals(listOf(AgentReasoningEffort.Low, AgentReasoningEffort.High), pro.efforts)

        assertEquals(emptyList(), options.single { it.id == "claude-sonnet-4-6" }.efforts)
        assertEquals(emptyList(), options.single { it.id == "claude-opus-4-6-thinking" }.efforts)
        assertEquals(listOf(AgentReasoningEffort.Medium), options.single { it.id == "gpt-oss-120b" }.efforts)
    }

    @Test
    fun parsesCursorModelsGroupingEffortAndFast() {
        val options = parseCursorModels(
            """
            Available models

            auto - Auto (default)
            gemini-3.6-flash-minimal - Gemini 3.6 Flash Minimal
            gemini-3.6-flash-low - Gemini 3.6 Flash Low
            gemini-3.6-flash-medium - Gemini 3.6 Flash Medium
            gemini-3.6-flash-high - Gemini 3.6 Flash
            cursor-grok-4.5-high - Cursor Grok 4.5
            cursor-grok-4.5-high-fast - Cursor Grok 4.5 Fast
            cursor-grok-4.5-low - Cursor Grok 4.5 Low
            gpt-5.5-extra-high - GPT-5.5 1M Extra High
            gpt-5.5-extra-high-fast - GPT-5.5 Extra High Fast
            composer-2.5 - Composer 2.5
            composer-2.5-fast - Composer 2.5 Fast
            Tip: use --model <id>
            """.trimIndent(),
        )

        val flash = options.single { it.id == "gemini-3.6-flash" }
        assertEquals("Gemini 3.6 Flash", flash.label)
        assertEquals(
            listOf(
                AgentReasoningEffort.Minimal,
                AgentReasoningEffort.Low,
                AgentReasoningEffort.Medium,
                AgentReasoningEffort.High,
            ),
            flash.efforts,
        )
        assertFalse(flash.supportsFastMode)

        val grok = options.single { it.id == "cursor-grok-4.5" }
        assertTrue(grok.supportsFastMode)
        assertEquals(listOf(AgentReasoningEffort.Low, AgentReasoningEffort.High), grok.efforts)

        val gpt55 = options.single { it.id == "gpt-5.5" }
        assertEquals("extra-high", gpt55.effortToken(AgentReasoningEffort.ExtraHigh))
        assertTrue(gpt55.supportsFastMode)

        val composer = options.single { it.id == "composer-2.5" }
        assertEquals(emptyList(), composer.efforts)
        assertTrue(composer.supportsFastMode)
        assertFalse(composer.fastRequired)

        assertEquals("auto", options.single { it.id == "auto" }.id)
    }

    @Test
    fun parsesAntigravityParenthesizedModelsWithFetchingSpinner() {
        val options = parseAntigravityModels(
            """
            Fetching available models...
            Gemini 3.7 Flash High (gemini-3.7-flash:high)
            Gemini 3.7 Flash Medium (gemini-3.7-flash:medium)
            Gemini 3.7 Flash Low (gemini-3.7-flash:low)
            Gemini 3.6 Flash High (gemini-3.6-flash:high)
            Gemini 3.6 Flash Medium (gemini-3.6-flash:medium)
            Gemini 3.6 Flash Low (gemini-3.6-flash:low)
            Gemini 3.5 Flash High (gemini-3.5-flash:high)
            Gemini 3.5 Flash Medium (gemini-3.5-flash:medium)
            Gemini 3.5 Flash Low (gemini-3.5-flash:low)
            Gemini 3.1 Pro High (gemini-3.1-pro:high)
            Gemini 3.1 Pro Low (gemini-3.1-pro:low)
            Claude Sonnet 4.6 (claude-sonnet-4-6)
            Claude Opus 4.6 (claude-opus-4-6-thinking)
            GPT-OSS 120B (gpt-oss-120b:medium)
            """.trimIndent(),
        )

        assertTrue(options.none { it.id.contains("Fetching", ignoreCase = true) || it.label.contains("Fetching", ignoreCase = true) })

        val flash37 = options.single { it.id == "gemini-3.7-flash" }
        assertEquals("Gemini 3.7 Flash", flash37.label)
        assertEquals(
            listOf(AgentReasoningEffort.Low, AgentReasoningEffort.Medium, AgentReasoningEffort.High),
            flash37.efforts,
        )
        assertEquals("high", flash37.effortToken(AgentReasoningEffort.High))

        val pro = options.single { it.id == "gemini-3.1-pro" }
        assertEquals("Gemini 3.1 Pro", pro.label)
        assertEquals(listOf(AgentReasoningEffort.Low, AgentReasoningEffort.High), pro.efforts)

        val sonnet = options.single { it.id == "claude-sonnet-4-6" }
        assertEquals("Claude Sonnet 4.6", sonnet.label)
        assertEquals(emptyList(), sonnet.efforts)

        val gptOss = options.single { it.id == "gpt-oss-120b" }
        assertEquals("GPT-OSS 120B", gptOss.label)
        assertEquals(listOf(AgentReasoningEffort.Medium), gptOss.efforts)
    }

    @Test
    fun antigravityModelForCliUsesBaseModelId() {
        val task = AgentTask(
            id = "1",
            title = "t",
            prompt = "p",
            agent = AgentKind.Antigravity,
            model = "gemini-3.6-flash",
            reasoningEffort = AgentReasoningEffort.Medium,
            createdAtMillis = 0,
        )
        assertEquals("gemini-3.6-flash", task.modelForCli())
    }

    @Test
    fun piModelForCliRequiresProviderSlashModel() {
        val bare = AgentTask(
            id = "1",
            title = "t",
            prompt = "p",
            agent = AgentKind.Pi,
            model = "openai-codex",
            createdAtMillis = 0,
        )
        assertEquals(null, bare.modelForCli())

        val full = bare.copy(model = "openai-codex/gpt-5.5")
        assertEquals("openai-codex/gpt-5.5", full.modelForCli())
    }

    @Test
    fun cursorModelForCliUsesDiscoveredExtraHighToken() {
        val discovered = mapOf(
            AgentKind.Cursor to listOf(
                AgentModelOption(
                    id = "gpt-5.5",
                    label = "GPT-5.5",
                    efforts = listOf(AgentReasoningEffort.ExtraHigh),
                    supportsFastMode = true,
                    effortTokens = mapOf(AgentReasoningEffort.ExtraHigh to "extra-high"),
                ),
            ),
        )
        val task = AgentTask(
            id = "1",
            title = "t",
            prompt = "p",
            agent = AgentKind.Cursor,
            model = "gpt-5.5",
            reasoningEffort = AgentReasoningEffort.ExtraHigh,
            fastMode = true,
            createdAtMillis = 0,
        )
        assertEquals("gpt-5.5-extra-high-fast", task.modelForCli(discovered))
    }

    @Test
    fun catalogResolvesLegacyAntigravityDisplayNames() {
        val option = AgentModelCatalog.option(AgentKind.Antigravity, "Gemini 3.6 Flash")
        assertEquals("gemini-3.6-flash", option?.id)
    }

    @Test
    fun fastOnlyCursorModelRequiresFastSuffix() {
        val options = parseCursorModels(
            """
            composer-2.5-fast - Composer 2.5 Fast
            """.trimIndent(),
        )

        val composer = options.single { it.id == "composer-2.5" }
        assertTrue(composer.supportsFastMode)
        assertTrue(composer.fastRequired)

        val task = AgentTask(
            id = "1",
            title = "t",
            prompt = "p",
            agent = AgentKind.Cursor,
            model = "composer-2.5",
            reasoningEffort = null,
            fastMode = false,
            createdAtMillis = 0,
        )
        assertEquals("composer-2.5-fast", task.modelForCli(mapOf(AgentKind.Cursor to options)))
    }

    @Test
    fun groupsCursorModelsByVendorFamily() {
        val grouped = listOf(
            AgentModelOption("auto", "Auto", emptyList()),
            AgentModelOption("composer-2.5", "Composer 2.5", emptyList()),
            AgentModelOption("gpt-5.6-sol", "GPT-5.6 Sol", emptyList()),
            AgentModelOption("claude-opus-4-8", "Opus 4.8", emptyList()),
            AgentModelOption("gemini-3.6-flash", "Gemini 3.6 Flash", emptyList()),
            AgentModelOption("kimi-k2.7-code", "Kimi K2.7 Code", emptyList()),
            AgentModelOption("glm-5.2", "GLM 5.2", emptyList()),
            AgentModelOption("mystery-model", "Mystery", emptyList()),
        ).groupedByModelFamily()

        assertEquals(
            listOf(
                AgentModelFamily.Cursor,
                AgentModelFamily.OpenAI,
                AgentModelFamily.Anthropic,
                AgentModelFamily.Google,
                AgentModelFamily.Moonshot,
                AgentModelFamily.Zhipu,
                AgentModelFamily.Other,
            ),
            grouped.map { it.first },
        )
        assertEquals(listOf("auto", "composer-2.5"), grouped.first().second.map { it.id })
        assertEquals("gpt-5.6-sol", grouped[1].second.single().id)
        assertEquals(AgentModelFamily.Cursor, modelFamilyForId("cursor-grok-4.5"))
        assertEquals(AgentModelFamily.XAI, modelFamilyForId("grok-4"))
    }

    @Test
    fun parsesGooseConfigYamlAndInfoDump() {
        val fromYaml = parseGooseModels(
            """
            GOOSE_PROVIDER: anthropic
            GOOSE_MODEL: claude-sonnet-4-5
            extensions:
              developer:
                enabled: true
                type: builtin
                name: developer
            providers:
              openai:
                enabled: true
                model: gpt-5.4
            """.trimIndent(),
        )
        assertTrue(fromYaml.any { it.id == "anthropic/claude-sonnet-4-5" })
        assertTrue(fromYaml.any { it.id == "openai/gpt-5.4" })
        assertEquals(AgentModelFamily.Anthropic, fromYaml.single { it.id == "anthropic/claude-sonnet-4-5" }.modelFamily())

        val fromInfo = parseGooseModels(
            """
            active_provider: google
            GOOSE_MODEL: gemini-2.5-pro
            """.trimIndent(),
        )
        assertEquals("google/gemini-2.5-pro", fromInfo.single().id)
        assertTrue(gooseLooksConfigured("GOOSE_PROVIDER: anthropic\n"))
        assertTrue(gooseLooksConfigured("active_provider: databricks\n"))
        assertFalse(gooseLooksConfigured("extensions:\n  developer:\n    enabled: true\n"))
    }
}
