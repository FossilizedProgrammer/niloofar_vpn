#!/bin/sh
# Cross-builds the OpenRung engine for Android and drops it into jniLibs as
# libopenrung.so. The upstream tree is copied to a scratch directory first: the
# source checkout is never written to.
#
# OpenRung is the desktop CLI (cmd/client) of github.com/openrung/openrung,
# built on the shared connectcore engine that drives a bundled sing-box. The
# app execs it from nativeLibraryDir in zero-privilege proxy mode and bridges
# its loopback SOCKS inbound to the TUN with Tun2Socks, like the other cores.
#
# Engine notes (all learned the hard way):
#   - GOTOOLCHAIN=go1.25.5 is mandatory. go.mod pins 1.25.5, and the host's
#     newer Go (1.27) breaks the x/net/http2 connPool linkname at link time.
#   - -tags with_utls is mandatory: the relays are VLESS+REALITY, and without
#     the uTLS build tag sing-box cannot build the REALITY transport.
#   - CGO_ENABLED=0 cannot resolve names on Android (no /etc/resolv.conf); the
#     build goes through bionic's resolver, i.e. GOOS=android with cgo and the
#     NDK clang as CC.
#   - The result is renamed to .so so the package installer marks it executable.
set -e

case "${1:-}" in
    -h|--help)
        printf '%s\n' \
            'Usage: ./tools/openrung/build-android.sh' \
            'Rebuilds only the OpenRung native engine for ARM32 and ARM64.' \
            'Use ./tools/build-apks.sh to package the complete multicore app.' \
            'Defaults: sibling openrung sources and Android SDK NDK 26.3.11579264.' \
            'Optional overrides: OPENRUNG_SRC, ANDROID_HOME, ANDROID_NDK_HOME, NDKROOT.'
        exit 0
        ;;
esac
[ "$#" -eq 0 ] || { printf '%s\n' 'Unexpected arguments; use --help.' >&2; exit 2; }

HERE=$(cd "$(dirname "$0")" && pwd)
PROJECT=$(cd "$HERE/../.." && pwd)
SRC=${OPENRUNG_SRC:-$PROJECT/../openrung}
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -z "$SDK" ] && [ -f "$PROJECT/local.properties" ]; then
    SDK=$(sed -n 's/^sdk.dir=//p' "$PROJECT/local.properties")
fi
NDKROOT=${NDKROOT:-${ANDROID_NDK_HOME:-${SDK:+$SDK/ndk/26.3.11579264}}}
NDK=$NDKROOT/toolchains/llvm/prebuilt/linux-x86_64/bin
API=26
OUT=$PROJECT/app/src/main/jniLibs

[ -s "$SRC/go.mod" ] || { printf 'OpenRung Go sources not found: %s\n' "$SRC" >&2; exit 1; }
[ -n "$NDKROOT" ] || { printf '%s\n' 'Android SDK not found; configure sdk.dir in local.properties.' >&2; exit 1; }
for tool in armv7a-linux-androideabi26-clang aarch64-linux-android26-clang llvm-strip; do
    [ -x "$NDK/$tool" ] || { printf 'Missing NDK tool: %s\n' "$NDK/$tool" >&2; exit 1; }
done
for tool in go patch tar; do
    command -v "$tool" >/dev/null 2>&1 || { printf 'Missing build tool: %s\n' "$tool" >&2; exit 1; }
done

# A fresh copy prevents stale sources and patches from leaking into a rebuild.
SCRATCH=$(mktemp -d "${TMPDIR:-/tmp}/niloofar-openrung.XXXXXX")
trap 'rm -rf "$SCRATCH"' 0
trap 'exit 1' HUP INT TERM
printf 'Copying %s to %s\n' "$SRC" "$SCRATCH"
(cd "$SRC" && tar --exclude=./.git -cf - .) | (cd "$SCRATCH" && tar xf -)

# The deviations from upstream, kept as patches because the source checkout is
# only ever read from:
#   relays-subcommand.patch - a `relays` subcommand prints the usable signed
#     relay directory in ranked order as one OPENRUNG_RELAYS_JSON line, which is
#     what the app fetches, probes and ranks before dialling (upstream has no
#     machine-readable list command).
#   android-proxy-interface.patch - sing-box's auto_detect_interface binds every
#     outbound socket to the default interface (SO_BINDTODEVICE). In proxy mode
#     there is no TUN to bypass, and under an unprivileged Android app UID that
#     bind fails with EPERM, killing every relay dial. Gate it to TUN mode only.
for patch in relays-subcommand android-proxy-interface; do
    case $patch in
        relays-subcommand) marker=OPENRUNG_RELAYS_JSON; file=cmd/client/headless.go ;;
        android-proxy-interface) marker='auto_detect_interface.*input.Mode'; file=connectcore/client/singbox_config.go ;;
    esac
    if ! grep -q "$marker" "$SCRATCH/$file"; then
        echo "Applying $patch.patch"
        (cd "$SCRATCH" && patch -p1 -i "$HERE/$patch.patch")
    fi
done

build() {
    abi=$1
    goarch=$2
    prefix=$3
    goarm=$4
    echo "Building $abi"
    mkdir -p "$OUT/$abi"
    # Android 15+ on a 16 KB-page kernel refuses to load a segment aligned to
    # less than 16384, and Go's android/arm64 default is still 4 KB.
    env GOTOOLCHAIN=go1.25.5 GOOS=android GOARCH=$goarch CGO_ENABLED=1 \
        CC="$NDK/${prefix}${API}-clang" CXX="$NDK/${prefix}${API}-clang++" \
        ${goarm:+GOARM=$goarm} \
        go build -C "$SCRATCH" -trimpath -tags with_utls \
        -ldflags="-s -w -extldflags=-Wl,-z,max-page-size=16384" \
        -o "$OUT/$abi/libopenrung.so.tmp" ./cmd/client
    "$NDK/llvm-strip" -o "$OUT/$abi/libopenrung.so" "$OUT/$abi/libopenrung.so.tmp"
    rm -f "$OUT/$abi/libopenrung.so.tmp"
    ls -l "$OUT/$abi/libopenrung.so"
}

build armeabi-v7a arm armv7a-linux-androideabi 7
build arm64-v8a arm64 aarch64-linux-android