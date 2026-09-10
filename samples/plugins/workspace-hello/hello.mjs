#!/usr/bin/env node
import { appendFileSync, mkdirSync } from "node:fs";
import { join } from "node:path";

const stateDir = process.env.ANDY_PLUGIN_STATE_DIR || ".";
mkdirSync(stateDir, { recursive: true });

if (process.argv.includes("--startup")) {
  appendFileSync(
    join(stateDir, "startup.log"),
    `${new Date().toISOString()} plugin loaded\n`,
  );
  console.log("startup ok");
  process.exit(0);
}

console.log(
  JSON.stringify(
    {
      pluginId: process.env.ANDY_PLUGIN_ID,
      event: process.env.ANDY_PLUGIN_EVENT || null,
      context: process.env.ANDY_PLUGIN_CONTEXT_JSON
        ? JSON.parse(process.env.ANDY_PLUGIN_CONTEXT_JSON)
        : null,
    },
    null,
    2,
  ),
);
