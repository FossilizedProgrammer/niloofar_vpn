# OpenRung engine

`libopenrung.so` in `app/src/main/jniLibs/{armeabi-v7a,arm64-v8a}` is the desktop
CLI (`cmd/client`) of [openrung/openrung](https://github.com/openrung/openrung),
cross-built for Android. It is the shared `connectcore` engine driving a bundled
sing-box; the relays are VLESS + REALITY. The app execs it from
`nativeLibraryDir` in zero-privilege proxy mode (`connect -headless -relay-id`)
and bridges its loopback SOCKS inbound to the TUN with Tun2Socks, exactly like
the Firefox / Lantern / SSTP cores.

## Rebuild

```sh
OPENRUNG_SRC=/path/to/openrung ./tools/openrung/build-android.sh
```

Defaults to a sibling `../openrung` checkout and NDK `26.3.11579264`. The build
pins `GOTOOLCHAIN=go1.25.5` (newer Go breaks the `x/net/http2` linkname) and
`-tags with_utls` (REALITY needs uTLS). See the script header for the rest.

## Patches against upstream

- `relays-subcommand.patch` — adds a `relays` subcommand that prints the usable
  signed relay directory, ranked, as one `OPENRUNG_RELAYS_JSON` line. The app
  fetches, TCP-probes and ranks this list before dialling (`OpenRungServers.kt`).
- `android-proxy-interface.patch` — gates sing-box's `auto_detect_interface` to
  TUN mode. In proxy mode it bound every outbound socket to the default
  interface (`SO_BINDTODEVICE`), which is `EPERM` under an unprivileged Android
  app UID and killed every relay dial. Proxy mode has no TUN to bypass: the app
  UID is already exempt via `addDisallowedApplication`.

## License

OpenRung is GPL-3.0-or-later. Linking it into Niloofar makes the combined work
GPL; the corresponding source must include these patches. Keep them alongside
the binary so the `.so` stays reproducible and auditable.