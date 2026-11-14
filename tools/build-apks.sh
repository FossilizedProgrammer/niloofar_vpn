#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

GRADLE="${GRADLE:-./gradlew}"
OUT="dist"
APK_DIR="app/build/outputs/apk/release"
ABIS=(armeabi-v7a arm64-v8a)
LIBRARIES=(
    libaether.so libc2tun.so libffvpn.so liblantern.so liblyrebird.so
    libsstp.so libsunlion.so libsunlionbridge.so libtor.so libtorrunner.so
)
ASSETS=(
    ca_roots.pem psiphon_embedded_entries.txt psiphon_servers.bin
    sstp_servers.txt tor_obfs4_bridges.txt
)

MANIFEST="tools/cores.manifest"
GRADLE_FILE="app/build.gradle.kts"
BUMP_BACKUP=""

# A bumped version that never shipped would leave the next build with a
# versionCode gap, and the in-app update check compares versionName.
restore_version() {
    [ -n "$BUMP_BACKUP" ] || return 0
    cp "$BUMP_BACKUP" "$GRADLE_FILE"
    rm -f "$BUMP_BACKUP"
    printf '%s\n' 'Release did not finish; versionName and versionCode were restored.' >&2
}
trap restore_version EXIT

CLEAN=0
BUMP=0
CHECK=0
RECORD=0
for arg in "$@"; do
    case "$arg" in
        --clean) CLEAN=1 ;;
        --bump) BUMP=1 ;;
        --check) CHECK=1 ;;
        --update-manifest) RECORD=1 ;;
        -h|--help)
            printf '%s\n' \
                'Usage: ./tools/build-apks.sh [--clean] [--bump] [--check] [--update-manifest]' \
                'Build signed multicore APKs for ARM32, ARM64 and both (universal).' \
                'All prebuilt core libraries and bootstrap assets must be present.' \
                'This packages the native engines; it does not compile their sources.' \
                '--clean: run Gradle clean before assembling.' \
                '--bump: ask for versionName and increment versionCode; restored if the build fails.' \
                '--check: run the preflight checks for a complete and ABI-correct' \
                '         multicore tree, then exit without building.' \
                '--update-manifest: record the current native libraries in tools/cores.manifest.' \
                '         Required after dropping in a new engine build.' \
                'Upgrade procedure for a new core: docs/core-upgrade.md' \
                'Uses ./gradlew and Java from JAVA_HOME or PATH; no environment override is required.' \
                'READELF and APKSIGNER override the discovered readelf and apksigner binaries.'
            exit 0
            ;;
        *) printf 'Unknown option: %s\n' "$arg" >&2; exit 2 ;;
    esac
done

missing=0
for abi in "${ABIS[@]}"; do
    for library in "${LIBRARIES[@]}"; do
        file="app/src/main/jniLibs/$abi/$library"
        if [ ! -s "$file" ]; then
            printf 'Missing or empty native library: %s\n' "$file" >&2
            missing=1
        fi
    done
done
for asset in "${ASSETS[@]}"; do
    file="app/src/main/assets/$asset"
    if [ ! -s "$file" ]; then
        printf 'Missing or empty asset: %s\n' "$file" >&2
        missing=1
    fi
done
if [ "$missing" = 1 ]; then
    printf '%s\n' 'Restore the required multicore binaries/assets before building; an incomplete APK will not be built.' >&2
    exit 1
fi

# A core built for the wrong ABI packages fine and only fails when the app calls
# dlopen on the device, so every library is checked against the directory it
# ships in here rather than after a release is in someone's hands. The page
# alignment only warns: a 4 KB-aligned core still loads on 4 KB-page devices but
# is rejected outright by Android 15+ with 16 KB pages.
READELF=${READELF:-}
if [ -z "$READELF" ]; then
    for candidate in llvm-readelf readelf; do
        if command -v "$candidate" >/dev/null 2>&1; then
            READELF=$candidate
            break
        fi
    done
fi
if [ -z "$READELF" ]; then
    printf '%s\n' \
        'No llvm-readelf/readelf found, so the cores cannot be checked against their ABI' \
        'directories or for the entry points the bridges resolve. Install binutils (or an' \
        'NDK, and point READELF at its llvm-readelf) before building a release.' >&2
    exit 1
fi

bad=0
unaligned=()
for abi in "${ABIS[@]}"; do
    case "$abi" in
        armeabi-v7a) want_class=ELF32; want_machine=ARM ;;
        arm64-v8a) want_class=ELF64; want_machine=AArch64 ;;
    esac
    for library in "${LIBRARIES[@]}"; do
        file="app/src/main/jniLibs/$abi/$library"
        [ -s "$file" ] || continue
        if ! header="$("$READELF" -h "$file" 2>/dev/null)"; then
            printf 'Not a readable ELF library: %s\n' "$file" >&2
            bad=1
            continue
        fi
        class=$(printf '%s\n' "$header" | sed -n 's/^ *Class: *//p')
        machine=$(printf '%s\n' "$header" | sed -n 's/^ *Machine: *//p')
        if [ "$class" != "$want_class" ] || [ "$machine" != "$want_machine" ]; then
            printf 'Wrong ABI: %s is %s/%s, expected %s/%s.\n' \
                "$file" "$class" "$machine" "$want_class" "$want_machine" >&2
            bad=1
        fi
        # Every LOAD segment counts: one 4 KB segment is enough for the loader to
        # reject the whole library. Only arm64 is checked, because a 16 KB-page
        # device runs 64-bit code and never loads the armeabi-v7a tree at all.
        if [ "$abi" = arm64-v8a ]; then
            aligns=$("$READELF" -lW "$file" 2>/dev/null | awk '/ LOAD /{print $NF}' | sort -u)
            for align in $aligns; do
                if [ "$((align))" -lt 16384 ]; then
                    unaligned+=("$file")
                    break
                fi
            done
        fi
    done
done
if [ "${#unaligned[@]}" -gt 0 ]; then
    printf 'Warning: %s libraries are not 16 KB page aligned and are rejected by Android 15+ on 16 KB-page devices:\n' \
        "${#unaligned[@]}" >&2
    printf '  %s\n' "${unaligned[@]}" >&2
    printf '%s\n' 'Rebuild them with -Wl,-z,max-page-size=16384, then run ./tools/build-apks.sh --update-manifest.' >&2
fi
# The bridges dlopen the engine and resolve exactly these two entry points. A new
# engine release that renames them, or is dropped in as the wrong file, still
# parses as a valid ARM library and only fails as a failed connect on a phone.
for abi in "${ABIS[@]}"; do
    for engine in sunlion; do
        file="app/src/main/jniLibs/$abi/lib$engine.so"
        exports=$("$READELF" --dyn-syms -W "$file" 2>/dev/null |
            awk '$4 == "FUNC" && $7 != "UND" { print $8 }')
        for symbol in PsiphonTunnelStart PsiphonTunnelStop; do
            if ! printf '%s\n' "$exports" | grep -qx "$symbol"; then
                printf 'Missing entry point: %s does not export %s, which lib%sbridge.so resolves with dlsym.\n' \
                    "$file" "$symbol" "$engine" >&2
                bad=1
            fi
        done
    done
done
if [ "$bad" = 1 ]; then
    printf '%s\n' 'The jniLibs tree does not match its ABI directories or the entry points the bridges resolve; fix the cores before building.' >&2
    exit 1
fi

# Every shipped binary is recorded so that a replaced core is visible in review
# and a stale manifest is caught instead of being packaged silently.
if [ "$RECORD" = 1 ]; then
    {
        printf '%s\n' \
            '# sha256 of every prebuilt native library shipped in app/src/main/jniLibs.' \
            '# Paths are relative to that directory; verify with ./tools/build-apks.sh --check.' \
            '# Regenerate after replacing a core: ./tools/build-apks.sh --update-manifest'
        for abi in "${ABIS[@]}"; do
            for library in "${LIBRARIES[@]}"; do
                ( cd app/src/main/jniLibs && sha256sum "$abi/$library" )
            done
        done
        if command -v go >/dev/null 2>&1; then
            printf '\n# Build provenance from `go version -m`, informational only:\n'
            for abi in "${ABIS[@]}"; do
                for engine in libsunlion.so; do
                    info=$( cd app/src/main/jniLibs && go version -m "$abi/$engine" 2>/dev/null |
                        sed -n '1p;/^[[:space:]]*build/p' | tr '\n' ' ' ) || info=
                    [ -n "$info" ] && printf '# %s\n' "$(printf '%s' "$info" | tr -s ' ')"
                done
            done
        fi
    } > "$MANIFEST"
    printf 'Recorded %s native libraries in %s.\n' \
        "$(( ${#ABIS[@]} * ${#LIBRARIES[@]} ))" "$MANIFEST"
    exit 0
fi

if [ ! -s "$MANIFEST" ]; then
    printf 'Missing %s: run ./tools/build-apks.sh --update-manifest to record the shipped cores.\n' "$MANIFEST" >&2
    exit 1
fi
changed=0
for abi in "${ABIS[@]}"; do
    for library in "${LIBRARIES[@]}"; do
        key="$abi/$library"
        want=$(awk -v key="$key" '$2 == key { print $1; exit }' "$MANIFEST")
        if [ -z "$want" ]; then
            printf 'Unrecorded native library: %s has no entry in %s.\n' "$key" "$MANIFEST" >&2
            changed=1
            continue
        fi
        have=$( cd app/src/main/jniLibs && sha256sum "$key" | awk '{ print $1 }' )
        if [ "$have" != "$want" ]; then
            printf 'Changed native library: %s\n  manifest %s\n  on disk  %s\n' "$key" "$want" "$have" >&2
            changed=1
        fi
    done
done
if [ "$changed" = 1 ]; then
    printf '%s\n' \
        'The native tree differs from its manifest. After a deliberate core upgrade run' \
        './tools/build-apks.sh --update-manifest and review the diff (it shows the sha256 and' \
        'the Go build tags of the new engine). Also re-run ./tools/build-apks.sh --check.' >&2
    exit 1
fi

if [ "$CHECK" = 1 ]; then
    printf '%s\n' 'Preflight OK: assets present; every core matches its ABI, exports the entry points the bridges resolve, and matches its manifest.'
    exit 0
fi

VERSION="$(sed -nE 's/^[[:space:]]*versionName = "([^"]+)".*/\1/p' "$GRADLE_FILE")"
CODE="$(sed -nE 's/^[[:space:]]*versionCode = ([0-9]+).*/\1/p' "$GRADLE_FILE")"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
    printf '%s\n' 'Could not read a numeric major.minor.patch versionName.' >&2
    exit 1
}
[[ "$CODE" =~ ^[1-9][0-9]{0,9}$ ]] && [ "$CODE" -le 2100000000 ] || {
    printf '%s\n' 'Could not read a valid Android versionCode.' >&2
    exit 1
}

if [ "$BUMP" = 1 ]; then
    read -r -p "New versionName (current $VERSION): " next
    [[ "$next" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] && [ "$next" != "$VERSION" ] || {
        printf '%s\n' 'Enter a different numeric major.minor.patch version.' >&2
        exit 1
    }
    [ "$CODE" -lt 2100000000 ] || {
        printf '%s\n' 'versionCode has reached the Android limit.' >&2
        exit 1
    }
    CODE=$((CODE + 1))
    BUMP_BACKUP=$(mktemp)
    cp "$GRADLE_FILE" "$BUMP_BACKUP"
    sed -i -E \
        -e "s/^( *versionName = )\"[^\"]+\"/\1\"$next\"/" \
        -e "s/^( *versionCode = )[0-9]+/\1$CODE/" \
        "$GRADLE_FILE"
    VERSION="$next"
fi

printf 'Niloofar VPN %s (%s) — multicore release build\n' "$VERSION" "$CODE"

# The user-level SOCKS proxy rejects Gradle requests on this network.
GRADLE_ENV=(env -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY -u all_proxy -u ALL_PROXY)
# clean runs on its own: with org.gradle.parallel a combined invocation lets it
# delete outputs that assembleRelease is already writing.
if [ "$CLEAN" = 1 ]; then
    "${GRADLE_ENV[@]}" "$GRADLE" --console=plain -DsocksProxyHost= :app:clean
fi
"${GRADLE_ENV[@]}" "$GRADLE" --console=plain -DsocksProxyHost= :app:assembleRelease

for abi in "${ABIS[@]}" universal; do
    src="$APK_DIR/app-$abi-release.apk"
    [ -s "$src" ] || { printf 'Missing APK: %s\n' "$src" >&2; exit 1; }
done

# Gradle signs the APK, but only apksigner says whether the result actually
# verifies. It takes the minimum SDK from the APK's own manifest, so the v2
# scheme is required exactly where the installed app needs it.
SDK_DIR=$(sed -nE 's/^sdk\.dir=//p' local.properties 2>/dev/null | tail -n 1 || true)
SDK_DIR=${SDK_DIR:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}}
APKSIGNER=${APKSIGNER:-}
if [ -z "$APKSIGNER" ] && [ -n "$SDK_DIR" ]; then
    APKSIGNER=$(find "$SDK_DIR/build-tools" -maxdepth 2 -name apksigner -type f 2>/dev/null |
        sort -V | tail -n 1 || true)
fi
if [ -z "$APKSIGNER" ]; then
    printf '%s\n' \
        'No apksigner found under the SDK build-tools, so the signed APKs cannot be verified.' \
        'Install the Android SDK build-tools, or point APKSIGNER at the binary.' >&2
    exit 1
fi
for abi in "${ABIS[@]}" universal; do
    "$APKSIGNER" verify --verbose "$APK_DIR/app-$abi-release.apk"
done

mkdir -p "$OUT"
for abi in "${ABIS[@]}" universal; do
    src="$APK_DIR/app-$abi-release.apk"
    dst="$OUT/NiloofarVPN-$VERSION-$abi.apk"
    cp "$src" "$dst"
    sha256sum "$dst"
done
# The release is complete, so the bumped version is kept.
if [ -n "$BUMP_BACKUP" ]; then
    rm -f "$BUMP_BACKUP"
    BUMP_BACKUP=""
fi
printf 'Release ready in %s/ (%s, versionCode %s).\n' "$OUT" "$VERSION" "$CODE"
