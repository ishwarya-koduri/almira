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

# Sorted keys and stable indentation, so the committed file diffs meaningfully
# instead of reshuffling every time the JVM feels like it.
curl -s "$BASE/v3/api-docs" | python3 -c "
import sys, json
print(json.dumps(json.load(sys.stdin), indent=2, sort_keys=True, ensure_ascii=False))
" > "$TARGET"

after=$(shasum -a 256 "$TARGET" | cut -d' ' -f1)

python3 - "$TARGET" <<'PY'
import json, sys
spec = json.load(open(sys.argv[1]))
ops = sum(len([m for m in v if m in ("get", "post", "patch", "put", "delete")])
          for v in spec["paths"].values())
print(f"frozen {spec['info']['title']} v{spec['info']['version']}: "
      f"{len(spec['paths'])} paths, {ops} operations, "
      f"{len(spec['components']['schemas'])} schemas")
PY

if [ "$before" = "$after" ]; then
  echo "unchanged"
else
  echo "updated — review the diff before committing:  git diff $TARGET"
fi
