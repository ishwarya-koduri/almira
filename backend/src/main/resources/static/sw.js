/* =============================================================================
   Service worker — an app shell that opens without a network, and nothing more.

   The one rule that matters here is what is NOT cached: **no API response ever
   touches disk.** The Cache Storage API is persistent per-origin storage, so a
   cached /api response would leave a family's balance sheet readable on a
   shared laptop long after they signed out — with none of the protections the
   rest of the product spends its effort on. Offline data sync is a deliberate
   non-goal (docs/17); the shell opens, says it is offline, and waits.

   So:
     · static assets  — cache-first, because they are versioned by this file
     · navigations    — network-first, falling back to the cached shell
     · /api/**        — network-only, never stored, and a clean JSON error when
                        the network is not there, so the client renders its own
                        message instead of a browser error page
   ============================================================================= */

// Bump this when the shell changes. Old caches are removed on activate, so the
// version is the only bookkeeping.
const VERSION = "almira-v10";
const SHELL = `${VERSION}-shell`;

/**
 * Everything needed to draw the first screen with no network at all. Deliberately
 * small: the screens themselves are ES modules fetched on demand, and each is
 * cached the first time it is used.
 */
const SHELL_FILES = [
  "/",
  "/index.html",
  "/manifest.webmanifest",
  "/app/tokens.css",
  "/app/base.css",
  "/app/app.js",
  "/app/api.js",
  "/app/ui.js",
  "/app/state.js",
  "/app/i18n.js",
  "/app/screens/auth.js",
  "/icons/icon-192.png",
  "/icons/icon-512.png",
  "/icons/favicon.svg",
];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(SHELL).then((cache) =>
      // addAll fails the whole install if any one file 404s, which would leave
      // no worker at all. Each is added on its own so a renamed screen degrades
      // to "not precached" rather than "no offline support".
      Promise.all(SHELL_FILES.map((file) =>
        cache.add(new Request(file, { cache: "reload" })).catch(() => undefined))),
    ),
  );
  // Deliberately no skipWaiting: a new worker takes over on the next launch.
  // Swapping assets under a session that is mid-edit is a good way to serve a
  // screen its own stale module.
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(
        keys.filter((key) => key !== SHELL).map((key) => caches.delete(key)),
      ))
      .then(() => self.clients.claim()),
  );
});

const isApi = (url) =>
  url.pathname.startsWith("/api/") || url.pathname.startsWith("/actuator/");

const isStatic = (url) =>
  url.pathname.startsWith("/app/") ||
  url.pathname.startsWith("/icons/") ||
  url.pathname === "/manifest.webmanifest" ||
  url.pathname === "/favicon.ico";

self.addEventListener("fetch", (event) => {
  const { request } = event;
  if (request.method !== "GET") return;

  const url = new URL(request.url);
  // Another origin's problem: fonts, and anything else. Left to the browser.
  if (url.origin !== self.location.origin) return;

  if (isApi(url)) {
    event.respondWith(apiOnly(request));
    return;
  }

  if (request.mode === "navigate") {
    event.respondWith(shellFirstOnFailure(request));
    return;
  }

  if (isStatic(url)) {
    event.respondWith(cacheFirst(request));
  }
});

/**
 * Network only, and an honest failure. Nothing here is written to a cache, and
 * that is the point rather than an oversight.
 */
async function apiOnly(request) {
  try {
    return await fetch(request);
  } catch {
    return new Response(
      JSON.stringify({
        error: {
          code: "offline",
          message: "You're offline. Almira needs a connection to read your records.",
        },
      }),
      { status: 503, headers: { "Content-Type": "application/json" } },
    );
  }
}

/** The document, or the shell we already have. Either way, something opens. */
async function shellFirstOnFailure(request) {
  try {
    return await fetch(request);
  } catch {
    const cache = await caches.open(SHELL);
    return (await cache.match("/index.html")) ||
      (await cache.match("/")) ||
      new Response("Offline", { status: 503, headers: { "Content-Type": "text/plain" } });
  }
}

/**
 * Cache-first, then network, and store what came back. Assets are immutable
 * for the life of a cache version, so this is the cheap path and the update
 * story is the version bump above.
 */
async function cacheFirst(request) {
  const cache = await caches.open(SHELL);
  const hit = await cache.match(request);
  if (hit) return hit;

  try {
    const response = await fetch(request);
    if (response.ok && response.type === "basic") cache.put(request, response.clone());
    return response;
  } catch {
    return hit || Response.error();
  }
}
