#!/usr/bin/env bash
# =============================================================================
# Re-freezes docs/api/openapi-v1.json from a running server.
#
#   ./scripts/dev.sh              # in one terminal
#   ./scripts/freeze-api-spec.sh  # in another
#
# Run this ONLY for additive changes — a new endpoint, a new optional request
# field, a new response field. Anything a v1 client would notice belongs in
# /api/v2 instead; the contract test will tell you which of the two you have.
#
# Commit the result in the same change as the code, so a reviewer sees the
# contract move alongside the reason it moved.
#
# The frozen file is replaced only by a spec that has been fetched completely
# and checked: it is written to a temporary file beside the target, validated,
# and moved over the target in one rename. A failed fetch, an error page or a
# spec with no paths leaves the committed file exactly as it was. (It used to
# be emptied first by the redirect and validated after, so any failure left an
# empty or half-written contract behind.)
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

BASE="${1:-http://localhost:8080}"
TARGET="docs/api/openapi-v1.json"

if ! curl -sf "$BASE/health" >/dev/null; then
  echo "No server at $BASE. Start one with ./scripts/dev.sh" >&2
  exit 1
fi

before=$( [ -f "$TARGET" ] && shasum -a 256 "$TARGET" | cut -d' ' -f1 || echo none )

# Beside the target, so the final mv is a rename on one filesystem.
TMP=$(mktemp "$TARGET.XXXXXX")
trap 'rm -f "$TMP"' EXIT

# -f: an HTTP error is a failure, not a body to freeze.
if ! curl -sf "$BASE/v3/api-docs" > "$TMP.raw"; then
  rm -f "$TMP.raw"
  echo "Could not fetch $BASE/v3/api-docs; $TARGET is unchanged." >&2
  exit 1
fi

# Sorted keys and stable indentation, so the committed file diffs meaningfully
# instead of reshuffling every time the JVM feels like it. Validated before it
# is written anywhere that matters.
if ! python3 - "$TMP.raw" "$TMP" <<'PY'
import json, sys
raw, out = sys.argv[1:3]
try:
    spec = json.load(open(raw))
except ValueError as e:
    sys.exit(f"the server did not return JSON ({e})")
problems = []
if not isinstance(spec, dict):
    sys.exit("the server returned JSON that is not an OpenAPI document")
if not str(spec.get("openapi", "")).startswith("3."):
    problems.append("no openapi 3.x version")
if not isinstance(spec.get("info"), dict) or not spec["info"].get("title") or not spec["info"].get("version"):
    problems.append("no info.title and info.version")
if not isinstance(spec.get("paths"), dict) or not spec["paths"]:
    problems.append("no paths")
if not isinstance(spec.get("components", {}).get("schemas"), dict):
    problems.append("no components.schemas")
if problems:
    sys.exit("not a complete spec: " + ", ".join(problems))
with open(out, "w") as f:
    f.write(json.dumps(spec, indent=2, sort_keys=True, ensure_ascii=False) + "\n")
ops = sum(len([m for m in v if m in ("get", "post", "patch", "put", "delete")])
          for v in spec["paths"].values())
print(f"frozen {spec['info']['title']} v{spec['info']['version']}: "
      f"{len(spec['paths'])} paths, {ops} operations, "
      f"{len(spec['components']['schemas'])} schemas")
PY
then
  rm -f "$TMP.raw"
  echo "Refusing to freeze: $BASE/v3/api-docs is not a complete spec (above). $TARGET is unchanged." >&2
  exit 1
fi
rm -f "$TMP.raw"

mv -f "$TMP" "$TARGET"
trap - EXIT

after=$(shasum -a 256 "$TARGET" | cut -d' ' -f1)

if [ "$before" = "$after" ]; then
  echo "unchanged"
else
  echo "updated — review the diff before committing:  git diff $TARGET"
fi
