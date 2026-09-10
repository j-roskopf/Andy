#!/usr/bin/env node
import { spawn } from "node:child_process";
import { existsSync, mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const stateDir = process.env.ANDY_PLUGIN_STATE_DIR || ".";
mkdirSync(stateDir, { recursive: true });
const logPath = join(stateDir, "status.log");
if (!existsSync(logPath)) writeFileSync(logPath, "");

console.log("Agent Status Log");
console.log("Wire statuses: working | blocked | done | idle | unknown");
console.log(`Log: ${logPath}\n`);

const child = spawn("tail", ["-n", "40", "-f", logPath], {
  stdio: ["ignore", "inherit", "inherit"],
});
child.on("exit", (code) => process.exit(code ?? 0));
process.on("SIGINT", () => {
  child.kill("SIGTERM");
  process.exit(0);
});
