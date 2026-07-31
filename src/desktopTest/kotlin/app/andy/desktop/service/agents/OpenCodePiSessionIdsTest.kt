package app.andy.desktop.service.agents

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenCodePiSessionIdsTest {
    @Test
    fun parsesOpenCodeSessionListJson() {
        val ids = OpenCodeSessionIds.parseSessionListOutput(
            """
            [
              {"id":"11111111-1111-1111-1111-111111111111","title":"a"},
              {"sessionID":"22222222-2222-2222-2222-222222222222"}
            ]
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                "11111111-1111-1111-1111-111111111111",
                "22222222-2222-2222-2222-222222222222",
            ),
            ids,
        )
    }

    @Test
    fun parsesOpenCodeSessionListPlainText() {
        val ids = OpenCodeSessionIds.parseSessionListOutput(
            "sess 33333333-3333-3333-3333-333333333333 older",
        )
        assertEquals(listOf("33333333-3333-3333-3333-333333333333"), ids)
    }

    @Test
    fun piSessionDirsPreferWorkspaceHashAndName() {
        val home = kotlin.io.path.createTempDirectory("andy-pi-home").toFile()
        try {
            val sessions = File(home, ".pi/agent/sessions")
            sessions.mkdirs()
            File(sessions, "myrepo").mkdirs()
            val dirs = PiSessionIds.sessionDirsFor("/tmp/myrepo", home)
            assertTrue(dirs.any { it.name == "myrepo" })
            assertTrue(dirs.contains(sessions))
        } finally {
            home.deleteRecursively()
        }
    }
}
