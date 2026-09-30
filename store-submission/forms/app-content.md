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

- **Accessibility API.** StudioSnap is not an accessibility tool. Core feature: it notices the capture key (Screenshot key, or Action + Shift + S) and reads the screen's windows, UI elements and text when the user captures, to capture an area, a window, a UI element or text. It doesn't log keystrokes or store input, and nothing leaves the device (no internet permission). Needs a **video** showing the setup screen's explanation, the consent in Accessibility settings, and a capture with the key.
- **Foreground service permissions:** `mediaProjection` (screen recording the user starts, with Android's own consent each time and a notification with Stop) and `microphone` (the voice-over and system audio while recording, only with those toggles on). Each needs a description and a **video** of the feature (one recording video can show both).
