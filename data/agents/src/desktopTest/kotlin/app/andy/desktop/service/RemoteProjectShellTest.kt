package app.andy.desktop.service

import app.andy.service.RemoteShellEndpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteProjectShellTest {
    @Test
    fun buildsSshTtyOverExistingControlMaster() {
        val launch = RemoteProjectShell.launch(
            endpoint = RemoteShellEndpoint(
                sshTarget = "joer@other-mac.local",
                controlPath = "/tmp/andy-r1/abcd.ctrl",
            ),
            remoteCwd = "/Users/joer/Code/Phoebe",
        )

        assertEquals("ssh", launch.argv.first())
        assertTrue(launch.argv.contains("-t"))
        assertTrue(launch.argv.contains("ControlMaster=no"))
        assertTrue(launch.argv.contains("ControlPath=/tmp/andy-r1/abcd.ctrl"))
        assertEquals("joer@other-mac.local", launch.argv[launch.argv.lastIndex - 1])
        val remoteCommand = launch.argv.last()
        assertTrue(remoteCommand.contains("cd '/Users/joer/Code/Phoebe'"))
        assertTrue(remoteCommand.contains("exec"))
        // Never use the remote path as the local ssh process cwd.
        assertFalse(launch.localCwd.contains("Phoebe"))
        assertTrue(launch.localCwd == System.getProperty("user.home") || launch.localCwd == "/")
    }

    @Test
    fun quotesRemotePathsWithSpacesAndQuotes() {
        val launch = RemoteProjectShell.launch(
            endpoint = RemoteShellEndpoint("host", "/tmp/c"),
            remoteCwd = "/Users/joer/My Project's App",
        )
        assertTrue(launch.argv.last().contains("My Project'\\''s App"))
    }

    @Test
    fun exportsActionEnvIntoRemoteCommand() {
        val launch = RemoteProjectShell.launch(
            endpoint = RemoteShellEndpoint("host", "/tmp/c"),
            remoteCwd = "/Users/joer/Code/X",
            env = mapOf(
                "ANDY_API_KEY" to "sk-secret",
                "BUILD_FLAVOR" to "debug",
            ),
        )
        val remoteCommand = launch.argv.last()
        assertTrue(remoteCommand.contains("export 'ANDY_API_KEY'='sk-secret'"), remoteCommand)
        assertTrue(remoteCommand.contains("export 'BUILD_FLAVOR'='debug'"), remoteCommand)
        // Exports must precede the cd so the login shell starts with them set.
        assertTrue(remoteCommand.indexOf("export") < remoteCommand.indexOf("cd"), remoteCommand)
    }
}
