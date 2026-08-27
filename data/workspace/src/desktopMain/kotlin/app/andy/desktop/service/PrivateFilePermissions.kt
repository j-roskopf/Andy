package app.andy.desktop.service

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet

/** Best-effort owner-only permissions for secrets under `~/.andy/`. */
internal fun restrictPrivateFilePermissions(file: File) {
    if (!file.exists()) return
    val os = System.getProperty("os.name").orEmpty().lowercase()
    if (os.contains("windows")) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
        return
    }
    runCatching {
        val perms = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
        Files.setPosixFilePermissions(file.toPath(), perms)
    }.onFailure {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }
}
