#!/bin/sh
# Cross-builds the Opera proxy engine for Android and drops it into jniLibs as
# libopera.so. The upstream tree is copied to a scratch directory first: the
# source checkout is never written to.
#
# The engine is opera-proxy-cli: Opera VPN (SurfEasy) as a local HTTP + SOCKS5
# proxy. It registers a SurfEasy device, ranks and picks an endpoint, masks the
# SNI, keeps failing over and serves both listeners on loopback, so the app
# execs it from nativeLibraryDir in zero-privilege proxy mode and bridges its
# SOCKS inbound to the TUN with Tun2Socks, like the other executable cores.
#
# Engine notes:
#   - CGO_ENABLED=1 is mandatory. GOOS=android without cgo cannot resolve names:
#     Go reads /etc/resolv.conf, which does not exist on Android, so both the
#     SurfEasy API lookup and the bootstrap DoH resolvers would fail.
#   - The CLI keeps its endpoint cache, SNI learning and saved session in one
#     state file, passed with -state so it never falls back to a path Android
#     does not have (see OperaCore.kt).
#   - The result is renamed to .so so the package installer marks it executable.
set -e

case "${1:-}" in
    -h|--help)
        printf '%s\n' \
            'Usage: ./tools/opera/build-android.sh' \
            'Rebuilds only the Opera native engine for ARM32 and ARM64.' \
            'Use ./tools/build-apks.sh to package the complete multicore app.' \
            'Defaults: sibling OperaProxyCLI sources and Android SDK NDK 26.3.11579264.' \
            'Optional overrides: OPERA_SRC, ANDROID_HOME, ANDROID_NDK_HOME, NDKROOT.'
        exit 0
        ;;
esac
[ "$#" -eq 0 ] || { printf '%s\n' 'Unexpected arguments; use --help.' >&2; exit 2; }

HERE=$(cd "$(dirname "$0")" && pwd)
PROJECT=$(cd "$HERE/../.." && pwd)
SRC=${OPERA_SRC:-/home/me/prg/cli/OperaProxyCLI}
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -z "$SDK" ] && [ -f "$PROJECT/local.properties" ]; then
    SDK=$(sed -n 's/^sdk.dir=//p' "$PROJECT/local.properties")
fi
NDKROOT=${NDKROOT:-${ANDROID_NDK_HOME:-${SDK:+$SDK/ndk/26.3.11579264}}}
NDK=$NDKROOT/toolchains/llvm/prebuilt/linux-x86_64/bin
API=26
OUT=$PROJECT/app/src/main/jniLibs

[ -s "$SRC/go.mod" ] || { printf 'Opera Go sources not found: %s\n' "$SRC" >&2; exit 1; }
[ -n "$NDKROOT" ] || { printf '%s\n' 'Android SDK not found; configure sdk.dir in local.properties.' >&2; exit 1; }
for tool in armv7a-linux-androideabi26-clang aarch64-linux-android26-clang llvm-strip; do
    [ -x "$NDK/$tool" ] || { printf 'Missing NDK tool: %s\n' "$NDK/$tool" >&2; exit 1; }
done
for tool in go tar; do
    command -v "$tool" >/dev/null 2>&1 || { printf 'Missing build tool: %s\n' "$tool" >&2; exit 1; }
done

# A fresh copy prevents stale sources from leaking into a rebuild. dist/ is
# skipped: it holds the desktop release binaries, not sources.
SCRATCH=$(mktemp -d "${TMPDIR:-/tmp}/niloofar-opera.XXXXXX")
trap 'rm -rf "$SCRATCH"' 0
trap 'exit 1' HUP INT TERM
printf 'Copying %s to %s\n' "$SRC" "$SCRATCH"
(cd "$SRC" && tar --exclude=./.git --exclude=./dist -cf - .) | (cd "$SCRATCH" && tar xf -)

build() {
    abi=$1
    goarch=$2
    prefix=$3
    goarm=$4
    echo "Building $abi"
    mkdir -p "$OUT/$abi"
    # Android 15+ on a 16 KB-page kernel refuses to load a segment aligned to
    # less than 16384, and Go's default for android/arm64 is still 4 KB.
    env GOOS=android GOARCH=$goarch CGO_ENABLED=1 \
        CC="$NDK/${prefix}${API}-clang" CXX="$NDK/${prefix}${API}-clang++" \
        ${goarm:+GOARM=$goarm} \
        go build -C "$SCRATCH" -trimpath -buildvcs=false \
        -ldflags="-s -w -extldflags=-Wl,-z,max-page-size=16384" \
        -o "$OUT/$abi/libopera.so.tmp" .
    "$NDK/llvm-strip" -o "$OUT/$abi/libopera.so" "$OUT/$abi/libopera.so.tmp"
    rm -f "$OUT/$abi/libopera.so.tmp"
    ls -l "$OUT/$abi/libopera.so"
}

build armeabi-v7a arm armv7a-linux-androideabi 7
build arm64-v8a arm64 aarch64-linux-android
