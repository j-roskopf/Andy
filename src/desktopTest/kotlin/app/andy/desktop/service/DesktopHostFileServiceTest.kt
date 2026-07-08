package app.andy.desktop.service

import app.andy.model.HostFileSaveResult
import app.andy.model.HostSearchMatchKind
import app.andy.model.HostSearchMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DesktopHostFileServiceTest {
    @Test
    fun indexesPersistsAndSearchesSavedRoot() = runBlocking {
        val root = createTempDirectory("andy-host-files-root").toFile()
        val indexDir = createTempDirectory("andy-host-files-index").toFile()
        root.resolve("src").mkdirs()
        root.resolve("src/Main.kt").writeText("fun main() {\n    println(\"needle value\")\n}\n")
        root.resolve("README.md").writeText("Andy editor notes\n")
        root.resolve("build/generated.txt").apply {
            parentFile.mkdirs()
            writeText("needle should be excluded")
        }

        val service = DesktopHostFileService(indexDir = indexDir)
        val status = withTimeout(5_000) {
            service.indexRoot(root.absolutePath).first { !it.indexing && it.indexedFiles >= 2 }
        }
        assertEquals(2, status.indexedFiles)

        val nameResults = service.search("main", HostSearchMode.FileName, listOf(root.absolutePath), 10)
        assertEquals("Main.kt", File(nameResults.single().path).name)

        val contentResults = service.search("needle", HostSearchMode.Content, listOf(root.absolutePath), 10)
        assertEquals(1, contentResults.size)
        assertEquals(HostSearchMatchKind.Content, contentResults.single().kind)
        assertTrue(contentResults.single().preview.contains("needle value"))

        val reloaded = DesktopHostFileService(indexDir = indexDir)
        val persistedResults = reloaded.search("Andy", HostSearchMode.Content, listOf(root.absolutePath), 10)
        assertEquals("README.md", File(persistedResults.single().path).name)
    }

    @Test
    fun saveDetectsExternalConflictAndUpdatesIndexAfterOverwrite() = runBlocking {
        val root = createTempDirectory("andy-host-save-root").toFile()
        val indexDir = createTempDirectory("andy-host-save-index").toFile()
        val file = root.resolve("notes.txt").apply { writeText("before") }
        val service = DesktopHostFileService(indexDir = indexDir)
        withTimeout(5_000) {
            service.indexRoot(root.absolutePath).first { !it.indexing && it.indexedFiles == 1 }
        }
        val document = service.read(file.absolutePath)

        Thread.sleep(5)
        file.writeText("outside")
        val conflict = service.save(file.absolutePath, "inside", document.modifiedMillis)
        assertIs<HostFileSaveResult.Conflict>(conflict)

        val saved = service.save(file.absolutePath, "inside searchable", 0L)
        assertIs<HostFileSaveResult.Saved>(saved)
        assertEquals("inside searchable", file.readText())

        val results = service.search("searchable", HostSearchMode.Content, listOf(root.absolutePath), 10)
        assertEquals(1, results.size)
        assertEquals(file.absolutePath, results.single().path)
    }

    @Test
    fun saveOnlyWritesRequestedPath() = runBlocking {
        val root = createTempDirectory("andy-host-save-target-root").toFile()
        val readme = root.resolve("README.md").apply { writeText("readme before") }
        val source = root.resolve("src/Main.kt").apply {
            parentFile.mkdirs()
            writeText("source before")
        }
        val service = DesktopHostFileService(indexDir = createTempDirectory("andy-host-save-target-index").toFile())
        val readmeDocument = service.read(readme.absolutePath)
        val result = service.save(readmeDocument.path, "readme after", readmeDocument.modifiedMillis)

        assertIs<HostFileSaveResult.Saved>(result)
        assertEquals("readme after", readme.readText())
        assertEquals("source before", source.readText())
    }

    @Test
    fun watcherRemovesDeletedDirectoryDescendantsFromIndex() = runBlocking {
        val root = createTempDirectory("andy-host-delete-root").toFile()
        val indexDir = createTempDirectory("andy-host-delete-index").toFile()
        val deletedDir = root.resolve("gone").apply { mkdirs() }
        deletedDir.resolve("stale.txt").writeText("stale searchable content")
        val service = DesktopHostFileService(indexDir = indexDir)
        val collector = launch { service.indexRoot(root.absolutePath).collect {} }
        try {
            withTimeout(5_000) {
                while (service.search("stale", HostSearchMode.Content, listOf(root.absolutePath), 10).isEmpty()) {
                    delay(50)
                }
            }

            deletedDir.deleteRecursively()

            withTimeout(5_000) {
                while (service.search("stale", HostSearchMode.Content, listOf(root.absolutePath), 10).isNotEmpty()) {
                    delay(50)
                }
            }
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun listsDirectoriesBeforeFiles() = runBlocking {
        val root = createTempDirectory("andy-host-list-root").toFile()
        root.resolve("z-file.txt").writeText("z")
        root.resolve("a-dir").mkdirs()

        val service = DesktopHostFileService(indexDir = createTempDirectory("andy-host-list-index").toFile())
        val rows = service.list(root.absolutePath)

        assertEquals(listOf("a-dir", "z-file.txt"), rows.map { it.name })
        assertTrue(rows.first().isDirectory)
    }
}
