#!/usr/bin/env node
/**
 * Generic webhook notifier for Andy plugins.
 * Configure WEBHOOK_URL in $ANDY_PLUGIN_CONFIG_DIR/.env
 */
import { readFileSync, appendFileSync, mkdirSync, existsSync } from "node:fs";
import { join } from "node:path";

const configDir = process.env.ANDY_PLUGIN_CONFIG_DIR || ".";
const stateDir = process.env.ANDY_PLUGIN_STATE_DIR || ".";
mkdirSync(stateDir, { recursive: true });

function loadEnv() {
  const envPath = join(configDir, ".env");
  if (!existsSync(envPath)) return {};
  const out = {};
  for (const line of readFileSync(envPath, "utf8").split("\n")) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#")) continue;
    const i = trimmed.indexOf("=");
    if (i <= 0) continue;
    out[trimmed.slice(0, i).trim()] = trimmed.slice(i + 1).trim();
  }
  return out;
}

function parseEvent() {
  const raw = process.env.ANDY_PLUGIN_EVENT_JSON;
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

async function post(url, body) {
  const res = await fetch(url, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  const text = await res.text();
  return { status: res.status, text };
}

const env = loadEnv();
const webhookUrl = env.WEBHOOK_URL || process.env.WEBHOOK_URL;
const isTest = process.argv.includes("--test");
const event = parseEvent();
const status = event?.data?.agent_status || event?.data?.agentStatus;
const shouldSend =
  isTest || status === "done" || status === "blocked";

if (!shouldSend) {
  process.exit(0);
}

if (!webhookUrl) {
  console.error(
    "WEBHOOK_URL not set. Add it to",
    join(configDir, ".env"),
  );
  process.exit(isTest ? 1 : 0);
}

const payload = isTest
  ? {
      source: "andy-plugin",
      plugin: process.env.ANDY_PLUGIN_ID,
      test: true,
      at: new Date().toISOString(),
    }
  : {
      source: "andy-plugin",
      plugin: process.env.ANDY_PLUGIN_ID,
      event: event?.event || process.env.ANDY_PLUGIN_EVENT,
      data: event?.data || {},
      context: process.env.ANDY_PLUGIN_CONTEXT_JSON
        ? JSON.parse(process.env.ANDY_PLUGIN_CONTEXT_JSON)
        : {},
      at: new Date().toISOString(),
    };

try {
  const result = await post(webhookUrl, payload);
  const line = `${new Date().toISOString()} status=${result.status} event=${payload.event || "test"}\n`;
  appendFileSync(join(stateDir, "deliveries.log"), line);
  console.log(line.trim());
  process.exit(result.status >= 200 && result.status < 300 ? 0 : 1);
} catch (err) {
  console.error(String(err));
  process.exit(1);
}
