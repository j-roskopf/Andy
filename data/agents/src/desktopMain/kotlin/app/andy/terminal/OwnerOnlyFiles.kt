package app.andy.terminal

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/** Write [content] to [file] with owner-only permissions (0600 file, 0700 parent dirs). */
internal fun writeOwnerOnlyText(file: File, content: String) {
    val parent = file.parentFile
    if (parent != null) {
        parent.mkdirs()
        restrictToOwner(parent, executable = true)
    }
    file.writeText(content, StandardCharsets.UTF_8)
    restrictToOwner(file, executable = false)
}

internal fun restrictToOwner(file: File, executable: Boolean) {
    if (!file.exists()) return
    runCatching {
        val perms = mutableSetOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
        if (executable || file.isDirectory) perms += PosixFilePermission.OWNER_EXECUTE
        Files.setPosixFilePermissions(file.toPath(), perms)
    }
}
