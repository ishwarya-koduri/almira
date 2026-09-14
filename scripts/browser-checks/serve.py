#!/usr/bin/env python3
"""
Serves the web client's static files and these checks, for a browser to run.

    python3 scripts/browser-checks/serve.py        # then open
    http://127.0.0.1:8766/checks/on-device.html

The checks need what jsc does not have — WebCrypto, IndexedDB, workers,
WebAssembly, a canvas — so they run in a real browser against the files the app
serves. Nothing is fetched from anywhere but this server. The page ends with
"All on-device checks passed." or lists what failed.
"""
import http.server
import os
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
STATIC = os.path.normpath(os.path.join(ROOT, "..", "..", "backend", "src", "main", "resources", "static"))


class Handler(http.server.SimpleHTTPRequestHandler):
    extensions_map = {**http.server.SimpleHTTPRequestHandler.extensions_map,
                      ".mjs": "text/javascript", ".wasm": "application/wasm"}

    def translate_path(self, path):
        path = path.split("?", 1)[0].split("#", 1)[0]
        if path.startswith("/checks/"):
            base, rest = ROOT, path[len("/checks/"):]
        else:
            base, rest = STATIC, path.lstrip("/")
        full = os.path.normpath(os.path.join(base, rest))
        return full if full.startswith(base) else base


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8766
    print(f"http://127.0.0.1:{port}/checks/on-device.html")
    http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
