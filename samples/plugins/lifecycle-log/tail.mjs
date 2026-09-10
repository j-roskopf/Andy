#!/usr/bin/env node
/**
 * Live tail of activity.log inside an Andy Terminal dock pane.
 */
import { spawn } from "node:child_process";
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const stateDir = process.env.ANDY_PLUGIN_STATE_DIR || ".";
mkdirSync(stateDir, { recursive: true });
const logPath = join(stateDir, "activity.log");
if (!existsSync(logPath)) {
  writeFileSync(logPath, "");
}

console.log("Lifecycle Log — live activity");
console.log(`Watching ${logPath}`);
console.log("Switch projects, canvas tabs, or run agents; lines appear below.");
console.log("Close this dock tab to stop.\n");

const child = spawn("tail", ["-n", "50", "-f", logPath], {
  stdio: ["ignore", "inherit", "inherit"],
});
child.on("exit", (code) => process.exit(code ?? 0));

process.on("SIGINT", () => {
  child.kill("SIGTERM");
  process.exit(0);
});
