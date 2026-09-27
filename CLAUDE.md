# StudioSnap — notes for Claude

A CleanShot/ShareX-class screenshot + recording + annotation app for Googlebook OS (Android 17,
SDK 37), native Kotlin + Jetpack Compose, MIT. The floating **capture bar** is the whole app's
face. Read `docs/PICKING-UP.md` first — it holds live status and how to continue after a VM crash.

## Rules
- **No `INTERNET` permission** in the capture app. Uploads, if ever, ship as a separate companion.
- **Never commit signing keys.** Debug builds (auto `~/.android/debug.keystore`) are used for dev;
  the release key will live in `~/.config/studiosnap` with a Drive backup (Phase 5).
- **Never capture the user's screen content for visual checks.** Use `./ss shot` which screenshots
  ONLY StudioSnap's own overlay window (`captureOverlayShot` in `SnapService`).
- Test hotkeys with the `uinput` virtual keyboard (`./ss key`) — same path as the real keys; it
  never types into the user's windows. Don't inject keys that reach the focused app.
- The **key handler must return immediately** (Android waits up to 500 ms per key on the service).
- Commit and push to GitHub at every milestone/phase so work survives a VM restart.
- Do not run Android emulators on the VM (see the user's global memory). CI x86 tests are deferred.

## System map
- `service/SnapService.kt` — the AccessibilityService: key router (`onKeyEvent`), overlay control,
  `captureOverlayShot`. Static `instance` drives it from activities and the debug receiver.
- `service/ComposeOverlay.kt` — hosts Compose in a `TYPE_ACCESSIBILITY_OVERLAY` window with its own
  lifecycle/savedstate/viewmodel owners. Full-screen while the bar is open.
- `ui/CaptureBar.kt` — the floating bar (`BarState`, modes, sources). `ui/CaptureRoot.kt` — the
  overlay content (bar docked bottom-centre). `ui/Hud.kt` — HUD palette, `SymText` icon renderer,
  `HudButton`.
- `util/Sym.kt` — Material Symbols codepoints (font subset in `assets/fonts`, regular + filled).
- `MainActivity.kt` — home/Library placeholder + service status. `CaptureActivity.kt` — the
  "StudioSnap Capture" one-tap trampoline (+ launcher alias). `util/DebugReceiver.kt` — adb hooks.

## Build/run
`./ss app` (build+install+enable+home), `./ss run` (…+open bar), `./ss key`, `./ss shot <tag>`,
`./ss debug <cmd>`, `./ss logs`. SDK/adb env from `~/.config/vscodebook/android.env`; adb runs on a
unix socket, so always go through `./ss` (or source that env) — never a bare `adb`.

## Attribution for commits
Co-Authored-By line per the session's current instruction.
