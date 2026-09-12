package app.andy.desktop.service.remote

import app.andy.model.CachedRemoteProject
import app.andy.service.RemoteProjectScanStatus
import app.andy.service.RemoteTargetProjects
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val TwoProjectsToml = """
version = 1

[[projects]]
id = "andy"
name = "Andy"
contextDir = "/Users/joe/Code/Andy"

[[projects]]
id = "site"
name = "Site"
contextDir = "/Users/joe/Code/site"
"""

class RemoteProjectScannerTest {
    private fun scannerReturning(read: RemoteTomlRead) =
        RemoteProjectScanner { _, _ -> read }

    @Test
    fun parsesProjectsFromRemoteToml() {
        val result = scannerReturning(RemoteTomlRead(0, TwoProjectsToml, ""))
            .scan("joe@studio", controlPath = null)

        assertEquals(RemoteProjectScanStatus.Ok, result.status)
        assertEquals(listOf("Andy", "Site"), result.projects.map { it.name })
        assertEquals("/Users/joe/Code/Andy", result.projects.first().contextDir)
    }

    @Test
    fun missingConfigIsAnEmptyOkNotAFailure() {
        // The remote `if [ -f ... ]` guard prints nothing when the host has no actions.toml.
        val result = scannerReturning(RemoteTomlRead(0, "", ""))
            .scan("joe@studio", controlPath = null)

        assertEquals(RemoteProjectScanStatus.Ok, result.status)
        assertTrue(result.projects.isEmpty())
    }

    @Test
    fun sshFailureReportsFirstStderrLine() {
        val result = scannerReturning(
            RemoteTomlRead(255, "", "ssh: Could not resolve hostname studio\nlost connection\n"),
        ).scan("joe@studio", controlPath = null)

        assertEquals(RemoteProjectScanStatus.Unavailable, result.status)
        assertEquals("ssh: Could not resolve hostname studio", result.error)
    }

    @Test
    fun authFailureTellsTheUserHowToMakeScansWork() {
        val result = scannerReturning(
            RemoteTomlRead(255, "", "joe@studio: Permission denied (publickey,password).\n"),
        ).scan("joe@studio", controlPath = null)

        assertTrue(result.error.orEmpty().contains("Connect to this host once"), "got: ${result.error}")
    }

    @Test
    fun nonAuthFailureIsReportedWithoutTheConnectHint() {
        val result = scannerReturning(RemoteTomlRead(255, "", "ssh: connect to host studio port 22: No route to host\n"))
            .scan("joe@studio", controlPath = null)

        assertEquals("ssh: connect to host studio port 22: No route to host", result.error)
    }

    @Test
    fun unparseableTomlIsReportedRatherThanThrown() {
        val result = scannerReturning(RemoteTomlRead(0, "this is not toml {{{", ""))
            .scan("joe@studio", controlPath = null)

        assertEquals(RemoteProjectScanStatus.Unavailable, result.status)
        assertTrue(result.error.orEmpty().contains("invalid"), "got: ${result.error}")
    }

    @Test
    fun readCrashIsReportedRatherThanThrown() {
        val scanner = RemoteProjectScanner { _, _ -> error("ssh binary missing") }

        val result = scanner.scan("joe@studio", controlPath = null)

        assertEquals(RemoteProjectScanStatus.Unavailable, result.status)
        assertEquals("ssh binary missing", result.error)
    }

    @Test
    fun reusesControlMasterPathWhenGiven() {
        var seenControlPath: File? = null
        val scanner = RemoteProjectScanner { _, controlPath ->
            seenControlPath = controlPath
            RemoteTomlRead(0, "", "")
        }
        val mux = File("/tmp/andy-test-mux-scan")

        scanner.scan("joe@studio", controlPath = mux)

        assertEquals(mux, seenControlPath)
    }

    @Test
    fun seedFromCacheDropsTargetsTheUserRemoved() {
        val cache = mapOf(
            "joe@studio" to listOf(CachedRemoteProject("andy", "Andy", "/Code/Andy")),
            "joe@old-box" to listOf(CachedRemoteProject("legacy", "Legacy", "/Code/legacy")),
        )

        val seeded = RemoteProjectScanner.seedFromCache(cache, savedTargets = listOf("joe@studio"))

        assertEquals(setOf("joe@studio"), seeded.keys)
        assertEquals(RemoteProjectScanStatus.Cached, seeded.getValue("joe@studio").status)
        assertEquals(listOf("Andy"), seeded.getValue("joe@studio").projects.map { it.name })
    }

    @Test
    fun unavailableHostKeepsItsPreviouslyCachedProjects() {
        val previous = mapOf(
            "joe@studio" to listOf(CachedRemoteProject("andy", "Andy", "/Code/Andy")),
        )
        val scanned = mapOf(
            "joe@studio" to RemoteTargetProjects(
                target = "joe@studio",
                status = RemoteProjectScanStatus.Unavailable,
                error = "host is asleep",
            ),
        )

        val updated = RemoteProjectScanner.updatedCache(previous, scanned, listOf("joe@studio"))

        assertEquals(previous, updated)
    }

    @Test
    fun successfulScanReplacesCacheAndPrunesRemovedTargets() {
        val previous = mapOf(
            "joe@studio" to listOf(CachedRemoteProject("andy", "Andy", "/Code/Andy")),
            "joe@gone" to listOf(CachedRemoteProject("x", "X", "/x")),
        )
        val scanned = mapOf(
            "joe@studio" to RemoteProjectScanner { _, _ -> RemoteTomlRead(0, TwoProjectsToml, "") }
                .scan("joe@studio", null),
        )

        val updated = RemoteProjectScanner.updatedCache(previous, scanned, listOf("joe@studio"))

        assertEquals(setOf("joe@studio"), updated.keys)
        assertEquals(listOf("Andy", "Site"), updated.getValue("joe@studio").map { it.name })
    }
}
