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
  const mcpUrl = (process.env.ANDY_MCP_URL || "").trim();
  if (mcpUrl) {
    // Surface Andy MCP for the session. Pi has no native MCP; inject a short
    // reminder into the first agent turn via before_agent_start when useful.
    pi.on("session_start", async () => {
      // no-op marker — ANDY_MCP_URL is available to tools/skills the user adds.
    });
  }

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
