# StudioSnap — notes for Claude

A CleanShot/ShareX-class screenshot + recording + annotation app for Googlebook OS (Android 17,
SDK 37), native Kotlin + Jetpack Compose, MIT. The floating **capture bar** is the whole app's face.
**Read `docs/PICKING-UP.md` first** — it holds the live status and how to continue after a VM crash
(this VM is ephemeral; commit + push at every milestone).

## Where things are
- Repo: `~/studiosnap`, GitHub **kuscher/studiosnap** (private, MIT).
- Releases: GitHub Releases — v0.1 and **v0.2 published** (APK attached); newer fixes may be ahead
  of the published tag, so republish when the user asks.
- **Release signing key:** `~/.config/studiosnap/keystore.jks` (+ `keystore.pass`), git-ignored.
  Alias `studiosnap`, cert SHA-256 `E1:D1:CB:07:3B:BD:58:25:4D:B0:EA:27:AA:31:E1:93:31:A8:2F:20:E1:E9:EC:01:1B:7D:04:1A:9F:79:69:7A`.
  Backed up to the user's Google Drive folder **"StudioSnap release key"** (the keystore base64 +
  a README with restore steps). The keystore **password is intentionally NOT in Drive or git** — it
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
- Activities: `MainActivity` (native coral home), `StudioActivity` ("StudioSnap Editor", own
  launcher), `CaptureActivity` (one-tap trampoline), `SettingsActivity`, `RecordActivity`.

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
- Recording is full-screen only (per-window/region, audio, pause, GIF, countdown = deferred "4b").
- Text OCR uses exact accessibility text first, ML Kit OCR only when there's none (images/PDF/etc).

## Attribution for commits
Co-Authored-By line per the session's current instruction.
