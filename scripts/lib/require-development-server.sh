# =============================================================================
# Two gates, in one place: the target must be a local address, AND the server
# there must say it is in development.
#
#   . "$(dirname "$0")/lib/require-development-server.sh"
#   require_development_server "$BASE" "This suite signs up users and …"
#
# WHY BOTH: a hostname check alone is fooled by a tunnel or a hosts entry
# pointing "localhost" somewhere real, and asking the server alone would let a
# typo send writes to a public host that happens to be misconfigured. Together
# they are hard to get past by accident, which is the only way this goes wrong —
# nobody attacks themselves at 1am, they mistype a URL.
#
# It fails CLOSED at every step: an unreachable server, a server too old to
# report an environment, or an unparseable answer all refuse. The cost of a
# false refusal is retyping a command; the cost of a false pass is somebody's
# real records.
#
# Deliberately NOT used by scripts/smoke-prod.sh, whose whole purpose is to run
# against a deployment. That one announces that it writes and prints the SQL to
# undo it.
# =============================================================================

require_development_server() {
  local base="$1" what="${2:-This script writes data.}"
  local red bold off health environment
  red=$'\033[31m'; bold=$'\033[1m'; off=$'\033[0m'

  _refuse() {
    echo "${red}${bold}Refusing to run against $base.${off}" >&2
    echo "  $1" >&2
    echo >&2
    echo "  $what" >&2
    echo "  It is meant for a local development server and nothing else." >&2
    exit 1
  }

  case "$base" in
    http://localhost:*|http://127.0.0.1:*|http://[::1]:*|http://localhost|http://127.0.0.1) ;;
    *) _refuse "$base is not a local address." ;;
  esac

  health=$(curl -fsS --max-time 10 "$base/health" 2>/dev/null) \
    || _refuse "No server answering at $base."

  case "$health" in
    *'"environment":"development"'*) ;;
    *)
      environment=$(printf '%s' "$health" \
        | sed -n 's/.*"environment"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
      _refuse "That server reports ${environment:-no environment at all}, not development."
      ;;
  esac
}
