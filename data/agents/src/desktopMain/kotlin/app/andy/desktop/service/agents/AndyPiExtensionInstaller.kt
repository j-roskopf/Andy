package app.andy.desktop.service.agents

import java.io.File

/**
 * Installs the Andy Pi extension to `~/.andy/pi/andy-extension.ts`.
 *
 * The Pi adapter loads it with `-e` so status hooks and ANDY_MCP_URL are available
 * without mutating the user's `~/.pi/agent/extensions/` tree.
 */
object AndyPiExtensionInstaller {
    const val EXTENSION_NAME = "andy-extension.ts"
    const val MCP_URL_ENV = "ANDY_MCP_URL"

    /**
     * Canonical extension body. Keep in sync with [scripts/pi-andy-extension.ts].
     */
    val extensionContent: String =
        """
        /**
         * Andy-managed Pi extension — status hooks + optional MCP URL injection.
         *
         * Installed to ~/.andy/pi/andy-extension.ts by the Andy desktop app / andyd.
         * Loaded via `pi -e ~/.andy/pi/andy-extension.ts`.
         *
         * Environment:
         *   ANDY_TASK_ID   — active Andy task (preferred over .andy/active-task)
         *   ANDY_MCP_URL   — Andy MCP HTTP endpoint when attach is enabled
         */
        import { execFileSync } from "node:child_process";
        import { homedir } from "node:os";
        import { join } from "node:path";

        type PiApi = {
          on: (event: string, handler: (...args: any[]) => any) => void;
        };

        const HOOK = join(homedir(), ".andy", "bin", "andy-status-hook.sh");

        function runStatus(status: "working" | "done" | "blocked" | "error"): void {
          try {
            execFileSync(HOOK, [status], {
              stdio: ["ignore", "ignore", "ignore"],
              env: process.env,
              timeout: 5_000,
            });
          } catch {
            // Hooks are best-effort artifacts; screen scrape remains badge authority.
          }
        }

        export default function (pi: PiApi) {
          pi.on("session_start", async () => {
            runStatus("working");
          });

          pi.on("before_agent_start", async () => {
            runStatus("working");
          });

          pi.on("agent_end", async () => {
            runStatus("done");
          });

          pi.on("turn_end", async () => {
            runStatus("done");
          });

          pi.on("session_shutdown", async () => {
            runStatus("done");
          });

          pi.on("tool_call", async (event: { toolName?: string }) => {
            const name = (event?.toolName || "").toLowerCase();
            if (name.includes("ask") || name.includes("permission") || name.includes("confirm")) {
              runStatus("blocked");
            }
          });
        }
        """.trimIndent() + "\n"

    fun extensionPath(home: File = File(System.getProperty("user.home"))): File =
        File(home, ".andy/pi/$EXTENSION_NAME")

    fun ensureInstalled(home: File = File(System.getProperty("user.home"))): File {
        AndyStatusHookInstaller.ensureInstalled(home)
        val dest = extensionPath(home)
        dest.parentFile?.mkdirs()
        val existing = dest.takeIf { it.isFile }?.readText()
        if (existing != extensionContent) {
            dest.writeText(extensionContent)
        }
        return dest
    }
}
