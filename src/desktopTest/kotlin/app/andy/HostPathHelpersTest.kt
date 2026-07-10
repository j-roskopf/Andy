package app.andy

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostPathHelpersTest {
    @Test
    fun uniqueLocalPathAutoRenamesConflicts() {
        val dir = File.createTempFile("andy-downloads", null).also {
            it.delete()
            it.mkdirs()
        }
        try {
            File(dir, "report.txt").writeText("one")
            val first = uniqueLocalPath(dir.absolutePath, "report.txt")
            assertEquals(File(dir, "report (1).txt").absolutePath, first)
            File(first).writeText("two")
            val second = uniqueLocalPath(dir.absolutePath, "report.txt")
            assertEquals(File(dir, "report (2).txt").absolutePath, second)
            assertFalse(File(dir, "report.txt").absolutePath == first)
            assertTrue(File(dir, "fresh.bin").let { uniqueLocalPath(dir.absolutePath, "fresh.bin") == it.absolutePath })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun downloadsDirectoryIsWritable() {
        val path = downloadsDirectory()
        val dir = File(path)
        assertTrue(dir.isDirectory || dir.mkdirs())
    }
}
