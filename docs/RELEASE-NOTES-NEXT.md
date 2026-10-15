# DiPlay next release notes

Changes through DiPlay 0.2.19 are documented in [0.2.19 release notes](RELEASE-NOTES-0.2.19.md). Measured checks are in [VALIDATION.md](VALIDATION.md). Device acceptance and remaining failure families are tracked in [connection reliability validation](CONNECTION_RELIABILITY.md).

## Leapmotor C11 fork

- Music clock correction now includes compressed audio waiting for decode. Sustained stale music is trimmed after a grace period; short Wi-Fi catch-up bursts and non-music streams are preserved. Audio rebuffer reports are capped to keep diagnostic exports bounded.
- Voice-message microphone capture can start when the iPhone sends no downlink audio. A recorder returning empty reads is restarted up to three times, with a short pause between reads. Calls still wait for downlink audio as their echo reference.
- Crash reports retain up to six additional DiPlay stack frames when Android frames fill the usual report window.

These changes have automated coverage. Acceptance on a Leapmotor C11 is pending.

## Display

- With **Efficient video** (HEVC) on, a head unit without a hardware HEVC decoder now gets H.264. Android had picked its own software decoder instead: a Spreadtrum SC7731E head unit (Android 8.1) decoded every session in software with `OMX.google.hevc.decoder` while DiPlay logged `decoder=hardware`, and music skipped ([#727](https://github.com/shihabal3amri/DiPlay/issues/727)). The report says `HEVC is on, but no hardware decoder plays it`. **HEVC software decoder** still keeps HEVC on Android 10 and later.
