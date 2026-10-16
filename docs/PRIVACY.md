# Privacy and diagnostics — Leapmotor C11 edition

DiPlay connects locally to the iPhone for CarPlay. It does not require an account or remote authentication service, and it does not upload diagnostic reports automatically. The iPhone's CarPlay apps have their own internet and privacy behavior.

## Data stored on the head unit

The app stores preferences, paired-device selections, pairing data, and bounded diagnostic information in app storage. Android backup excludes authentication and pairing material. Uninstalling removes app-private data; reports that you exported elsewhere remain until you delete them.

Diagnostic reports can include app/device versions, display settings, connection transitions, Wi-Fi state, and decoder recovery events. The exporter filters protocol payloads, credentials, and common identifiers, but vendor-specific text may not be recognized. Review a report before sharing it publicly. A GitHub issue is public. Reports are shared only when you choose **Share** or attach them yourself.

On Android 9 and earlier, **Save diagnostic report** may ask for storage access. When Downloads or the document picker is unavailable, the app offers an external or private app-storage fallback; the confirmation identifies the file location. External reports may be readable by other apps with storage permission on older Android versions. Uninstalling removes app-private reports but not files saved in Downloads.

## Permissions and optional C11 vehicle logs

Bluetooth/Nearby devices and Wi-Fi/location permissions support device discovery and wireless transport. Microphone access supports Siri and calls. The optional local VPN permission carries the USB network link; it is not a remote internet VPN.

**C11 vehicle-log reading (experimental)** is off by default. If you enable it, DiPlay uses an already-authorized, reachable network ADB connection to read local vehicle log events for gear, battery, speed, and steering state. These events can support reverse-related music pause/resume. The feature does not grant ADB access by itself. Turn it off to stop this optional reading; reconnect after changing the setting. Core CarPlay does not need this feature or ADB.

## Network requests and updates

The background update checker currently queries the public GitHub Releases list for upstream shihabal3amri/DiPlay, not this C11 fork. GitHub receives the head unit's network request. Nothing is downloaded or installed without your action. Because upstream and C11 builds have different signing certificates, an upstream APK cannot update this fork in place. Disable **Background update check** under **Settings → About** and use the C11 fork's [Releases page](https://github.com/w012w012/DiPlay-Leapmotor/releases).

The static site has no analytics script or account system. GitHub Pages and GitHub apply their own policies when you use those services.
