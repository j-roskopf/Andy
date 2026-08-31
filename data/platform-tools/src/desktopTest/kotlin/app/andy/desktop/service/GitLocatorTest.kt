package app.andy.desktop.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitLocatorTest {
    @Test
    fun fromPathFindsGitInPathEntries() {
        assertEquals(
            "/usr/bin/git",
            GitLocator.fromPath("/opt/bin:/usr/bin:/home/me/.local/bin"),
        )
        assertNull(GitLocator.fromPath("/opt/bin:/home/me/.local/bin"))
        assertNull(GitLocator.fromPath(null))
    }

    @Test
    fun locatePrefersProcessPathBeforeKnownLocations() {
        val resolved = GitLocator.locate(
            processPath = "/usr/bin",
            loginShellEnv = mapOf("PATH" to "/opt/homebrew/bin"),
            knownPaths = listOf("/opt/homebrew/bin/git"),
            runShell = { error("shell lookup must not run when PATH already resolves git") },
        )
        assertEquals("/usr/bin/git", resolved)
    }

    @Test
    fun locateFallsBackToKnownLocationsWhenPathIsEmpty() {
        val resolved = GitLocator.locate(
            processPath = null,
            loginShellEnv = emptyMap(),
            knownPaths = listOf("/usr/bin/git"),
            runShell = { error("shell lookup must not run when known path matches") },
        )
        assertEquals("/usr/bin/git", resolved)
    }

    @Test
    fun locateUsesLoginShellLookupAsLastResort() {
        val resolved = GitLocator.locate(
            processPath = null,
            loginShellEnv = emptyMap(),
            knownPaths = emptyList(),
            runShell = { _ -> "/bin/sh\n" },
            osName = "Linux",
        )
        assertEquals("/bin/sh", resolved)
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
}
