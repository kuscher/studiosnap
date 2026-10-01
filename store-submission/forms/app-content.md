# App content declarations (Play Console › Policy › App content)

| Declaration | Answer |
|---|---|
| Privacy policy | https://googlebook.studio/privacy/studiosnap |
| Ads | No, the app contains no ads |
| App access | All functionality is available without special access (no login) |
| Content rating | See `content-rating.md` |
| Target audience | 13 and over (13–15, 16–17, 18+). Not designed for children, so the Families policy doesn't apply |
| Data safety | See `data-safety.md` |
| News app | No |
| Government app | No |
| Financial features | My app doesn't provide any financial features |
| Health apps | No health features |
| Advertising ID | Not used (no ad or analytics SDKs; `AD_ID` isn't declared) |
| Permissions | Accessibility service and foreground service types (declarations needed, below), microphone, camera, notifications. `CAMERA` and `RECORD_AUDIO` are runtime permissions Android asks for when a Record toggle needs them. |

## Declarations that need more than a tick

- **Accessibility API.** StudioSnap is not an accessibility tool. Core feature: it notices the capture key (Screenshot key, or Action + Shift + S) and reads the screen's windows, UI elements and text when the user captures, to capture an area, a window, a UI element or text. It doesn't log keystrokes or store input, and nothing leaves the device (no internet permission). Needs a **video** showing the explanation before consent, the consent in Accessibility settings, and a capture with the key. The in-app disclosure (`ui/AccessibilityDisclosure.kt`, since PR #9) is a dialog that every Turn on opens (first run, home, Settings › Permissions): it names each use, says what the service reads stays on the device and is never collected or shared, and ends in **Agree and turn on** or **Cancel**. **The video filed on 2026-09-30 shows 0.4.1's setup card, not the dialog: re-record it before submitting a build with the dialog**, showing Turn on, the dialog, Cancel (the decline flow), Turn on again, Agree and turn on, the consent in Accessibility settings, and a capture with the key. Listing screenshot 04 shows the old card too.
- **Foreground service permissions:** `mediaProjection` (screen recording the user starts, with Android's own consent each time and a notification with Stop; since 0.4.1 StudioSnap asks once for notifications before the first recording, otherwise Android hides that notification) and `microphone` (the voice-over and system audio while recording, only with those toggles on). Each needs a description and a **video** of the feature (one recording video can show both).
- **Done 2026-09-30** (saved in Play Console, not sent for review): Accessibility API with App functionality and no data collected; media projection as "Media and content projection, streaming" and microphone as "Background audio input". The videos are unlisted on YouTube; links in Play Console and in kuscher/googlebook-tech `docs/PICKING-UP.md`.
