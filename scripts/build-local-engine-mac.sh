#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT="$ROOT/engine/ThorVault.Engine.Wasm"
WEB_OUT="$ROOT/app/local-engine"
ANDROID_OUT="$ROOT/android/app/src/main/assets/www/local-engine"
BOOTSTRAP="$PROJECT/main.js"

find_dotnet10() {
  local candidates=(
    "/opt/homebrew/share/dotnet/dotnet"
    "/usr/local/share/dotnet/dotnet"
    "/usr/share/dotnet/dotnet"
    "$HOME/.dotnet/dotnet"
  )
  for candidate in "${candidates[@]}"; do
    if [ -x "$candidate" ] &&
       "$candidate" --list-sdks 2>/dev/null |
       grep -Eq '(^|[[:space:]])10\.[0-9]+\.[0-9]+([[:space:]]|$)'; then
      echo "$candidate"
      return 0
    fi
  done
  if command -v dotnet >/dev/null 2>&1; then
    local current
    current="$(command -v dotnet)"
    if "$current" --list-sdks 2>/dev/null |
       grep -Eq '(^|[[:space:]])10\.[0-9]+\.[0-9]+([[:space:]]|$)'; then
      echo "$current"
      return 0
    fi
  fi
  return 1
}

DOTNET_BIN="$(find_dotnet10 || true)"
if [ -z "$DOTNET_BIN" ]; then
  echo "ERROR: No .NET 10 SDK was found."
  command -v dotnet >/dev/null 2>&1 && dotnet --list-sdks || true
  exit 1
fi

[ -f "$BOOTSTRAP" ] || { echo "ERROR: missing WASM bootstrap: $BOOTSTRAP"; exit 1; }

echo "Using .NET SDK:"
echo "  $DOTNET_BIN"
echo "  $("$DOTNET_BIN" --version)"
echo
echo "Restoring required .NET workloads..."
"$DOTNET_BIN" workload restore "$PROJECT/ThorVault.Engine.Wasm.csproj"

echo "Checking wasm-tools workload..."
"$DOTNET_BIN" workload list 2>/dev/null |
  grep -Eiq '(^|[[:space:]])wasm-tools([[:space:]]|$)' || {
    echo "ERROR: wasm-tools is not installed for the selected SDK."
    exit 1
  }

rm -rf "$WEB_OUT" "$ANDROID_OUT" "$PROJECT/bin" "$PROJECT/obj"
mkdir -p "$WEB_OUT" "$ANDROID_OUT"

PUBLISH_DIR="$WEB_OUT/publish"

echo
echo "Building ThorVault local engine with .NET 10 + PKHeX.Core (compatibility / no IL trimming)..."
"$DOTNET_BIN" publish "$PROJECT/ThorVault.Engine.Wasm.csproj" \
  -c Release \
  -p:PublishTrimmed=false \
  -p:RunAOTCompilation=false \
  -p:WasmStripILAfterAOT=false \
  -p:WasmEnableExceptionHandling=true \
  -o "$PUBLISH_DIR"

for required in dotnet.js dotnet.native.wasm dotnet.native.js dotnet.runtime.js ThorVault.Engine.Wasm.dll; do
  if [ ! -f "$PUBLISH_DIR/$required" ]; then
    echo "ERROR: WASM build succeeded but publish/$required is missing."
    exit 1
  fi
done

# Generate the boot configuration from the actual flat publish output.
# dotnet.js defaults to importing ./dotnet.boot.js when no explicit config
# source is supplied. The generated config describes the files that exist
# in THIS publish directory, so it stays in sync with SDK/trim output.
python3 - "$PUBLISH_DIR" <<'PY'
import json, pathlib, sys

publish = pathlib.Path(sys.argv[1])

dlls = sorted(p.name for p in publish.glob("*.dll"))
icu = sorted(p.name for p in publish.glob("icudt*.dat"))

config = {
    "mainAssemblyName": "ThorVault.Engine.Wasm",
    "resources": {
        "assembly": [{"name": n} for n in dlls],
        "wasmNative": [{"name": "dotnet.native.wasm"}],
        "jsModuleNative": [{"name": "dotnet.native.js"}],
        "jsModuleRuntime": [{"name": "dotnet.runtime.js"}],
    },
}

if icu:
    config["resources"]["icu"] = [{"name": n} for n in icu]

(publish / "dotnet.boot.js").write_text(
    "export const config = " +
    json.dumps(config, separators=(",", ":")) +
    ";\n",
    encoding="utf-8",
)

print(f"Generated dotnet.boot.js with {len(dlls)} assemblies and {len(icu)} ICU assets.")
PY

[ -f "$PUBLISH_DIR/dotnet.boot.js" ] || {
  echo "ERROR: failed to generate publish/dotnet.boot.js"
  exit 1
}

# Copy the complete flat runtime output and our application bootstrap.
cp -R "$PUBLISH_DIR/." "$WEB_OUT/"
cp "$BOOTSTRAP" "$WEB_OUT/main.js"

rm -rf "$ANDROID_OUT"
mkdir -p "$ANDROID_OUT"
cp -R "$WEB_OUT/." "$ANDROID_OUT/"

echo
echo "Local engine packaged successfully."
echo "  Web:       $WEB_OUT"
echo "  Android:   $ANDROID_OUT"
echo "  Bootstrap: $WEB_OUT/main.js"
echo "  Boot:      $WEB_OUT/dotnet.boot.js"
echo "  Runtime:   $WEB_OUT/dotnet.js"
