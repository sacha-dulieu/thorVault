#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

required=(
  "$ROOT/app/local-engine/main.js"
  "$ROOT/app/local-engine/dotnet.js"
  "$ROOT/app/local-engine/dotnet.boot.js"
  "$ROOT/app/local-engine/dotnet.native.wasm"
  "$ROOT/app/local-engine/dotnet.native.js"
  "$ROOT/app/local-engine/dotnet.runtime.js"
  "$ROOT/app/local-engine/ThorVault.Engine.Wasm.dll"
  "$ROOT/android/app/src/main/assets/www/local-engine/main.js"
  "$ROOT/android/app/src/main/assets/www/local-engine/dotnet.js"
  "$ROOT/android/app/src/main/assets/www/local-engine/dotnet.boot.js"
  "$ROOT/android/app/src/main/assets/www/local-engine/dotnet.native.wasm"
  "$ROOT/android/app/src/main/assets/www/local-engine/dotnet.native.js"
  "$ROOT/android/app/src/main/assets/www/local-engine/dotnet.runtime.js"
  "$ROOT/android/app/src/main/assets/www/local-engine/ThorVault.Engine.Wasm.dll"
)

for file in "${required[@]}"; do
  [ -f "$file" ] || { echo "MISSING: $file"; exit 1; }
done

cmp -s "$ROOT/app/local-engine/main.js" \
       "$ROOT/android/app/src/main/assets/www/local-engine/main.js" || {
  echo "MISMATCH: main.js differs between web and Android assets."
  exit 1
}

cmp -s "$ROOT/app/local-engine/dotnet.boot.js" \
       "$ROOT/android/app/src/main/assets/www/local-engine/dotnet.boot.js" || {
  echo "MISMATCH: dotnet.boot.js differs between web and Android assets."
  exit 1
}

echo "Compatibility build: IL trimming disabled."\necho "Local engine packaging is complete."
echo "Boot config: dotnet.boot.js"
echo "Web engine:  $ROOT/app/local-engine"
echo "Android:     $ROOT/android/app/src/main/assets/www/local-engine"
