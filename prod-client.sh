#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

export FOXMES_ENV=prod
unset FOXMES_URL FOXMES_WEB_URL

exec "$ROOT/dev-client.sh" "$@"
