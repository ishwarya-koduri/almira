/* =============================================================================
   A harness for static/sw.js, runnable without a browser.

   Registering a service worker needs a top-level browsing context, which the
   machine this was built on does not have — no Chrome, and an embedded preview
   pane is an iframe, where registration is refused. That leaves the part where
   bugs actually live untested: which request takes which path.
   So the worker is loaded here against a stub of the handful of browser objects
   it touches, and the routing is asserted directly.
   It proves the decisions, not the plumbing; a real device still has to confirm
   installability and the offline launch (docs/17).

       /System/Library/Frameworks/JavaScriptCore.framework/Versions/A/Helpers/jsc \
         scripts/check-service-worker.js
       # or: node scripts/check-service-worker.js
   ============================================================================= */

/* --- the smallest browser that will do ----------------------------------- */

const listeners = {};
let networkUp = true;
const store = new Map();          // cache name -> Map(url -> Response)

const ORIGIN = "http://localhost:8080";

globalThis.URL = class {
  constructor(url, base = ORIGIN) {
    // Relative URLs resolve against the worker's scope, exactly as they do in a
    // browser — the precache list is written that way.
    const absolute = /^https?:\/\//.test(url) ? url : base.replace(/\/$/, "") + url;
    const match = /^(https?:)\/\/([^/]+)(\/[^?#]*)?/.exec(absolute) || [];
    this.href = absolute;
    this.protocol = match[1] || "http:";
    this.host = match[2] || "";
    this.origin = `${this.protocol}//${this.host}`;
    this.pathname = match[3] || "/";
  }
};

globalThis.Request = class {
  constructor(url, options = {}) {
    this.url = new URL(typeof url === "string" ? url : url.url).href;
    this.method = options.method || "GET";
    this.mode = options.mode || "no-cors";
    this.cache = options.cache;
  }
};

globalThis.Response = class {
  constructor(body, init = {}) {
    this.body = body;
    this.status = init.status ?? 200;
    this.ok = this.status >= 200 && this.status < 300;
    this.type = init.type || "basic";
    this.headers = new Map(Object.entries(init.headers || {}));
    this.from = init.from;
  }
  clone() { return this; }
  static error() { return new Response(null, { status: 0 }); }
};

globalThis.fetch = async (request) => {
  if (!networkUp) throw new TypeError("Failed to fetch");
  const url = typeof request === "string" ? request : request.url;
  return new Response("network", { from: "network", headers: { url } });
};

globalThis.caches = {
  async open(name) {
    if (!store.has(name)) store.set(name, new Map());
    const entries = store.get(name);
    return {
      async add(request) {
        if (!networkUp) throw new TypeError("Failed to fetch");
        entries.set(new URL(typeof request === "string" ? request : request.url).pathname,
          new Response("precached", { from: "cache" }));
      },
      async put(request, response) {
        entries.set(new URL(typeof request === "string" ? request : request.url).pathname,
          new Response(response.body, { from: "cache" }));
      },
      async match(request) {
        return entries.get(new URL(typeof request === "string" ? request : request.url).pathname);
      },
      async keys() {
        return [...entries.keys()].map((pathname) => ({ url: `http://localhost:8080${pathname}` }));
      },
    };
  },
  async keys() { return [...store.keys()]; },
  async delete(name) { return store.delete(name); },
};

globalThis.self = {
  location: { origin: "http://localhost:8080" },
  addEventListener: (type, handler) => { listeners[type] = handler; },
  clients: { claim: async () => undefined },
};

/* --- load the worker ------------------------------------------------------ */

const source = (typeof readFile === "function")
  ? readFile("backend/src/main/resources/static/sw.js")
  : require("fs").readFileSync("backend/src/main/resources/static/sw.js", "utf8");
(0, eval)(source);

/* --- run its lifecycle ---------------------------------------------------- */

const waited = [];
const fire = (type, event) => listeners[type]({ ...event, waitUntil: (p) => waited.push(p) });

let failures = 0;
function check(label, actual, expected) {
  const ok = JSON.stringify(actual) === JSON.stringify(expected);
  if (!ok) failures++;
  print(`  ${ok ? "ok  " : "FAIL"} ${label}${ok ? "" : `\n       expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`}`);
}

async function respond(url, options) {
  let response;
  fire("fetch", {
    request: new Request(url, options),
    respondWith: (r) => { response = r; },
  });
  return response ? await response : undefined;
}

(async () => {
  fire("install", {});
  await Promise.all(waited);
  fire("activate", {});
  await Promise.all(waited);

  print("\nThe shell is precached");
  const cached = await (await caches.open((await caches.keys())[0])).keys();
  const paths = cached.map((r) => new URL(r.url).pathname);
  check("index.html is there", paths.includes("/index.html"), true);
  check("the entry module is there", paths.includes("/app/app.js"), true);
  check("nothing from /api is", paths.some((p) => p.startsWith("/api/")), false);

  print("\nOnline");
  check("an API call goes to the network",
    (await respond("http://localhost:8080/api/v1/me")).from, "network");
  check("a static asset comes from the cache",
    (await respond("http://localhost:8080/app/app.js")).from, "cache");
  check("a cross-origin request is left alone",
    await respond("https://fonts.googleapis.com/css2?family=Inter"), undefined);
  check("a POST is left alone",
    await respond("http://localhost:8080/api/v1/investments", { method: "POST" }), undefined);

  print("\nOffline");
  networkUp = false;
  const navigation = await respond("http://localhost:8080/", { mode: "navigate" });
  check("a navigation falls back to the shell", navigation.from, "cache");
  const api = await respond("http://localhost:8080/api/v1/me");
  check("an API call fails cleanly rather than hanging", api.status, 503);
  check("...and says it is an offline error", JSON.parse(api.body).error.code, "offline");

  print("\nPrivacy");
  networkUp = true;
  await respond("http://localhost:8080/api/v1/households");
  const afterApiCalls = await (await caches.open((await caches.keys())[0])).keys();
  check("no API response was ever written to disk",
    afterApiCalls.some((r) => new URL(r.url).pathname.startsWith("/api/")), false);

  print(failures === 0 ? "\nAll service-worker checks passed.\n"
                       : `\n${failures} service-worker checks failed.\n`);
  if (typeof quit === "function") quit(failures === 0 ? 0 : 1);
  else process.exit(failures === 0 ? 0 : 1);
})();
