package app.andy.desktop.service

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitLocatorTest {
    @Test
    fun fromPathFindsGitInPathEntries() {
        val root = createTempDirectory(prefix = "andy-git-path-").toFile()
        try {
            val opt = File(root, "opt").also { it.mkdirs() }
            val usr = File(root, "usr-bin").also { it.mkdirs() }
            File(usr, "git").apply {
                writeText("")
                setExecutable(true)
            }
            val path = listOf(opt.path, usr.path).joinToString(File.pathSeparator)
            assertEquals(
                File(usr, "git").path,
                GitLocator.fromPath(path),
            )
            assertNull(GitLocator.fromPath(opt.path))
            assertNull(GitLocator.fromPath(null))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun fromPathResolvesWindowsGitExeSuffix() {
        val dir = createTempDirectory(prefix = "andy-git-locator-").toFile()
        try {
            val exe = File(dir, "git.exe").apply {
                writeText("")
                setExecutable(true)
            }
            assertEquals(
                exe.path,
                GitLocator.fromPath(dir.path, windows = true),
            )
            assertNull(GitLocator.fromPath(dir.path, windows = false))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun locatePrefersProcessPathBeforeKnownLocations() {
        val root = createTempDirectory(prefix = "andy-git-prefer-").toFile()
        try {
            val processDir = File(root, "process").also { it.mkdirs() }
            val known = File(root, "known").also { it.mkdirs() }
            val processGit = File(processDir, "git").apply {
                writeText("")
                setExecutable(true)
            }
            File(known, "git").apply {
                writeText("")
                setExecutable(true)
            }
            val resolved = GitLocator.locate(
                processPath = processDir.path,
                loginShellEnv = mapOf("PATH" to known.path),
                knownPaths = listOf(File(known, "git").path),
                runShell = { error("shell lookup must not run when PATH already resolves git") },
                osName = "Linux",
            )
            assertEquals(processGit.path, resolved)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun locateFallsBackToKnownLocationsWhenPathIsEmpty() {
        val root = createTempDirectory(prefix = "andy-git-known-").toFile()
        try {
            val knownGit = File(root, "git").apply {
                writeText("")
                setExecutable(true)
            }
            val resolved = GitLocator.locate(
                processPath = null,
                loginShellEnv = emptyMap(),
                knownPaths = listOf(knownGit.path),
                runShell = { error("shell lookup must not run when known path matches") },
                osName = "Linux",
            )
            assertEquals(knownGit.path, resolved)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun locateUsesLoginShellLookupAsLastResort() {
        val root = createTempDirectory(prefix = "andy-git-shell-").toFile()
        try {
            val shellGit = File(root, "git-from-shell").apply {
                writeText("")
                setExecutable(true)
            }
            val resolved = GitLocator.locate(
                processPath = null,
                loginShellEnv = emptyMap(),
                knownPaths = emptyList(),
                runShell = { _ -> "${shellGit.path}\n" },
                osName = "Linux",
            )
            assertEquals(shellGit.path, resolved)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun locateSkipsShellLookupOnWindows() {
        val resolved = GitLocator.locate(
            processPath = null,
            loginShellEnv = emptyMap(),
            knownPaths = emptyList(),
            runShell = { error("shell lookup must not run on Windows") },
            osName = "Windows 11",
        )
        assertNull(resolved)
    }

    @Test
    fun defaultKnownPathsIncludesWindowsGitInstalls() {
        val paths = GitLocator.defaultKnownPaths(osName = "Windows 11")
        assertTrue(paths.any { it.contains("Git") && it.endsWith("git.exe") })
    }
}
