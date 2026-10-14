# Third-party components and licenses

Moth itself is licensed under [GPL-3.0-or-later](LICENSE). The files in [licenses/](licenses/) are packaged into the APK as assets.

## Native cores (`libbox.aar`)

`libbox.aar` is not stored in the repository. [`scripts/build-libbox.sh`](scripts/build-libbox.sh) builds it from official upstream sources at pinned commits, and CI ([`.github/workflows/android.yml`](.github/workflows/android.yml)) runs that script for every build.

| Component | Version | Commit | License |
|---|---|---|---|
| [sing-box](https://github.com/SagerNet/sing-box) (libbox) | v1.12.25 | `73bfb99ebce7923c485435e4faf8571b412065a9` | GPL-3.0-or-later with an additional naming condition: [`licenses/sing-box.txt`](licenses/sing-box.txt) |
| [Xray-core](https://github.com/XTLS/Xray-core) | v25.3.6 | `2cba2c4d59e48a04aa5d09b8e4c2b2d26f8608a4` | MPL-2.0: [`licenses/Xray-MPL-2.0.txt`](licenses/Xray-MPL-2.0.txt) |

**Go module notices.** libbox also links the Go standard library and every Go module that sing-box and Xray-core depend on. [`licenses/go-modules.txt`](licenses/go-modules.txt) lists each of them with its version, license and full license text. It is packaged into the APK with the other files in `licenses/` and shown in the app under About → Licenses → Core Go modules. [`scripts/go-licenses.sh`](scripts/go-licenses.sh) generates it from the prepared core sources (`go list -deps` for all four Android ABIs with the libbox build tags, licenses identified with the pinned [google/licensecheck](https://github.com/google/licensecheck)). The file is committed so local builds work without the core sources; CI regenerates it and fails if it differs from the committed copy. Modules whose license cannot be identified automatically are kept in the file with a note and reported as CI warnings. Currently that is `github.com/sagernet/cloudflare-tls`, a fork of Go's `crypto/tls` that ships no license file; its source headers refer to Go's BSD-style license. Xray-core is listed as `github.com/xtls/xray-core v1.250306.0` (v25.3.6), built from the patched copy described below.

The Moth adapter linked into libbox ([`scripts/native/moth_xray.go`](scripts/native/moth_xray.go)) is GPL-3.0-or-later.

The build toolchain used by CI is Go 1.24.3, JDK 17 for the core build, Android NDK 28.0.13004108 and SagerNet gomobile, with the official `cmd/internal/build_libbox` target `android`. The app itself is built with JDK 21, Android Gradle Plugin 8.2.2 and Kotlin 1.9.22.

**Xray modifications.** The build script copies Xray-core into `.core-build/sing-box/.moth-xray` and patches two MPL-covered files:

- `transport/internet/system_dialer.go`
- `transport/internet/system_listener.go`

Upstream logs and ignores a failure to protect an Android TCP/UDP socket from the VPN. The patch returns that error instead, so a socket that cannot be protected fails rather than leaking. The exact change is in the build script.

Moth is not affiliated with SagerNet or XTLS. The sing-box license forbids using the sing-box name or implying association without permission.

## Android and JVM libraries

| Library | Version | License |
|---|---|---|
| AndroidX Activity Compose | 1.8.2 | Apache-2.0 |
| AndroidX AppCompat | 1.6.1 | Apache-2.0 |
| Compose Material 3 | 1.2.0 | Apache-2.0 |
| Compose Material Icons Extended | 1.6.0 | Apache-2.0 |
| AndroidX Lifecycle Runtime KTX | 2.7.0 | Apache-2.0 |
| AndroidX WorkManager (work-runtime-ktx) | 2.9.1 | Apache-2.0 |
| Kotlin standard library | 1.9.22 | Apache-2.0 |
| kotlinx.coroutines (Android) | 1.7.3 | Apache-2.0 |
| SnakeYAML | 2.2 | Apache-2.0 |
| ZXing Android Embedded (`com.journeyapps`) | 4.3.0 | Apache-2.0 |
| ZXing core (`com.google.zxing`) | 3.5.3 | Apache-2.0 |
| org.json | 20240303 | Public domain; JVM module and tests only, excluded from the APK (Android's built-in `org.json` is used) |

The Apache-2.0 text is in [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt). Transitive AndroidX and Compose dependencies are also Apache-2.0. Test-only dependencies (JUnit, AndroidX Test, UiAutomator) are not shipped in the APK.

## Data downloaded at runtime

When "Russian sites and IPs directly" is enabled, the app downloads these rule sets from GitHub and refreshes them weekly. They are not bundled in the APK.

- `geoip-ru.srs` from [SagerNet/sing-geoip](https://github.com/SagerNet/sing-geoip) (`rule-set` branch). The generator is GPL-3.0-or-later. The data comes from MaxMind GeoLite2 via [Dreamacro/maxmind-geoip](https://github.com/Dreamacro/maxmind-geoip) and is subject to MaxMind's GeoLite2 terms.
- `geosite-category-ru.srs` from [SagerNet/sing-geosite](https://github.com/SagerNet/sing-geosite) (`rule-set` branch). The generator is GPL-3.0-or-later. The data comes from [v2fly/domain-list-community](https://github.com/v2fly/domain-list-community), which is MIT-licensed.

Network endpoints the app contacts are services, not bundled dependencies. These include the HTTPS check URLs and the speed test endpoint.

## Source code and building

The complete corresponding source for every release is in this public repository at the release tag (for example `v0.5.1`). That includes the build scripts that fetch the pinned upstream sing-box and Xray-core sources, apply the patch above and build `libbox.aar`. Go module dependencies are fetched from their upstream sources at the versions locked by the pinned sing-box `go.mod`/`go.sum` and `go mod tidy`.

Anyone can build Moth from this source with the toolchain listed above. A signing key is needed only to produce APKs that install as updates over the official builds. Debug builds or builds signed with your own key work without it.

Obligations this implies for redistribution:

- **GPL-3.0-or-later** (Moth and sing-box): distribute the complete corresponding source, including build scripts, under the same license, and keep the license and copyright notices.
- **MPL-2.0** (Xray-core): keep the notices, and make the source of the MPL-covered files available, including the two modified files. Other code may be under different terms.
