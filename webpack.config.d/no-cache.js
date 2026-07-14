// Development rebuilds must be observable without asking the user to clear the
// browser cache. This configuration is only consumed by Kotlin's webpack tasks.
config.devServer = config.devServer || {};
config.devServer.headers = {
  ...(config.devServer.headers || {}),
  "Cache-Control": "no-store, no-cache, must-revalidate",
  "Pragma": "no-cache",
  "Expires": "0",
};
