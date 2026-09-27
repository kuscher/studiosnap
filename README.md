# StudioSnap

A capture studio for Google's Android-based laptops ("Googlebooks"). Press one key, pick an
area, a window or the whole screen, and the shot is copied, saved and ready to mark up — plus
scrolling capture, text/OCR, pins, a real editor, and screen recording.

- **Native** Kotlin + Jetpack Compose. **MIT** licensed. **No `INTERNET` permission** in the
  capture app — everything stays on the device.
- A floating **capture bar** is the face of the app; it opens over anything you're doing.
- Capture runs through an **accessibility service** (instant, no per-shot prompt), with a
  **picker mode** fallback that uses `MediaProjection` when accessibility is off.

Design and plan: see `docs/` and the published plan. Feasibility study lives in the sibling
`~/shotbook` research folder.

## Build & run (developer)

Requires the Android SDK (platform 37, build-tools 36) and JDK 21. On the Googlebook dev VM the
paths come from `~/.config/vscodebook/android.env`.

```
./ss app     # build, install, enable the service, open the home screen
./ss run     # build, install, enable, open the capture bar
./ss key     # press the Screenshot key via a virtual keyboard (real hotkey path)
./ss shot p  # screenshot ONLY StudioSnap's own overlay to /tmp/ss-p.png
./ss logs    # recent StudioSnap logcat
```

## Status

Under active development. See `docs/PICKING-UP.md` for the current phase, what works, and how to
continue (especially important if the dev VM restarts).

## Install (users)

Download the APK from GitHub Releases, open it from Files to install, then turn on **StudioSnap**
in **Settings → Accessibility**. (Play Store distribution is planned.)
