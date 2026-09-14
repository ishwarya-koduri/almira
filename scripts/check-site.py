#!/usr/bin/env python3
"""Checks the public site (site/) against the promises it makes about itself.

    python3 scripts/check-site.py

Every page's footer says the site "sets no cookies, runs no scripts and loads
nothing from any other website". That sentence is only true while nobody adds a
tracker, a CDN font or an embed, and nothing a browser shows would warn anyone
if they did. So this reads every file and fails on:

  ISOLATION     a script, an inline handler, a frame, a form, or any URL that
                points off this origin; a page without the shared CSP.
  LINKS         a relative link or asset that does not exist.
  IDENTITY      a colour token that has drifted from static/app/tokens.css,
                a mark that is no longer the app's favicon (brand/render-icons.py
                writes the app's copy, not this one),
                a text size below 13px, faint ink used for text, or a page
                with other than exactly one primary action.
  FONTS         a font file without its licence, or whose bytes are not the
                ones SOURCE says were fetched (SHA256SUMS).
  DRAFTS        a Telugu or Hindi page that does not say, on the page, that it
                is an unreviewed draft, or that asks to be indexed.

Run by `scripts/dev.sh test`. Standard library only.
"""

from __future__ import annotations

import hashlib
import re
import sys
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SITE = ROOT / "site"
TOKENS = ROOT / "backend/src/main/resources/static/app/tokens.css"
FAVICON = ROOT / "backend/src/main/resources/static/icons/favicon.svg"
FAILURES: list[str] = []
CHECKS = 0

CSP = ("default-src 'none'; style-src 'self'; img-src 'self'; font-src 'self'; "
       "base-uri 'none'; form-action 'none'")
# The one link that leaves the site's own files: the host redirects it to the
# app (docs/17 §10), so it is same-origin by construction.
SIGN_IN = "/sign-in"
DRAFT_LANGS = {"te", "hi"}
# Tokens the site shares with the app. --brass and --mark-ground are the
# site's own: the app has no brass token yet, and the mark ground is the icon's.
SHARED_TOKENS = ["--canvas", "--surface", "--surface-sunken", "--ink", "--ink-muted",
                 "--hairline", "--accent", "--accent-ink", "--gold"]


def check(ok: bool, message: str) -> None:
    global CHECKS
    CHECKS += 1
    if not ok:
        FAILURES.append(message)


class Page(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.tags: list[tuple[str, dict[str, str]]] = []
        self.text: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        self.tags.append((tag, {k: (v or "") for k, v in attrs}))

    def handle_startendtag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        self.handle_starttag(tag, attrs)

    def handle_data(self, data: str) -> None:
        self.text.append(data)


def off_origin(url: str) -> bool:
    return bool(re.match(r"^\s*([a-z][a-z0-9+.-]*:|//)", url, re.I)) and not url.startswith("#")


def resolve(page: Path, url: str) -> Path | None:
    target = url.split("#", 1)[0].split("?", 1)[0]
    if not target:
        return None
    return (page.parent / target).resolve()


def tokens_in(css: str, block_pattern: str) -> dict[str, str]:
    match = re.search(block_pattern, css, re.S)
    if not match:
        return {}
    return {k: v.upper() for k, v in re.findall(r"(--[a-z-]+):\s*(#[0-9A-Fa-f]{6})", match.group(1))}


def check_pages() -> None:
    pages = sorted(SITE.rglob("*.html"))
    check(len(pages) > 0, "site/ has no pages")
    for page in pages:
        rel = page.relative_to(ROOT)
        raw = page.read_text(encoding="utf-8")
        parsed = Page()
        parsed.feed(raw)
        tags = parsed.tags

        html = [a for t, a in tags if t == "html"]
        lang = html[0].get("lang", "") if html else ""
        check(bool(lang), f"{rel}: <html> has no lang")
        check(any(t == "title" for t, _ in tags), f"{rel}: no <title>")
        check(any(t == "meta" and a.get("name") == "viewport" for t, a in tags), f"{rel}: no viewport meta")
        csp = [a.get("content", "") for t, a in tags
               if t == "meta" and a.get("http-equiv", "").lower() == "content-security-policy"]
        check(csp == [CSP], f"{rel}: the Content-Security-Policy meta is missing or differs from the shared one")

        for forbidden in ("script", "iframe", "frame", "object", "embed", "form", "video", "audio"):
            check(not any(t == forbidden for t, _ in tags), f"{rel}: contains <{forbidden}>")
        for tag, attrs in tags:
            for name, value in attrs.items():
                check(not name.startswith("on"), f"{rel}: inline handler {name} on <{tag}>")
                check(name != "style", f"{rel}: inline style on <{tag}> (the CSP would drop it)")
                if name in ("href", "src", "srcset", "action", "poster", "data"):
                    if value == SIGN_IN or value.startswith("#"):
                        continue
                    check(not off_origin(value), f"{rel}: <{tag} {name}=\"{value}\"> leaves this origin")
                    target = resolve(page, value)
                    if target is not None and not off_origin(value):
                        check(target.exists(), f"{rel}: <{tag} {name}=\"{value}\"> does not exist")

        primaries = [a for t, a in tags if "primary" in a.get("class", "").split()]
        check(len(primaries) == 1, f"{rel}: {len(primaries)} primary actions, expected exactly one")

        langs = [a for t, a in tags if t == "a" and a.get("hreflang")]
        check({a["hreflang"] for a in langs} >= {"en-IN", "te", "hi"}, f"{rel}: the language switch is incomplete")

        top = lang.split("-")[0]
        if top in DRAFT_LANGS:
            text = " ".join(parsed.text)
            check("Unreviewed draft translation" in text, f"{rel}: a {top} page must say it is an unreviewed draft")
            check(any(t == "meta" and a.get("name") == "robots" and "noindex" in a.get("content", "")
                      for t, a in tags), f"{rel}: an unreviewed draft must not ask to be indexed")


def check_css() -> None:
    for css_path in sorted(SITE.rglob("*.css")):
        rel = css_path.relative_to(ROOT)
        css = css_path.read_text(encoding="utf-8")
        for url in re.findall(r"url\(\s*['\"]?([^'\")]+)", css):
            check(not off_origin(url), f"{rel}: url({url}) leaves this origin")
            if not off_origin(url):
                check((css_path.parent / url).exists(), f"{rel}: url({url}) does not exist")
        check("@import" not in css, f"{rel}: @import (link the stylesheet instead)")
        for size in re.findall(r"font-size:\s*([0-9.]+)(px|rem|em)\b", css):
            value = float(size[0]) * (16 if size[1] != "px" else 1)
            check(value >= 13, f"{rel}: font-size {size[0]}{size[1]} is below the 13px floor")
        for size in re.findall(r"--text-[a-z]+:\s*([0-9.]+)rem", css):
            check(float(size) * 16 >= 13, f"{rel}: a text token of {size}rem is below the 13px floor")
        check("--ink-faint" not in css, f"{rel}: faint ink is never used on the site")

    site = (SITE / "assets/site.css").read_text(encoding="utf-8")
    app = TOKENS.read_text(encoding="utf-8")
    app_light = tokens_in(app, r":root\s*\{(.*?)\n\}")
    app_dark = tokens_in(app, r":root\[data-theme=\"dark\"\]\s*\{(.*?)\n\}")
    site_light = tokens_in(site, r":root\s*\{(.*?)\n\}")
    site_dark = tokens_in(site, r"prefers-color-scheme:\s*dark\)\s*\{\s*:root\s*\{(.*?)\}")
    for name in SHARED_TOKENS:
        check(site_light.get(name) == app_light.get(name),
              f"site.css light {name} is {site_light.get(name)}, the app's is {app_light.get(name)}")
        check(site_dark.get(name) == app_dark.get(name),
              f"site.css dark {name} is {site_dark.get(name)}, the app's is {app_dark.get(name)}")
    mark = SITE / "assets/mark.svg"
    check(mark.exists() and FAVICON.exists() and mark.read_bytes() == FAVICON.read_bytes(),
          "site/assets/mark.svg is not static/icons/favicon.svg — copy it again after brand/render-icons.py")
    check(site_light.get("--brass") == "#8A6D10", "site.css light --brass must be #8A6D10")
    check(site_dark.get("--brass") == "#D8B95A", "site.css dark --brass must be #D8B95A")


def check_fonts() -> None:
    fonts = SITE / "assets/fonts"
    sums = fonts / "SHA256SUMS"
    check(sums.exists(), "site/assets/fonts/SHA256SUMS is missing")
    check((fonts / "SOURCE").exists(), "site/assets/fonts/SOURCE is missing")
    if not sums.exists():
        return
    listed: dict[str, str] = {}
    for line in sums.read_text().splitlines():
        if line.strip():
            digest, name = line.split(maxsplit=1)
            listed[name.strip()] = digest
    for path in sorted(fonts.glob("*.woff2")) + sorted(fonts.glob("*.txt")):
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        check(listed.get(path.name) == digest, f"site/assets/fonts/{path.name} does not match SHA256SUMS")
    for path in sorted(fonts.glob("*.woff2")):
        family = path.name.split("-", 1)[0].lower()
        check((fonts / f"OFL-{family}.txt").exists(), f"site/assets/fonts/{path.name} has no OFL-{family}.txt")


def main() -> int:
    check(SITE.is_dir(), "site/ does not exist")
    if SITE.is_dir():
        check_pages()
        check_css()
        check_fonts()
    if FAILURES:
        for failure in FAILURES:
            print(f"FAIL  {failure}")
        print(f"\n{len(FAILURES)} of {CHECKS} checks failed.")
        return 1
    print(f"site/ holds to its own promises: {CHECKS} checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
