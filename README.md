# Moth

An open-source Android VPN client built with Kotlin, Jetpack Compose, sing-box and Xray. Import a subscription, choose a server and connect.

[Download](https://github.com/Bu11t0N/Moth/releases/latest) · [Русский](README.ru.md) · [Report an issue](https://github.com/Bu11t0N/Moth/issues)

## Features

- Several subscriptions with their own name, traffic, end date, reminders and automatic updates, plus your own servers ("My servers").
- Add a subscription or a server by opening a link (Moth, v2rayNG, Happ, Hiddify and sing-box import links, and server links); Moth always asks first.
- Independent server selection for Wi-Fi and mobile data, favorites, and "Auto-select the best server".
- HTTPS checks through each server, TCP port checks and tunnel health checks.
- Kill switch, reconnection after network changes, start on boot and Android's Always-on VPN.
- Split tunneling by app, routing rules (domains, keywords, IP ranges: VPN, direct or block) and "Russian sites and IPs directly".
- DNS settings with DNS leak protection; DPI bypass (TLS fragmentation, UDP noise) and multiplexing, off by default.
- Traffic statistics, backup and restore (optionally password-protected), optional in-app updates.
- Quick Settings tile and home screen widget, both showing "Traffic blocked" / "Reconnecting…".
- English and Russian, system language detection and the Russian app name **Мотылёк**. A monochrome design with light, dark, pure black (AMOLED) and system themes.

Moth requires a subscription from a compatible provider or your own server. It does not supply VPN servers.

## Installation

Requires **Android 8.0 or newer**.

1. Download an APK from [Releases](https://github.com/Bu11t0N/Moth/releases/latest). Choose `arm64-v8a` for most modern phones; `armeabi-v7a` for older 32-bit ARM devices; or `x86_64` / `x86` for matching devices and emulators.
2. Install the APK and add the HTTPS subscription link supplied by the provider.
3. Open **Servers** and choose a server for Wi-Fi and mobile data.
4. Press the connection button on **Home** and grant Android's VPN permission.

To add the Quick Settings toggle, open **Settings → Interface → Add VPN button to Quick Settings** on Android 13+. Alternatively, edit Android's Quick Settings panel and drag **Moth** / **Мотылёк** into it. The first connection may open the app to request VPN permission.

Language is under **Settings → Interface**. The default follows the system; English and Russian can be selected explicitly. Android 13+ exposes the same choice in system app language settings.

Full version history: [CHANGELOG.md](CHANGELOG.md).

A developer log (Settings → For developers → Log) has levels, share and clear; it never records subscription links, keys or server addresses.

Test builds show a version label on the home screen: `./gradlew :app:assembleRelease -PversionSuffix=-beta.1` gives `0.6.1-beta.1`. A release build without `-PversionSuffix` has no label.

The application ID is `org.mothvpn.android` (since 0.4.5). Official APKs update each other without deleting app data; independently built APKs have a different signature.

## Supported subscriptions

Text and Base64-encoded URI lists, Clash YAML/JSON (an embedded `proxies` list or `proxy-providers` that link to other lists) and sing-box JSON configs are supported. Xray-core JSON configs (an array of configs, one config, or a list of outbounds) are also imported: VLESS and Hysteria 2 outbounds become servers, and a config with a balancer becomes one balancer server. Routing rules, DNS and inbounds from such configs are ignored.

| Protocol | Support |
| --- | --- |
| VLESS | TLS, REALITY, Vision; XHTTP through Xray |
| VMess | `alterId=0`, TLS; XHTTP through Xray |
| Trojan | TLS; XHTTP through Xray |
| Shadowsocks | SIP002, without plugins |
| Hysteria | v1 over UDP, with `upmbps` / `downmbps` |
| Hysteria2 | TLS, Salamander |
| TUIC | v5, UUID and password |
| AnyTLS | TLS |
| SOCKS5 / HTTP(S) | Username and password; marked "Unencrypted" without TLS |
| WireGuard | Keys, addresses, reserved bytes |
| SSH | Password or private key |
| ShadowTLS | v1–v3, as a hop for Shadowsocks |

TCP, WebSocket, gRPC and HTTPUpgrade are supported where applicable. XHTTP supports `auto`, `packet-up`, `stream-up` and `stream-one`.

Hysteria port hopping, TUIC v4, AmneziaWG, Shadowsocks plugins and Clash `dialer-proxy` chains are not supported. Clash routing rules and groups are not applied. Profiles that disable TLS verification are rejected. Unsupported profiles are counted during import.

## Connection checks

HTTPS checks establish a connection **through the selected server**. The default tries the configured URL, Cloudflare and Google; a custom URL can be used instead. Latency includes HTTPS connection setup and is not ICMP ping. Tunnel health is checked separately through Android's VPN network, without a direct-network fallback.

TCP checks only show whether a port is reachable; they do not verify VPN traffic and cannot check UDP protocols such as Hysteria or TUIC.

Checks run on the current physical network. Switch off Wi-Fi to test mobile data. Results are separated by network type, marked when the network changes and reset when the app process ends. The unavailable-server filter only hides failed HTTPS checks from the last 15 minutes on the current network; unchecked, cancelled and TCP checks remain visible.

## Privacy and security

- No advertising, analytics, accounts or hidden telemetry.
- Subscription links, profiles and settings are stored in AES-GCM encrypted files using Android Keystore. Configuration backup and core logs are disabled.
- Subscription links are sent to their subscription service, not to Moth's developers.
- HWID is a random installation UUID, not an IMEI or hardware serial number. Device model and Android version are sent by default, can be turned off or replaced with your own values. Device headers are restricted to the original HTTPS origin and are not forwarded across origins on redirects.
- Subscription and HWID fields are masked. Screenshots and screen sharing are allowed.

Subscription downloads and bootstrap DNS requests may use the direct connection. Traffic DNS inside the tunnel uses the selected server. Bootstrap DNS defaults to Cloudflare DoH and can be changed in Connection settings.

With the kill switch on, traffic is blocked while Moth reconnects. Android's Always-on VPN and “Block connections without VPN” are supported as well. Apps excluded by split tunneling cannot send traffic through the VPN. User-installed CA certificates are trusted for servers and subscription downloads. Comprehensive DNS/IPv6 leak testing is still pending. Availability depends on the provider, configuration and network conditions.

Never include subscription links, credentials or private keys in public issues or screenshots.

## Build

Android Studio, **JDK 21** and **Android SDK 34** are required. The Gradle 8.13 wrapper is included. Set `ANDROID_HOME` or `sdk.dir` in a local `local.properties` file.

The app needs `libbox.aar` in the repository root. On Linux, build it with Go 1.26.9, JDK 17 and Android NDK 28.0.13004108:

```bash
bash scripts/build-libbox.sh
# Switch JAVA_HOME to JDK 21 before building the Android app.
./gradlew :core:test :app:assembleRelease :app:lintRelease
```

[CI](.github/workflows/android.yml) builds the pinned native sources and publishes the AAR and APKs as workflow artifacts, and runs end-to-end tests on an emulator. Windows builds can use the AAR from a successful workflow. `scripts/fetch-libbox.ps1` is retained for the original prototype and does not fetch the current hybrid core.

APKs are generated at `app/build/outputs/apk/release/app-<ABI>-release.apk`. R8, resource shrinking, ABI splits and compressed native libraries reduce download size. Installed size is larger because native libraries are extracted.

Release builds are signed with the Moth keystore: set `MOTH_KEYSTORE_FILE` to the PKCS#12 file and `MOTH_KEYSTORE_PASSWORD` to its password (key alias `moth`). Without them, use `-PunsignedValidation=true` for an unsigned check build. Updating an existing installation requires the same signing key. Official release certificate SHA-256:

```text
e326f86f82b63071ee23a49b174b4cb5d25df1595e46c00571560a62f5ec210a
```

## Project structure

- `app/` — Compose UI, languages, Quick Settings, encrypted storage and Android VPN integration.
- `core/` — Android-independent Kotlin/JVM parser, models and configuration generation.
- `scripts/` — native builds, license notices and the end-to-end test servers.
- `e2e-helper/` — a tiny test app used only by the emulator tests (never shipped).
- `licenses/` — third-party notices included in the APK.

The native core uses **sing-box 1.12.25** for TUN and **Xray 26.7.28** for XHTTP. Sources are pinned by commit in the build script.

Android is the only implemented platform. Other operating systems require their own tunnel integration and interfaces; the Android UI is not directly portable to iOS.

## Contributing

Issues should include reproducible steps, Android version, device model and protocol/transport, with credentials removed. Submit changes through pull requests and run the tests, release build and Android Lint. Use fictional credentials in fixtures. Do not commit real subscriptions, signing keys, tokens or `local.properties`.

## License

Moth is licensed under [GPL-3.0-or-later](LICENSE). sing-box uses GPL-3.0-or-later with an additional naming/non-association condition; Xray uses MPL-2.0. Moth is an independent project and is not an official SagerNet client.

See [THIRD_PARTY.md](THIRD_PARTY.md) and [licenses/](licenses/) for notices and source details. The full source of each release, including the scripts that build the pinned native cores, is available in this repository at the release tag.

## Moth 0.4.5 and later

The application ID is `org.mothvpn.android`. Version 0.4.5 installs separately from older packages: save your subscription URL before removing the old app. Later versions update the new package normally.

Optional updates are available in Settings → Updates. Nothing downloads or installs automatically. Moth selects the APK for your architecture, verifies its checksum and signing certificate, then opens Android’s installer. Android may ask you to allow installations from Moth first.

### Release signing

Release builds require the dedicated Moth keystore. Set `MOTH_KEYSTORE_FILE` to its path and `MOTH_KEYSTORE_PASSWORD` securely; the alias is `moth`. Never generate a replacement key for an existing installation. Keep an offline backup of the keystore and password. GitHub Actions can use the encrypted `MOTH_KEYSTORE_BASE64` and `MOTH_KEYSTORE_PASSWORD` repository secrets. No key or password is stored in this repository or release assets.

Without signing secrets, CI produces explicitly unsigned validation APKs; these are not installable updates.

Moth release certificate SHA-256: `e326f86f82b63071ee23a49b174b4cb5d25df1595e46c00571560a62f5ec210a`.
