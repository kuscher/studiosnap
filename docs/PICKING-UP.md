# Picking up StudioSnap

The dev VM can restart and lose in-progress work, so this file is the source of truth for status
and how to continue. **Keep it current at every milestone**, and always `git push`.

## Where things live
- Repo: `~/studiosnap`, GitHub `kuscher/studiosnap` (private, MIT).
- Plan artifact: https://claude.ai/artifact/91HmujyCE6ScpqqU4F3aFB (UI/design source of truth).
- Feasibility + device API research: `~/shotbook/` (`research/`, `probe/`) and memories
  `studiosnap`, `googlebook-capture-apis`.
- Build/adb env: `~/.config/vscodebook/android.env`. Helper: `./ss` (see CLAUDE.md).

## Status — Phases 0-4 core done + Settings/home + signed 0.1 release
Verified on the HP Googlebook 14 (SDK 37.1) on 2026-09-27:
- Project builds: Gradle 9.8 / AGP 9.4.1 (built-in Kotlin) / JDK 21 / Compose BOM 2026.09.00,
  minSdk 34 target 37, package `io.github.kuscher.studiosnap`.
- Accessibility service filters keys and hosts a Compose overlay.
- The **capture bar renders on-device** matching the design, and opens in **6–18 ms**
  (target <100 ms) via the Screenshot key (SysRq), Action+Shift+S, the debug receiver, or the
  home screen button.
- `./ss shot` captures ONLY the overlay window (safe visual checks); `./ss key` drives the real
  hotkey via `uinput`.
- What the bar does so far: switch mode (Screenshot/Record) and source (Area/Window/Screen/
  Scroll/Text), toggle timer, collapse, close. **Capture/record actions are stubs** (log + close).

## Phase 1a verified on device (2026-09-27, safe synthetic test frame)
- Freeze backdrop + selection layer: dim-outside, white box, exact W×H pill, coordinate-accurate.
- Area drag-select -> crop -> clipboard (image/png confirmed) + save to Pictures/StudioSnap
  (file confirmed) -> result card (thumbnail + Copy/Annotate/Close + Copied/Saved chips).
- Window (tap-to-grab) and full-screen paths wired (crop from frozen frame).
- Test harness: `./ss debug opentest` (synthetic frame), `debug sel a b w h`, `debug grab a b w h`,
  `./ss shot <tag>` (overlay-only). Real path: `./ss run` then drag; `./ss key`.

## Phase 1b verified on device (2026-09-27)
- Element snapping (Area mode: hover a UI element from node bounds, click to grab), window hover
  highlight + info tag, true window capture (takeScreenshotOfWindow with frozen-crop fallback).
- Loupe magnifier (6x) + crosshair + live coord/colour readout (#hex). Verified over the test frame.
- Real injected drag validated the full gesture path (drag -> AREA capture).

## Phase 2a verified on device (2026-09-27)
- StudioActivity opens a capture (from the card's working file in cache/captures, or an EDIT
  intent). Tool strip (arrow/line/rect/ellipse/pen/highlighter/step/redact), property bar
  (palette + width), undo/redo, Copy/Save (flatten via EditorState.export), Close.
- Annotations in image coords (Ann model), Compose + android renderers; arrow drawn via real
  pointer input with arrowhead — verified in the Studio window over the test frame.
- Card "Annotate" opens Studio (SnapService.openStudio). `./ss debug studio` + `shotwin <substr> <tag>`.

## Deferred within Phase 1/2 (do when convenient)
Phase 1c: DataStore settings, first-run, Library grid, picker-mode fallback, card drag-out,
timer countdown, adjust-handles + arrow-key nudge.
Phase 2b/2c: DONE (text/crop/select-move tools, Frame/beautify panel w/ live preview, Layers panel). Auto-redact still deferred (needs OCR on the flattened image).

## Phase 3 text capture verified on device (2026-09-27)
- Text source extracts EXACT text from accessibility nodes in the region (topmost overlapping
  window only, occlusion-aware), sorted top-to-bottom/left-to-right, auto-copied to clipboard,
  shown in a centred TextRoot popover (Copied / Search-via-share / Close). Verified on our own
  home screen (138 chars, clean). OCR fallback for pixels + scrolling capture still deferred.

## Phase 4 recording v1 verified on device (2026-09-27)
- RecordActivity (consent trampoline) -> RecordService (FGS type mediaProjection):
  MediaProjection -> VirtualDisplay -> MediaCodec H.264 -> MediaMuxer MP4 -> Movies/StudioSnap.
- Picker-free via `appops set <pkg> PROJECT_MEDIA allow` (verified instant consent). Recording
  pill HUD (RecordRoot: red dot, timer, Stop, Discard) via RecordingBus. Result: video card.
- Verified: 3.4 s / 447 KB / 1920x1200 MP4, frame-extractable (recframe), pill renders.
- v1 limits (do in 4b): mic + device audio, pause, region/window crop, GIF/WebP export, 3-2-1
  countdown, EXCLUDE the pill from the recording (currently the pill appears in the video),
  video result-card actions (trim/GIF/share).

## Settings/home + release (2026-09-27)
- Settings (SharedPreferences via util/Settings): key takeover, copy/save/card after capture, bar
  position — wired into the service (onKeyEvent reads keyTakeover; onResult honours copy/save/card;
  CaptureRoot honours barAtTop). SettingsActivity + a real home (MainActivity: status, buttons,
  RECENT strip from MediaStore, tap opens Studio).
- Signed release: key in ~/.config/studiosnap/keystore.jks (+ keystore.pass), NOT in git, SHA-256
  E1:D1:CB:07:...:69:7A. `./gradlew :app:assembleRelease` -> 2.36 MB (R8), smoke-tested OK.
  GitHub release v0.1 has the APK. **Back up the keystore to Drive; store the password in the pw
  manager** (still TODO).

## Mouse cursor erased from captures (2026-09-28, verified on device)
- The OS bakes the mouse pointer into `takeScreenshot` (freeze frame) — it showed in area/screen
  shots. `takeScreenshotOfWindow` does NOT include the pointer (verified: cursor centred over our
  window, window shot is clean), so window captures were already fine.
- Fix: the service now observes raw mouse motion (`FLAG_SEND_MOTION_EVENTS` +
  `setMotionEventSources(SOURCE_MOUSE)`, `onMotionEvent` records screen-space `rawX/rawY`). On
  freeze, `deCursor()` repaints the pointer's box (`cursorBox`, ~60x64 around the hotspot) with
  clean pixels from `takeScreenshotOfWindow` of the top window under the pointer — identical to
  what's beneath the sprite, so the cursor vanishes with no seam. No-op when the pointer is
  unknown/stale (touch/keyboard-only, no cursor anyway) or over bare desktop (no app window).
- Adds only a few ms (bar still shows in ~58 ms). Applies to area/screen/element (all crop the
  frozen frame). Tried & rejected first: a null pointer-icon overlay — a stationary cursor's icon
  isn't re-resolved without a mouse move, and we can't inject one at runtime.
- Debug: `./ss debug cursortest 1 <tag>` (opaque-overlay + synthetic erase, safe self-check;
  `isSolid` guards against ever saving real content). Failed-approach + verification detail in the
  `googlebook-capture-apis` memory.

## Next — scrolling capture, first-run onboarding, then 0.2 release. (4b recording polish paused per user.)
Remaining Phase 1 work:
1. Capture engine: `takeScreenshot` (full/area-crop) and `takeScreenshotOfWindow`; ~333 ms limit.
2. Selection layer in the full-screen overlay: freeze frame, dim outside, crosshair + loupe, W×H
   pill, window/element snapping (from `getWindowsOnAllDisplays` + node bounds), adjust handles,
   arrow-key nudge, Shift=square / Alt=from-centre.
3. After capture: clipboard image (FileProvider URI), save to `Pictures/StudioSnap`, naming.
4. Result cards (bottom-left, drag-out), Settings v1 (DataStore), first-run, Library v1.
5. Picker-mode fallback (MediaProjection) when the service is off.
Done when: Screenshot key → drag → paste into Gmail is under 2 s and feels finished.

## Open checks to fold in during Phase 1 (per the user's corrections)
- Skip x86 emulator/CI tests for now (add later).
- Don't build the Chrome "restricted settings" flow; assume Files/Play install.
- Verify: drag-out from an overlay window; system clipboard-preview overlap; mouse hover/right-click
  on the overlay.

## Build/verify quickly
```
./ss run          # build, install, enable service, open the bar
./ss key          # press the Screenshot key (uinput) — expect "bar shown in Nms" in logs
./ss shot p0      # /tmp/ss-p0.png = the overlay only
./ss logs         # recent StudioSnap logcat
```
