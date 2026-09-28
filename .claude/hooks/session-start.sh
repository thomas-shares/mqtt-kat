#!/bin/bash
# Makes a Claude Code on the web session ready to run `lein test`: installs
# Leiningen if missing and fetches the project's dependencies into ~/.m2
# (Rama from nexus.redplanetlabs.com, the rest from Clojars and Central).
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

LEIN_VERSION=2.11.2

if ! command -v lein >/dev/null 2>&1; then
  curl -fsSL -o /usr/local/bin/lein \
    "https://raw.githubusercontent.com/technomancy/leiningen/${LEIN_VERSION}/bin/lein"
  chmod +x /usr/local/bin/lein
fi

cd "${CLAUDE_PROJECT_DIR:-$(dirname "$0")/../..}"
# The first run of lein downloads its own jar; `lein deps` then fills ~/.m2.
# Both are no-ops once cached. Maven Central answers a burst of requests
# with 429 now and then, so a failed fetch is tried again; what already
# arrived stays in ~/.m2.
for attempt in 1 2 3 4; do
  LEIN_ROOT=1 lein deps && exit 0
  echo "lein deps failed (attempt $attempt), retrying" >&2
  sleep $((attempt * 5))
done
exit 1
