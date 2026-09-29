# StudioSnap — notes for Claude

A CleanShot/ShareX-class screenshot + recording + annotation app for Googlebook OS (Android 17,
SDK 37), native Kotlin + Jetpack Compose, MIT. The floating **capture bar** is the whole app's face.
**Read `docs/PICKING-UP.md` first** — it holds the live status and how to continue after a VM crash
(this VM is ephemeral; commit + push at every milestone).

## Where things are
- Repo: `~/studiosnap`, GitHub **kuscher/studiosnap** (**public**, MIT). Anything committed is world-readable:
  keep secrets, keys, personal hosts and IPs out of commits, docs and PRs.
- Releases: GitHub Releases, v0.1 to v0.3 published (APK attached as `StudioSnap.apk` +
  `StudioSnap-<version>.apk`); `main` may be ahead of the latest tag. How to cut one (by hand, or by
  pushing a tag once the Actions secrets exist): `docs/RELEASING.md`; notes in `docs/release-notes/`.
- **Release signing key:** `~/.config/studiosnap/keystore.jks` (+ `keystore.pass`), git-ignored.
  Alias `studiosnap`, cert SHA-256 `E1:D1:CB:07:3B:BD:58:25:4D:B0:EA:27:AA:31:E1:93:31:A8:2F:20:E1:E9:EC:01:1B:7D:04:1A:9F:79:69:7A`.
  Backed up to the user's a private folder (the keystore base64 +
  a README with restore steps). The keystore **password is intentionally NOT in the backup or git** — it
  is in the user's password manager (and on the VM at `~/.config/studiosnap/keystore.pass`). Every
  future release MUST be signed with this exact key or users can't update without uninstalling.
- Dev env: SDK/adb from `~/.config/vscodebook/android.env`; adb runs over a unix socket, so always
  go through `./ss` (or source that env) — never a bare `adb`.

## Rules
- **No `INTERNET` permission** — everything is on-device (the OCR model is bundled in the APK). The
  accessibility service must only filter the Screenshot hotkey; **no mouse/input monitoring** (the
  user flagged device-wide input observation as overreach — do not reintroduce it).
- **Never commit signing keys** (`.gitignore` covers `*.jks` / `keystore.pass`).
- **Never capture the user's screen for visual checks.** Use `./ss shot` (our overlay window only)
  or the synthetic test frame (`./ss debug opentest` / `grab`). When a real capture is unavoidable,
  cover the screen with an opaque overlay first, and guard before saving.
- Test hotkeys with `./ss key` (uinput virtual keyboard, real key path). The **key handler must
  return immediately** — Android waits up to 500 ms per key on the service.
- Don't run Android emulators on the VM; use the real Googlebook or GitHub Actions.

## System map
- `service/SnapService.kt` — the AccessibilityService: key router (`onKeyEvent`), capture
  primitives (`takeScreenshot` / `takeScreenshotOfWindow`), overlay control, and the OCR / scroll /
  record flows. Static `instance` drives it from activities and `util/DebugReceiver` (adb hooks).
- `service/ComposeOverlay.kt` — hosts Compose in a `TYPE_ACCESSIBILITY_OVERLAY` window (own
  lifecycle/savedstate). NB: these overlays **ignore gravity** (always centre) — place a corner
  window (the result card) by offsetting from centre via `params.x`/`params.y`.
- `capture/` — `CaptureSession` (selection state + the `onHover`/`tapAt`/`primary` logic),
  `ScrollCapture` (full-page overlap-stitch, **BETA**), `OcrEngine` (ML Kit bundled, offline),
  `Output` (save to gallery / clipboard / working file).
- `ui/` — `CaptureBar` (bar + the `Source` enum: Area / Sections / Window / Screen / Scroll·beta /
  Text), `SelectionLayer` (freeze frame + element snapping + loupe), `ResultCard` (bottom-left
  card), `TextPopover`, `Hud` (palette + `SymText` icon renderer), `OnboardingScreen` (desktop
  first-run). `util/Sym.kt` = Material Symbols codepoints (font subset in `assets/fonts`).
- `studio/` — the annotation editor (`StudioScreen`, `EditorState`, `EmptyEditor` empty state).
- `record/` — `RecordService` (MediaProjection → H.264 → `Mp4Writer`), `AudioCapture` (mic +
  AudioPlaybackCapture → AAC, timestamps in the monotonic clock), `RecOptions` (the bar's Record
  toggles, persisted in `Settings`), `CameraBubble` (the ChromeOS-style camera bubble: a CameraX
  PreviewView in its own small accessibility overlay, recorded as part of the screen; bound to the
  overlay's lifecycle, no camera FGS because the a11y binding already grants the camera capability).
  `util/RecProbe` = adb checks of recorded audio (`debug recinfo`,
  `debug tone`, `debug recopt mic|sys|both|off`) that never play or pull the content. Bubble checks:
  `debug bubble cam on|off|test on|off|corner N|size|shape|switch|info`. Use `test on` before any
  overlay screenshot so it shows a test pattern, never the user's face.
- Icons: add a codepoint to `util/Sym.kt`, then regenerate the font subsets with
  `tools/subset_symbols.py` (instructions in the script).
- Activities: `MainActivity` (native coral home), `StudioActivity` ("StudioSnap Editor", own
  launcher), `CaptureActivity` (one-tap trampoline), `SettingsActivity`, `RecordActivity`,
  `PermissionActivity` (asks for a Record toggle's runtime permission; the service can't).

## Build/run
`./ss app` (build+install+enable+home), `./ss run` (…+open bar), `./ss key`, `./ss shot <tag>`,
`./ss debug <cmd>` (`opentest|grab|scrollcap|ocr|studio|shotwin|…`), `./ss logs`. Release:
`./gradlew :app:assembleRelease` (signed when the key is present) → ~27 MB, abiFilter x86_64 + arm64.
Release and debug are signed differently, so `adb uninstall` before swapping between them.

## Known limitations / beta
- **Scroll** capture is **beta**: it doesn't yet handle elements that are sticky *within* the
  scroll area (sticky headers/footers, floating buttons repeat / confuse the stitch). Fixed
  toolbars *outside* the scroll node are already excluded. Proper fix = detect the moving region
  between frames and keep each sticky band once.
- Recording is full-screen only (per-window/region, pause, GIF, countdown = deferred "4b"). Audio
  (mic and/or system audio) is in; with both on and no headphones, the speakers echo into the mic.
- Text OCR uses exact accessibility text first, ML Kit OCR only when there's none (images/PDF/etc).

## Attribution for commits
Co-Authored-By line per the session's current instruction.
