#!/usr/bin/env node
import { join } from "node:path";

const stateDir = process.env.ANDY_PLUGIN_STATE_DIR || ".";
const logPath = join(stateDir, "activity.log");
console.log(logPath);
console.log("");
console.log("Tail from a shell:");
console.log(`  tail -f ${logPath}`);
console.log("");
console.log("Or open the Lifecycle activity pane from Settings → Plugins.");
