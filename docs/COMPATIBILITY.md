# Compatibility — Leapmotor C11 edition

This fork is specifically adapted for the Leapmotor C11 Android head unit. Its C11-specific code is enabled when the system fingerprint contains qti/msmnile_gvmq. This is a firmware-family check, not a guarantee that every C11 year, head-unit revision, or software release behaves identically.

| Area | Current information |
| --- | --- |
| Product scope | Leapmotor C11 adaptation. Other vehicle models and C11 firmware variants are unconfirmed. |
| Android | Minimum API 25 (Android 7.1); target SDK 37. This does not imply every car permits APK installation or supports every connection feature. |
| CarPlay connection | Wired USB and firmware-dependent wireless methods: car hotspot, Wi-Fi Direct, or an existing shared Wi-Fi network. A data-capable USB path is required for wired use. |
| C11 instrument display | CarPlay navigation picture with adjustable size/position and edge fading; music and turn-guidance cards are retained. Changes to map layout apply after reconnecting. |
| Steering controls | C11 steering-wheel media broadcasts are handled by the C11 integration. Vehicle acceptance on this release remains pending. |
| Vehicle logs | Optional, experimental, off by default. When enabled, already-authorized network ADB is used for gear, battery, speed, and steering-event reads; reverse-related music behavior depends on those logs. |
| BYD integrations | BYD SOME/IP HUD, BYD lighting/music analysis, and BYD vehicle-data integrations are removed or disabled on C11. Old saved BYD preferences do not activate them. |

## Validation boundary

The fix24 record documents automated unit tests, lint, build, packaging, and source checks. It explicitly does **not** claim emulator, instrumentation, or target-vehicle acceptance. The current release still needs C11 confirmation for wired/wireless connection, instrument map geometry and fading, music/turn cards, steering controls, audio, and long sessions. See [fix24 scope and verification](C11-FIX24-CLEANUP.md).

Wi-Fi Direct depends on firmware and Android version. Android 7.1–9 uses a legacy path whose requested frequency cannot be verified; Android 10+ can verify the negotiated group frequency. A car's ability to join a 5 GHz Wi-Fi network does not guarantee 5 GHz Wi-Fi Direct support. See [Wi-Fi Direct details](ANDROID9_WIFI_DIRECT.md).

## Update compatibility

The C11 fork and upstream DiPlay share application ID com.shihab.diplay but use different signing certificates. They cannot update one another in place or be installed side by side. Uninstalling removes app-private settings and pairing data. The in-app update checker currently queries upstream releases, so disable **Background update check** under **Settings → About** and obtain C11 updates from this repository's [Releases page](https://github.com/w012w012/DiPlay-Leapmotor/releases).

The APK uses an experimental accessory identity with an extractable private key. This project is not Apple- or Leapmotor-certified, and future iOS acceptance is not guaranteed.
