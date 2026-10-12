#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT="$ROOT/engine/ThorVault.Engine/ThorVault.Engine.csproj"

if ! command -v dotnet >/dev/null 2>&1; then
  cat <<'EOF'
ThorVault's ORAS importer needs the .NET 10 SDK.

Install it using one of these methods:
  1. Homebrew: brew install --cask dotnet-sdk
  2. Official Microsoft .NET 10 SDK installer for macOS

Then close and reopen Terminal and run this script again.
EOF
  exit 2
fi

SDK_MAJOR="$(dotnet --version | cut -d. -f1)"
if [[ "$SDK_MAJOR" -lt 10 ]]; then
  echo "ThorVault requires .NET SDK 10 or newer. Detected: $(dotnet --version)"
  exit 2
fi

echo "Restoring PKHeX.Core 26.7.7..."
dotnet restore "$PROJECT"

echo "Building ThorVault.Engine..."
dotnet build "$PROJECT" --configuration Release --no-restore

DLL="$ROOT/engine/ThorVault.Engine/bin/Release/net10.0/ThorVault.Engine.dll"
if [[ ! -f "$DLL" ]]; then
  echo "Build completed but the engine DLL was not found at: $DLL"
  exit 1
fi

echo
echo "ThorVault Generation 4–7 engine is ready:"
echo "$DLL"
