#!/usr/bin/env node
import { appendFileSync, mkdirSync } from "node:fs";
import { join } from "node:path";

const stateDir = process.env.ANDY_PLUGIN_STATE_DIR || ".";
mkdirSync(stateDir, { recursive: true });
const logPath = join(stateDir, "status.log");

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

const status = data.agent_status || data.agentStatus || "—";
const agent = data.agent || "—";
const pane = data.pane_id || "—";
const line = `${new Date().toISOString()}  ${event}  agent=${agent}  status=${status}  pane=${pane}\n`;
appendFileSync(logPath, line);
console.log(line.trim());
