// InstaDown service worker — offline shell + share-target receiver.
//
// We cache the static shell so the PWA can launch even when the
// network is slow. API calls always go to the network (no cache) so
// downloads always reflect the latest server state.

const CACHE = "instadown-shell-v1";
const SHELL = [
  "/",
  "/static/style.css",
  "/static/app.js",
  "/static/manifest.webmanifest",
  "/static/icon.svg",
];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting())
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);

  // Never cache API responses.
  if (url.pathname.startsWith("/api/")) return;

  // Network-first for navigation, cache fallback.
  if (event.request.mode === "navigate") {
    event.respondWith(
      fetch(event.request).catch(() => caches.match("/"))
    );
    return;
  }

  // Cache-first for static shell.
  if (url.pathname.startsWith("/static/")) {
    event.respondWith(
      caches.match(event.request).then((hit) => hit || fetch(event.request))
    );
  }
});

// Share-target POST: PWA manifest says POST with form data containing
// the shared URL. We forward it to the open client (the page) so the
// page can run the download. If no client is open, we open the app.
self.addEventListener("fetch", (event) => {
  if (event.request.method === "POST" && event.request.url.includes("source=share")) {
    event.respondWith(
      (async () => {
        const form = await event.request.formData();
        const url = form.get("url") || extractInstagramUrl(form.get("text") || "");
        const clients = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
        if (clients.length > 0) {
          clients[0].postMessage({ type: "share", url });
          return Response.redirect("/", 303);
        }
        return Response.redirect("/?shared=" + encodeURIComponent(url), 303);
      })()
    );
  }
});

function extractInstagramUrl(text) {
  const m = String(text).match(/https?:\/\/(?:www\.)?instagram\.com\/[^\s]+/i);
  return m ? m[0] : "";
}
