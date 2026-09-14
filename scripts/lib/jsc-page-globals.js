/* The few page globals the web client's modules touch while loading, for jsc.
   Imported first, so it runs before the modules that need them. */
if (typeof globalThis.TextEncoder === "undefined") {
  globalThis.TextEncoder = class { encode(s) { return Uint8Array.from(unescape(encodeURIComponent(String(s))), (c) => c.charCodeAt(0)); } };
  globalThis.TextDecoder = class { decode(b) { return decodeURIComponent(escape(String.fromCharCode(...new Uint8Array(b)))); } };
}
if (typeof globalThis.localStorage === "undefined") {
  const map = new Map();
  globalThis.localStorage = {
    getItem: (key) => (map.has(key) ? map.get(key) : null),
    setItem: (key, value) => map.set(key, String(value)),
    removeItem: (key) => map.delete(key),
    key: (i) => [...map.keys()][i] ?? null,
    get length() { return map.size; },
  };
}
