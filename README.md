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

1. Download **StudioSnap-0.1.apk** from the [Releases](https://github.com/kuscher/studiosnap/releases) page.
2. Open it from **Files** to install (allow installing from Files if asked).
3. Open StudioSnap and tap **Turn on instant capture** → enable **StudioSnap** in Accessibility.
   (If installed from Chrome, first allow *Restricted settings* for StudioSnap in App info.)
4. Press the **Screenshot key** (or **Action+Shift+S**) anywhere.

Drag an area, or click a window or UI element; the shot is copied and saved to
**Pictures/StudioSnap**, and a card lets you annotate, pin or share it. Switch the bar to **Text**
to copy on-screen text, or **Record** to capture MP4 (saved to Movies/StudioSnap).

What works in 0.1: area/window/screen/element capture with freeze + loupe, clipboard + save,
result cards, the annotation editor (arrows, shapes, pen, highlighter, steps, redaction), exact
text capture, and full-screen screen recording. In progress: scrolling capture, beautify frames,
recording audio/region/GIF, and settings polish.
