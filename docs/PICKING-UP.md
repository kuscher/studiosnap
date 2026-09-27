# Picking up StudioSnap

The dev VM can restart and lose in-progress work, so this file is the source of truth for status
and how to continue. **Keep it current at every milestone**, and always `git push`.

## Where things live
- Repo: `~/studiosnap`, GitHub `kuscher/studiosnap` (private, MIT).
- Plan artifact: https://claude.ai/artifact/91HmujyCE6ScpqqU4F3aFB (UI/design source of truth).
- Feasibility + device API research: `~/shotbook/` (`research/`, `probe/`) and memories
  `studiosnap`, `googlebook-capture-apis`.
- Build/adb env: `~/.config/vscodebook/android.env`. Helper: `./ss` (see CLAUDE.md).

## Status — Phase 1a (core capture loop) COMPLETE; Phase 0 done
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

## Next — Phase 1b/1c (polish + settings)
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
