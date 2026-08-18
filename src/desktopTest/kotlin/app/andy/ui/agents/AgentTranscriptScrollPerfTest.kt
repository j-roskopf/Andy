package app.andy.ui.agents

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.andy.model.AgentEvent
import app.andy.ui.theme.AndyTheme
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AgentTranscriptScrollPerfTest {
    @Test
    fun scrollingDoesNotRestartTranscriptCompositionEveryFrame() =
        runDesktopComposeUiTest(width = 800, height = 500) {
            val counter = TranscriptCompositionCounter()
            val events = (0..35).flatMap { index ->
                listOf(
                    AgentEvent.UserMessage(
                        atMillis = index * 2L,
                        text = "user turn $index — please check the approach",
                    ),
                    AgentEvent.AssistantText(
                        atMillis = index * 2L + 1,
                        text = """
                            ## Response $index
                            Here is a **medium** reply with `inline code` and a list:
                            - item one
                            - item two
                            - item three

                            ```kotlin
                            fun example_$index() = $index
                            ```
                        """.trimIndent(),
                    ),
                )
            }

            setContent {
                AndyTheme {
                    CompositionLocalProvider(LocalTranscriptCompositionCounter provides counter) {
                        Box(Modifier.fillMaxSize()) {
                            AgentTranscript(
                                events = events,
                                isActive = false,
                                restoreScrollKey = "perf",
                                scrollMemory = TranscriptScrollMemory(),
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
            waitForIdle()

            val afterMount = counter.rootRestarts
            assertTrue(afterMount >= 1, "transcript should compose at least once")

            repeat(25) {
                onNodeWithTag("transcript-list").performMouseInput {
                    moveTo(center)
                    scroll(-40f)
                }
                waitForIdle()
            }

            val delta = counter.rootRestarts - afterMount
            // Detach-from-bottom may restart once; scroll frames must not.
            assertTrue(
                delta <= 3,
                "scroll must not recompose AgentTranscript every frame (restarts=$delta). " +
                    "Reading LazyListState.layoutInfo during composition is the usual cause.",
            )
        }
}
