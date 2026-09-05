package app.andy.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalServerScanTest {
    @Test
    fun parseLsofTcpListenOutputGroupsAddresses() {
        val output = """
            p4242
            cvite
            n127.0.0.1:5173
            n*:5173
            p5555
            cnode
            n[::1]:3000
        """.trimIndent()

        val listeners = LocalServerScan.parseLsofTcpListenOutput(output)
        assertEquals(3, listeners.size)
        assertEquals(4242, listeners[0].pid)
        assertEquals("vite", listeners[0].command)
        assertEquals(5173, listeners[0].port)
        assertEquals(5555, listeners[2].pid)
        assertEquals(3000, listeners[2].port)
    }

    @Test
    fun parseLsofCwdOutputMapsPid() {
        val output = """
            p4242
            n/Users/joer/Code/app
            p5555
            n/tmp/other
        """.trimIndent()
        val cwd = LocalServerScan.parseLsofCwdOutput(output)
        assertEquals("/Users/joer/Code/app", cwd[4242])
        assertEquals("/tmp/other", cwd[5555])
    }

    @Test
    fun parsePsProcessTableExtractsAndyTaskId() {
        val output = " 4242  100 node /app ANDY_TASK_ID=task-abc vite --port 5173"
        val info = LocalServerScan.parsePsProcessTable(output)
        assertEquals(1, info.size)
        assertEquals(100, info[4242]?.ppid)
        assertEquals("task-abc", info[4242]?.andyTaskId)
    }

    @Test
    fun classifiesCommonLocalDevServerMatrix() {
        val cases = listOf(
            Triple("node", "/x/node_modules/vite/bin/vite.js", "Vite"),
            Triple("node", "next dev", "Next.js"),
            Triple("node", "nuxt dev", "Nuxt"),
            Triple("node", "astro dev", "Astro"),
            Triple("webpack", "", "Webpack"),
            Triple("node", "webpack", "Webpack"),
            Triple("node", "storybook dev -p 6006", "Storybook"),
            Triple("node", "remix vite:dev", "Remix"),
            Triple("node", "svelte-kit sync && vite dev", "Vite"),
            Triple("uvicorn", "main:app --reload --port 8000", "Uvicorn"),
            Triple("python", "-m http.server 8000", "Python"),
            Triple("python3", "manage.py runserver", "Django"),
            Triple("php", "artisan serve", "Laravel"),
            Triple("php", "-S 127.0.0.1:8080", "PHP"),
            Triple("ruby", "bin/rails server", "Rails"),
            Triple("go", "run ./cmd/api", "Go"),
            Triple("cargo", "run", "Cargo"),
            Triple("dotnet", "watch run", "Dotnet"),
            Triple("java", "org.springframework.boot.loader.JarLauncher", "Spring"),
            Triple("java", "io.ktor.server.netty.EngineMain", "Ktor"),
            Triple("npm", "run dev", "Dev Server"),
            Triple("pnpm", "run start", "Dev Server"),
            Triple("bun", "run preview", "Dev Server"),
        )
        for ((command, args, expected) in cases) {
            val ports = when {
                expected == "Storybook" -> listOf(6006)
                else -> listOf(8080)
            }
            val got = LocalServerScan.detectDevServerKind(
                LocalServerScan.DevServerCandidate(command, args, ports),
            )
            assertEquals(expected, got, "expected $expected for `$command $args`")
        }
        // Opaque node is not assumed from port alone — needs Andy ownership at build time.
        assertNull(
            LocalServerScan.detectDevServerKind(
                LocalServerScan.DevServerCandidate("node", "opaque-server.js", listOf(38471)),
            ),
        )
        assertNull(
            LocalServerScan.detectDevServerKind(
                LocalServerScan.DevServerCandidate("node", "opaque-server.js", listOf(3000)),
            ),
        )
        // Host tools (OpenClaw / OpenCode) are never project servers.
        assertTrue(
            LocalServerScan.isIgnoredLocalServerProcess(
                LocalServerScan.DevServerCandidate(
                    "node",
                    "openclaw gateway --port 18789",
                    listOf(18789),
                ),
            ),
        )
        assertTrue(
            LocalServerScan.isIgnoredLocalServerProcess(
                LocalServerScan.DevServerCandidate("opencode", "serve --port 49613", listOf(49613)),
            ),
        )
        assertFalse(
            LocalServerScan.isLikelyDevServerProcess(
                LocalServerScan.DevServerCandidate(
                    "node",
                    "openclaw gateway --port 18789",
                    listOf(18789),
                ),
            ),
        )
        // Language servers stay hidden even on common ports.
        assertTrue(
            LocalServerScan.isIgnoredLocalServerProcess(
                LocalServerScan.DevServerCandidate(
                    "node",
                    "typescript-language-server --stdio",
                    listOf(3000),
                ),
            ),
        )
        assertTrue(
            LocalServerScan.isIgnoredLocalServerProcess(
                LocalServerScan.DevServerCandidate("postgres", "", listOf(5432)),
            ),
        )
    }

    @Test
    fun buildDetectsWebpackWhenProcessRenamesArgv0() {
        val listeners = listOf(
            LocalServerScan.ParsedLsofListener(78051, "node", "*", 8080),
        )
        val processInfo = mapOf(
            78051 to LocalServerScan.ProcessInfo(
                ppid = 51419,
                commandLine = "webpack",
                rawCommandLine = "webpack",
            ),
            51419 to LocalServerScan.ProcessInfo(
                ppid = 1,
                commandLine = "GradleDaemon",
                rawCommandLine = "java org.gradle.launcher.daemon.bootstrap.GradleDaemon",
            ),
        )
        val cwd = mapOf(78051 to "/Users/joer/Code/Basil2/build/wasm/packages/Basil-composeApp")
        val owners = listOf(
            LocalServerOwnerIdentity(
                id = "run-9",
                title = "wasmJsBrowserDevelopmentRun",
                projectId = "proj-basil",
                cwd = "/Users/joer/Code/Basil2",
                rootPid = 999L, // not in ancestry — Gradle daemon adopted the child
                kind = LocalServerOwnerIdentity.Kind.Action,
            ),
        )
        val servers = LocalServerScan.buildLocalServerProcesses(listeners, processInfo, cwd, owners)
        assertEquals(1, servers.size)
        assertEquals("Webpack", servers.single().displayName)
        assertEquals(listOf(8080), servers.single().ports)
        assertEquals("run-9", servers.single().runId)
        assertEquals("Action: wasmJsBrowserDevelopmentRun", servers.single().ownerLabel)
    }

    @Test
    fun buildLocalServerProcessesFiltersAndAttributesByEnv() {
        val listeners = listOf(
            LocalServerScan.ParsedLsofListener(4242, "node", "127.0.0.1", 5173),
            LocalServerScan.ParsedLsofListener(90, "Chrome", "127.0.0.1", 9222),
        )
        val processInfo = mapOf(
            4242 to LocalServerScan.ProcessInfo(
                ppid = 10,
                commandLine = "node vite",
                rawCommandLine = "node /app/node_modules/vite/bin/vite.js ANDY_TASK_ID=task-1",
                andyTaskId = "task-1",
            ),
            90 to LocalServerScan.ProcessInfo(
                ppid = 1,
                commandLine = "Chrome Helper --type=renderer",
                rawCommandLine = "Chrome Helper --type=renderer",
            ),
        )
        val cwd = mapOf(4242 to "/Users/joer/Code/app")
        val owners = listOf(
            LocalServerOwnerIdentity(
                id = "task-1",
                title = "Start vite",
                projectId = "proj-a",
                cwd = "/Users/joer/Code/app",
            ),
        )
        val servers = LocalServerScan.buildLocalServerProcesses(listeners, processInfo, cwd, owners)
        assertEquals(1, servers.size)
        val server = servers.single()
        assertEquals(4242, server.pid)
        assertEquals(listOf(5173), server.ports)
        assertEquals("Vite", server.displayName)
        assertEquals("proj-a", server.projectId)
        assertEquals("localhost:5173", server.addressLabel)
        assertEquals("app", server.folderLabel)
        assertNull(server.ownerLabel)
        assertNull(server.runId)
        assertNull(server.actionName)
    }

    @Test
    fun buildIncludesAndyOwnedDescendantsEvenWithoutKeywordMatch() {
        val listeners = listOf(
            LocalServerScan.ParsedLsofListener(50, "node", "127.0.0.1", 4000),
        )
        val processInfo = mapOf(
            50 to LocalServerScan.ProcessInfo(ppid = 40, commandLine = "node app.js", rawCommandLine = "node app.js"),
            40 to LocalServerScan.ProcessInfo(ppid = 10, commandLine = "zsh", rawCommandLine = "zsh"),
        )
        val owners = listOf(
            LocalServerOwnerIdentity(
                id = "run-1",
                title = "Start API",
                projectId = "proj-a",
                cwd = "/Users/joer/Code/api",
                rootPid = 10L,
                kind = LocalServerOwnerIdentity.Kind.Action,
            ),
        )
        val servers = LocalServerScan.buildLocalServerProcesses(listeners, processInfo, emptyMap(), owners)
        assertEquals(1, servers.size)
        assertEquals("Node", servers.single().displayName)
        assertEquals("run-1", servers.single().runId)
        assertEquals("Start API", servers.single().actionName)
        assertEquals("Action: Start API", servers.single().ownerLabel)
    }

    @Test
    fun buildExcludesHostToolsEvenWhenCwdMatchesAndyProject() {
        val listeners = listOf(
            LocalServerScan.ParsedLsofListener(11, "node", "127.0.0.1", 18789),
            LocalServerScan.ParsedLsofListener(22, "opencode", "127.0.0.1", 49613),
            LocalServerScan.ParsedLsofListener(33, "node", "127.0.0.1", 5173),
        )
        val processInfo = mapOf(
            11 to LocalServerScan.ProcessInfo(
                ppid = 1,
                commandLine = "node openclaw gateway --port 18789",
                rawCommandLine = "node /Users/joer/.openclaw/openclaw gateway --port 18789",
            ),
            22 to LocalServerScan.ProcessInfo(
                ppid = 1,
                commandLine = "opencode serve --port 49613",
                rawCommandLine = "opencode serve --port 49613",
            ),
            33 to LocalServerScan.ProcessInfo(
                ppid = 1,
                commandLine = "node vite",
                rawCommandLine = "node /Users/joer/Code/Andy/Andy/node_modules/vite/bin/vite.js",
                andyTaskId = "task-chat",
            ),
        )
        val cwd = mapOf(
            11 to "/Users/joer/.openclaw",
            22 to "/Users/joer/Code/Andy/Andy",
            33 to "/Users/joer/Code/Andy/Andy",
        )
        val owners = listOf(
            LocalServerOwnerIdentity(
                id = "task-chat",
                title = "right now the thinking orb doesn't animate any...",
                projectId = "proj-andy",
                cwd = "/Users/joer/Code/Andy/Andy",
            ),
        )
        val servers = LocalServerScan.buildLocalServerProcesses(listeners, processInfo, cwd, owners)
        assertEquals(1, servers.size)
        assertEquals("Vite", servers.single().displayName)
        // Chat ownership can keep a listener visible, but never surfaces as a chat link.
        assertNull(servers.single().ownerLabel)
        assertEquals("proj-andy", servers.single().projectId)
        assertTrue(servers.none { it.ports.contains(18789) || it.ports.contains(49613) })
    }

    @Test
    fun buildDoesNotLinkServersToChatsViaCwd() {
        val listeners = listOf(
            LocalServerScan.ParsedLsofListener(33, "node", "127.0.0.1", 5173),
        )
        val processInfo = mapOf(
            33 to LocalServerScan.ProcessInfo(
                ppid = 1,
                commandLine = "node vite",
                rawCommandLine = "node /Users/joer/Code/Andy/Andy/node_modules/vite/bin/vite.js",
            ),
        )
        val cwd = mapOf(33 to "/Users/joer/Code/Andy/Andy")
        val owners = listOf(
            LocalServerOwnerIdentity(
                id = "task-chat",
                title = "some chat",
                projectId = "proj-andy",
                cwd = "/Users/joer/Code/Andy/Andy",
            ),
        )
        val servers = LocalServerScan.buildLocalServerProcesses(listeners, processInfo, cwd, owners)
        assertTrue(servers.isEmpty())
    }

    @Test
    fun attributeToOwnerPrefersEnvThenRootPidThenCwd() {
        val owners = listOf(
            LocalServerOwnerIdentity("task-a", "A", cwd = "/work/a", rootPid = 100L),
            LocalServerOwnerIdentity("task-b", "B", worktreePath = "/work/b-wt", cwd = "/work/b"),
        )
        assertEquals(
            "task-a",
            LocalServerScan.attributeToOwner(9, 1, "/tmp", "task-a", owners)?.id,
        )
        assertEquals(
            "task-a",
            LocalServerScan.attributeToOwner(55, 100, "/tmp", null, owners)?.id,
        )
        assertEquals(
            "task-b",
            LocalServerScan.attributeToOwner(9, 1, "/work/b-wt/src", null, owners)?.id,
        )
        assertNull(LocalServerScan.attributeToOwner(9, 1, "/elsewhere", null, owners))
    }

    @Test
    fun workspaceRootWithinMatchesNestedPaths() {
        assertTrue(LocalServerScan.isWorkspaceRootWithin("/work/app/src", "/work/app"))
        assertTrue(LocalServerScan.isWorkspaceRootWithin("/work/app", "/work/app"))
        assertFalse(LocalServerScan.isWorkspaceRootWithin("/work/app-other", "/work/app"))
    }

    @Test
    fun stopRevalidationUsesListedSnapshotSemantics() {
        val servers = listOf(
            LocalServerProcess(pid = 11, ports = listOf(3000), displayName = "Next.js", isStoppable = true),
            LocalServerProcess(pid = 12, ports = listOf(5173), displayName = "Vite", isStoppable = false),
        )
        assertNotNull(servers.firstOrNull { it.pid == 11 && 3000 in it.ports })
        assertNull(servers.firstOrNull { it.pid == 11 && 9999 in it.ports })
        assertFalse(servers.first { it.pid == 12 }.isStoppable)
    }

    @Test
    fun browserUrlUsesHttpOnFirstPort() {
        val server = LocalServerProcess(pid = 11, ports = listOf(8080, 8081), displayName = "Webpack")
        assertEquals("http://localhost:8080", server.browserUrl)
        assertNull(LocalServerProcess(pid = 12, ports = emptyList(), displayName = "Unknown").browserUrl)
    }

    @Test
    fun parseLinuxLsofTcpListenOutput() {
        // Linux lsof -F often reports IPv6 dual-stack and bare command names.
        val output = """
            p2211
            cnode
            n*:5173
            p2211
            cnode
            n[::1]:5173
            p3344
            cjava
            n127.0.0.1:8080
        """.trimIndent()
        val listeners = LocalServerScan.parseLsofTcpListenOutput(output)
        assertEquals(3, listeners.size)
        assertEquals(2211, listeners[0].pid)
        assertEquals("node", listeners[0].command)
        assertEquals(5173, listeners[0].port)
        assertEquals(8080, listeners[2].port)
        assertEquals("java", listeners[2].command)
    }
}
