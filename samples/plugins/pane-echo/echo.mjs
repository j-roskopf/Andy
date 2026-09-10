#!/usr/bin/env node
console.log(`Pane echo (${process.argv[2] || "unknown"})`);
console.log(`plugin=${process.env.ANDY_PLUGIN_ID}`);
console.log(`entrypoint=${process.env.ANDY_PLUGIN_ENTRYPOINT_ID}`);
console.log(`cwd=${process.cwd()}`);
console.log("Press Ctrl+C or close the dock tab to exit.");
setInterval(() => {}, 1 << 30);
