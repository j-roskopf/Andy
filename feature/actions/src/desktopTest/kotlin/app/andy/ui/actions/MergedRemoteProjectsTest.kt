package app.andy.ui.actions

import app.andy.model.ActionProject
import app.andy.service.RemoteProjectScanStatus
import app.andy.service.RemoteTargetProjects
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MergedRemoteProjectsTest {
    private fun project(id: String, name: String = id, dir: String = "/Code/$id") =
        ActionProject(id = id, name = name, contextDir = dir)

    private val aliases = mapOf("joe@10.0.0.7" to "studio-mini")
    private val displayName: (String) -> String = { target -> aliases[target] ?: target }

    private val scanned = mapOf(
        "joe@10.0.0.7" to RemoteTargetProjects(
            target = "joe@10.0.0.7",
            status = RemoteProjectScanStatus.Ok,
            projects = listOf(project("site", "Site"), project("andy", "Andy")),
        ),
        "joe@builder" to RemoteTargetProjects(
            target = "joe@builder",
            status = RemoteProjectScanStatus.Ok,
            projects = listOf(project("tools", "Tools")),
        ),
    )

    @Test
    fun settingOffHidesEveryRemoteRow() {
        val rows = otherHostProjectRows(
            enabled = false,
            savedTargetProjects = scanned,
            activeTarget = null,
            displayNameFor = displayName,
        )

        assertTrue(rows.isEmpty())
    }

    @Test
    fun groupsByHostAliasThenSortsProjectsByName() {
        val rows = otherHostProjectRows(
            enabled = true,
            savedTargetProjects = scanned,
            activeTarget = null,
            displayNameFor = displayName,
        )

        // "joe@builder" sorts before the aliased "studio-mini"; names sort within each host.
        assertEquals(
            listOf("Tools" to "joe@builder", "Andy" to "studio-mini", "Site" to "studio-mini"),
            rows.map { it.project.name to it.hostLabel },
        )
    }

    @Test
    fun connectedHostIsNotDuplicatedAsARemoteRow() {
        val rows = otherHostProjectRows(
            enabled = true,
            savedTargetProjects = scanned,
            activeTarget = "joe@10.0.0.7",
            displayNameFor = displayName,
        )

        assertEquals(listOf("joe@builder"), rows.map { (it.host as ProjectHost.Ssh).target }.distinct())
    }

    @Test
    fun keyNamespacesProjectIdByHostSoCollisionsDoNotMerge() {
        val sameIdOnBothHosts = mapOf(
            "joe@a" to RemoteTargetProjects("joe@a", RemoteProjectScanStatus.Ok, listOf(project("andy"))),
            "joe@b" to RemoteTargetProjects("joe@b", RemoteProjectScanStatus.Ok, listOf(project("andy"))),
        )

        val rows = otherHostProjectRows(
            enabled = true,
            savedTargetProjects = sameIdOnBothHosts,
            activeTarget = null,
            displayNameFor = { it },
        )

        assertEquals(listOf("joe@a::andy", "joe@b::andy"), rows.map { it.key })
        assertEquals(2, rows.map { it.key }.toSet().size)
    }

    @Test
    fun unavailableHostKeepsItsRowsButMarksThemStale() {
        val asleep = mapOf(
            "joe@builder" to RemoteTargetProjects(
                target = "joe@builder",
                status = RemoteProjectScanStatus.Unavailable,
                projects = listOf(project("tools", "Tools")),
                error = "host is asleep",
            ),
        )

        val rows = otherHostProjectRows(
            enabled = true,
            savedTargetProjects = asleep,
            activeTarget = null,
            displayNameFor = displayName,
        )

        assertEquals(1, rows.size)
        assertTrue(rows.single().stale)
        assertEquals("host is asleep", rows.single().error)
    }

    @Test
    fun freshRowsAreNotStale() {
        val rows = otherHostProjectRows(
            enabled = true,
            savedTargetProjects = scanned,
            activeTarget = null,
            displayNameFor = displayName,
        )

        assertTrue(rows.none { it.stale })
    }

    @Test
    fun cachedRowsRenderWhileTheFirstScanIsStillRunning() {
        val cached = mapOf(
            "joe@builder" to RemoteTargetProjects(
                target = "joe@builder",
                status = RemoteProjectScanStatus.Cached,
                projects = listOf(project("tools", "Tools")),
            ),
        )

        val rows = otherHostProjectRows(
            enabled = true,
            savedTargetProjects = cached,
            activeTarget = null,
            displayNameFor = displayName,
        )

        assertEquals(listOf("Tools"), rows.map { it.project.name })
        assertFalse(rows.single().stale)
    }

    @Test
    fun onlyEmptyUnavailableHostsAreReportedSeparately() {
        val hosts = mapOf(
            "joe@empty" to RemoteTargetProjects(
                target = "joe@empty",
                status = RemoteProjectScanStatus.Unavailable,
                error = "Permission denied (publickey).",
            ),
            // Has cached rows of its own, so it is already visible in the list.
            "joe@stale" to RemoteTargetProjects(
                target = "joe@stale",
                status = RemoteProjectScanStatus.Unavailable,
                projects = listOf(project("tools", "Tools")),
            ),
            // Reachable with genuinely zero projects — nothing to report.
            "joe@blank" to RemoteTargetProjects("joe@blank", RemoteProjectScanStatus.Ok),
        )

        val reported = unreachableRemoteHosts(
            enabled = true,
            savedTargetProjects = hosts,
            activeTarget = null,
            displayNameFor = displayName,
        )

        assertEquals(listOf("joe@empty"), reported.map { it.target })
    }

    @Test
    fun whileRemotedThisComputersProjectsJoinTheListFirst() {
        val rows = otherHostProjectRows(
            enabled = true,
            savedTargetProjects = scanned,
            activeTarget = "joe@10.0.0.7",
            localProjects = listOf(project("home", "Home"), project("alpha", "Alpha")),
            displayNameFor = displayName,
        )

        assertEquals(
            listOf("Alpha", "Home", "Tools"),
            rows.map { it.project.name },
        )
        assertEquals(
            listOf(ProjectHost.Local, ProjectHost.Local, ProjectHost.Ssh("joe@builder")),
            rows.map { it.host },
        )
        assertEquals(listOf("local::alpha", "local::home"), rows.take(2).map { it.key })
    }

    @Test
    fun localProjectsAreNotDuplicatedWhileAndyIsLocal() {
        // Local projects already *are* the main list when no remote is attached.
        val rows = otherHostProjectRows(
            enabled = true,
            savedTargetProjects = emptyMap(),
            activeTarget = null,
            localProjects = listOf(project("home", "Home")),
            displayNameFor = displayName,
        )

        assertTrue(rows.isEmpty())
    }

    @Test
    fun unreachableHostReportingRespectsTheSettingAndActiveHost() {
        val hosts = mapOf(
            "joe@empty" to RemoteTargetProjects(
                target = "joe@empty",
                status = RemoteProjectScanStatus.Unavailable,
                error = "timed out",
            ),
        )

        assertTrue(
            unreachableRemoteHosts(false, hosts, activeTarget = null, displayNameFor = displayName).isEmpty(),
        )
        assertTrue(
            unreachableRemoteHosts(true, hosts, activeTarget = "joe@empty", displayNameFor = displayName).isEmpty(),
        )
    }
}
