package app.andy.desktop.service.agents

import java.io.File

/**
 * Installs the Andy OpenCode status plugin into a project's `.opencode/plugins/`.
 *
 * Canonical source is packaged here (and in [scripts/opencode-andy-status.js]) so
 * runtime does not depend on the repo checkout.
 *
 * Badge authority remains screen scrape — this plugin only writes status.json artifacts.
 */
object AndyOpenCodePluginInstaller {
    const val PLUGIN_NAME = "andy-status.js"
    private const val MARKER = "andy-status-hook"

    val pluginContent: String =
        """
        /**
         * Andy-managed OpenCode plugin — writes lifecycle status via andy-status-hook.sh.
         *
         * Installed into project `.opencode/plugins/andy-status.js` by Andy on session start.
         *
         * IMPORTANT: Andy UI badges are driven by terminal screen scrape, not this plugin.
         * Hooks only write optional `.andy/<taskId>/status.json` artifacts for MCP/debug.
         *
         * Marker string "andy-status-hook" lets Andy replace prior installs without
         * clobbering other user plugins.
         */
        import { execFileSync } from "node:child_process";
        import { homedir } from "node:os";
        import { join } from "node:path";

        const HOOK = join(homedir(), ".andy", "bin", "andy-status-hook.sh");

        function runStatus(status) {
          try {
            execFileSync(HOOK, [status], {
              stdio: ["ignore", "ignore", "ignore"],
              env: process.env,
              timeout: 5_000,
            });
          } catch {
            // best-effort
          }
        }

        export default async function andyStatusPlugin() {
          return {
            event: async ({ event }) => {
              const type = String(event?.type || "");
              if (type === "session.idle") {
                runStatus("done");
                return;
              }
              if (type === "permission.asked") {
                runStatus("blocked");
                return;
              }
              if (
                type === "session.created" ||
                type === "session.status" ||
                type === "message.updated" ||
                type === "tool.execute.before"
              ) {
                if (type === "session.status") {
                  const status = String(event?.properties?.status || event?.status || "").toLowerCase();
                  if (status.includes("idle") || status === "done") {
                    runStatus("done");
                    return;
                  }
                }
                runStatus("working");
              }
            },
            "tool.execute.before": async () => {
              runStatus("working");
            },
            "tool.execute.after": async () => {
              // leave working; session.idle marks done
            },
          };
        }
        """.trimIndent() + "\n"

    fun pluginFile(worktreeOrCwd: File): File =
        File(worktreeOrCwd, ".opencode/plugins/$PLUGIN_NAME")

    fun ensureInstalled(worktreeOrCwd: File): File? {
        AndyStatusHookInstaller.ensureInstalled()
        if (shouldSkip(worktreeOrCwd)) return null
        val dest = pluginFile(worktreeOrCwd)
        dest.parentFile?.mkdirs()
        val existing = dest.takeIf { it.isFile }?.readText()
        if (existing == null || existing.contains(MARKER) || existing != pluginContent) {
            dest.writeText(pluginContent)
        }
        return dest
    }

    private fun shouldSkip(worktreeOrCwd: File): Boolean {
        val home = File(System.getProperty("user.home")).absoluteFile.normalize()
        val cwd = worktreeOrCwd.absoluteFile.normalize()
        return cwd == home
    }
}
