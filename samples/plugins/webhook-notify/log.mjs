#!/usr/bin/env node
import { existsSync, readFileSync, watchFile } from "node:fs";
import { join } from "node:path";

const stateDir = process.env.ANDY_PLUGIN_STATE_DIR || ".";
const logPath = join(stateDir, "deliveries.log");
console.log(`Watching ${logPath}`);
console.log("Deliveries appear here when pane.agent_status_changed fires.");

function dump() {
  if (!existsSync(logPath)) return;
  process.stdout.write(readFileSync(logPath, "utf8"));
}

dump();
watchFile(logPath, { interval: 1000 }, () => dump());

// Keep the pane alive until closed.
setInterval(() => {}, 1 << 30);
