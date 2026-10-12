#!/usr/bin/env bash
set -euo pipefail
SOURCE="${1:-}"
if [ -z "$SOURCE" ] || [ ! -d "$SOURCE" ]; then
  echo "Usage: $0 /path/to/old/thorvault-release/docker-data"
  exit 1
fi
docker volume create thorvault-data >/dev/null
docker run --rm -v thorvault-data:/target -v "$(cd "$SOURCE" && pwd):/source:ro" alpine:3.20 sh -c 'cp -a /source/. /target/'
echo "Imported legacy ThorVault data into Docker volume: thorvault-data"
