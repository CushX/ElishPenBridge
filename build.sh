#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="/d/Android/Sdk"
BUILD_TOOLS="$SDK/build-tools/36.0.0"
ANDROID_JAR="$SDK/platforms/android-37.0/android.jar"
API_JAR="/d/Android/lsp-api-102/classes.jar"
OUT="$ROOT/build"

rm -rf "$OUT"
mkdir -p "$OUT/compiled" "$OUT/classes" "$OUT/dex" "$OUT/apk"

"$BUILD_TOOLS/aapt2.exe" compile --dir "$ROOT/src/main/res" -o "$OUT/compiled/res.zip"
"$BUILD_TOOLS/aapt2.exe" link \
  -o "$OUT/apk/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$ROOT/src/main/AndroidManifest.xml" \
  -R "$OUT/compiled/res.zip" \
  --min-sdk-version 30 \
  --target-sdk-version 37 \
  --auto-add-overlay

javac -encoding UTF-8 -source 17 -target 17 \
  -cp "$ANDROID_JAR:$API_JAR" \
  -d "$OUT/classes" \
  $(find "$ROOT/src/main/java" -name '*.java')

"$BUILD_TOOLS/d8.bat" --release \
  --lib "$ANDROID_JAR" \
  --lib "$API_JAR" \
  --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

cp "$OUT/apk/base.apk" "$OUT/unsigned.apk"
python - "$OUT/unsigned.apk" "$OUT/dex/classes.dex" "$ROOT/src/main/resources" <<'PY'
import os
import sys
import zipfile

apk = sys.argv[1]
dex = sys.argv[2]
resources = sys.argv[3]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as archive:
    archive.write(dex, 'classes.dex')
    for root, dirs, files in os.walk(resources):
        for name in files:
            path = os.path.join(root, name)
            arcname = os.path.relpath(path, resources).replace(os.sep, '/')
            archive.write(path, arcname)
PY

"$BUILD_TOOLS/zipalign.exe" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f "$ROOT/debug.jks" ]; then
  keytool -genkeypair -v -keystore "$ROOT/debug.jks" \
    -alias elishpen -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=ElishPenBridge, OU=Personal, O=Personal, L=NA, ST=NA, C=CN" >/dev/null
fi

"$BUILD_TOOLS/apksigner.bat" sign \
  --ks "$ROOT/debug.jks" \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "$ROOT/ElishPenBridge-v1.5.1.apk" \
  "$OUT/aligned.apk"

echo "Built: $ROOT/ElishPenBridge-v1.5.1.apk"
