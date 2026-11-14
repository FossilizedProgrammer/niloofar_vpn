#!/bin/sh
# Cross-builds the Firefox Proxy engine (ffvpn) for Android and drops it into
# jniLibs as libffvpn.so. The upstream tree is copied to a scratch directory
# first: the source checkout is never written to.
#
# Engine notes (all learned the hard way):
#   - CGO_ENABLED=0 cannot resolve names on Android: Go reads /etc/resolv.conf,
#     which does not exist, so every lookup dies with "connection refused" on
#     ::1. The build has to go through bionic's resolver, i.e. GOOS=android with
#     cgo and the NDK clang as CC.
#   - Do not pass -D__ANDROID_API__ by hand. The NDK wrapper defines it already
#     and the redefinition is a -Werror failure.
#   - The result is renamed to .so so the package installer marks it executable;
#     the app execs it from nativeLibraryDir.
set -e

case "${1:-}" in
    -h|--help)
        printf '%s\n' \
            'Usage: ./tools/ffvpn/build-android.sh' \
            'Rebuilds only the Firefox native engine for ARM32 and ARM64.' \
            'Use ./tools/build-apks.sh to package the complete multicore app.' \
            'Defaults: sibling firefox-vpn-go sources and Android SDK NDK 26.3.11579264.' \
            'Optional overrides: FFVPN_SRC, ANDROID_HOME, ANDROID_NDK_HOME, NDKROOT.'
        exit 0
        ;;
esac
[ "$#" -eq 0 ] || { printf '%s\n' 'Unexpected arguments; use --help.' >&2; exit 2; }

HERE=$(cd "$(dirname "$0")" && pwd)
PROJECT=$(cd "$HERE/../.." && pwd)
SRC=${FFVPN_SRC:-$PROJECT/../firefox-vpn-go}
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -z "$SDK" ] && [ -f "$PROJECT/local.properties" ]; then
    SDK=$(sed -n 's/^sdk.dir=//p' "$PROJECT/local.properties")
fi
NDKROOT=${NDKROOT:-${ANDROID_NDK_HOME:-${SDK:+$SDK/ndk/26.3.11579264}}}
NDK=$NDKROOT/toolchains/llvm/prebuilt/linux-x86_64/bin
API=26
OUT=$PROJECT/app/src/main/jniLibs

[ -s "$SRC/go.mod" ] || { printf 'Firefox Go sources not found: %s\n' "$SRC" >&2; exit 1; }
[ -n "$NDKROOT" ] || { printf '%s\n' 'Android SDK not found; configure sdk.dir in local.properties.' >&2; exit 1; }
for tool in armv7a-linux-androideabi26-clang aarch64-linux-android26-clang llvm-strip; do
    [ -x "$NDK/$tool" ] || { printf 'Missing NDK tool: %s\n' "$NDK/$tool" >&2; exit 1; }
done
for tool in go patch tar; do
    command -v "$tool" >/dev/null 2>&1 || { printf 'Missing build tool: %s\n' "$tool" >&2; exit 1; }
done

# A fresh copy prevents stale sources and patches from leaking into a rebuild.
SCRATCH=$(mktemp -d "${TMPDIR:-/tmp}/niloofar-ffvpn.XXXXXX")
trap 'rm -rf "$SCRATCH"' 0
trap 'exit 1' HUP INT TERM
printf 'Copying %s to %s\n' "$SRC" "$SCRATCH"
(cd "$SRC" && tar --exclude=./.git -cf - .) | (cd "$SCRATCH" && tar xf -)

# The deviations from upstream, kept as patches because the source checkout
# is only ever read from:
#   fxa-v2-salt.patch  - upstream rebuilds the v2 quick-stretch salt around the
#     namespace prefix the server already put in clientSalt, so every account
#     created today (all of them v2) fails /account/login with errno 103
#     "Incorrect password". The salt from /account/credentials/status is used
#     verbatim instead.
#   session-token-file.patch - --session-token-file also READS a cached token
#     (upstream only writes it after a sign-in), so a later connect needs
#     neither the password nor the app to hold the token itself.
#   serverlist-json.patch  - --list-countries --json prints one FFVPN_SERVER_JSON
#     line per connectable address, which is what the app probes before dialling.
#   captcha-prompt.patch - a Fastly image CAPTCHA is printed to stdout as
#     FFVPN_CAPTCHA <data-url> and the characters are read from stdin, so the app
#     can show the picture and let the person answer it (upstream fails instead).
#   handshake-timeout.patch - http.DefaultTransport abandons a TLS handshake after
#     10s whatever the client timeout says; on a slow link the auth servers need
#     longer than that and every request dies with "TLS handshake timeout". The
#     netx.Client(timeout, jar) it adds also carries the auth and OAuth
#     clients' challenge cookie jars.
#
# Retired: ua-identity.patch. Upstream now gets past the edge's HTTP 406 by
# solving the Fastly anti-bot challenge itself (internal/fxa/challenge.go)
# with a browser User-Agent, which is what the app-identity spoof used to
# paper over.
for patch in fxa-v2-salt session-token-file serverlist-json captcha-prompt handshake-timeout; do
    case $patch in
        fxa-v2-salt) marker='clientSalt), 650000'; file=internal/fxa/crypto.go ;;
        session-token-file) marker=readSessionFile; file=main.go ;;
        serverlist-json) marker=FFVPN_SERVER_JSON; file=main.go ;;
        captcha-prompt) marker=SetCaptchaSolver; file=internal/fxa/challenge.go ;;
        handshake-timeout) marker=netx.Client; file=internal/fxa/client.go ;;
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
    env GOOS=android GOARCH=$goarch CGO_ENABLED=1 CC="$NDK/${prefix}${API}-clang" \
        ${goarm:+GOARM=$goarm} \
        go build -C "$SCRATCH" -trimpath \
        -ldflags="-s -w -extldflags=-Wl,-z,max-page-size=16384" \
        -o "$OUT/$abi/libffvpn.so.tmp" .
    "$NDK/llvm-strip" -o "$OUT/$abi/libffvpn.so" "$OUT/$abi/libffvpn.so.tmp"
    rm -f "$OUT/$abi/libffvpn.so.tmp"
    ls -l "$OUT/$abi/libffvpn.so"
}

build armeabi-v7a arm armv7a-linux-androideabi 7
build arm64-v8a arm64 aarch64-linux-android
