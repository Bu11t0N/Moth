# Changelog

Moth versions, newest first. Russian version: [CHANGELOG.ru.md](CHANGELOG.ru.md).

## 0.7.1

Not released yet.

### Changes

- Smoother motion: subscription groups open and close softly, the server list moves into place after checks and changes, settings pages grow out of the item you tap, every button, row and card reacts to a press, and the bottom bar icons bounce and fill in when you switch tabs.
- Home: the status text and the server card change with a short fade; the traffic ring fills smoothly.
- Home: a soft light glows behind the moth: dim when off, warm and pulsing while connecting or checking, bright when connected.
- While servers are being checked and the VPN is off, the moth flaps in its off colours with “Checking servers…”; tap it to stop the check. While the VPN is on, a check makes the moth flap in its on colours.
- Servers: “Update all” and “Check all” are now buttons in the top bar instead of the ⋮ menu.
- Notices at the top of the screen stay visible when you switch tabs.
- Auto-select and failover no longer drop the VPN while checking servers: the connection stays up on the current server and switches only to a server that answers.
- The TCP server check now works while the VPN is on (before, every server showed as failed).

## 0.7.0

Released 2026-10-20.

### Design

- A new monochrome design, now the only one. The connect button is a moth: it flaps its wings while connecting, moves them slowly while connected and rests when the VPN is off.
- A pure black (AMOLED) option for the dark theme. The themed app icon, the home-screen widget and the VPN notification icon follow the new look and your system colours.
- The home screen shows speed, session traffic and connection time, a highlighted server card and the subscription card, and fits short screens and large fonts.
- Menus open from the bottom of the screen; short notices appear at the top.

### Servers and subscriptions

- The server list opens from the bottom of the home screen. The Servers tab has a Wi-Fi / mobile data switch, a group per subscription with its own update and check buttons, and search that opens groups with matches.
- The ⋮ menu updates all subscriptions and checks all servers; the add button is in the top bar.
- A keyword filter hides matching servers or shows only them, separately for Wi-Fi and mobile data. Auto-select and failover never pick hidden servers.
- Subscriptions can be added, updated, renamed and deleted while the VPN is on. The connection stays on its server even if an update removed it; only the subscription with the server in use can’t be deleted until you disconnect.
- The subscription card shows traffic as a ring, the provider’s logo (or its website icon or Telegram avatar), support, news and website buttons, and announcements.
- Ping measures latency on an open connection, like most clients; “Full latency” and “TCP” are in Settings. The speed test has been removed.

### Connection and settings

- Connection settings and split tunneling can be changed while the VPN is on.
- Split tunneling lists every app that uses the internet, including apps without a launcher icon, with selected apps first.
- Quick Settings tile: a tap toggles the VPN without closing the shade; a long press opens Moth. The VPN notification stays near the top of the shade.
- The system name, version and device model sent to your provider can be changed in a dialog or reset to the defaults. The Android version is sent by default.
- Statistics show the totals for the day, week and month at the top.
- For developers: a list of the header names a subscription sends.

### Fixes and updates

- The keyboard no longer covers the server JSON editor, and swiping between Wi-Fi and mobile data works again.
- Shorter settings hints and fewer unneeded messages and log lines.
- Updated Xray to 26.10.17.

## 0.6.5

Released 2026-10-17.

### Changes

- The notification shows speed, traffic and connection time right after connecting.

## 0.6.4

Released 2026-10-16.

### Changes

- The home-screen icon is slightly smaller inside its background so the shield is not clipped by the launcher's shape.
- Sharing a server edited with "Edit JSON" now uses its current settings instead of the original link.
- TCP checks recognize WireGuard as a UDP protocol and explain that an HTTPS check is needed.
- A background subscription update no longer brings back a deleted subscription or overwrites a link changed while it was downloading.

## 0.6.3

Released 2026-10-16.

### Changes

- New server sorting option "Default": servers are listed the way your provider arranged them. It is used for new installs; your current choice is kept. The other options are now called "By ping" and "By name".
- "By name" ignores flags and emoji at the start of server names, so countries are no longer split into separate groups.

## 0.6.2

Released 2026-10-15.

### Changes

- Disconnect now turns the VPN off right away. Before, the phone could stay without internet for up to a minute after Disconnect, and the notification could stay on screen.
- Russian interface: the app is called Мотылёк everywhere, and the remaining English buttons and messages are translated.

## 0.6.1

Released 2026-10-15.

### Changes

- Updated Xray core (used for XHTTP servers).
- Better protection: apps excluded by split tunneling cannot use the VPN, also with XHTTP servers.
- Certificates you installed on the phone are now trusted for XHTTP servers too.
- The server list shows each server's type in one style, for example "VLESS · TCP · Reality" or "Hysteria2", plus the subscription format (JSON, Clash, sing-box) where it applies.
- For developers: the "Edit servers" option adds "Edit JSON" to a server's long-press menu to change its settings by hand. Changes are replaced on the next subscription update.

## 0.6.0

### Changes

- Add a subscription or a server by opening a link. Moth accepts its own import links and those of v2rayNG, Happ, Hiddify and sing-box, as well as server links (VLESS, VMess, Trojan, Shadowsocks, Hysteria2, TUIC, AnyTLS, SOCKS, WireGuard). Moth always asks before adding anything.
- New server types: AnyTLS, SOCKS5 and HTTP(S) proxies with a username and password, WireGuard, SSH, and Shadowsocks with ShadowTLS. Proxies without encryption are marked "Unencrypted".
- More subscription formats: sing-box and Xray configs, and Clash subscriptions that link to other server lists.
- DPI bypass in Settings → Connection: "TLS fragmentation" and "UDP noise" can help when servers are blocked. Off by default.
- "Multiplexing (mux)" in Settings → Connection, for servers that support it. Off by default.
- Home screen widget: shows the server and the VPN state, including "Traffic blocked", with a Connect/Disconnect button.
- Better leak protection: other apps can no longer use Moth's internal connection, and apps excluded by split tunneling can no longer send traffic through the VPN (Android 10+).
- The widget, the quick settings tile and the notification now show "Traffic blocked" (or "Reconnecting…" without the kill switch) when the phone loses its network, and update back when it returns.
- The subscription link field shows the link you paste instead of dots.
- Subscription groups in the server list can be collapsed with a tap on their header; Moth remembers which ones are collapsed.
- "Check servers" in a subscription's menu checks only that subscription. The button at the top still checks all servers.
- With several subscriptions, the subscription button at the bottom of the home screen lists all of them with traffic, end date and last update.
- Certificates you installed on the phone (for example a company certificate) are trusted again, both for servers and for subscription downloads.
- Fixed: after disconnecting, the notification could stay stuck on "Disconnecting…".
- Fixed: tapping the notification, the widget or the tile while Moth was open opened a second copy of the app.
- Fixed: after cancelling an app update, the screen kept saying the update was downloading. Now you can start the update again.
- Updated VPN core.
- AmneziaWG servers are not supported yet.

## 0.5.3

### Changes

- Backup (Settings → Backup): save all subscriptions, servers, favorites, routing rules and settings to one file, optionally protected with a password, and restore them on another phone or after reinstalling.
- Routing rules can be exported to a file and imported from a file or the clipboard, including simple lists of domains.
- The Quick Settings tile shows "Traffic blocked" while the kill switch blocks traffic and "Reconnecting…" while Moth reconnects. Tap it to disconnect and stop blocking.
- Warning when a subscription's traffic is running out (Settings → Subscription: off, 80 %, 90 % or 95 %; 90 % by default), if the provider reports a limit. It is shown once per subscription and billing period.

## 0.5.2

### Changes

- Several subscriptions: add as many as you need, each with its own name. Servers are grouped by subscription, and each subscription has its own traffic, end date, reminder and automatic update. Edit, update or delete a subscription with the menu button next to its name in the server list.
- Routing rules (Settings → Connection): send a website, part of a website name or an IP address through the VPN, directly, or block it. Rules apply in your order.
- When the kill switch is blocking traffic, the home screen shows "Disconnect" and "Traffic blocked". Tap the button to stop blocking and disconnect.
- About → Licenses: license texts of Moth and the components it uses.
- Fixed: subscription providers could see an older Moth version.

## 0.5.1

### Changes

- Traffic statistics (Settings → Statistics): today, this week and this month, plus a chart by day for 7 or 30 days. Tap a bar to see sent and received traffic for that day.
- Reminder before the subscription ends: a notification 3 days before and on the last day, if the provider reports the end date (Settings → Subscription, on by default).
- Settings are organized into sections: Connection, Split tunneling, Routing and DNS, Subscription, Statistics, Interface, Updates, About and For developers. Each shows a short summary.
- Fixed: a cancelled server check no longer shows a red dash as if the server were down.

## 0.5.0

### Changes

- Split tunneling: choose apps that use the VPN only, or apps that bypass it (Settings → Split tunneling).
- "Russian sites and IPs directly": Russian websites open without the VPN. Off by default.
- Automatic subscription update every 1, 2, 3, 6, 12 or 24 hours (Settings → Subscription). Off by default.
- "Auto-select the best server": connects to the fastest server and switches to another one if the internet stops working.
- "Start VPN when the phone turns on". Android's Always-on VPN is also supported.
- Kill switch (Settings → Connection, off by default): if the connection drops, internet is blocked until Moth reconnects or you disconnect.
- Choose another server while connected: Moth switches to it right away.
- Reliable reconnection when switching between Wi-Fi and mobile data, using the server chosen for that network.
- Favorite servers: long-press a server → "Add to favorites". Favorites are marked with a heart and shown at the top.
- The server list shows the traffic left and the subscription end date, if the provider reports them.
- Add a subscription or a server by scanning a QR code or pasting from the clipboard.
- Share any server as a QR code or link (long-press the server).
- DNS settings: choose a DNS server (Cloudflare, Google, Quad9, AdGuard or your own) and turn on DNS leak protection.
- The speed test downloads 10 MB and can be run for any server from its long-press menu.
- The notification icon is now a moth.

## 0.4.7

### Changes

- Servers are checked from top to bottom, in the order of the list.
- A server being checked shows a small spinner; a failed or cancelled check shows a red dash.
- Settings → For developers → "Show detailed icons": small icons instead of the red dash; tap one to see the reason.
- The VPN notification shows the server name, a Disconnect button and the current state.
- While connected, the notification shows the current speed; expanded, it also shows total traffic and connection time.
- The main button is red when disconnected and green when connected.
- A green arrow on the home screen shows that an app update is available; tap it to open Updates.

## 0.4.6

### Changes

- The app version is shown next to the name on the home screen.
- Tap the provider on the home screen to see and copy the subscription link.
- The language has its own row in Settings.
- Updates are checked automatically when you open Updates. Choose the "Release" or "Beta" channel; Beta also offers test versions.
- Updates download and install automatically while Updates is open, with an explanation before Android asks for permission to install apps. An already downloaded update is reused.
- "Sort servers by" name or latency (Settings → Interface); by name by default.
- More useful log for troubleshooting; logging is off by default.
- Smoother transitions between screens and tabs.
- Fixed: duplicate "add subscription" text and scrolling in the server list.

## 0.4.5

### Changes

- New app icon.
- In-app updates (Settings → Updates): Moth checks that the file is genuine before installing.
- Moth 0.4.5 installs as a separate app. Save your subscription link before removing the old version; data is not transferred. Later versions update on top of 0.4.5.

## 0.4.4

### Subscriptions

- Support for Xray subscriptions: each server is added as a separate entry.
- Entries like "Fastest" work as a group: the app picks the best server itself.
- Servers with unsafe or unsupported settings are skipped, and you see how many were skipped.
- Routing rules from providers are not used yet.

## 0.4.3

### Fixes

- Repeated server checks no longer use more and more memory.
- Error messages in check settings are shown in the app language.

## 0.4.2

### Logs and diagnostics

- Settings → For developers: Log and Crash log, to help find problems. Logging is off by default; you can share, save or clear the log. Passwords and server addresses are hidden.

### Server ping

- Fixed server ping.
- Ping is shown next to each server in color: green under 150 ms, yellow under 400 ms, red above.
- Several servers are checked at once; Cancel stops all checks.
- Settings for the number of simultaneous checks and for timeouts.
- Fewer crashes during checks.

### Interface

- Simpler settings: no Back button at the top and no note about screenshots; the language is chosen from a list.
- Swipe between the Wi-Fi and Mobile data tabs.
- The HWID field has a show/hide button.
- The bottom bar blends with the background.
- Changelog in Settings, in the app language.

### Fixes

- Subscription providers now see the correct app version.

## 0.4.1

### New features

- Choose the app language: system, English or Russian (Settings → Interface). On Android 13+ the system language setting for apps also works.
- Russian app name: Мотылёк.
- Quick Settings tile to turn the VPN on and off with the server chosen for the current network. On Android 13+ it can be added from Settings → Interface.

### Interface

- The interface, errors and notifications are translated.

## 0.4.0

### New features

- Choose how servers are checked: HTTPS to several addresses, HTTPS to your own address, or a simple port check. The port check does not prove the VPN works and does not suit Hysteria and TUIC. Checks are cancelled when the network changes.
- Choose the DNS server used to find server addresses.
- Separate server lists for Wi-Fi and mobile data: each network remembers its own server.

### Interface

- Three tabs at the bottom: Home, Servers, Settings.
- Home shows the connection, the selected server and the provider: name, end date and traffic, if the provider reports them. Update the subscription once after installing to load them.
- The selected server is highlighted.
- Buttons on the Servers tab to add, update, check, test speed and delete.
- Settings apply automatically; wrong values show an error and are not saved.

### Performance and size

- The home screen stays smooth while servers are being checked.
- Much smaller download: about 18 MB instead of 209 MB.

## 0.3.0

### Interface

- New home screen with a round connect button.
- Search servers by name and protocol; compact list by default.
- Subscription actions and checks are in the "⋮" menu.
- The speed test explains what it does before it starts.
- Settings are split into five categories.

### New features

- "Hide unavailable": hides servers that failed a check in the last 15 minutes.

### Fixes

- Screen sharing works again. The subscription link and HWID are hidden on screen.

### Compatibility

- Installs over 0.2.0.

## 0.2.0

### New features

- Settings for theme, DNS over HTTPS, IPv6, MTU and timeouts.
- New protocols: VLESS, VMess and Trojan with XHTTP, Hysteria, Hysteria2 and TUIC v5.
- Clash subscriptions.
- Device ID (HWID) for providers, and an optional device model and Android version.
- Server latency check for one or all servers, with cancel and sorting by latency.
- Regular check that the internet works through the VPN.
- Speed test through the selected server.

### Compatibility

- Android 8.0 or newer.
- Not supported yet: Clash subscriptions that link to other lists, TUIC v4 and Shadowsocks plugins.

## 0.1.0

### New features

- First preview of Moth, a free open-source VPN client.
- Add a subscription by link.
- Server list and manual server selection.
- Connect and disconnect the VPN.
- Update and delete the subscription manually.
- Your subscription is stored encrypted on the phone.

### Interface

- Russian interface and a notification with a Disconnect button.

### Compatibility

- Android 8.0 or newer. Supported protocols: VLESS (including REALITY), VMess, Trojan and Shadowsocks.
- Not supported yet: automatic server selection, automatic subscription updates, XHTTP, Clash subscriptions, Hysteria and TUIC.
