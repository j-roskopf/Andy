#!/usr/bin/env node
/**
 * Append one structured line to $ANDY_PLUGIN_STATE_DIR/activity.log
 * Usage: node log.mjs <kind> [label]
 *   kind = startup | event | action
 */
import { appendFileSync, mkdirSync } from "node:fs";
import { join } from "node:path";

const stateDir = process.env.ANDY_PLUGIN_STATE_DIR || ".";
mkdirSync(stateDir, { recursive: true });
const logPath = join(stateDir, "activity.log");

const kind = process.argv[2] || "unknown";
const label = process.argv[3] || "";

let event = process.env.ANDY_PLUGIN_EVENT || null;
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

let context = null;
try {
  if (process.env.ANDY_PLUGIN_CONTEXT_JSON) {
    context = JSON.parse(process.env.ANDY_PLUGIN_CONTEXT_JSON);
  }
} catch {
  /* ignore */
}

const line = {
  at: new Date().toISOString(),
  kind,
  label: label || undefined,
  event,
  data,
  workspaceId: process.env.ANDY_WORKSPACE_ID || context?.workspaceId || null,
  tabId: process.env.ANDY_TAB_ID || context?.tabId || null,
  paneId: process.env.ANDY_PANE_ID || context?.focusedPaneId || null,
  actionId: process.env.ANDY_PLUGIN_ACTION_ID || null,
  via: process.env.ANDY_BIN_PATH ? "andy-host" : "manual",
};

appendFileSync(logPath, `${JSON.stringify(line)}\n`);
console.log(`logged → ${logPath}`);
console.log(JSON.stringify(line, null, 2));
