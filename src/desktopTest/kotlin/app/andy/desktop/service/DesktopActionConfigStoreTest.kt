package app.andy.desktop.service

import app.andy.model.ActionProject
import app.andy.model.ActionsConfig
import app.andy.model.ProjectAction
import app.andy.model.ProjectNote
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopActionConfigStoreTest {
    @Test
    fun roundTripsProjectsActionsAndNotes() = runBlocking {
        val dir = createTempDirectory("andy-actions-config").toFile()
        val file = dir.resolve("actions.toml")
        val store = DesktopActionConfigStore(file)
        val config = ActionsConfig(
            projects = listOf(
                ActionProject(
                    id = "proj-demo",
                    name = "Demo",
                    contextDir = "/tmp/demo",
                    env = mapOf("FOO" to "bar"),
                    actions = listOf(
                        ProjectAction(
                            id = "act-build",
                            name = "Build",
                            icon = "build",
                            command = "./gradlew build",
                            cwd = "/tmp/demo/app",
                        ),
                    ),
                    notes = listOf(
                        ProjectNote(
                            id = "note-ship",
                            title = "Ship checklist",
                            body = "Verify release notes",
                            completed = false,
                        ),
                        ProjectNote(
                            id = "note-done",
                            title = "Done item",
                            body = "",
                            completed = true,
                        ),
                    ),
                ),
            ),
        )

        store.save(config)
        val loaded = store.load()

        assertEquals(1, loaded.projects.size)
        val project = loaded.projects.single()
        assertEquals("proj-demo", project.id)
        assertEquals("Demo", project.name)
        assertEquals("/tmp/demo", project.contextDir)
        assertEquals(mapOf("FOO" to "bar"), project.env)
        assertEquals(1, project.actions.size)
        assertEquals("act-build", project.actions.single().id)
        assertEquals("./gradlew build", project.actions.single().command)
        assertEquals("/tmp/demo/app", project.actions.single().cwd)
        assertEquals(2, project.notes.size)
        assertEquals(
            ProjectNote("note-ship", "Ship checklist", "Verify release notes", completed = false),
            project.notes.first { it.id == "note-ship" },
        )
        assertEquals(
            ProjectNote("note-done", "Done item", "", completed = true),
            project.notes.first { it.id == "note-done" },
        )
        assertTrue(file.readText().contains("[[notes]]"))
        assertTrue(file.readText().contains("title = \"Ship checklist\""))
    }

    @Test
    fun loadsLegacyTomlWithoutNotesAsEmptyNotes() = runBlocking {
        val dir = createTempDirectory("andy-actions-legacy").toFile()
        val file = dir.resolve("actions.toml")
        file.writeText(
            """
            version = 1
            [[projects]]
            id = "proj-legacy"
            name = "Legacy"
            contextDir = "/tmp/legacy"
            env = { }
            [[actions]]
            id = "act-run"
            projectId = "proj-legacy"
            name = "Run"
            icon = "run"
            command = "echo hi"
            cwd = ""
            env = { }
            """.trimIndent() + "\n",
        )

        val loaded = DesktopActionConfigStore(file).load()
        assertEquals(1, loaded.projects.size)
        val project = loaded.projects.single()
        assertEquals("proj-legacy", project.id)
        assertEquals(1, project.actions.size)
        assertEquals("act-run", project.actions.single().id)
        assertTrue(project.notes.isEmpty())
        assertFalse(file.readText().contains("[[notes]]"))
    }
}
