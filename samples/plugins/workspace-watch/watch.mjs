#!/usr/bin/env node
import { appendFileSync, mkdirSync } from "node:fs";
import { join } from "node:path";

const stateDir = process.env.ANDY_PLUGIN_STATE_DIR || ".";
mkdirSync(stateDir, { recursive: true });
const logPath = join(stateDir, "watch.log");

let event = process.env.ANDY_PLUGIN_EVENT || "unknown";
let data = {};
try {
  const raw = process.env.ANDY_PLUGIN_EVENT_JSON;
  if (raw) {
    const parsed = JSON.parse(raw);
    event = parsed.event || event;
    data = parsed.data || {};
  }
} catch {
  /* ignore */
}

const line = `${new Date().toISOString()}  ${event}  ${JSON.stringify(data)}\n`;
appendFileSync(logPath, line);
console.log(line.trim());
