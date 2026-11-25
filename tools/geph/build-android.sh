#!/bin/sh
# Cross-builds the Geph engine for Android and drops it into jniLibs as
# libgeph.so. The upstream tree is copied to a scratch directory first: the
# source checkout is never written to.
#
# Geph is the `geph5-proxy` client of the Geph5 tree cross-built for Android. It
# signs a free account up, picks an exit by country and serves a loopback SOCKS5
# + HTTP proxy, so the app execs it from nativeLibraryDir in zero-privilege
# proxy mode and bridges its SOCKS inbound to the TUN with Tun2Socks, like the
# Firefox / OpenRung / VeePN cores.
#
# Engine notes:
#   - Rust, not Go: the toolchain needs the aarch64-linux-android and
#     armv7-linux-androideabi targets installed (`rustup target add ...`).
#   - The account code and the SQLite cache are passed explicitly
#     (`--account-file`, `--cache`) because the `dirs` crate has no config or
#     cache directory on Android; see GephCore.kt.
#   - OpenSSL is compiled statically by openssl-src (native-tls is a hard
#     dependency of geph5-client), so CC/AR/RANLIB must point at the NDK
#     toolchain by target triple: openssl's Android config otherwise guesses
#     `aarch64-linux-android-ranlib`, which does not exist here.
#   - RUST_LOG=warn keeps the default DEBUG firehose out of the app log.
#   - The result is renamed to .so so the package installer marks it executable.
set -e

case "${1:-}" in
    -h|--help)
        printf '%s\n' \
            'Usage: ./tools/geph/build-android.sh' \
            'Rebuilds only the Geph native engine for ARM32 and ARM64.' \
            'Use ./tools/build-apks.sh to package the complete multicore app.' \
            'Defaults: sibling geph sources and Android SDK NDK 26.3.11579264.' \
            'Optional overrides: GEPH_SRC, ANDROID_HOME, ANDROID_NDK_HOME, NDKROOT.'
        exit 0
        ;;
esac
[ "$#" -eq 0 ] || { printf '%s\n' 'Unexpected arguments; use --help.' >&2; exit 2; }

HERE=$(cd "$(dirname "$0")" && pwd)
PROJECT=$(cd "$HERE/../.." && pwd)
SRC=${GEPH_SRC:-/home/me/prg/cli/geph}
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -z "$SDK" ] && [ -f "$PROJECT/local.properties" ]; then
    SDK=$(sed -n 's/^sdk.dir=//p' "$PROJECT/local.properties")
fi
NDKROOT=${NDKROOT:-${ANDROID_NDK_HOME:-${SDK:+$SDK/ndk/26.3.11579264}}}
NDK=$NDKROOT/toolchains/llvm/prebuilt/linux-x86_64/bin
API=26
OUT=$PROJECT/app/src/main/jniLibs

[ -s "$SRC/Cargo.toml" ] || { printf 'Geph Rust sources not found: %s\n' "$SRC" >&2; exit 1; }
[ -n "$NDKROOT" ] || { printf '%s\n' 'Android SDK not found; configure sdk.dir in local.properties.' >&2; exit 1; }
for tool in armv7a-linux-androideabi26-clang aarch64-linux-android26-clang llvm-strip; do
    [ -x "$NDK/$tool" ] || { printf 'Missing NDK tool: %s\n' "$NDK/$tool" >&2; exit 1; }
done
for tool in cargo rustup tar; do
    command -v "$tool" >/dev/null 2>&1 || { printf 'Missing build tool: %s\n' "$tool" >&2; exit 1; }
done
for target in aarch64-linux-android armv7-linux-androideabi; do
    rustup target list --installed | grep -qx "$target" ||
        { printf 'Missing rust target: %s (rustup target add %s)\n' "$target" "$target" >&2; exit 1; }
done

# A fresh copy prevents stale sources from leaking into a rebuild. The release
# build tree of the checkout is skipped: it is gigabytes of host objects.
SCRATCH=$(mktemp -d "${TMPDIR:-/tmp}/niloofar-geph.XXXXXX")
trap 'rm -rf "$SCRATCH"' 0
trap 'exit 1' HUP INT TERM
printf 'Copying %s to %s\n' "$SRC" "$SCRATCH"
(cd "$SRC" && tar --exclude=./.git --exclude=./target --exclude=./dist -cf - .) |
    (cd "$SCRATCH" && tar xf -)

build() {
    abi=$1
    target=$2
    prefix=$3
    echo "Building $abi"
    mkdir -p "$OUT/$abi"
    # Android 15+ on a 16 KB-page kernel refuses to load a segment aligned to
    # less than 16384.
    env CARGO_TARGET_DIR="$SCRATCH/target" \
        "CARGO_TARGET_$(echo "$target" | tr 'a-z-' 'A-Z_')_LINKER=$NDK/${prefix}${API}-clang" \
        "CC_$target=$NDK/${prefix}${API}-clang" \
        "CXX_$target=$NDK/${prefix}${API}-clang++" \
        "AR_$target=$NDK/llvm-ar" \
        "RANLIB_$target=$NDK/llvm-ranlib" \
        "NM_$target=$NDK/llvm-nm" \
        "CFLAGS_$target=--target=${prefix}${API}" \
        "CXXFLAGS_$target=--target=${prefix}${API}" \
        "RUSTFLAGS=-C link-arg=-Wl,-z,max-page-size=16384" \
        cargo build --release --offline --target "$target" \
        --manifest-path "$SCRATCH/binaries/geph5-proxy/Cargo.toml"
    "$NDK/llvm-strip" -o "$OUT/$abi/libgeph.so" "$SCRATCH/target/$target/release/geph5-proxy"
    ls -l "$OUT/$abi/libgeph.so"
}

build armeabi-v7a armv7-linux-androideabi armv7a-linux-androideabi
build arm64-v8a aarch64-linux-android aarch64-linux-android
