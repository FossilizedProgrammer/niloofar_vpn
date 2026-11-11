# نیلوفر وی‌پی‌ان (Niloofar VPN)

An Android client for the Firefox VPN engine in `../firefox-vpn-go`. The Go
engine is cross-compiled into `libffvpn.so`, exec'd from `nativeLibraryDir`,
and pointed at a free Firefox exit; the app either wraps the device traffic in
a TUN tunnel (VPN mode) or exposes the engine's local listeners (proxy mode).

## Features

- One-tap connect: the app fetches the exit list, probes every edge, dials the
  fastest one that actually carries traffic, and only then reports "Connected".
- **VPN mode** — a TUN interface is opened and `libc2tun.so` (tun2socks)
  forwards the device's traffic into the engine's SOCKS5 listener.
- **Proxy mode** — no system VPN, no consent dialog: only the local SOCKS/HTTP
  listeners run, for apps that you point at the proxy yourself.
- **Share on network** — the listeners bind to `0.0.0.0` so other devices on the
  same Wi-Fi/hotspot can use `SOCKS <ip>:1080` or `HTTP <ip>:1081`.
- Live throughput/latency readout, per-account quota, Firefox Account sign-in
  (the engine does the login, including two-factor codes, the confirmation
  email and Mozilla's edge challenge), a create-account guide that opens
  Mozilla's sign-up page, several accounts saved on the device with a one-tap
  switch, English/Persian/Russian/Spanish UI with an in-app language picker,
  and a sandstone rosette launcher icon.

## Build

Requirements: JDK 17+ (21 is used here), Android SDK with build-tools 35 and
NDK 26.3.11579264, Go for the engine.

```sh
./tools/build-apks.sh
```

This runs `assembleRelease` and copies the splits into `dist/`. The same build
runs on GitHub: `.github/workflows/build-apks.yml` builds every push, pull
request and tag, and attaches the three APKs to the run as the
`NiloofarVPN-apks` artifact, so binaries never live in the repository.

| File | ABI |
| --- | --- |
| `dist/NiloofarVPN-1.0.0-armeabi-v7a.apk` | 32-bit ARM |
| `dist/NiloofarVPN-1.0.0-arm64-v8a.apk` | 64-bit ARM |
| `dist/NiloofarVPN-1.0.0-universal.apk` | both |

Release builds are signed with the debug keystore so they install as-is;
replace `signingConfig` in `app/build.gradle.kts` with a real keystore before
publishing.

### Dependencies and network

`settings.gradle.kts` resolves through the Tencent and Myket Maven mirrors
first, because `dl.google.com` is unreliable from this machine. The user-level
`~/.gradle/gradle.properties` sets a SOCKS proxy that Java cannot use, so the
build script passes `-DsocksProxyHost=` to turn it off and also clears the
environment proxy variables.

### Rebuilding the engine

```sh
./tools/ffvpn/build-android.sh
```

The script copies `../firefox-vpn-go` to a scratch directory, applies the four
patches in `tools/ffvpn/` (v2 quick-stretch salt, cached session token,
`--list-countries --json`, TLS handshake timeout), and cross-compiles for both
ABIs with the NDK clang, using cgo so Android's resolver is used.

## Structure

```
app/src/main/java/com/ffvpn/client/
  MainActivity.kt            UI host, VPN consent, notifications permission
  SignInActivity.kt          Firefox Account form, relays the engine's prompts
  AppLocale.kt               in-app language override
  ui/                        Compose screens and theme
  vpn/FirefoxVpnService.kt   foreground service, TUN setup, session mode/share
  vpn/FirefoxEngine.kt       engine process, probing, traffic check
  vpn/FxaLogin.kt            account sign-in, token cached for later connects
  vpn/ServerList.kt          exit list parsing, probing and ranking
  vpn/Store.kt, State.kt     preferences and observable UI state
  vpn/NetInfo.kt             LAN address shown when sharing
app/src/main/java/com/multicore/vpn/tunnel/
  Tun2Socks.kt               JNI bridge to libc2tun (package fixed by the .so)
```

## Limitations

- Sharing works through the proxy ports, not the tunnel: Android's VPN does not
  cover tethered clients, so a device on your hotspot must set the proxy to
  `<phone-ip>:1080` (SOCKS) or `<phone-ip>:1081` (HTTP) manually.
- Changing mode or the share switch takes effect on the next connect; the
  running session is not restarted automatically.
- Proxy mode has no kill switch and no per-app routing.
