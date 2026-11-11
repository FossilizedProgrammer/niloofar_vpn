#!/usr/bin/env bash
# Builds the Niloofar VPN release APKs and copies them to dist/.
set -euo pipefail

cd "$(dirname "$0")/.."

GRADLE="${GRADLE:-/home/me/tools/gradle-8.9/bin/gradle}"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export JAVA_HOME

VERSION="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -1)"
OUT="dist"
APK_DIR="app/build/outputs/apk/release"

echo "Building Niloofar VPN $VERSION..."
# Dependencies resolve from the mirrors in settings.gradle.kts; the SOCKS proxy
# in ~/.gradle/gradle.properties rejects Java's requests, so turn it off.
env -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY \
    -u all_proxy -u ALL_PROXY \
    "$GRADLE" --console=plain -DsocksProxyHost= assembleRelease

mkdir -p "$OUT"
for abi in armeabi-v7a arm64-v8a universal; do
    src="$APK_DIR/app-$abi-release.apk"
    dst="$OUT/NiloofarVPN-$VERSION-$abi.apk"
    cp "$src" "$dst"
    printf '  %-46s %s\n' "$dst" "$(du -h "$dst" | cut -f1)"
done
