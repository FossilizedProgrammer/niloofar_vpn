#!/usr/bin/env bash
# Cuts a final Niloofar VPN release: checks the native engine is present, builds
# the signed release splits, writes dist/ and prints checksums.
#
#   ./tools/release.sh            # build and package
#   ./tools/release.sh --clean    # also wipe build/ first (slower)
#   ./tools/release.sh --bump     # ask for a new versionName before building
#
# Environment: GRADLE (default ~/tools/gradle-8.9/bin/gradle), JAVA_HOME,
# ANDROID_HOME, KEYSTORE/PASSWORD overrides are read from app/build.gradle.kts.
set -euo pipefail

cd "$(dirname "$0")/.."

GRADLE="${GRADLE:-$HOME/tools/gradle-8.9/bin/gradle}"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export JAVA_HOME
OUT="dist"
APK_DIR="app/build/outputs/apk/release"

CLEAN=0
BUMP=0
for arg in "$@"; do
    case "$arg" in
        --clean) CLEAN=1 ;;
        --bump) BUMP=1 ;;
        -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
        *) echo "unknown option: $arg" >&2; exit 2 ;;
    esac
done

if [ "$BUMP" = 1 ]; then
    read -r -p "New versionName: " next
    [ -n "$next" ] || { echo "empty version, aborting" >&2; exit 1; }
    sed -i "s/versionName = \".*\"/versionName = \"$next\"/" app/build.gradle.kts
fi

VERSION="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -1)"
CODE="$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts | head -1)"
[ -n "$VERSION" ] || { echo "could not read versionName" >&2; exit 1; }

# The tunnel is useless without the engine in both ABIs; fail before the long
# build rather than ship an APK that reports "engine missing".
for abi in armeabi-v7a arm64-v8a; do
    so="app/src/main/jniLibs/$abi/libffvpn.so"
    if [ ! -s "$so" ]; then
        echo "missing $so — run ./tools/ffvpn/build-android.sh" >&2
        exit 1
    fi
done
[ -s app/src/main/assets/ca_roots.pem ] || {
    echo "missing app/src/main/assets/ca_roots.pem" >&2
    exit 1
}

echo "Niloofar VPN $VERSION ($CODE) — release build"
[ "$CLEAN" = 1 ] && rm -rf app/build

TASKS="assembleRelease"
[ "$CLEAN" = 1 ] && TASKS="clean assembleRelease"

# Dependencies come from the mirrors in settings.gradle.kts. The user-level
# Gradle properties set a SOCKS proxy Java cannot use (it resolves names on the
# proxy side), so it is switched off for this build.
env -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY \
    -u all_proxy -u ALL_PROXY \
    "$GRADLE" --console=plain --no-daemon -DsocksProxyHost= $TASKS

mkdir -p "$OUT"
rm -f "$OUT"/NiloofarVPN-*.apk
for abi in armeabi-v7a arm64-v8a universal; do
    src="$APK_DIR/app-$abi-release.apk"
    [ -s "$src" ] || { echo "expected $src" >&2; exit 1; }
    dst="$OUT/NiloofarVPN-$VERSION-$abi.apk"
    cp "$src" "$dst"
    printf '  %-48s %6s  sha256 %s…\n' \
        "$dst" "$(du -h "$dst" | cut -f1)" \
        "$(sha256sum "$dst" | cut -c1-16)"
done

echo
echo "Release ready in $OUT/ ($VERSION, versionCode $CODE)."
