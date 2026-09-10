#!/usr/bin/env node
import { readFileSync, existsSync } from "node:fs";
import { join } from "node:path";

const logPath = join(process.env.ANDY_PLUGIN_STATE_DIR || ".", "watch.log");
if (!existsSync(logPath)) {
  console.log("(empty — switch or edit a project in Andy first)");
  process.exit(0);
}
const lines = readFileSync(logPath, "utf8").trimEnd().split("\n");
console.log(lines.slice(-30).join("\n"));
console.log("");
console.log(`full log: ${logPath}`);
