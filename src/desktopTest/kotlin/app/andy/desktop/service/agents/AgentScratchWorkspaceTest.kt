package app.andy.desktop.service.agents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.io.File

class AgentScratchWorkspaceTest {
    @Test
    fun resolveCwdFallsBackToAndyTasksScratch() {
        val home = File.createTempFile("andy-scratch-home", null).also { it.delete(); it.mkdirs() }
        try {
            val resolved = AgentScratchWorkspace.resolveCwd(null, home)
            assertEquals(File(home, ".andy-tasks").absoluteFile.normalize().absolutePath, resolved)
            assertTrue(File(resolved).isDirectory)
            assertTrue(File(resolved, "README.txt").isFile)
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun resolveCwdKeepsExplicitDirectory() {
        val home = File.createTempFile("andy-scratch-home2", null).also { it.delete(); it.mkdirs() }
        val project = File(home, "repo").also { it.mkdirs() }
        try {
            val resolved = AgentScratchWorkspace.resolveCwd(project.absolutePath, home)
            assertEquals(project.absoluteFile.normalize().absolutePath, resolved)
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun isScratchDetectsNullAndScratchSubtree() {
        val home = File.createTempFile("andy-scratch-home3", null).also { it.delete(); it.mkdirs() }
        try {
            val scratch = AgentScratchWorkspace.ensure(home)
            assertTrue(AgentScratchWorkspace.isScratch(null, home))
            assertTrue(AgentScratchWorkspace.isScratch(scratch.absolutePath, home))
            assertTrue(AgentScratchWorkspace.isScratch(File(scratch, "nested").absolutePath, home))
            assertFalse(AgentScratchWorkspace.isScratch(File(home, "other").absolutePath, home))
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun ensureClaudeTrustSeedsHasTrustDialogAccepted() {
        val home = File.createTempFile("andy-scratch-home4", null).also { it.delete(); it.mkdirs() }
        try {
            val scratch = AgentScratchWorkspace.ensure(home)
            AgentScratchWorkspace.ensureClaudeTrust(scratch, home)
            val config = File(home, ".claude.json")
            assertTrue(config.isFile)
            val projects = Json.parseToJsonElement(config.readText()).jsonObject["projects"] as JsonObject
            val entry = projects[scratch.absolutePath] as JsonObject
            assertEquals(true, (entry["hasTrustDialogAccepted"] as JsonPrimitive).booleanOrNull)

            // Idempotent — second call keeps the flag.
            AgentScratchWorkspace.ensureClaudeTrust(scratch, home)
            val again = Json.parseToJsonElement(config.readText()).jsonObject["projects"] as JsonObject
            assertEquals(true, ((again[scratch.absolutePath] as JsonObject)["hasTrustDialogAccepted"] as JsonPrimitive).booleanOrNull)
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun ensureClaudeTrustNeverMarksHome() {
        val home = File.createTempFile("andy-scratch-home5", null).also { it.delete(); it.mkdirs() }
        try {
            AgentScratchWorkspace.ensureClaudeTrust(home, home)
            assertFalse(File(home, ".claude.json").exists())
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun dirForNullUsesScratchRoot() {
        val home = File.createTempFile("andy-scratch-home6", null).also { it.delete(); it.mkdirs() }
        val previous = System.getProperty("user.home")
        try {
            System.setProperty("user.home", home.absolutePath)
            val dir = AgentWorkflowArtifacts.dirFor(null, "task-xyz")
            assertEquals(
                File(home, ".andy-tasks/.andy/task-xyz").absoluteFile.normalize().absolutePath,
                dir.absoluteFile.normalize().absolutePath,
            )
        } finally {
            System.setProperty("user.home", previous)
            home.deleteRecursively()
        }
    }
}
