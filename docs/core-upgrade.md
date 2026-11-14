# Upgrading Psiphon or Sun and Lion

Psiphon is the official `ca.psiphon.aar` from
`Psiphon-Labs/psiphon-tunnel-core-binaries` (`app/libs/`), consumed through its
`psi.Psi` gomobile API. Upgrading it is replacing that file; nothing in
`app/src/main/jniLibs` belongs to it. The vendored `.so` it used to be was built
on `clientlib`, whose `StartTunnel` closes its "connected" channel from the
notice callback on every tunnel registration, so a tunnel replacement panicked
the Go runtime (`close of closed channel`).

Sun and Lion is still a vendored prebuilt `.so`. A new release is a file
replacement, so nothing in the compiler can protect us: the checks below exist
to make the upgrade boring instead of an incident.

## Why an overlap crashes the process

Sun and Lion keeps a single tunnel pointer and a single start-result buffer in
process globals, and its `Stop()` also clears the library's "already started"
guard while a start may still be running. If a start and a stop (or two starts)
overlap, the failing one stores a nil tunnel, the succeeding one dereferences it,
and the Go runtime aborts the process:

```
panic: runtime error: invalid memory address or nil pointer dereference
goroutine 17 [running, locked to thread]:
main.SunAndLionTunnelStart(...) PsiphonTunnel.go:249
```

Java cannot catch that. The protection is architectural, and a new engine
version does not change it:

- `com.multicore.vpn.tunnel.NativeGate` is the only lock, one per engine.
- `SunAndLionBridge` is `private` to its file, so no code
  outside `dev/…/tunnel/PsiphonNative.kt` / `SunAndLionNative.kt` can reach JNI.
- `NativeBridgeDisciplineTest` fails if a new bridge or a direct native call
  appears without the gate.

## Replacing a core

1. Put the new library in place, **both ABIs**:

   ```
   app/src/main/jniLibs/armeabi-v7a/libsunlion.so
   app/src/main/jniLibs/arm64-v8a/libsunlion.so
   ```

   (Do not touch `libsunlionbridge.so` — that is our shim, not the engine. For
   Psiphon, replace `app/libs/ca.psiphon.aar` instead.)

2. Run the preflight:

   ```
   ./tools/build-apks.sh --check
   ```

   It verifies, and the release build refuses to run when any of these fail:

   | check | what it protects against |
   | --- | --- |
   | ELF Class/Machine per ABI folder | a 32-bit or wrong-arch file packaged into `arm64-v8a` |
   | page alignment (warning) | a core that cannot load on Android 15+ 16 KB pages |
   | exports `PsiphonTunnelStart`/`PsiphonTunnelStop` | a release that renamed the entry points the Sun and Lion bridge resolves with `dlsym` |
   | sha256 matches `tools/cores.manifest` | a half-copied, corrupted or accidentally swapped binary |

   Expected output: `Preflight OK: …`.

3. Record the deliberate change:

   ```
   ./tools/build-apks.sh --update-manifest
   git diff tools/cores.manifest
   ```

   The diff shows the new `sha256` and the engine's Go build information
   (`go version -m`: `buildRev`, `buildDate`, `-tags`, `-ldflags`). Keep it in
   the commit: that line is how a future bug report gets its engine revision.

4. Run the tests, then the release build:

   ```
   ./gradlew :app:testDebugUnitTest --offline
   ./tools/build-apks.sh
   ```

## What to smoke-test on a device

Some failures are behaviour, not linkage, so they only appear here. For **each**
engine (Psiphon and Sun and Lion):

1. Connect, then disconnect while it is still dialling. Repeat several times.
   This is the sequence that used to panic.
2. Connect, disconnect, immediately connect again.
3. While connected, switch network (Wi-Fi ↔ mobile data) to force the
   reconnect path, which internally does stop + start.
4. Confirm the tunnel actually carries traffic; a core that starts but does not
   proxy looks healthy in the log.

## If something fails

- **`Missing entry point`** — the new release changed or dropped the exported
  symbol. The bridge has to be rebuilt against the new engine, and
  `SunAndLionBridge` must keep its class name: the exported
  JNI symbols are `Java_com_multicore_vpn_tunnel_<Class>_native<Method>`.
- **`Wrong ABI`** — the file does not match its ABI folder; get the matching
  build.
- **`Changed native library`** — expected after step 1, until step 3 records it.
- **It starts but never connects** — the engine's JSON contract (`{"Code":0}`)
  or the config schema changed. The result is surfaced as a start error in the
  log; compare the new engine's `PsiphonTunnelStart` result handling. (Psiphon's
  AAR exposes no such entry points: failures show up as an `Exception` from
  `psi.Psi.start` or as an `EstablishTunnelTimeout` notice.)
