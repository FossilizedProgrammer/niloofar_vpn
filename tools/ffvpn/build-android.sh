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

SRC=${FFVPN_SRC:-/home/me/prg/firefox-vpn-go}
SCRATCH=${SCRATCH:-/tmp/ffvpn-android}
NDKROOT=${NDKROOT:-/home/me/android-sdk/ndk/26.3.11579264}
NDK=$NDKROOT/toolchains/llvm/prebuilt/linux-x86_64/bin
API=26
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=$(cd "$HERE/../.." && pwd)/app/src/main/jniLibs

if [ ! -f "$SCRATCH/go.mod" ]; then
    echo "Copying $SRC to $SCRATCH"
    mkdir -p "$SCRATCH"
    (cd "$SRC" && tar --exclude=./.git -cf - .) | (cd "$SCRATCH" && tar xf -)
fi

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
    env GOOS=android GOARCH=$goarch CGO_ENABLED=1 CC=$NDK/${prefix}${API}-clang \
        ${goarm:+GOARM=$goarm} \
        go build -C "$SCRATCH" -trimpath -ldflags="-s -w" -o "$OUT/$abi/libffvpn.so.tmp" .
    "$NDK/llvm-strip" -o "$OUT/$abi/libffvpn.so" "$OUT/$abi/libffvpn.so.tmp"
    rm -f "$OUT/$abi/libffvpn.so.tmp"
    ls -l "$OUT/$abi/libffvpn.so"
}

build armeabi-v7a arm armv7a-linux-androideabi 7
build arm64-v8a arm64 aarch64-linux-android
