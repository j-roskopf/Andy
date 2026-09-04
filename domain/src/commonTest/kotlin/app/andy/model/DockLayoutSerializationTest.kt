package app.andy.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class DockLayoutSerializationTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun roundTripsLayoutWithNestedTerminalAndLiveTrees() {
        val layout = SavedDockLayout(
            id = "layout-test",
            name = "Test Layout",
            savedAtMillis = 123456789L,
            right = SavedDockPane(
                tabs = listOf(
                    SavedDockTab(
                        kind = SavedDockTabKind.Terminal,
                        title = "Terminal Split",
                        terminalTree = SavedTerminalNode.Split(
                            axis = SavedSplitAxis.Row,
                            children = listOf(
                                SavedTerminalNode.Leaf(
                                    sessions = listOf(
                                        SavedTerminalSession("proj-1", "Session 1"),
                                        SavedTerminalSession("proj-2", "Session 2"),
                                    ),
                                    activeSessionIndex = 1,
                                ),
                                SavedTerminalNode.Leaf(
                                    sessions = listOf(SavedTerminalSession("proj-3", "Session 3")),
                                    activeSessionIndex = 0,
                                ),
                            ),
                            weights = listOf(0.4f, 0.6f),
                        ),
                        focusedLeafIndex = 1,
                    ),
                    SavedDockTab(
                        kind = SavedDockTabKind.Live,
                        title = "Live Split",
                        liveTree = SavedLiveNode.Split(
                            axis = SavedSplitAxis.Column,
                            children = listOf(
                                SavedLiveNode.Leaf("target-1", "Device 1"),
                                SavedLiveNode.Leaf("target-2", "Device 2"),
                            ),
                            weights = listOf(0.5f, 0.5f),
                        ),
                        focusedLeafIndex = 0,
                    ),
                ),
                activeTabIndex = 0,
                visible = true,
            ),
            bottom = SavedDockPane(
                tabs = listOf(
                    SavedDockTab(kind = SavedDockTabKind.Browser, browserUrl = "https://andy.app", title = "Browser"),
                    SavedDockTab(kind = SavedDockTabKind.Chat, agentTaskId = "task-1", parentChatTaskId = "parent-1", title = "Chat"),
                    SavedDockTab(kind = SavedDockTabKind.Logs),
                ),
                activeTabIndex = 1,
                visible = true,
            ),
            rightPaneWidth = 520f,
            bottomPaneHeight = 340f,
        )

        val encoded = json.encodeToString(layout)
        val decoded = json.decodeFromString<SavedDockLayout>(encoded)
        assertEquals(layout, decoded)
    }

    @Test
    fun decodesWithUnknownFieldsWithoutThrowing() {
        val jsonWithUnknowns = """
            {
                "id": "layout-unknown",
                "name": "Unknown Field Test",
                "savedAtMillis": 12345,
                "extraField": "should be ignored",
                "right": {
                    "tabs": [
                        {
                            "kind": "Terminal",
                            "title": "Term",
                            "unknownTabProperty": 42,
                            "terminalTree": {
                                "type": "leaf",
                                "unknownNodeProperty": true,
                                "sessions": [
                                    {
                                        "projectId": "p1",
                                        "title": "S1",
                                        "unknownSessionProperty": "extra"
                                    }
                                ],
                                "activeSessionIndex": 0
                            }
                        }
                    ],
                    "activeTabIndex": 0,
                    "visible": true
                },
                "bottom": {
                    "tabs": [],
                    "activeTabIndex": -1,
                    "visible": false
                }
            }
        """.trimIndent()
        val decoded = json.decodeFromString<SavedDockLayout>(jsonWithUnknowns)
        assertEquals("layout-unknown", decoded.id)
        assertEquals("Unknown Field Test", decoded.name)
        assertEquals(1, decoded.right.tabs.size)
        val leaf = decoded.right.tabs[0].terminalTree as SavedTerminalNode.Leaf
        assertEquals(1, leaf.sessions.size)
        assertEquals("p1", leaf.sessions[0].projectId)
    }
}
