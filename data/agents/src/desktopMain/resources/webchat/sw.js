const CACHE = "andy-webchat-v11";
const OFFLINE_ASSETS = [
  "/manifest.json",
  "/icons/icon-192.png",
  "/icons/icon-512.png",
];

const SHELL_PATHS = new Set(["/", "/index.html", "/styles.css", "/app.js"]);

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(CACHE).then((cache) => cache.addAll(OFFLINE_ASSETS)).then(() => self.skipWaiting()),
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))),
    ).then(() => self.clients.claim()),
  );
});

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);
  if (url.pathname.startsWith("/api/") || url.pathname.startsWith("/ws/")) {
    return;
  }
  if (SHELL_PATHS.has(url.pathname)) {
    // Always use the network for shell assets so HTML/CSS/JS stay in sync.
    event.respondWith(fetch(event.request));
    return;
  }
  event.respondWith(
    caches.match(event.request).then((cached) => cached || fetch(event.request)),
  );
});

self.addEventListener("push", (event) => {
  let data = { title: "Andy", body: "Andy needs your input", url: "/" };
  try {
    if (event.data) data = Object.assign(data, event.data.json());
  } catch (_) {
    try {
      data.body = event.data.text();
    } catch (_) {}
  }
  const taskId = data.taskId;
  const targetUrl = data.url || (taskId ? `/#/chat/${taskId}` : "/");
  event.waitUntil(
    self.registration.showNotification(data.title || "Andy", {
      body: data.body || "Andy needs your input",
      icon: "/icons/icon-192.png",
      badge: "/icons/icon-192.png",
      data: { url: targetUrl, taskId },
    }),
  );
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const target = (event.notification.data && event.notification.data.url) || "/";
  event.waitUntil(
    clients.matchAll({ type: "window", includeUncontrolled: true }).then((list) => {
      for (const client of list) {
        if ("focus" in client) {
          client.navigate(target);
          return client.focus();
        }
      }
      if (clients.openWindow) return clients.openWindow(target);
    }),
  );
});
