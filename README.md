<div align="center">

# 📸 StudioSnap

### A capture studio for your Googlebook — screenshots, screen recording, and markup, all on-device.

Press one key. Grab an area, a window, a UI element, or a whole scrolling page.
It's **copied, saved, and ready to annotate** the instant you let go — and nothing ever leaves your laptop.

![MIT License](https://img.shields.io/badge/license-MIT-informational)
![Platform](https://img.shields.io/badge/platform-Googlebook%20OS%20(Android%2017)-success)
![No internet](https://img.shields.io/badge/network-none-critical)

<img src="docs/screenshots/bar.png" width="760" alt="The StudioSnap floating capture bar">

</div>

---

## Why you'll like it

- 🎯 **Capture anything** — an **area**, a **window**, the **full screen**, a single **UI element**, or an entire **scrolling page**. A live loupe and colour picker help you nail the exact pixel.
- ⚡ **Instant** — hit the **Screenshot key** (or **Action + Shift + S**) anywhere. Your shot lands on the clipboard and in your gallery before you blink — no per-capture pop-up.
- ✏️ **Mark it up** — a real editor with arrows, shapes, pen, highlighter, text, numbered steps, blur/redaction and crop — plus **one-tap frames** that make any screenshot look designed.
- 🔤 **Grab the text** — copy selectable text out of any window, and **on-device OCR** reads text straight out of images, PDFs, and canvas apps.
- 🎥 **Record** — save a screen recording to an MP4 with a tap.
- 🔒 **Private by design** — 100% on-device with **no `INTERNET` permission**. Your captures, recordings, and OCR never touch a network.

## Mark it up

<img src="docs/screenshots/editor.png" width="840" alt="The StudioSnap editor: arrows, a numbered step, text, a redaction bar, and a colourful frame">

Annotate, blur the sensitive bits, drop numbered steps, then wrap it all in a colourful **frame** with adjustable padding and rounded corners — and Copy or Save.

## A look around

<img src="docs/screenshots/onboarding.png" width="840" alt="StudioSnap's first-run welcome">

<table>
<tr>
<td width="62%"><img src="docs/screenshots/home.png" alt="Home screen"></td>
<td width="38%"><img src="docs/screenshots/card.png" alt="Result card"></td>
</tr>
<tr>
<td align="center"><em>Home — status, quick actions, and recent captures</em></td>
<td align="center"><em>Every capture drops a card, ready to copy or edit</em></td>
</tr>
</table>

## Get it

1. Download the latest **`StudioSnap-*.apk`** from the [**Releases**](https://github.com/kuscher/studiosnap/releases) page.
2. Open it from **Files** and allow the install.
3. Launch StudioSnap and follow the one-time setup to turn it on in **Settings → Accessibility** — that's how it watches for the Screenshot key and grabs the screen. (It only listens for the hotkey; it never logs your keystrokes.)
4. Press the **Screenshot key** — or **Action + Shift + S** — anywhere to capture. 🎉

## Using it

- In the floating bar, pick a **mode** (Screenshot / Record) and a **source**:
  **Area** (drag a box), **Sections** (click a UI element), **Window**, **Screen**, **Scroll** (full page — *beta*), or **Text**.
- After a capture, a card appears in the corner — **Copy** it again, hit **Edit** to open the editor, or dismiss it.
- In the editor, the **Frame** panel adds a background, padding and rounded corners; **Layers** lists everything you've drawn.

## Privacy

StudioSnap requests **no `INTERNET` permission** at all — screenshots, recordings, and OCR run entirely on your device and never reach a network. The accessibility service exists only to catch the capture hotkey and read the screen when you capture; it does not log or store your keystrokes or mouse.

## Build from source

Requires the Android SDK (platform 37, build-tools 36) and JDK 21.

```bash
git clone https://github.com/kuscher/studiosnap
cd studiosnap
./gradlew :app:assembleRelease   # or :app:assembleDebug
```

Contributor notes and the project pickup guide live in [`CLAUDE.md`](CLAUDE.md) and [`docs/PICKING-UP.md`](docs/PICKING-UP.md).

## License

[MIT](LICENSE) © 2026 Alexander Kuscher
