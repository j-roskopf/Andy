package app.andy.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderModelParsingTest {
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
    fun antigravityModelForCliUsesSlugEffortSuffix() {
        val task = AgentTask(
            id = "1",
            title = "t",
            prompt = "p",
            agent = AgentKind.Antigravity,
            model = "gemini-3.6-flash",
            reasoningEffort = AgentReasoningEffort.Medium,
            createdAtMillis = 0,
        )
        assertEquals("gemini-3.6-flash-medium", task.modelForCli())
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
}
