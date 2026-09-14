/* =============================================================================
   Service worker — an app shell that opens without a network, and nothing more.

   The one rule that matters here is what is NOT cached: **no API response ever
   touches disk** through this worker. The Cache Storage API is persistent per-origin storage, so a
   cached /api response would leave a family's balance sheet readable on a
   shared laptop long after they signed out — with none of the protections the
   rest of the product spends its effort on. Offline data sync is a deliberate
   non-goal (docs/17); the shell opens, says it is offline, and waits — or, on
   a device someone has asked to keep one, shows the family handbook from an
   encrypted copy the page itself keeps (app/offline-store.js, docs/16). That
   copy is never a cached response, and never passes through here.

   So:
     · static assets  — cache-first, because they are versioned by this file
     · navigations    — network-first, falling back to the cached shell
     · /api/**        — network-only, never stored, and a clean JSON error when
                        the network is not there, so the client renders its own
                        message instead of a browser error page
   ============================================================================= */

// The build appends a fingerprint of every other static file to this, so the
// served value is "almira-v34+<12 hex>" and changes whenever any shell asset
// does (backend/build.gradle.kts, known-issues 3). Bumping the number by hand
// is still allowed and no longer required. Old caches are removed on activate,
// so the version is the only bookkeeping.
const VERSION = "almira-v39";
const SHELL = `${VERSION}-shell`;

// The libraries under /app/vendor are pinned and their paths carry their
// versions, so they never change under a name. They get a cache of their own
// that a shell change does not throw away: several megabytes of OCR engine
// and model should not download again because a button's label changed. A new
// library version is a new path, and a new name here.
const VENDOR = "almira-vendor-pdfjs-6.3.289+tesseract-7.0.0";

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
  // api.js imports it: sign-out clears drafts kept on the device (X-83).
  "/app/drafts.js",
  // api.js imports it too: the last known views (X-38).
  "/app/cache.js",
  "/app/ui.js",
  "/app/format.js",
  "/app/prefs.js",
  "/app/state.js",
  "/app/i18n.js",
  "/app/screens/auth.js",
  "/app/auth-outcome.js",
  "/app/sign-in-security.js",
  // Without a connection the page draws the kept handbook (P-21), so what draws
  // it has to be here before the connection goes.
  "/app/offline.js",
  "/app/offline-store.js",
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
        keys.filter((key) => key !== SHELL && key !== VENDOR).map((key) => caches.delete(key)),
      ))
      .then(() => self.clients.claim()),
  );
});

const isApi = (url) =>
  url.pathname.startsWith("/api/") || url.pathname.startsWith("/actuator/");

const isVendor = (url) => url.pathname.startsWith("/app/vendor/");

const isStatic = (url) =>
  url.pathname.startsWith("/app/") ||
  url.pathname.startsWith("/icons/") ||
  url.pathname === "/manifest.webmanifest" ||
  url.pathname === "/favicon.ico";

self.addEventListener("fetch", (event) => {
  const { request } = event;
  if (request.method !== "GET") return;

  const url = new URL(request.url);
  // Another origin's problem, left to the browser. The fonts are served from
  // here now (P-36), so they are ordinary static assets below.
  if (url.origin !== self.location.origin) return;

  if (isApi(url)) {
    event.respondWith(apiOnly(request));
    return;
  }

  if (request.mode === "navigate") {
    event.respondWith(shellFirstOnFailure(request));
    return;
  }

  if (isVendor(url)) {
    event.respondWith(cacheFirst(request, VENDOR));
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
async function cacheFirst(request, name = SHELL) {
  const cache = await caches.open(name);
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
