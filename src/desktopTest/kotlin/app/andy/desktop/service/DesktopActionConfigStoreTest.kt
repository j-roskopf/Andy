package app.andy.desktop.service

import app.andy.model.ActionProject
import app.andy.model.ActionsConfig
import app.andy.model.ConfigSource
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
    fun discoversVirtualProjectFromRepoRoot() = runBlocking {
        val dir = createTempDirectory("andy-actions-repo-discovery").toFile()
        val homeConfig = dir.resolve("home/actions.toml")
        val repoDir = dir.resolve("workspace").apply { mkdirs() }
        repoDir.resolve(".andy/actions.toml").apply {
            parentFile.mkdirs()
            writeText(
                """
                version = 1
                [[projects]]
                id = "repo-proj"
                name = "Repo Project"
                contextDir = "."
                env = { }
                [[actions]]
                id = "repo-act"
                projectId = "repo-proj"
                name = "Repo Run"
                icon = "run"
                command = "echo repo"
                cwd = ""
                env = { }
                """.trimIndent() + "\n",
            )
        }

        val store = DesktopActionConfigStore(homeConfig, discoveryRootsProvider = { listOf(repoDir.absolutePath) })
        val loaded = store.load()

        assertEquals(1, loaded.projects.size)
        val proj = loaded.projects.single()
        assertEquals("repo-proj", proj.id)
        assertEquals(repoDir.absolutePath, proj.contextDir)
        assertEquals(ConfigSource.Repo, proj.source)
        assertEquals(1, proj.actions.size)
        assertEquals(ConfigSource.Repo, proj.actions.single().source)
        assertFalse(homeConfig.exists(), "personal config must not be created on load")
    }

    @Test
    fun augmentsGlobalProjectWithRepoActionsAndNotesAndDedups() = runBlocking {
        val dir = createTempDirectory("andy-actions-augment").toFile()
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
        workspace.resolve(".andy/actions.toml").apply {
            parentFile.mkdirs()
            writeText(
                """
                version = 1
                [[projects]]
                id = "andy"
                name = "Andy Repo"
                contextDir = "."
                env = { }
                [[actions]]
                id = "test"
                projectId = "andy"
                name = "Test"
                icon = "test"
                command = "./gradlew desktopTest"
                cwd = ""
                env = { }
                [[actions]]
                id = "record"
                projectId = "andy"
                name = "Record screenshots"
                icon = "test"
                command = "./gradlew recordRoborazziDesktop"
                cwd = ""
                env = { }
                [[notes]]
                id = "note-repo"
                projectId = "andy"
                title = "Repo note"
                body = "Read me"
                completed = true
                """.trimIndent() + "\n",
            )
        }

        val store = DesktopActionConfigStore(homeConfig, discoveryRootsProvider = { listOf(workspace.absolutePath) })
        val loaded = store.load()
        val project = loaded.projects.single()

        assertEquals(ConfigSource.Global, project.source)
        assertEquals(2, project.actions.size)
        val globalAction = project.actions.first { it.id == "test" }
        assertEquals(ConfigSource.Global, globalAction.source)
        val repoAction = project.actions.first { it.id == "record" }
        assertEquals(ConfigSource.Repo, repoAction.source)

        assertEquals(1, project.notes.size)
        val repoNote = project.notes.single()
        assertEquals("note-repo", repoNote.id)
        assertEquals(true, repoNote.completed)
        assertEquals(ConfigSource.Repo, repoNote.source)
    }

    @Test
    fun roundTripSafetyDropsRepoContentAndVirtualProjectsOnSave() = runBlocking {
        val dir = createTempDirectory("andy-actions-roundtrip").toFile()
        val homeConfig = dir.resolve("home/actions.toml").apply {
            parentFile.mkdirs()
            writeText(
                """
                version = 1
                [[projects]]
                id = "global-proj"
                name = "Global Project"
                contextDir = "/tmp/global"
                env = { }
                [[actions]]
                id = "global-act"
                projectId = "global-proj"
                name = "Global Action"
                icon = "run"
                command = "echo global"
                cwd = ""
                env = { }
                """.trimIndent() + "\n",
            )
        }
        val repoDir = dir.resolve("workspace").apply { mkdirs() }
        repoDir.resolve(".andy/actions.toml").apply {
            parentFile.mkdirs()
            writeText(
                """
                version = 1
                [[projects]]
                id = "virtual-proj"
                name = "Virtual Project"
                contextDir = "."
                env = { }
                [[actions]]
                id = "virtual-act"
                projectId = "virtual-proj"
                name = "Virtual Action"
                icon = "run"
                command = "echo virtual"
                cwd = ""
                env = { }
                """.trimIndent() + "\n",
            )
        }

        val store = DesktopActionConfigStore(homeConfig, discoveryRootsProvider = { listOf(repoDir.absolutePath) })
        val merged = store.load()
        assertEquals(2, merged.projects.size)

        store.save(merged)

        val reloadedRaw = homeConfig.readText()
        assertFalse(reloadedRaw.contains("virtual-proj"), "virtual projects must not be saved")
        assertFalse(reloadedRaw.contains("virtual-act"), "virtual actions must not be saved")
        assertTrue(reloadedRaw.contains("global-proj"))
        assertTrue(reloadedRaw.contains("global-act"))
    }

    @Test
    fun stripsEnvFromRepoConfig() = runBlocking {
        val dir = createTempDirectory("andy-actions-strip-env").toFile()
        val homeConfig = dir.resolve("home/actions.toml")
        val repoDir = dir.resolve("workspace").apply { mkdirs() }
        repoDir.resolve(".andy/actions.toml").apply {
            parentFile.mkdirs()
            writeText(
                """
                version = 1
                [[projects]]
                id = "secret-proj"
                name = "Secret Project"
                contextDir = "."
                env = { SECRET = "repo_secret" }
                [[actions]]
                id = "secret-act"
                projectId = "secret-proj"
                name = "Secret Action"
                icon = "run"
                command = "echo secret"
                cwd = ""
                env = { API_KEY = "12345" }
                """.trimIndent() + "\n",
            )
        }

        val store = DesktopActionConfigStore(homeConfig, discoveryRootsProvider = { listOf(repoDir.absolutePath) })
        val loaded = store.load()
        val project = loaded.projects.single()

        assertTrue(project.env.isEmpty(), "repo project env must be stripped")
        assertTrue(project.actions.single().env.isEmpty(), "repo action env must be stripped")
    }

    @Test
    fun roundTripsProjectsActionsAndNotes() = runBlocking {
        val dir = createTempDirectory("andy-actions-config").toFile()
        val file = dir.resolve("actions.toml")
        val store = DesktopActionConfigStore(file, discoveryRootsProvider = { emptyList() })
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
    fun discoversRepoActionsFromGlobalProjectContextDir() = runBlocking {
        val dir = createTempDirectory("andy-actions-global-context").toFile()
        val workspace = dir.resolve("workspace").apply { mkdirs() }
        val homeConfig = dir.resolve("home/actions.toml").apply {
            parentFile.mkdirs()
            writeText(
                """
                version = 1
                [[projects]]
                id = "proj-basil"
                name = "Basil"
                contextDir = "${workspace.absolutePath.replace("\\", "\\\\")}/"
                env = { }
                """.trimIndent() + "\n",
            )
        }
        workspace.resolve(".andy/actions.toml").apply {
            parentFile.mkdirs()
            writeText(
                """
                version = 1
                [[projects]]
                id = "basil"
                name = "Basil"
                contextDir = "."
                env = { }
                [[actions]]
                id = "act-desktop"
                projectId = "basil"
                name = "Desktop"
                icon = "run"
                command = "./gradlew :composeApp:run"
                cwd = ""
                env = { }
                """.trimIndent() + "\n",
            )
        }

        val store = DesktopActionConfigStore(homeConfig, discoveryRootsProvider = { emptyList() })
        val loaded = store.load()
        val project = loaded.projects.single()

        assertEquals("proj-basil", project.id)
        assertEquals(ConfigSource.Global, project.source)
        assertEquals(1, project.actions.size)
        assertEquals("act-desktop", project.actions.single().id)
        assertEquals(ConfigSource.Repo, project.actions.single().source)
    }

    @Test
    fun savesVirtualProjectWhenGlobalChildIsAdded() = runBlocking {
        val dir = createTempDirectory("andy-actions-global-child").toFile()
        val homeConfig = dir.resolve("home/actions.toml")
        val repoDir = dir.resolve("workspace").apply { mkdirs() }
        repoDir.resolve(".andy/actions.toml").apply {
            parentFile.mkdirs()
            writeText(
                """
                version = 1
                [[projects]]
                id = "repo-proj"
                name = "Repo Project"
                contextDir = "."
                env = { }
                [[actions]]
                id = "repo-act"
                projectId = "repo-proj"
                name = "Repo Action"
                icon = "run"
                command = "echo repo"
                cwd = ""
                env = { }
                """.trimIndent() + "\n",
            )
        }

        val store = DesktopActionConfigStore(homeConfig, discoveryRootsProvider = { listOf(repoDir.absolutePath) })
        val loaded = store.load()
        val project = loaded.projects.single()

        val updatedActions = project.actions + ProjectAction(
            id = "user-act",
            name = "User Action",
            command = "echo user",
            source = ConfigSource.Global,
        )
        val updatedConfig = loaded.copy(projects = listOf(project.copy(actions = updatedActions)))

        store.save(updatedConfig)

        val savedRaw = homeConfig.readText()
        assertTrue(savedRaw.contains("repo-proj"))
        assertTrue(savedRaw.contains("user-act"))
        assertFalse(savedRaw.contains("repo-act"), "repo action must still be filtered out on save")
    }
}
