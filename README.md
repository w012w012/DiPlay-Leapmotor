# DiPlay — Leapmotor C11 edition

[English](README.md) · [简体中文](README.zh-CN.md) · [繁體中文](README.zh-TW.md)

**This repository adapts DiPlay specifically for the Leapmotor C11 Android head unit.** It is an independent C11-focused fork, not the BYD-oriented upstream release. C11 behavior is selected when the system fingerprint contains `qti/msmnile_gvmq`; other C11 firmware variants and other vehicle models have not been confirmed.

Current source version: `0.2.20-C11.24` (versionCode `46`). Application ID: `com.shihab.diplay`; minimum Android version: 7.1 (API 25); target SDK: 37.

## What this C11 edition includes

- Wired USB and wireless CarPlay connections using the car hotspot, Wi-Fi Direct, or an existing shared Wi-Fi network.
- CarPlay navigation video on the C11 instrument display, with adjustable map size and position, edge fading, and turn-guidance and music cards. Saved layout changes apply after reconnecting.
- C11 steering-wheel media broadcasts for play, pause, and track controls.
- Optional C11 vehicle-log reading, **off by default**. When enabled, it uses already-authorized network ADB to read gear, battery, speed, and steering events; log-based reverse pause/resume is also available. Core CarPlay does not need ADB.

BYD-specific SOME/IP HUD output, ambient-light/music analysis, and BYD vehicle-data paths were removed or disabled for this C11 edition. Old BYD preferences do not enable those features on C11.

## Install and update

Install the C11 APK on the Leapmotor C11 head unit, not on the iPhone. Use the [releases for this repository](https://github.com/w012w012/DiPlay-Leapmotor/releases). If there is no suitable published APK, follow the [build guide](docs/BUILD.md). Do not substitute the BYD upstream APK.

The C11 fork and upstream use the same application ID but different signing certificates. They cannot be installed side by side or directly update one another; uninstalling removes app-private preferences and pairing data.

The in-app update checker currently queries the upstream DiPlay GitHub releases, not this C11 fork. It may offer an APK that is unsuitable for this build. Turn off **Background update check** under **Settings → About** and use this repository's C11 release page for updates.

## Compatibility and validation

This is a community-maintained adaptation, not an Apple- or Leapmotor-certified product. The firmware fingerprint check may not match every C11 software release. Automated checks for fix24 are documented in the [cleanup and verification record](docs/C11-FIX24-CLEANUP.md); the release has **not** completed vehicle acceptance. Connection, instrument-map display/fading, audio, steering controls, and long-session behavior still need confirmation on the target C11.

The release uses an experimental accessory identity whose private key can be extracted from the APK. Future iOS acceptance is not guaranteed; the APK is not an Apple-certified product or a security credential.

## Documentation

- [Install and connect](docs/INSTALL.md)
- [C11 compatibility and limits](docs/COMPATIBILITY.md)
- [C11 map size and position](docs/C11-MANUAL-MAP-LAYOUT.md)
- [C11 fix24 scope and verification](docs/C11-FIX24-CLEANUP.md)
- [Privacy and diagnostics](docs/PRIVACY.md)
- [Build from source](docs/BUILD.md)
- [Current release notes](docs/RELEASE-NOTES-0.2.20-C11.24.md) · [project change history](CHANGELOG.md)
- [Third-party notices and licenses](docs/THIRD_PARTY_NOTICES.md)

## Report a problem

Include the C11 model/year and head-unit firmware, Android version, iPhone/iOS version, connection method, relevant settings, reproduction steps, and approximate failure time. You can export a report from **Settings → Diagnostics → Save diagnostic report**. Review it for personal information before sharing; never include hotspot passwords or authentication files. [Open an issue in this repository](https://github.com/w012w012/DiPlay-Leapmotor/issues).

## Source and licenses

The receiver is based on [xcertplay](https://github.com/shilapi/xcertplay) (GPL-3.0). The settings interface and website are adapted from [DiAuto](https://github.com/shihabal3amri/DiAuto) (AGPL-3.0). Keep the applicable notices and [third-party licenses](docs/THIRD_PARTY_NOTICES.md) when distributing modifications. DiPlay is not affiliated with Apple, Leapmotor, or BYD. CarPlay and its icon belong to Apple Inc.
