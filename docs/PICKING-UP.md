# Picking up StudioSnap

The dev VM can restart and lose in-progress work, so this file is the source of truth for status
and how to continue. **Keep it current at every milestone**, and always `git push`.

## Where things live
- Repo: `~/studiosnap`, GitHub `kuscher/studiosnap` (public, MIT).
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
  3A:D1:42:18:...:57:0F. `./gradlew :app:assembleRelease` -> 2.36 MB (R8), smoke-tested OK.
  GitHub release v0.1 has the APK. The key was replaced on 2026-09-30 and is backed up with its password in a private folder.

## Mouse cursor in captures — REVERTED for privacy (2026-09-28)
- The OS bakes the pointer into `takeScreenshot`, so it can show in area/screen shots.
  `takeScreenshotOfWindow` never includes it, so **window captures are already cursor-free**.
- A cursor-erase was built (track the pointer, repaint its box from the window surface) and it
  worked — but it required the service to observe **all** mouse motion device-wide
  (`FLAG_SEND_MOTION_EVENTS` + `setMotionEventSources(SOURCE_MOUSE)` + `onMotionEvent`), i.e.
  continuous global input monitoring. The user flagged that (rightly) as overreach / a persistent
  system indicator, so it was fully removed. Erasing the cursor without that monitoring would mean
  compositing whole window surfaces onto the freeze, which overwrites window caption/controls and
  relives content — a visual regression — so we do NOT do it. Connected flags dropped 0x4172 → 0x72.
- Also removed the unused `flagRequestAccessibilityButton` (we never handled the a11y button;
  `requestA11yBtn` is now false — one less always-on system affordance).
- The key filter (`flagRequestFilterKeyEvents`) stays — it's required for the global Screenshot
  hotkey and is minimal: `onKeyEvent` only checks for the hotkey and never logs or stores keys.
- If cursor removal is wanted later, do it per-source from window surfaces (window already clean),
  or add an opt-in that composites windows — but never via always-on input observation.
- `./ss key` fix: `connect()`'s probe `adb` commands ran inside `$(...)` and swallowed the stdin
  piped into `A shell "cat > json"`, so uinput got truncated JSON and the key never fired. Added
  `</dev/null` to those probes (+ bumped the register delay to 1000ms). Now 3/3 reliable.

## First-run onboarding (2026-09-28, verified on device)
- `ui/OnboardingScreen.kt`: guided welcome (what it does + why) → "Turn on StudioSnap" opens
  Accessibility settings (the app can't toggle the service itself). MainActivity re-checks the
  service in `onResume`, so it flips to an "all set" state the moment the user returns with it on.
  `Settings.onboardingDone` persists; after finishing/skipping, launches go to Home (which still
  handles the service-off case). Debug: launch MainActivity with `--ez force_off true` to preview
  the enable state while the service is actually on (so window screenshots work).

## Scrolling capture (2026-09-28, verified on device)
- `capture/ScrollCapture.kt`: grabs the scroll node's own region (so sticky headers/footers, which
  sit outside it, never contaminate the stitch), scrolls forward, and stitches each frame by
  matching the overlap between the previous bottom and the new top (per-row luma signature at 24
  columns; picks the min-cost alignment, MIN_OVERLAP 24, MATCH_THRESHOLD 26). Stops when a scroll
  adds < 8px, the node can't scroll, or 16 frames / 20000px. Async: window capture callback +
  450ms settle between scrolls.
- SnapService `findScrollable` (largest visible node with `isScrollable`/`ACTION_SCROLL_FORWARD`) +
  `startScrollFlow` (dismiss bar → capture/scroll loop → `onResult(SCROLL)`); wired to the SCROLL
  source's tap/primary. Falls back to a plain window shot when nothing scrolls.
- Verified: self-test (`debug scrollself`) stitches 4 synthetic frames to a pixel-exact 540×2400
  (meanRedDiff 0); real path (`debug scrollcap <win>`) captured the full enable-state onboarding
  (540×1004, one scroll, seamless — content below the fold included). Note: no on-screen progress
  HUD yet during the multi-second capture (the visible auto-scroll is the feedback) — v1.1 polish.

## On-device feedback fixes (2026-09-28, verified on device)
User tested the build and reported 8 issues; all fixed + verified via the safe harness:
1. **Bar hover highlights were rectangles** — `.clickable` wasn't clipped to the pill shape, so
   the mouse-hover/press ripple drew on square bounds. Added `.clip(CircleShape)` to `HudButton`
   and the mode/source chips.
2. **Area highlighted every UI element** — Area now a clean drag (crosshair + loupe + marquee),
   no element snapping (`onHover` returns null for AREA/TEXT).
3. **New "Sections" source** (`Sym.ADS_CLICK`) — carries the element/section snapper (hover to
   highlight rounded, click/primary to grab); moved out of Area. In `shotSources` after Area.
4. **Result card floated mid-screen** — a fixed-size accessibility-overlay window gets centred by
   the system. Made the cards overlay full-screen and dock the card **bottom-right**
   (`Alignment.BottomEnd`). Bottom inset from `currentWindowMetrics` (returns 0 here — taskbar not
   reported — so it sits like the capture bar, over the taskbar; refine later if wanted).
5. **Record mode screenshotted on window-tap** — `tapAt` ignored REC mode. Now `recSources` is
   just `[SCREEN]` and any tap/primary in REC records. (Per-window/region record still 4b.)
6. **Editor vs home confusion** — editor is now "StudioSnap Editor" (own `taskAffinity`, task
   label, `.EditorLauncher` MAIN/LAUNCHER alias, coral pencil in its top bar); home stays
   "StudioSnap" with its own task label.
7. **Home looked web-ish** — reworked with Material 3: `TopAppBar`, elevated status card, tonal
   Capture-bar / Editor buttons, Recent grid.
8. **Editor empty mode** — `StudioActivity` with no image shows `EmptyEditor` (editor chrome +
   drop-zone + "Open from Files" → `OpenDocument` image picker → loads into the editor).
- Debug helpers added: `debug open section`, `debug mode rec|shot`.

## First-run redesign + coral brand + v0.2 published (2026-09-28)
- Onboarding rebuilt desktop-class: two-column hero (brand + features + one-time-setup left, a
  rendered **product preview** right — mock captured window with coral highlight/arrow/step badge +
  the floating bar). Stacks on narrow windows (`BoxWithConstraints`, wide >= 900dp). Coral brand
  mapped onto Material 3 (`CoralLight`/`CoralDark` in MainActivity) so home + onboarding match the bar.
- **GitHub release v0.2 published** (kuscher/studiosnap/releases, `StudioSnap-0.2.apk` ~2.9 MB).
- `./ss enable/disable` fixed to add/remove ONLY our a11y component (was overwriting the whole
  `enabled_accessibility_services`, disabling other apps like BarBook on the shared Googlebook).

## Permissions card: notifications asked, mic and camera explained (branch setup-checklist)
- Found testing 0.4 on the Acer: nothing ever asks for POST_NOTIFICATIONS, so on a fresh install
  the recording notification (and its Stop button, new in 0.4) never shows.
- `ui/PermissionsCard.kt` lists what StudioSnap may use beyond its accessibility service, each
  with its tier and reason: **Accessibility service** (required; Settings only, since first run and
  home have their own card for it), **Notifications** (recommended; an Allow button), **Microphone** and
  **Camera** (when you use them; explained only, since Record mode already asks for them the first
  time their toggle is turned on). It shows on the home screen while notifications are off, and
  always in Settings › Permissions.
- First run fits the window without scrolling (Jesse's Acer opens it at about 1230x770 dp, and the
  page had grown to about 920 dp): the features are a 2x2 grid, and one "One-time setup" card holds
  the service row (with the Accessibility › StudioSnap › on steps), the notifications row, and a
  line on the mic and camera. Start capturing is a full-width button at its foot, disabled until
  the service is on (no "Skip for now": the editor has its own launcher entry). The preview is
  centered on the right, with the key hint as its caption. `ServiceRow` and `NotificationsRow` are shared with the card, so the
  wording is the same everywhere. BentoBar's Setup page makes
  the same required/optional split.
- `util/NotificationAccess.kt` asks with Android's dialog while Android still shows it; once
  refused twice (`Settings.notificationsAsked` and no rationale), or switched off in Settings, it
  opens the app's notification settings instead. "Allowed" also needs the recording channel
  (`RecordService.CHANNEL`) on, since it can be switched off by itself; then Allow opens that
  channel's settings. A channel not created yet (no recording so far) counts as on. Screens re-read the state on resume.
- **Rebased onto 0.4.1** (2026-09-30): Play's disclosure is a dialog, `ui/AccessibilityDisclosure.kt`
  (0.4.1's bullets; the closing line now says what the service reads stays on the device and is
  never collected or shared, Play's "how it's shared"; **Agree and turn on** or **Cancel**), shown by every Turn on: first
  run, home and Settings › Permissions. Play wants it on its own (not with other permission
  disclosures) and immediately before consent, so it isn't on the setup card, which stays as it was.
  Cancel, Back or a tap outside never count as consent. Notifications are offered up front on the
  card (optional) and `RecordActivity` still asks once before the first recording if they aren't on
  by then; it also sets `notificationsAsked`, so the card's Allow knows when Android's answer is final.
- The landing page fits the window it opens in (about 1276x758 dp on the Acer), measured on the
  device: 716 dp of content, about 42 dp to spare. Text uses `ui/ReadableText.kt` (line height in
  proportion to the size, no tracking, leading trimmed), so the `spacedBy` gaps are what you see:
  40 dp page margins and between the header, features and setup card, 24 dp between feature rows,
  18 dp inside the card. "No internet permission" is the sentence after the mic and camera line.
  Re-measure after adding anything to the page.
- Start capturing has to light up on the way back from Accessibility settings. In desktop windowing
  Settings can open in its own window while MainActivity stays resumed, so `onResume` alone misses
  it; `onTopResumedActivityChanged(true)` re-reads the service and permissions too.
- New icon `Sym.NOTIFICATIONS` (U+E7F4); fonts regenerated with `tools/subset_symbols.py`, and the
  128 existing glyphs were checked outline for outline against the old subsets: unchanged.

## Feedback round 2 (2026-09-28)
- **OCR** (`capture/OcrEngine.kt`, ML Kit bundled Latin, offline, no INTERNET perm): Text source
  runs exact a11y node text first, falls back to OCR on the captured pixels when there's none
  (images/canvas/PDF/remote). Verified ~290ms on device. Release ~27MB (abiFilter x86_64+arm64).
- **Result card → bottom-left** corner window (beside the system clipboard chip). A11y overlays
  ignore gravity (centre), so it's offset-positioned via params.x/y; sized small so only the card
  takes touches (the earlier full-screen card overlay ate all taps for 9s). Capped to one card.
- Debug: `./ss debug ocr` (synthetic OCR self-test).

## The bar during a recording (branch bar-while-recording)
- Opened while a recording runs, the bar starts in Video mode, and its main button is a red
  **Stop** (`Sym.STOP`) that ends the recording, instead of a Record that could only say "Already
  recording". Record mode's live camera toggle is right there too; Screenshot mode is one click
  away (a Window-mode shot leaves the bubble out).
- A click elsewhere on the overlay in Video mode only closes the bar while a recording runs: a
  stray click must never stop a recording (`CaptureSession.tapAt`).
- The mode is called **Video** (was "Record", next to a "Record" button). Modes are nouns
  (Screenshot, Video), the main button is a verb (Capture, Record, Stop). Code keeps
  `CaptureMode.REC`.

## Recording audio: mic + system audio (2026-09-28, verified on device)
- Record mode shows two toggles in the bar: **mic** (voice-over) and **system audio** (what apps
  play, via AudioPlaybackCapture on the same MediaProjection). Persisted in `Settings`
  (`recMic`, `recSystemAudio`); observable via `record/RecOptions`.
- Permission: turning a toggle on without RECORD_AUDIO hides the bar (`ComposeOverlay.setHidden`:
  invisible, untouchable, keys pass through; the system dialog draws under our overlay), opens
  `PermissionActivity`, and brings the same bar back afterwards. Any runtime-permission change makes
  Android RESTART this accessibility service (new object, all windows gone), so the bar's state
  (frozen screen, mode, source) is also parked in `SnapService.parkedBar` and restored on connect.
  Denied leaves the toggle off with a toast. `RecordActivity` re-asks before consent if the
  permission was revoked while a toggle is on; a refusal records video only.
- `RecordService` claims FGS type microphone only when audio is on AND permitted (asking for the
  type without the permission throws and would kill the recording).
- `Mp4Writer` waits for both tracks before starting the muxer (buffers early samples); an audio
  source that dies before its first format is abandoned so the video still saves.
- `AudioCapture`: 48 kHz stereo AAC 160 kbps. Mic is the clock when on; system audio feeds a
  100 ms ring (silence-padded). Timestamps from `AudioRecord.getTimestamp` (monotonic, same clock
  as the screen frames). AEC is attached when both are on, if available.
- Verified on the Acer (x86_64): mic-only, system-only (440 Hz tone → 454 Hz measured), both
  (880 Hz → 830 Hz measured, mixed with room sound), off (no audio track, unchanged), revoked+denied
  (video only). Audio vs video start offset within ±26 ms. Debug: `recopt`, `tone`, `recinfo`.
- Known limit: with both on and no headphones, the speakers echo into the mic.
- The release APK used to carry INTERNET (and ACCESS_NETWORK_STATE): ML Kit's usage-logging
  library (`com.google.android.datatransport:transport-backend-cct`) merges them in, so the
  README's "no INTERNET permission" was not true of v0.1-v0.3. The manifest now removes INTERNET
  (`tools:node="remove"`). ACCESS_NETWORK_STATE stays on purpose (it can't send anything, and
  Android 14+ throws on the library's network-constrained upload job without it). Check with
  `aapt2 dump permissions` on the release APK after any dependency change.
- Review hardening (external code review, same day): the recording pill's window is pill-sized
  at the top center (the old full-screen overlay took every touch while recording); the
  notification has a Stop action; one recording at a time (UI + service guard); `Mp4Writer`
  contains muxer errors, treats video as the required track (a sleeping screen sends no frames,
  so early audio waits or drops) and starts without audio if audio is seconds late; the video
  drain always reports done and gives up 3 s after Stop without end-of-stream; a failed save or
  muxer stop is reported as a failure, not "saved"; a permission answer is parked in
  `SnapService.pendingPermission` and applied on reconnect, and a reconnect mid-recording
  restores the pill. `PermissionActivity` must NOT be `noHistory` (no result callbacks).

## Camera bubble (2026-09-28, verified on device)
- Record mode gets a third toggle, **camera**, which shows `record/CameraBubble`: a live CameraX
  preview (PreviewView in COMPATIBLE/TextureView mode so the Compose clip applies) in its own small
  accessibility overlay, so the MediaProjection recording captures it as displayed. It shows while
  the bar is in Record mode or a recording is starting/running, and hides otherwise.
- Drag in raw screen coordinates; release snaps (animated) to the nearest corner. Hover or tap
  shows controls: size (160/256 dp), shape (circle/rounded square), switch camera (only with 2+
  cameras). Size/shape/corner/camera id persist in `Settings`. Default: bottom-right, front camera.
- No camera FGS: the accessibility binding gives the process the camera capability
  (`dumpsys activity processes` → `curCapability=LCMN-U-TI`). The camera binds to the overlay's
  lifecycle, so it only runs while the bubble is visible.
- Z-order: a11y overlays stack in add order, so the bubble is re-added after the bar when it
  was already up (`bringToFront`). The pill is pill-sized now, so it doesn't need that.
- With the camera on, `RecordActivity` asks for an entire-screen recording
  (`MediaProjectionConfig.createConfigForDefaultDisplay()`): a "single app" recording would leave
  the bubble out.
- Known limit: the camera list is read when the camera binds, so a webcam plugged in while the
  bubble is up gets its switch button the next time the bubble shows.
- CameraX 1.6.2 adds ~2 MB to the release APK (29.2 MB).
- Verified on the Acer (x86_64, one front camera): permission via the real dialog; live camera
  from logs only (`bubble camera 0 facing=0`, `STREAMING`, `dumpsys media.camera` open by us)
  with NO foreground service running; camera closed when the bar closes; tap shows controls;
  size and shape toggles; drag snaps to top-left and back; a recording with the test pattern
  has the gradient at the bubble's position (`debug recpixel`); with the camera on, the
  consent dialog offers only "Share entire screen"; accepting it after the service is recreated
  brings back the bubble and the pill.
- Android recreates the accessibility service (a NEW object) around the permission and consent
  dialogs. State that must survive (test pattern, pending permission answers) is process-wide;
  a running recording is restored in `onServiceConnected`.
- `debug shot` refuses to run while the bubble shows the real camera: `debug bubble test on`
  first. Never screenshot a live bubble.

## Bubble controls stay out of recordings (branch bubble-controls-click)
- Before, the bubble's controls came up on hover, so a pointer passing over the bubble painted
  them into the video. Now, while `RecordingBus.active`, hover does nothing: a click brings the
  controls up, and they fold away 3 s after the last click on the bubble or its controls
  (`CONTROLS_HIDE_MS`; a click counter restarts the timer, Switch camera included). They
  also fold away when a recording starts. Hover still works before recording, for framing.
- Cut-out mode's hover outline follows the same rule, since it would be recorded too.

## Camera bubble: cut-out, free placement, Settings button (2026-09-28, verified on device)
- **Cut-out** ("Remove background" on the bubble, `Settings.bubbleCutout`): `record/Cutout.kt` runs
  Google's selfie segmentation model (`assets/models/selfie_segmenter.tflite`, MediaPipe, square
  256x256, Apache 2.0; RGB in [0, 1] -> one person-probability channel) on LiteRT 1.4.2 on the CPU.
  The camera binds ImageAnalysis (1280x960, RGBA) instead of the Preview in this mode; frames are
  rotated, mirrored and center-cropped to a square. The model runs on its own thread on the newest
  frame; each camera frame is drawn with the latest mask (DST_IN, bilinear upscale) into a private
  buffer and handed to the UI as an immutable copy (<= 512 px; one pending frame at most), so the UI
  never draws a half-rewritten frame. Memory is a GC sawtooth (~145-200 MB), no growth. Acer: 29.5 fps
  video and mask, ~10 ms per run, memory flat over a minute. Recorded transparency checked with
  `debug recpixel` (bubble corners match the screen behind them).
- Tried and dropped (all on the Acer): the landscape model (256x144 mask, coarse edges), ML Kit's
  selfie segmenter (no better; 71 MB APK vs 40 MB), the multi-class model (better edges, ~117 ms a
  run, so the mask trailed a moving person as a dark shadow), LiteRT's GPU delegate (OpenCL is clvk
  on this Googlebook: 18 s+ compile, then a crash; OpenGL via ANGLE won't initialize). LiteRT 2.x
  fails AGP 9's namespace check and adds FOREGROUND_SERVICE_DATA_SYNC.
- Compose makes a new AndroidView during composition, before the old branch's DisposableEffect
  cleanup runs: when the cut-out switches off, the cleanup must rebind (not just unbind) or the
  preview is left without a camera.
- **Free placement**: a dropped bubble stays where it's left (clamped on screen); within 64 dp of a
  corner spot (the nearest of all four; they aren't symmetric) it snaps into the corner. Clamping
  centers the bubble on an axis where it can't fit (tiny displays) instead of throwing. `Settings.bubbleCorner` is -1 when free, with the center in
  `bubbleFreeX` / `bubbleFreeY` (fractions of the screen).
- **Settings button**: the bar's Options (tune) button closes the bar and opens Settings.
- Known limits: the bubble window is a square, so its see-through part still takes clicks; the
  camera list is read when the camera binds (a hot-plugged webcam shows up next time).

## 0.4.1 for Google Play (2026-09-30)
- Play's Accessibility API policy wants a prominent in-app disclosure before consent. The onboarding
  setup card (`OnboardingScreen.EnableCard`) now lists each use (keys checked only for the shortcut,
  the screenshot, window/element positions, text for Text, scrolling for Scroll capture), says nothing
  leaves the device, and its button is **Agree and turn on** ("Not now" skips). The home screen's
  Turn on goes back to that screen (never straight to Settings). `a11y_description` says the same.
- The recording notification (with Stop) was hidden on fresh installs: StudioSnap never asked for
  POST_NOTIFICATIONS. `RecordActivity` now asks once (`Settings.askedNotifications`) before the first
  recording, together with the mic permission when that's needed.
- Play declaration videos: kuscher/googlebook-tech `scripts/play/videos` (flows for this app's taps).

## Camera off at Stop, saving off the main thread (branch stop-camera-offmain)
- Before, the bubble (and the camera, and its light) stayed up after Stop until the file had been
  copied to Movies, and that copy ran on the main thread. Now `requestStop` calls
  `SnapService.onRecordingStopping()`, which re-runs `updateBubble()`: with `RecordingBus.active`
  off, the bubble goes (unless the bar is open in Record mode). Frames after `stopPtsUs` aren't
  written, so the bubble disappearing isn't in the video.
- `finishFile` copies to Movies and loads the thumbnail on an `ss-rec-save` thread, then posts the
  result to the main thread, where a failed save is reported with `tell()` (the service's own
  window, from service-messages). The service stays in the foreground until then.
- Left as is: the pill still shows Stop, with the time frozen, until the file is saved. With the
  service between objects at Stop, the bubble goes at save time as before.

## 0.4 release review fixes (2026-09-29)
- A bar hidden for a permission dialog could stay hidden for good (dialog up > 60 s then denied,
  or closed without an answer): `openBar` then saw a shown bar and did nothing, while the
  Screenshot key was still swallowed. Now an expired parked bar is closed, `PermissionActivity`
  reports "no answer" as a refusal from `onDestroy`, and `openBar` drops a hidden bar whose
  dialog is gone.
- Mic and system audio are fixed when a recording starts, so their toggles refuse to change mid-
  recording (toast) instead of showing "off" while still recording. The camera toggle acts live.
- `RecordService` clears `SnapService.recordPending` itself: with the service between objects, a
  failed start used to leave it set (camera bubble up, "Already recording" until process death).

## Service messages in our own window, not toasts (branch service-messages)
- Found testing 0.4 on the Acer: a fresh install never asks for notifications, and Android drops a
  background app's toasts while its notifications are off (logcat: `NotificationService:
  Suppressing toast from package io.github.kuscher.studiosnap by user request.`). The service is
  always in the background, so every toast from it vanished: PR #5's "Stop the recording to change
  the mic or system audio.", "Already recording", "… is off", and the recording's save errors.
- `SnapService.notice(text, long)` shows the message as a pill above the taskbar in an untouchable
  accessibility overlay (`ComposeOverlay(touchable = false)`), gone after 2.5 s (4 s when long).
  While the bar's frozen screenshot is being taken (its retry included) a message waits, so it
  can't end up in the capture. A recorder message that comes while no service object is alive
  (Android recreates the service around dialogs) is parked in `pendingNotice` and shown by the
  next object if it connects within 10 s.
  It's re-added each time so it sits above the bar's window. While the bar is open it goes right
  next to it (above a bar floating over the taskbar, below one docked at the top), where the click
  that caused it just was; `BAR_BOTTOM_GAP_DP`, `BAR_TOP_GAP_DP` and `BAR_HEIGHT_DP` are shared
  with the bar's layout so the two can't drift apart. Opening the bar clears it first, so it
  isn't in the frozen screenshot, and a refusal's message comes after the bar is restored. `RecordService.tell` uses it, with a toast as
  the fallback while the service is between objects. `debug notice <text>` shows one from adb.
- CaptureActivity's toast stays: it comes from an activity in the foreground, so it shows.

## Next — republish release w/ these fixes when user OKs; polish (scroll progress HUD, home desktop layout) + 4b recording (paused).
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

## 0.5.0 (2026-09-30): Jesse's five PRs, released
- #8 to #12 (messages in an overlay pill, the permissions card and first run with the disclosure as a dialog,
  bubble controls on click while recording, camera off at Stop, Video mode with Stop) are in 0.5.0 (code 6).
  Notes: `docs/release-notes/0.5.0.md`. Tagged `v0.5.0`, so the release workflow publishes the APK.
- Play, as Jesse asked in #9: both declaration videos re-recorded on the "playvideo" emulator with 0.5.0
  (accessibility: Turn on → dialog → Cancel, Turn on → Agree and turn on → consent, Screenshot key capture,
  https://youtu.be/mwZW_b2e714; recording, now "Video" mode, https://youtu.be/y0SnZ3-ifPg), filed in the Console.
  Listing screenshot 04 and `docs/screenshots/onboarding.png` show 0.5.0's first run. The 0.5.0 AAB replaced
  0.4.1 as the closed-testing draft.

## 0.5.1 (2026-10-01): Play showed Googlebooks as "not compatible"
- The CAMERA permission implies `android.hardware.camera` (a REAR camera) as a required feature, and
  RECORD_AUDIO implies `android.hardware.microphone`. Only `camera.any` was marked optional, so Play filtered
  every Googlebook with just a front webcam: the HP reports `camera.front` and `camera.any` only
  (`pm list features`), and it was noticed on a Dell XPS Googlebook. True of every build since 0.4.
- The manifest now marks `camera`, `camera.autofocus` and `microphone` as `required="false"`. Check after any
  permission change: `aapt2 dump badging <apk> | grep uses-` must show only `faketouch` as required.
- Version 0.5.1, code 7. Not released yet: after merging, push the tag `v0.5.1` (GitHub APK), then the bundle
  is built and uploaded to Play's closed-testing track on the Mac (the new key isn't on the Debian VM), and
  Alex sends it for review. Play Console › Device catalog should then list the Dell and the HP as supported.
