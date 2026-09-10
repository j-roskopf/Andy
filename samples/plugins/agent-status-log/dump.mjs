#!/usr/bin/env node
import { readFileSync, existsSync } from "node:fs";
import { join } from "node:path";

const logPath = join(process.env.ANDY_PLUGIN_STATE_DIR || ".", "status.log");
if (!existsSync(logPath)) {
  console.log("(empty — start an agent chat in Andy first)");
  process.exit(0);
}
console.log(readFileSync(logPath, "utf8").trimEnd().split("\n").slice(-40).join("\n"));
console.log(`\nfull log: ${logPath}`);
