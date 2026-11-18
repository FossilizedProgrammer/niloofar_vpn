# Fluxcore engine

`libfluxcore.so` in `app/src/main/jniLibs/{armeabi-v7a,arm64-v8a}` is the
[Fluxcore](https://github.com/FossilizedProgrammer/fluxcore) CLI cross-built for
Android. It is a local SOCKS5/HTTP proxy whose WireGuard and AmneziaWG tunnels
live in gVisor's userspace netstack — no TUN, no root, no system routes — and it
discovers the fleet, enrols an identity and dials the fastest gateway that
answers.

The app execs it from `nativeLibraryDir` in zero-privilege proxy mode
(`run --listen 127.0.0.1:<socks> --http-listen 127.0.0.1:<http>`) and bridges
its loopback SOCKS inbound to the TUN with Tun2Socks, exactly like the Firefox /
OpenRung / Tor cores.

## Rebuild

```sh
FLUXCORE_SRC=/path/to/fluxcore ./tools/fluxcore/build-android.sh
```

Defaults to a sibling `../fluxcore` checkout and NDK `26.3.11579264`. The build
is `GOOS=android` with cgo (bionic's resolver, because a cgo-free Go binary
cannot resolve names on Android) and links with
`-Wl,-z,max-page-size=16384` for Android 15+ 16 KB-page devices.

## Runtime notes

- The engine stores its identity, config and fleet cache under
  `os.UserConfigDir()/fluxcore`. Android sets no `HOME`, so `FluxcoreCore`
  starts the process with `HOME` pointed at the app's `filesDir`; without it the
  engine cannot create the identity it enrols with.
- Readiness is the engine's own `connected` line, printed after the WireGuard
  handshake. The app then checks that the local SOCKS listener actually carries
  traffic before reporting "Connected".
- A lost tunnel is reconnected by the engine itself, and `tunnel lost;
  reconnecting…` is surfaced in the UI state.
- `SSL_CERT_FILE` is handed to the process from the app's CA bundle
  (`CaBundle`), so discovery does not depend on the device's frozen root store.

## License

Fluxcore is MIT-licensed. Its `LICENSE` and `NOTICE` (CumulusVPN attribution)
cover the sources this binary is built from; keep them alongside it so the
`.so` stays reproducible and auditable.
