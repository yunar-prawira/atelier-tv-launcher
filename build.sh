#!/bin/sh
# Builds out/atelier.apk. Needs Java 17 + Android SDK (platform 34, build-tools 34.x) + zip.
# The signing key never lives in this tree: set KEYSTORE and KEYSTORE_PASS yourself.
set -e
cd "$(dirname "$0")"

if [ -z "$JAVA_HOME" ]; then
  for j in /usr/lib/jvm/java-17-openjdk-amd64 /opt/homebrew/opt/openjdk@17 /usr/lib/jvm/java-17*; do
    [ -d "$j" ] && JAVA_HOME=$j && break
  done
fi
[ -n "$JAVA_HOME" ] || { echo "JDK 17 not found; set JAVA_HOME." >&2; exit 1; }
export PATH="$JAVA_HOME/bin:$PATH"

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}
JAR=$SDK/platforms/android-34/android.jar
BT=$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)
[ -f "$JAR" ] && [ -n "$BT" ] && [ -x "$BT/aapt2" ] \
  || { echo "Android SDK missing platform-34/build-tools (set ANDROID_HOME)." >&2; exit 1; }
command -v zip >/dev/null 2>&1 || { echo "zip is required (apt install zip)." >&2; exit 1; }
: "${KEYSTORE_PASS:?set KEYSTORE_PASS to your keystore password - never commit the keystore}"

[ -f apps.txt ] || cp apps.txt.example apps.txt
VERSION_CODE=${VERSION_CODE:-2}
VERSION_NAME=${VERSION_NAME:-1.1}

rm -rf out
mkdir -p out/classes out/dex out/gen/com/yunar/atelier out/res

{
  echo "package com.yunar.atelier;"
  echo "final class Apps { static final String[] LIST = {"
  grep -v '^[[:space:]]*#' apps.txt | tr -d ' \t\r' | grep -v '^$' | while IFS= read -r pkg; do
    printf '%s' "$pkg" | grep -Eq '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$' \
      || { echo "apps.txt: not a package name: '$pkg'" >&2; exit 1; }
    printf '    "%s",\n' "$pkg"
  done
  echo "}; }"
} > out/gen/com/yunar/atelier/Apps.java

"$BT/aapt2" compile -o out/res --dir res
# shellcheck disable=SC2046
"$BT/aapt2" link -o out/base.apk -I "$JAR" --manifest AndroidManifest.xml \
  --min-sdk-version 21 --target-sdk-version 34 --auto-add-overlay \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  $(ls out/res/*.flat | sed 's/^/-R /')

# shellcheck disable=SC2046
javac -nowarn -Xlint:-options -encoding UTF-8 -source 1.8 -target 1.8 \
  -bootclasspath "$JAR:$BT/core-lambda-stubs.jar" \
  -d out/classes $(find src out/gen -name "*.java")

# shellcheck disable=SC2046
"$BT/d8" --min-api 21 --release --lib "$JAR" --output out/dex $(find out/classes -name '*.class')

cp out/base.apk out/unsigned.apk
(cd out/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 out/unsigned.apk out/aligned.apk

KS=${KEYSTORE:-$HOME/.atelier/key.jks}
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  echo "No keystore at $KS - creating one (same key = same app identity, keep it safe)."
  keytool -genkeypair -keystore "$KS" -storepass "$KEYSTORE_PASS" -keypass "$KEYSTORE_PASS" \
    -alias atelier -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=atelier" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KEYSTORE_PASS" --out out/atelier.apk out/aligned.apk 2>/tmp/atelier-sign.err \
  || { echo "Signing failed with $KS — usually KEYSTORE_PASS does not match that keystore." >&2
       tail -5 /tmp/atelier-sign.err >&2; exit 1; }
echo "Built out/atelier.apk ($VERSION_NAME / $VERSION_CODE) signed with $KS"
