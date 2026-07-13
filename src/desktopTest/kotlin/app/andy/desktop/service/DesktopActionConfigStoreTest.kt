package app.andy.desktop.service

import app.andy.model.ActionProject
import app.andy.model.ActionsConfig
import app.andy.model.ProjectAction
import app.andy.model.ProjectNote
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopActionConfigStoreTest {
    @Test
    fun loadsStarterActionsFromARelativePath() = runBlocking {
        val dir = createTempDirectory("andy-actions-relative-starter").toFile()
        val homeConfig = dir.resolve("home/actions.toml")

        val loaded = DesktopActionConfigStore(homeConfig, File(".andy/actions.toml")).load()

        assertEquals(File(System.getProperty("user.dir")).absolutePath, loaded.projects.single().contextDir)
    }

    @Test
    fun loadsStarterActionsWhenNoPersonalConfigurationExists() = runBlocking {
        val dir = createTempDirectory("andy-actions-starter").toFile()
        val homeConfig = dir.resolve("home/actions.toml")
        val starter = dir.resolve("workspace/.andy/actions.toml").apply {
            parentFile.mkdirs()
            writeText(
                """
                version = 1
                [[projects]]
                id = "andy"
                name = "Andy"
                contextDir = "."
                env = { }
                [[actions]]
                id = "record"
                projectId = "andy"
                name = "Record screenshots"
                icon = "test"
                command = "./gradlew recordRoborazziDesktop"
                cwd = ""
                env = { }
                """.trimIndent() + "\n",
            )
        }

        val loaded = DesktopActionConfigStore(homeConfig, starter).load()

        assertEquals(1, loaded.projects.size)
        assertEquals(starter.parentFile.parentFile.absolutePath, loaded.projects.single().contextDir)
        assertEquals("./gradlew recordRoborazziDesktop", loaded.projects.single().actions.single().command)
        assertFalse(homeConfig.exists(), "starter data must not overwrite personal configuration")
    }

    @Test
    fun ignoresMalformedStarterActionsWithoutChangingPersonalConfiguration() = runBlocking {
        val dir = createTempDirectory("andy-actions-invalid-starter").toFile()
        val homeConfig = dir.resolve("home/actions.toml")
        val starter = dir.resolve("workspace/.andy/actions.toml").apply {
            parentFile.mkdirs()
            writeText("[[projects]\nid = \"unterminated\"")
        }

        val loaded = DesktopActionConfigStore(homeConfig, starter).load()

        assertTrue(loaded.projects.isEmpty())
        assertFalse(homeConfig.exists(), "starter data must not overwrite personal configuration")
        assertFalse(File(starter.absolutePath + ".corrupt").exists(), "starter data must not be renamed or copied")
    }

    @Test
    fun mergesScreenshotStarterActionIntoExistingPersonalProject() = runBlocking {
        val dir = createTempDirectory("andy-actions-merge").toFile()
        val workspace = dir.resolve("workspace").apply { mkdirs() }
        val homeConfig = dir.resolve("home/actions.toml").apply {
            parentFile.mkdirs()
            writeText(
                """
                version = 1
                [[projects]]
                id = "andy"
                name = "My Andy checkout"
                contextDir = "${workspace.absolutePath.replace("\\", "\\\\")}"
                env = { }
                [[actions]]
                id = "test"
                projectId = "andy"
                name = "Test"
                icon = "test"
                command = "./gradlew desktopTest"
                cwd = ""
                env = { }
                """.trimIndent() + "\n",
            )
        }
        val starter = workspace.resolve(".andy/actions.toml").apply {
            parentFile.mkdirs()
            writeText(
                """
                version = 1
                [[projects]]
                id = "andy"
                name = "Andy"
                contextDir = "."
                env = { }
                [[actions]]
                id = "record"
                projectId = "andy"
                name = "Record screenshots"
                icon = "test"
                command = "./gradlew recordRoborazziDesktop"
                cwd = ""
                env = { }
                """.trimIndent() + "\n",
            )
        }

        val project = DesktopActionConfigStore(homeConfig, starter).load().projects.single()

        assertEquals(2, project.actions.size)
        assertTrue(project.actions.any { it.command == "./gradlew recordRoborazziDesktop" })
        assertTrue(homeConfig.readText().contains("./gradlew desktopTest"), "personal config remains unchanged")
    }

    @Test
    fun roundTripsProjectsActionsAndNotes() = runBlocking {
        val dir = createTempDirectory("andy-actions-config").toFile()
        val file = dir.resolve("actions.toml")
        val store = DesktopActionConfigStore(file, starterFile = null)
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

        val loaded = DesktopActionConfigStore(file, starterFile = null).load()
        assertEquals(1, loaded.projects.size)
        val project = loaded.projects.single()
        assertEquals("proj-legacy", project.id)
        assertEquals(1, project.actions.size)
        assertEquals("act-run", project.actions.single().id)
        assertTrue(project.notes.isEmpty())
        assertFalse(file.readText().contains("[[notes]]"))
    }
}
