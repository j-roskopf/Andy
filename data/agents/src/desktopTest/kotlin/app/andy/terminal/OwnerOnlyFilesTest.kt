package app.andy.terminal

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OwnerOnlyFilesTest {
    @Test
    fun writeOwnerOnlyTextRestrictsFileAndParent() {
        if (!FileSystemsSupportsPosix()) return

        val root = File.createTempFile("andy-owner-only", null).also { it.delete(); it.mkdirs() }
        try {
            val file = File(root, "nested/secret.sh")
            writeOwnerOnlyText(file, "export SECRET=1\n")
            assertTrue(file.isFile)
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file.toPath()),
            )
            assertEquals(
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
                Files.getPosixFilePermissions(file.parentFile.toPath()),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun FileSystemsSupportsPosix(): Boolean =
        runCatching {
            Files.getPosixFilePermissions(File.createTempFile("posix-probe", null).toPath())
            true
        }.getOrDefault(false)
}
