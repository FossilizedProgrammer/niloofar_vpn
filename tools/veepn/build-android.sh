#!/bin/sh
# Cross-builds the VeePN engine for Android and drops it into jniLibs as
# libveepn.so. The upstream tree is copied to a scratch directory first: the
# source checkout is never written to.
#
# VeePN is the proxy-only VeePN CLI cross-built for Android. It fetches the
# official app's free subscription, parses the ss/vmess/vless/trojan links and
# runs one server with sing-box behind a loopback SOCKS5 + HTTP proxy, so the app
# execs it from nativeLibraryDir in zero-privilege proxy mode and bridges its
# SOCKS inbound to the TUN with Tun2Socks, like the Firefox / OpenRung / Tor
# cores.
#
# Engine notes:
#   - CGO_ENABLED=1 is mandatory. GOOS=android without cgo cannot resolve names:
#     Go reads /etc/resolv.conf, which does not exist on Android, so fetching the
#     subscription would fail with every lookup.
#   - with_utls is required by VLESS REALITY servers (uTLS fingerprint).
#   - The engine keeps its config, subscription cache and state under
#     $VEEPN_HOME, so the app runs it with that pointed at its own filesDir
#     (see VeepnCore.kt).
#   - The result is renamed to .so so the package installer marks it executable.
set -e

case "${1:-}" in
    -h|--help)
        printf '%s\n' \
            'Usage: ./tools/veepn/build-android.sh' \
            'Rebuilds only the VeePN native engine for ARM32 and ARM64.' \
            'Use ./tools/build-apks.sh to package the complete multicore app.' \
            'Defaults: sibling veepn-cli sources and Android SDK NDK 26.3.11579264.' \
            'Optional overrides: VEEPN_SRC, ANDROID_HOME, ANDROID_NDK_HOME, NDKROOT.'
        exit 0
        ;;
esac
[ "$#" -eq 0 ] || { printf '%s\n' 'Unexpected arguments; use --help.' >&2; exit 2; }

HERE=$(cd "$(dirname "$0")" && pwd)
PROJECT=$(cd "$HERE/../.." && pwd)
SRC=${VEEPN_SRC:-/home/me/prg/cli/veepn-cli}
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -z "$SDK" ] && [ -f "$PROJECT/local.properties" ]; then
    SDK=$(sed -n 's/^sdk.dir=//p' "$PROJECT/local.properties")
fi
NDKROOT=${NDKROOT:-${ANDROID_NDK_HOME:-${SDK:+$SDK/ndk/26.3.11579264}}}
NDK=$NDKROOT/toolchains/llvm/prebuilt/linux-x86_64/bin
API=26
OUT=$PROJECT/app/src/main/jniLibs

[ -s "$SRC/go.mod" ] || { printf 'VeePN Go sources not found: %s\n' "$SRC" >&2; exit 1; }
[ -n "$NDKROOT" ] || { printf '%s\n' 'Android SDK not found; configure sdk.dir in local.properties.' >&2; exit 1; }
for tool in armv7a-linux-androideabi26-clang aarch64-linux-android26-clang llvm-strip; do
    [ -x "$NDK/$tool" ] || { printf 'Missing NDK tool: %s\n' "$NDK/$tool" >&2; exit 1; }
done
for tool in go tar; do
    command -v "$tool" >/dev/null 2>&1 || { printf 'Missing build tool: %s\n' "$tool" >&2; exit 1; }
done

# A fresh copy prevents stale sources from leaking into a rebuild.
SCRATCH=$(mktemp -d "${TMPDIR:-/tmp}/niloofar-veepn.XXXXXX")
trap 'rm -rf "$SCRATCH"' 0
trap 'exit 1' HUP INT TERM
printf 'Copying %s to %s\n' "$SRC" "$SCRATCH"
(cd "$SRC" && tar --exclude=./.git -cf - .) | (cd "$SCRATCH" && tar xf -)

build() {
    abi=$1
    goarch=$2
    prefix=$3
    goarm=$4
    echo "Building $abi"
    mkdir -p "$OUT/$abi"
    # Android 15+ on a 16 KB-page kernel refuses to load a segment aligned to
    # less than 16384, and Go's android/arm64 default is still 4 KB.
    env GOOS=android GOARCH=$goarch CGO_ENABLED=1 \
        CC="$NDK/${prefix}${API}-clang" CXX="$NDK/${prefix}${API}-clang++" \
        ${goarm:+GOARM=$goarm} \
        go build -C "$SCRATCH" -tags with_utls -trimpath \
        -ldflags="-s -w -extldflags=-Wl,-z,max-page-size=16384" \
        -o "$OUT/$abi/libveepn.so.tmp" .
    "$NDK/llvm-strip" -o "$OUT/$abi/libveepn.so" "$OUT/$abi/libveepn.so.tmp"
    rm -f "$OUT/$abi/libveepn.so.tmp"
    ls -l "$OUT/$abi/libveepn.so"
}

build armeabi-v7a arm armv7a-linux-androideabi 7
build arm64-v8a arm64 aarch64-linux-android
