package app.andy.desktop.service

import app.andy.desktop.service.agents.shellQuote
import app.andy.service.RemoteShellEndpoint
import java.io.File

/**
 * Builds an interactive SSH argv for project Terminal docks while remoted.
 *
 * Uses the existing ControlMaster (`ControlMaster=no`) so password askpass already
 * completed at connect time. Local cwd is the client home — never the remote project
 * path (that path usually does not exist locally and would fall through to
 * `~/.andy-tasks` via [app.andy.desktop.service.agents.AgentScratchWorkspace]).
 */
internal object RemoteProjectShell {
    data class Launch(
        val argv: List<String>,
        /** Working directory for the local `ssh` process (not the remote project). */
        val localCwd: String,
    )

    fun launch(
        endpoint: RemoteShellEndpoint,
        remoteCwd: String,
        env: Map<String, String> = emptyMap(),
    ): Launch {
        // SSH does not forward local env to the remote login shell; emit exports so
        // action/project overrides (API keys, build settings) reach the remote command.
        val exports = env.entries.joinToString("; ") { (key, value) ->
            "export ${shellQuote(key)}=${shellQuote(value)}"
        }
        val remoteCommand =
            buildString {
                if (exports.isNotEmpty()) {
                    append(exports)
                    append("; ")
                }
                append("cd ${shellQuote(remoteCwd)} 2>/dev/null || cd \"\$HOME\"; ")
                append("exec \"\${SHELL:-/bin/sh}\" -l")
            }
        val argv = buildList {
            add("ssh")
            add("-t")
            add("-o")
            add("StrictHostKeyChecking=yes")
            add("-o")
            add("ForwardAgent=no")
            add("-o")
            add("ControlPath=${endpoint.controlPath}")
            add("-o")
            add("ControlMaster=no")
            add(endpoint.sshTarget)
            add(remoteCommand)
        }
        return Launch(argv = argv, localCwd = localClientCwd())
    }

    private fun localClientCwd(): String {
        val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() }
        if (home != null && File(home).isDirectory) return home
        return "/"
    }
}
