#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
: "${THORVAULT_TOKEN:=test-token}"
: "${THORVAULT_PORT:=8768}"
export THORVAULT_TOKEN THORVAULT_PORT
python3 server/server.py
