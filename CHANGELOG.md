# Changelog

Moth versions, newest first. Russian version: [CHANGELOG.ru.md](CHANGELOG.ru.md).

## 0.4.6

### Changes

- The app version is shown next to the name on the home screen, and the build number in Settings → About.
- Tap the provider on the home screen to see and copy the full subscription link.
- Language is chosen in a separate Settings row.
- The Updates screen checks for updates by itself when opened.
- Clearer log: reasons why servers were skipped, and import errors, are recorded. All log levels fit on one row.
- Servers: no duplicate "add subscription" text, and the list scrolls right after swiping between tabs.

## 0.4.5

### Changes

- New app icon.
- In-app updates: Settings → Updates. You check, download and install the new version yourself; the app verifies the file is genuine before installing.
- New package name: 0.4.5 installs as a separate app. Save your subscription link before removing the old version; data is not transferred.
- Future versions will update on top of 0.4.5.

## 0.4.4

### Subscriptions

- Support for Xray JSON subscriptions: each server from the file is added as a separate entry.
- Entries like "Fastest" work as a group: the app picks the best server on its own.
- Servers with insecure TLS or unsupported settings are skipped; their number is shown when adding.
- Routing rules from provider configs are not used yet.

## 0.4.3

### Fixes

- Repeated server checks no longer accumulate memory.
- Error messages in check settings are translated into the app language.

## 0.4.2

### Logs and diagnostics

- Developer section in Settings with Log and Crash log pages.
- Log levels: off, ERROR, WARN, INFO, DEBUG, VERBOSE; the page shows the last 400 lines; Share, Download and Clear; secrets and server addresses are masked.
- Crash log records previous process exits and crashes.
- Detailed logging of server probes (error reason, stage, network).

### Server ping

- Fixed server ping: probes were aborted at socket protect.
- Ping is shown on the right of each server, coloured: under 150 ms green, under 400 ms yellow, above that red.
- Removed the "server not checked" text.
- Parallel probes; Cancel stops all of them.
- Setting for parallel probes: 1–16, default 5.
- Ping timeout in ms: 500–30000, default 5000.
- General check timeout in ms: 1000–60000, default 10000.
- Probe cores start one at a time and list updates are throttled to reduce crashes.

### Interface

- Removed the Back button at the top right in Settings.
- Removed the note about screenshots.
- The language picker is a dropdown.
- Wi-Fi / Mobile data tabs can be swiped.
- HWID field has an eye toggle.
- The bottom bar blends with the background.
- Test builds show a version label like vX.Y.ZrcN; releases do not.
- Changelog screen in Settings (loads the changelog from GitHub in the app language).

### Fixes

- The app now sends the current version in User-Agent (Moth/0.4.2) instead of Moth/0.2.

## 0.4.1

### New features

- App language selection: System default, English and Russian, under Settings → Appearance. Android 13+ also supports the system app-language selector.
- Russian app name: Мотылёк.
- Quick Settings VPN toggle with the supplied moth icon. It uses the selected server for the current Wi-Fi/mobile network and the same VPN service as the app.
- The tile can be added from Settings → Appearance on Android 13+, or from the Android Quick Settings panel. First-time VPN permission is requested through the app.

### Interface

- English README by default, with a separate Russian README.
- Localized interface, errors and notifications; cancelled checks stay visible across language changes.

### Cores and compatibility

- Native cores unchanged: sing-box 1.11.15 and Xray 25.3.6. versionCode 6.

## 0.4.0

### New features

- Different checks: HTTPS to several addresses, HTTPS to your own address, or a TCP port. TCP does not prove the VPN works and does not suit the UDP protocols Hysteria/TUIC. Results depend on the network type and ID; checks are cancelled when the network changes.
- DNS for server addresses is configurable for both cores instead of a fixed Cloudflare.
- Servers tab has Wi-Fi and Mobile data lists. All servers appear in both; the selected profile is remembered per network and used for new connections on the current network.

### Interface

- Three bottom tabs: Home, Servers, Settings.
- Home shows the connection, the selected server and provider details. Name, expiry and traffic come from provider headers when sent; otherwise the domain and server count are shown. Update the subscription after installing to load them.
- Server rows no longer have dots or the "⋮" menu; the selection is highlighted.
- Add/replace subscription, refresh, check, speed test and delete are small icons on the Servers tab. The speed test downloads up to 1 MiB.
- Settings: removed category captions and the save button. Valid values apply automatically; invalid ones show an error and are not saved.

### Performance and size

- Home no longer receives every ping result; the server list is processed only on its own tab.
- APKs are split by architecture, with R8 and native library compression. The arm64 APK is 18.2 MB instead of 209 MB (about 91% smaller).

### Cores and compatibility

- versionCode 5. Native cores unchanged.

## 0.3.0

### Interface

- New Home screen with a round connect button and more room for the server list.
- Search by name and protocol; compact rows by default, normal mode in Settings.
- Subscription management and checks moved to the "⋮" menu; removed the bottom caption about missing automation.
- The "Measure speed" test explains the download of up to 1 MiB and the result in Mbit/s before it starts.
- Settings split into five categories.

### New features

- The "Hide unavailable" filter hides servers with a failed HTTPS check in the last 15 minutes. Unchecked and cancelled checks stay visible.

### Fixes

- Removed the screen-capture ban that interfered with screen sharing. Subscription link and HWID fields are masked.

### Cores and compatibility

- Signed with the same local key as 0.2.0, so it installs over it. versionCode 4.

## 0.2.0

### New features

- Settings for theme, DNS over HTTPS, IPv6, MTU, timeouts and periodic tunnel checks.
- VLESS/VMess/Trojan XHTTP through Xray next to sing-box; Hysteria/Hysteria2 and TUIC v5.
- Clash YAML/JSON with an inline `proxies` list.
- Persistent installation HWID in the `x-hwid` header, configurable User-Agent and optional device model/Android version. Device headers are not sent to another origin on a subscription redirect.
- HTTPS latency check for one or all servers, cancelling checks and sorting by latency.
- Internet check through the active Android VPN network without a direct fallback.
- Separate download speed test of up to 1 MiB through the selected profile.

### Cores and compatibility

- Android 8.0+, universal APK for four architectures.
- Not supported yet: Clash proxy-providers, routing groups/rules, TUIC v4, Hysteria port hopping and Shadowsocks plugins.

## 0.1.0

### New features

- First preview Android release of Moth, a free open-source VPN client.
- Import of an HTTPS subscription in text or Base64 format.
- Server list and manual selection.
- Connect and disconnect the system VPN.
- Manual subscription refresh and removal.
- Configuration encryption with an Android Keystore key.

### Interface

- Russian interface and a notification with a disconnect action.

### Cores and compatibility

- Android 8.0+. VLESS/REALITY/Vision, VMess alterId=0, Trojan, Shadowsocks SIP002; TCP, WebSocket, gRPC and HTTPUpgrade transports where applicable.
- Core: sing-box v1.11.15.
- Not supported yet: automatic selection and reconnection, background updates, XHTTP, Clash YAML/JSON, Hysteria, TUIC and Shadowsocks plugins.
