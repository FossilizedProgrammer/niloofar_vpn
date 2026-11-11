# نیلوفر وی‌پی‌ان (Niloofar VPN)

**English** · [فارسی](README.fa.md) · [Русский](README.ru.md) · [Español](README.es.md)

An Android client for the Firefox VPN engine in `../firefox-vpn-go`. The Go
engine is cross-compiled into `libffvpn.so`, exec'd from `nativeLibraryDir`,
and pointed at a free Firefox exit.

## How it connects

- **VPN mode (default).** The app opens a TUN interface and `libc2tun.so`
  (tun2socks) turns every packet from this phone into a SOCKS5 connection to
  the engine and out through the chosen Firefox exit. From the phone's side
  this is an ordinary system VPN: every app is covered and no proxy setting has
  to be entered anywhere.
- **Proxy mode.** No system VPN, no consent dialog: only the engine's local
  SOCKS (1080) and HTTP (1081) listeners run, for the apps you point at them
  yourself.

### Why sharing with other devices uses the proxy ports

`VpnService` only carries the traffic of apps running on the device that
created the tunnel. Android gives an app no way to route a tethered laptop or
a second phone through its VPN, so Share binds the engine's listeners to
`0.0.0.0` and the other device sets `<phone-ip>:1080` (SOCKS) or
`<phone-ip>:1081` (HTTP) as its proxy. The traffic still leaves the phone
through the same tunnel; only the hand-off to that device is a proxy, because
that is the only hand-off Android allows. So this is a VPN, not "only a
proxy" — proxy mode is a separate option you can pick instead.

## Features

- One-tap connect: the app fetches the exit list, probes every edge, dials the
  fastest one that actually carries traffic, and only then reports "Connected".
- **VPN mode** — a TUN interface is opened and `libc2tun.so` (tun2socks)
  forwards the device's traffic into the engine's SOCKS5 listener.
- **Proxy mode** — no system VPN, no consent dialog: only the local SOCKS/HTTP
  listeners run, for apps that you point at the proxy yourself.
- **Share on network** — the listeners bind to `0.0.0.0` so other devices on the
  same Wi-Fi/hotspot can use `SOCKS <ip>:1080` or `HTTP <ip>:1081`.
- Keep-alive: the local listener is probed every minute and a session that
  stops answering is rebuilt automatically instead of leaving a dead tunnel up.
- Sign-in on Mozilla's own page inside the app: email, password, two-factor
  code, the confirmation email and Mozilla's edge challenge are all handled by
  Mozilla's page, and the session is kept until Mozilla confirms it.
- Several accounts saved on the device with a one-tap switch, per-account
  quota, and a create-account guide that opens Mozilla's sign-up page.
- Live throughput/latency readout and an in-app log card: it shows the log's
  size, copies it to the clipboard, or clears it — the lines stay in memory and
  are never shown or written to disk.
- English/Persian/Russian/Spanish UI with an in-app language picker, and a
  sandstone rosette launcher icon.

## Build

Requirements: JDK 17+ (21 is used here), Android SDK with build-tools 35 and
NDK 26.3.11579264, Go for the engine.

```sh
./tools/build-apks.sh
```

This runs `assembleRelease` and copies the splits into `dist/`. The same build
runs on GitHub: `.github/workflows/build-apks.yml` builds every push, pull
request and tag. Pushes to `main` refresh the **Latest build** release and tags
create a numbered one, so the APKs in Releases are never older than `main`, and
every run also attaches them to the run as the `NiloofarVPN-apks` artifact.
Binaries never live in the repository.

| File | ABI |
| --- | --- |
| `dist/NiloofarVPN-1.1.1-armeabi-v7a.apk` | 32-bit ARM |
| `dist/NiloofarVPN-1.1.1-arm64-v8a.apk` | 64-bit ARM |
| `dist/NiloofarVPN-1.1.1-universal.apk` | both |

Release builds are signed with the fixed key in `signing/niloofar.keystore`, so
every build — local or CI — installs over the previous one. APKs from releases
up to 1.1.0 were signed with a throwaway key generated on each CI run; if one of
those is already installed, uninstall it once before installing 1.1.1.

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

The script copies `../firefox-vpn-go` to a scratch directory, applies the
patches in `tools/ffvpn/` (v2 quick-stretch salt, cached session token,
`--list-countries --json`, TLS handshake timeout, image-answer prompt), and
cross-compiles for both ABIs with the NDK clang, using cgo so Android's
resolver is used.

## Structure

```
app/src/main/java/com/ffvpn/client/
  MainActivity.kt            UI host, VPN consent, notifications permission
  SignInActivity.kt          Firefox Account form, relays the engine's prompts
  WebSignInActivity.kt       Mozilla's own sign-in page in a WebView
  AppLocale.kt               in-app language override
  ui/                        Compose screens and theme
  vpn/FirefoxVpnService.kt   foreground service, TUN setup, keep-alive, share
  vpn/FirefoxEngine.kt       engine process, probing, traffic check
  vpn/FxaLogin.kt            account sign-in, token cached for later connects
  vpn/ServerList.kt          exit list parsing, probing and ranking
  vpn/Logs.kt                in-memory log the settings card copies or clears
  vpn/Store.kt, State.kt     preferences and observable UI state
  vpn/NetInfo.kt             LAN address shown when sharing
app/src/main/java/com/multicore/vpn/tunnel/
  Tun2Socks.kt               JNI bridge to libc2tun (package fixed by the .so)
```

## Limitations

- Devices sharing the connection go through the proxy ports, not the tunnel:
  Android's VPN does not cover tethered clients, so a device on your hotspot
  must set the proxy to `<phone-ip>:1080` (SOCKS) or `<phone-ip>:1081` (HTTP)
  manually. See "Why sharing with other devices uses the proxy ports".
- Changing mode or the share switch takes effect on the next connect; the
  running session is not restarted automatically.
- Proxy mode has no kill switch and no per-app routing.
