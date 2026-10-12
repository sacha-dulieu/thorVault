#!/usr/bin/env sh
set -eu
: "${THORVAULT_TOKEN:=change-me}"
export THORVAULT_TOKEN
python3 server/server.py
