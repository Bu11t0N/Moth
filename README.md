# Moth

An open-source Android VPN client built with Kotlin, Jetpack Compose, sing-box and Xray. Import a subscription, choose a server and connect.

[Download](https://github.com/Bu11t0N/Moth/releases/latest) · [Русский](README.ru.md) · [Report an issue](https://github.com/Bu11t0N/Moth/issues)

## Features

- Three tabs: Home, Servers and Settings.
- Independent server selection for Wi-Fi and mobile data, with all profiles available in both lists.
- URI, Base64 and Clash subscriptions, including VLESS XHTTP, Hysteria and TUIC.
- HTTPS checks through each server, TCP port checks, tunnel health checks and a small download test.
- Search, compact rows, latency sorting and filtering of recently unavailable servers.
- Subscription expiration and traffic information when supplied by the provider.
- Persistent HWID and configurable subscription headers.
- English and Russian, system language detection and the Russian app name **Мотылёк**.
- A Quick Settings VPN toggle using the selected server for the current network.
- Light, dark and system themes. Settings apply automatically.

Moth requires a subscription from a compatible provider. It does not supply VPN servers. Automatic server selection, reconnection and background subscription updates are planned.

## Installation

Requires **Android 8.0 or newer**.

1. Download an APK from [Releases](https://github.com/Bu11t0N/Moth/releases/latest). Choose `arm64-v8a` for most modern phones, including the Galaxy S24 Ultra; `armeabi-v7a` for older 32-bit ARM devices; or `x86_64` / `x86` for matching devices and emulators.
2. Install the APK and add the HTTPS subscription link supplied by the provider.
3. Open **Servers** and choose a server for Wi-Fi and mobile data.
4. Press the connection button on **Home** and grant Android's VPN permission.

To add the Quick Settings toggle, open **Settings → Appearance → Add VPN button to Quick Settings** on Android 13+. Alternatively, edit Android's Quick Settings panel and drag **Moth** / **Мотылёк** into it. The first connection may open the app to request VPN permission.

Language is under **Settings → Appearance**. The default follows the system; English and Russian can be selected explicitly. Android 13+ exposes the same choice in system app language settings.

Full version history: [CHANGELOG.md](CHANGELOG.md).

0.4.2 adds a developer log (Settings → For developers → Log) with levels, share and clear; it never records subscription links, keys or server addresses.

Test builds show a version label on the home screen: `./gradlew :app:assembleRelease -PversionSuffix=rc6` gives `v0.4.2rc6`. A release build without `-PversionSuffix` has no label.

Official 0.4.1 APKs update official 0.2, 0.3 and 0.4.0 APKs without deleting app data. The application ID and signing certificate are unchanged. The original GitHub 0.1 APK and independently built APKs can have different signatures.

## Supported subscriptions

Text and Base64-encoded URI lists and Clash YAML/JSON with an embedded `proxies` list are supported.

| Protocol | Support |
| --- | --- |
| VLESS | TLS, REALITY, Vision; XHTTP through Xray |
| VMess | `alterId=0`, TLS; XHTTP through Xray |
| Trojan | TLS; XHTTP through Xray |
| Shadowsocks | SIP002, without plugins |
| Hysteria | v1 over UDP, with `upmbps` / `downmbps` |
| Hysteria2 | TLS, Salamander |
| TUIC | v5, UUID and password |

TCP, WebSocket, gRPC and HTTPUpgrade are supported where applicable. XHTTP supports `auto`, `packet-up`, `stream-up` and `stream-one`.

Hysteria port hopping, TUIC v4, Shadowsocks plugins, external Clash `proxy-providers` and `dialer-proxy` chains are not supported. Clash routing rules and groups are not applied. Profiles that disable TLS verification are rejected. Unsupported profiles are counted during import.

## Connection checks

HTTPS checks establish a connection **through the selected server**. The default tries the configured URL, Cloudflare and Google; a custom URL can be used instead. Latency includes HTTPS connection setup and is not ICMP ping. Tunnel health is checked separately through Android's VPN network, without a direct-network fallback.

TCP checks only show whether a port is reachable; they do not verify VPN traffic and cannot check UDP protocols such as Hysteria or TUIC. The speed icon downloads at most 1 MiB from Cloudflare and gives an approximate short-download rate.

Checks run on the current physical network. Switch off Wi-Fi to test mobile data. Results are separated by network type, marked when the network changes and reset when the app process ends. The unavailable-server filter only hides failed HTTPS checks from the last 15 minutes on the current network; unchecked, cancelled and TCP checks remain visible.

## Privacy and security

- No advertising, analytics, accounts or hidden telemetry.
- Subscription links, profiles and settings are stored in AES-GCM encrypted files using Android Keystore. Configuration backup and core logs are disabled.
- Subscription links are sent to their subscription service, not to Moth's developers.
- HWID is a random installation UUID, not an IMEI or hardware serial number. Device model and Android version are optional. Device headers are restricted to the original HTTPS origin and are not forwarded across origins on redirects.
- Subscription and HWID fields are masked. Screenshots and screen sharing are allowed.

Subscription downloads and bootstrap DNS requests may use the direct connection. Traffic DNS inside the tunnel uses the selected server. Bootstrap DNS defaults to Cloudflare DoH and can be changed in Connection settings.

Automatic recovery is not implemented. Android provides Always-on VPN and “Block connections without VPN”. Comprehensive DNS/IPv6 leak testing is still pending. Availability depends on the provider, configuration and network conditions.

Never include subscription links, credentials or private keys in public issues or screenshots.

## Build

Android Studio, **JDK 21** and **Android SDK 34** are required. The Gradle 8.13 wrapper is included. Set `ANDROID_HOME` or `sdk.dir` in a local `local.properties` file.

The app needs `libbox.aar` in the repository root. On Linux, build it with Go 1.24.3, JDK 17 and Android NDK 28.0.13004108:

```bash
bash scripts/build-libbox.sh
# Switch JAVA_HOME to JDK 21 before building the Android app.
./gradlew :core:test :app:assembleRelease :app:lintRelease
```

[CI](.github/workflows/android.yml) builds the pinned native sources and publishes the AAR, APKs, native source archive and Go dependencies as workflow artifacts. Windows builds can use the AAR from a successful workflow. `scripts/fetch-libbox.ps1` is retained for the original prototype and does not fetch the current hybrid core.

APKs are generated at `app/build/outputs/apk/release/app-<ABI>-release.apk`. R8, resource shrinking, ABI splits and compressed native libraries reduce download size. Installed size is larger because native libraries are extracted.

The current Gradle release configuration uses the local Android debug keystore. Local and CI builds are not automatically compatible with official APKs: updating an existing installation requires the same signing key. Official release certificate SHA-256:

```text
8da575e54f9a90626fa3f6cf5f78eb11b3f0252601eecc6cc355fa709af18f90
```

## Project structure

- `app/` — Compose UI, languages, Quick Settings, encrypted storage and Android VPN integration.
- `core/` — Android-independent Kotlin/JVM parser, models and configuration generation.
- `scripts/` — native builds and supporting tools.
- `licenses/` — third-party notices included in the APK.

The native core uses **sing-box 1.11.15** for TUN and **Xray 25.3.6** for XHTTP. Sources are pinned by commit in the build script. These versions need a separate upgrade and compatibility review.

Android is the only implemented platform. Other operating systems require their own tunnel integration and interfaces; the Android UI is not directly portable to iOS.

## Contributing

Issues should include reproducible steps, Android version, device model and protocol/transport, with credentials removed. Submit changes through pull requests and run the tests, release build and Android Lint. Use fictional credentials in fixtures. Do not commit real subscriptions, signing keys, tokens or `local.properties`.

## License

Moth is licensed under [GPL-3.0-or-later](LICENSE). sing-box uses GPL-3.0-or-later with an additional naming/non-association condition; Xray uses MPL-2.0. Moth is an independent project and is not an official SagerNet client.

See [THIRD_PARTY.md](THIRD_PARTY.md) and [licenses/](licenses/) for notices and source details. The unchanged native core's corresponding source and Go dependencies are available with the [0.2 release](https://github.com/Bu11t0N/Moth/releases/tag/v0.2.0-alpha.1); subsequent releases include their own app source archive.
