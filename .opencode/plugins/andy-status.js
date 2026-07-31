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
