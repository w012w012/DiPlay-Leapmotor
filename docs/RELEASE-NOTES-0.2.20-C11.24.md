# DiPlay 0.2.20-C11.24 — Leapmotor C11 edition

This release is a community adaptation for the Leapmotor C11 Android head unit. C11-specific code is selected when the system fingerprint contains qti/msmnile_gvmq. Compatibility with other C11 firmware and other vehicle models is unconfirmed.

## Included C11 behavior

- Show CarPlay navigation video on the C11 instrument display, with adjustable map size and position, edge fading, and music/turn-guidance cards.
- Receive C11 steering-wheel media broadcasts.
- Keep optional C11 vehicle-log reading off by default. When explicitly enabled, it uses already-authorized network ADB to read gear, battery, speed, and steering events; reverse-related music behavior depends on those logs.
- Remove BYD-specific SOME/IP HUD output, ambient-light/music analysis, and dedicated HUD debugging code. Existing BYD preferences are hidden or guarded on C11; no settings reset is required.
- Retain shared CarPlay route/call state, media/audio/video behavior, local ADB support, FCE service detection, and recovery state needed by other paths.

## Verification and limits

The fix24 record reports 2,680 unit tests passing, lint/build/package checks, source-tree checks, and release APK inspection. Those results are described in [C11 fix24 cleanup and verification](C11-FIX24-CLEANUP.md). They do not establish emulator, instrumentation, or C11 vehicle acceptance.

No C11 vehicle installation or full end-to-end acceptance is claimed for this release. Wired/wireless connection, instrument display geometry/fading, music and turn cards, steering controls, audio quality, and long-session behavior still need validation on the target vehicle. See [compatibility and known limits](COMPATIBILITY.md).

## Package details

- Application ID: com.shihab.diplay
- Version name / code: 0.2.20-C11.24 / 45
- Minimum / target SDK: 25 / 37
- APK recorded by the fix24 work: dist/Diplay-0.2.20-C11-fix24.apk
- SHA-256: DFAFA2619A84E52E13CC422DB383C1E11686C44921AD3611C885A5C0CAD178F8

The C11 fork and upstream DiPlay have different signing certificates despite sharing the application ID. The in-app update checker currently queries upstream releases; disable it under Settings → About and obtain C11 updates from this repository's [Releases page](https://github.com/w012w012/DiPlay-Leapmotor/releases).
