# Install and connect — Leapmotor C11 edition

This guide is for the C11-focused 0.2.20-C11.24 build. C11 integration is selected by a firmware fingerprint check (qti/msmnile_gvmq); compatibility with other C11 firmware versions has not been confirmed. See [compatibility and known limits](COMPATIBILITY.md).

## Before installing

1. Park the car and obtain the APK from this repository's [Releases page](https://github.com/w012w012/DiPlay-Leapmotor/releases). Install it on the Android head unit, not on the iPhone. If no matching C11 build is published, use the [build guide](BUILD.md).
2. This fork uses the same application ID as upstream DiPlay but a different signing certificate. Android will reject an in-place update between the two. Uninstalling the other build removes its app-private settings and pairing data; back up anything needed first.
3. Open DiPlay and grant the permissions requested for the connection and features you use. Bluetooth/Nearby devices and Wi-Fi/location permissions may be needed for wireless discovery; microphone access is for Siri and calls. USB mode may request a local VPN permission to carry the USB network link. This is not an internet VPN service.
4. Close other phone-projection apps before connecting.

The app's background update checker currently reads the upstream DiPlay release list rather than this fork. Disable **Background update check** under **Settings → About** and check this repository's Releases page for C11 builds.

## Wireless connection

Choose one of the wireless methods exposed by the head unit and DiPlay:

- **Car hotspot:** connect the iPhone and head unit through the car's hotspot. Keep Wi-Fi and Bluetooth enabled on the iPhone, pair it with the car when requested, and follow DiPlay's connection prompt.
- **Wi-Fi Direct:** use this when supported by the head-unit firmware. Availability and frequency behavior vary by Android version and firmware; see [Wi-Fi Direct limits](ANDROID9_WIFI_DIRECT.md).
- **Existing Wi-Fi / Same LAN:** connect both devices to the same router or portable Wi-Fi network. Client isolation must be off. DiPlay does not change the router or join the network for you.

Core CarPlay does not require ADB. The optional **C11 vehicle-log reading (experimental)** setting is separate, off by default, and requires an already-authorized, reachable network ADB connection. When enabled it can read gear, battery, speed, and steering events; log-based reverse music pause/resume depends on those events. Save the setting and reconnect for it to take effect. Do not enable it unless you need this vehicle-data behavior.

## USB connection

Use a USB port and cable that support data, select **Connect with USB**, then approve any USB access, iPhone Trust/CarPlay, or local VPN prompts. Charge-only ports and cables cannot carry the connection.

## C11 instrument display

The C11 build can show CarPlay's navigation picture on the instrument display. Open the C11 map settings to adjust size, horizontal/vertical position, and edge fading. Saving the layout does not interrupt an active session; reconnect to apply it. See [C11 map layout](C11-MANUAL-MAP-LAYOUT.md).

Music and turn-guidance cards and C11 steering-wheel media broadcasts are retained. These paths are not yet accepted through a full vehicle test on the current release. BYD HUD, BYD vehicle-data, and BYD ambient-lighting instructions elsewhere in the repository do not apply to this C11 build.

## Diagnostics

If a connection fails, reproduce it on the current C11 build and export a fresh report from **Settings → Diagnostics → Save diagnostic report**. Record the failure time and include head-unit/firmware, Android, iPhone/iOS, connection method, settings, and steps. Review the text for private information before sharing it in this repository's [issue tracker](https://github.com/w012w012/DiPlay-Leapmotor/issues). Reports are not uploaded automatically. Never share hotspot passwords or authentication files.
