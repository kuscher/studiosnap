package io.github.kuscher.studiosnap.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import io.github.kuscher.studiosnap.capture.CaptureKind
import io.github.kuscher.studiosnap.capture.CaptureSession
import io.github.kuscher.studiosnap.capture.ScrollCapture
import io.github.kuscher.studiosnap.capture.ElementInfo
import io.github.kuscher.studiosnap.capture.OcrEngine
import io.github.kuscher.studiosnap.capture.Output
import io.github.kuscher.studiosnap.capture.WinInfo
import io.github.kuscher.studiosnap.record.CameraBubble
import io.github.kuscher.studiosnap.record.RecOptions
import io.github.kuscher.studiosnap.record.RecToggle
import io.github.kuscher.studiosnap.ui.BAR_BOTTOM_GAP_DP
import io.github.kuscher.studiosnap.ui.BAR_HEIGHT_DP
import io.github.kuscher.studiosnap.ui.BAR_TOP_GAP_DP
import io.github.kuscher.studiosnap.ui.CaptureMode
import io.github.kuscher.studiosnap.ui.CaptureRoot
import io.github.kuscher.studiosnap.ui.CardData
import io.github.kuscher.studiosnap.ui.CardStack
import io.github.kuscher.studiosnap.ui.NoticeRoot
import io.github.kuscher.studiosnap.ui.RecordRoot
import io.github.kuscher.studiosnap.ui.TextRoot
import io.github.kuscher.studiosnap.ui.Source
import io.github.kuscher.studiosnap.util.Settings
import java.util.concurrent.Executors

/**
 * The always-ready service. It filters keyboard shortcuts (so StudioSnap can own the Screenshot
 * key) and hosts the overlays. Key handling returns immediately; UI work is posted to the main
 * thread, because Android waits up to 500 ms on the service for each key.
 */
class SnapService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private var captureOverlay: ComposeOverlay? = null
    private var cardsOverlay: ComposeOverlay? = null
    private var textOverlay: ComposeOverlay? = null
    private var recordOverlay: ComposeOverlay? = null
    private var noticeOverlay: ComposeOverlay? = null
    private val hideNotice = Runnable { noticeOverlay?.dismiss() }
    /** The bar's frozen screenshot is being taken (retries included): a message now would be in it. */
    private var freezing = false
    private var session: CaptureSession? = null
    private val cards = mutableStateListOf<CardData>()
    private var cardId = 0L

    private val settings by lazy { Settings(this) }
    private val recOptions by lazy { RecOptions(settings) }
    private val bubble by lazy { CameraBubble(this, settings) }
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "service created ${id()}")
    }

    override fun onDestroy() {
        Log.i(TAG, "service destroyed ${id()}")
        super.onDestroy()
    }

    /** Short identity for logs: Android recreates this service around some dialogs. */
    private fun id() = Integer.toHexString(System.identityHashCode(this))

    /**
     * True between connect and unbind. Android can destroy this service object and create a new
     * one around permission and consent dialogs; a late callback on the old object (a screenshot,
     * a permission answer) must not put windows on screen that nothing alive can remove.
     */
    @Volatile private var alive = false

    override fun onServiceConnected() {
        instance = this
        alive = true
        Log.i(TAG, "connected ${id()}: flags=0x${Integer.toHexString(serviceInfo.flags)} caps=0x${Integer.toHexString(serviceInfo.capabilities)}")
        // Android can re-bind this service mid-flow (seen around permission dialogs), which tears
        // down its overlays. Put back what a running recording needs, and finish a permission
        // request that was answered while the service was away.
        main.post {
            if (io.github.kuscher.studiosnap.record.RecordingBus.active) showRecordingControls()
            consumePendingPermission() // applies an answer that came in while we were away
            restoreBar() // then brings back a bar parked by the old object
            // A bubble for a pending recording comes back; one left by the old object goes.
            updateBubble()
            // A recorder message that came while no service object could show it.
            pendingNotice?.let { (text, at) ->
                pendingNotice = null
                if (SystemClock.uptimeMillis() - at < PENDING_NOTICE_TTL_MS) notice(text, long = true)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Log.i(TAG, "unbind ${id()}")
        alive = false
        // An open bar (or one hidden behind a permission dialog) comes back on the next object.
        if (captureOverlay?.shown == true) parkBar()
        closeBar()
        captureOverlay?.destroy(); captureOverlay = null
        cardsOverlay?.destroy(); cardsOverlay = null
        textOverlay?.destroy(); textOverlay = null
        recordOverlay?.destroy(); recordOverlay = null
        main.removeCallbacks(hideNotice)
        noticeOverlay?.destroy(); noticeOverlay = null
        bubble.hide()
        instance = null
        return super.onUnbind(intent)
    }

    // ---- keys ----

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        val code = event.keyCode
        if (barShown()) {
            if (!down) return true // swallow key-ups while the bar owns input
            when (code) {
                KeyEvent.KEYCODE_ESCAPE -> main.post { closeBar() }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> main.post { session?.primary() }
                KeyEvent.KEYCODE_A -> main.post { session?.changeSource(Source.AREA) }
                KeyEvent.KEYCODE_W -> main.post { session?.changeSource(Source.WINDOW) }
                KeyEvent.KEYCODE_F -> main.post { session?.changeSource(Source.SCREEN) }
                KeyEvent.KEYCODE_S -> main.post { session?.changeSource(Source.SCROLL) }
                KeyEvent.KEYCODE_T -> main.post { session?.changeSource(Source.TEXT) }
                KeyEvent.KEYCODE_R -> main.post { session?.let { it.changeMode(if (it.mode == CaptureMode.REC) CaptureMode.SHOT else CaptureMode.REC) } }
            }
            return true
        }
        if (!settings.keyTakeover) return false
        val screenshotKey = code == KeyEvent.KEYCODE_SYSRQ || code == KeyEvent.KEYCODE_SCREENSHOT
        val metaShiftS = code == KeyEvent.KEYCODE_S && event.isMetaPressed && event.isShiftPressed
        if (screenshotKey || metaShiftS) {
            if (down) {
                val src = when {
                    event.isShiftPressed && screenshotKey -> Source.SCREEN
                    event.isAltPressed && screenshotKey -> Source.WINDOW
                    else -> null
                }
                main.post { openBar(src) }
            }
            return true
        }
        return false
    }

    // ---- capture primitives ----

    private fun captureFullScreen(retries: Int, cb: (Bitmap?) -> Unit) {
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hb = result.hardwareBuffer
                val bmp = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                hb.close()
                cb(bmp)
            }
            override fun onFailure(code: Int) {
                if (retries > 0) main.postDelayed({ captureFullScreen(retries - 1, cb) }, 350)
                else { Log.w(TAG, "freeze failed code=$code"); cb(null) }
            }
        })
    }

    fun listWindows(): List<WinInfo> {
        val out = ArrayList<WinInfo>()
        val all = windowsOnAllDisplays
        for (i in 0 until all.size()) for (w in all.valueAt(i)) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val r = android.graphics.Rect(); w.getBoundsInScreen(r)
            if (r.width() <= 0 || r.height() <= 0) continue
            val label = w.title?.toString() ?: w.root?.packageName?.toString() ?: "Window"
            out.add(WinInfo(w.id, label, Rect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat()), w.layer))
        }
        return out
    }

    /** UI-element rectangles inside one window, for element snapping in Area mode. */
    fun elementsIn(winId: Int): List<ElementInfo> {
        val all = windowsOnAllDisplays
        var target: AccessibilityWindowInfo? = null
        loop@ for (i in 0 until all.size()) for (w in all.valueAt(i)) if (w.id == winId) { target = w; break@loop }
        val root = target?.root ?: return emptyList()
        val out = ArrayList<ElementInfo>()
        val q = ArrayDeque<AccessibilityNodeInfo>(); q.add(root)
        val r = android.graphics.Rect(); var n = 0
        while (q.isNotEmpty() && n < 4000) {
            val node = q.removeFirst(); n++
            node.getBoundsInScreen(r)
            if (node.isVisibleToUser && r.width() > 8 && r.height() > 8) {
                val label = node.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                    ?: node.viewIdResourceName?.substringAfterLast('/')
                    ?: node.className?.toString()?.substringAfterLast('.')
                    ?: "Element"
                out.add(ElementInfo(Rect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat()), label))
            }
            for (c in 0 until node.childCount) node.getChild(c)?.let { q.add(it) }
        }
        return out
    }

    /** Captures one window's own surface (clean, no overlaps, no caption). */
    fun captureWindow(winId: Int, cb: (Bitmap?) -> Unit) {
        takeScreenshotOfWindow(winId, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hb = result.hardwareBuffer
                val bmp = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                hb.close(); cb(bmp)
            }
            override fun onFailure(code: Int) { Log.w(TAG, "window shot fail=$code"); cb(null) }
        })
    }

    // ---- scrolling capture ----

    /** The largest visible scrollable node in a window (the main scroll container). */
    private fun findScrollable(winId: Int): AccessibilityNodeInfo? {
        val all = windowsOnAllDisplays
        var target: AccessibilityWindowInfo? = null
        loop@ for (i in 0 until all.size()) for (w in all.valueAt(i)) if (w.id == winId) { target = w; break@loop }
        val root = target?.root ?: return null
        var best: AccessibilityNodeInfo? = null; var bestArea = 0
        val q = ArrayDeque<AccessibilityNodeInfo>(); q.add(root)
        val r = android.graphics.Rect(); var n = 0
        while (q.isNotEmpty() && n < 4000) {
            val node = q.removeFirst(); n++
            val canScroll = node.isScrollable ||
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }
            if (canScroll && node.isVisibleToUser) {
                node.getBoundsInScreen(r)
                val area = r.width() * r.height()
                if (area > bestArea) { bestArea = area; best = node }
            }
            for (c in 0 until node.childCount) node.getChild(c)?.let { q.add(it) }
        }
        return best
    }

    /** Runs a scrolling capture on [win]: finds its scroll container, then stitches successive
     *  forward-scrolled frames into one tall image. Falls back to a plain window shot if nothing
     *  scrolls. Dismisses the bar first so it never appears in the frames. */
    private fun startScrollFlow(win: WinInfo) {
        val node = findScrollable(win.id)
        if (node == null) {
            Log.i(TAG, "scroll: no scrollable in '${win.label}', capturing window once")
            dismissCapture()
            captureWindow(win.id) { bmp -> if (bmp != null) onResult(bmp, CaptureKind.WINDOW, "Window \u00b7 ${win.label}") }
            return
        }
        val b = android.graphics.Rect(); node.getBoundsInScreen(b)
        val ox = win.rect.left.toInt(); val oy = win.rect.top.toInt()
        val local = android.graphics.Rect(
            (b.left - ox).coerceAtLeast(0), (b.top - oy).coerceAtLeast(0),
            (b.right - ox), (b.bottom - oy),
        )
        dismissCapture()
        Log.i(TAG, "scroll: '${win.label}' region=$local")
        main.postDelayed({
            ScrollCapture(
                main, win.id, local, ::captureWindow,
                scrollForward = { findScrollable(win.id)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false },
                log = { Log.i(TAG, "scroll: $it") },
            ).run { bmp ->
                if (bmp != null) { Log.i(TAG, "scroll: done ${bmp.width}x${bmp.height}"); onResult(bmp, CaptureKind.SCROLL, "Scrolling capture") }
                else Log.w(TAG, "scroll: null result")
            }
        }, 150)
    }

    fun debugScroll(substr: String) {
        val win = listWindows().firstOrNull { it.label.contains(substr, true) } ?: run { Log.w(TAG, "scroll: no window '$substr'"); return }
        val node = findScrollable(win.id) ?: run { Log.w(TAG, "scroll(debug): no scrollable in '${win.label}'"); return }
        val b = android.graphics.Rect(); node.getBoundsInScreen(b)
        val ox = win.rect.left.toInt(); val oy = win.rect.top.toInt()
        val local = android.graphics.Rect((b.left - ox).coerceAtLeast(0), (b.top - oy).coerceAtLeast(0), (b.right - ox), (b.bottom - oy))
        Log.i(TAG, "scroll(debug): '${win.label}' region=$local")
        ScrollCapture(
            main, win.id, local, ::captureWindow,
            scrollForward = { findScrollable(win.id)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false },
            log = { Log.i(TAG, "scroll: $it") },
        ).run { bmp ->
            if (bmp != null) {
                val dir = java.io.File(cacheDir, "shots").apply { mkdirs() }
                val f = java.io.File(dir, "scroll.png")
                java.io.FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                Log.i(TAG, "scroll(debug): ${bmp.width}x${bmp.height} -> ${f.absolutePath}")
            } else Log.w(TAG, "scroll(debug): null result")
        }
    }

    // ---- overlay control ----

    /** [dry] = don't freeze the screen (transparent backdrop); used only for visual checks so an
     *  overlay screenshot shows StudioSnap's own UI and never the user's apps. */
    fun openBar(initialSource: Source? = null, dry: Boolean = false, initialMode: CaptureMode? = null, frozen: Bitmap? = null) {
        if (!alive) return
        // A bar hidden for a permission dialog that is gone without bringing it back: drop it, or
        // it would block every new bar (and the Screenshot key) until the service restarts.
        if (captureOverlay?.hidden == true && !io.github.kuscher.studiosnap.PermissionActivity.showing) {
            parkedBar = null
            dismissCapture()
        }
        if (captureOverlay?.shown == true) return
        // A message still up would be in the frozen screenshot the bar opens with.
        main.removeCallbacks(hideNotice)
        noticeOverlay?.dismiss()
        val t0 = SystemClock.elapsedRealtime()
        val present = present@{ bmp: Bitmap? ->
            if (!alive) return@present // unbound while the screenshot was in flight
            val s = newSession()
            session = s
            s.onFrozen(bmp, initialSource)
            if (initialMode != null) s.changeMode(initialMode)
            val ov = captureOverlay ?: ComposeOverlay(this).also { captureOverlay = it }
            ov.show { CaptureRoot(s, dark = isNight(), barAtTop = settings.barAtTop) }
            // A bubble that was already up (say, mid-recording) is now under the bar: lift it.
            // A new one is added after the bar, so it's on top already.
            val wasShown = bubble.shown
            updateBubble()
            if (wasShown) bubble.bringToFront()
            Log.i(TAG, "bar shown in ${SystemClock.elapsedRealtime() - t0}ms frozen=${bmp != null} source=$initialSource mode=$initialMode")
        }
        if (dry) { present(null); return }
        if (frozen != null) { present(frozen); return } // a restored bar keeps its frozen screen
        freezing = true
        captureFullScreen(1) { bmp -> freezing = false; present(bmp) }
    }

    private fun newSession() = CaptureSession(
        this, ::listWindows, ::elementsIn, ::captureWindow, ::onResult, ::onText, ::startRecordFlow, ::startScrollFlow, ::dismissCapture,
        recOptions = recOptions.also { it.reload() }, onRecToggle = ::toggleRec,
        onModeChange = { updateBubble() },
        onSettings = ::openSettings,
    )

    private fun openSettings() {
        dismissCapture()
        startActivity(
            android.content.Intent(this, io.github.kuscher.studiosnap.SettingsActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun onResult(bmp: Bitmap, kind: CaptureKind, label: String) {
        val name = Output.defaultName()
        val img = bmp.asImageBitmap()
        val working = Output.saveWorkingFile(this, bmp, name)
        val copy = settings.copyAfter; val save = settings.saveAfter
        if (settings.showCard) {
            cards.add(CardData(cardId++, img, "$label · ${bmp.width} × ${bmp.height}", copied = copy, saved = save, filePath = working.absolutePath))
            while (cards.size > 1) cards.removeAt(0)
            ensureCardsOverlay()
        }
        bg.execute {
            if (copy) Output.copyToClipboard(this, bmp, name)
            if (save) Output.saveToGallery(this, bmp, name)
            Log.i(TAG, "result $kind ${bmp.width}x${bmp.height} copy=$copy save=$save")
        }
    }

    private fun openStudio(card: CardData) {
        val path = card.filePath ?: return
        startActivity(
            android.content.Intent(this, io.github.kuscher.studiosnap.StudioActivity::class.java)
                .putExtra(io.github.kuscher.studiosnap.StudioActivity.EXTRA_PATH, path)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        removeCard(card.id)
    }

    private fun ensureCardsOverlay() {
        // Full-screen overlay (accessibility overlays ignore gravity for smaller sizes, so a corner
        // window just gets centred); the card aligns itself to the bottom-left, beside the system
        // clipboard chip. Touch only lands on the card — the rest passes through (touchThrough).
        val density = resources.displayMetrics.density
        val wm = getSystemService(WindowManager::class.java)
        val metrics = wm.maximumWindowMetrics
        val screenW = metrics.bounds.width(); val screenH = metrics.bounds.height()
        val navBottom: Int = runCatching {
            metrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.navigationBars() or android.view.WindowInsets.Type.systemBars(),
            ).bottom
        }.getOrDefault(0)
        val taskbar = maxOf(navBottom, (120 * density).toInt())  // reported inset underreports the taskbar
        val wPx = (352 * density).toInt(); val hPx = (300 * density).toInt()
        val marginL = (14 * density).toInt(); val marginB = (8 * density).toInt()
        // Accessibility overlays centre; offset a fixed-size window to the bottom-left corner.
        val offX = marginL - (screenW - wPx) / 2
        val offY = (screenH - taskbar - marginB) - (screenH + hPx) / 2
        val ov = cardsOverlay ?: ComposeOverlay(
            this,
            widthSpec = wPx,
            heightSpec = hPx,
            gravity = Gravity.CENTER,
            noLimits = false,
            offsetX = offX,
            offsetY = offY,
        ).also { cardsOverlay = it }
        ov.show {
            CardStack(
                cards = cards,
                dark = isNight(),
                onDismiss = { id -> removeCard(id) },
                onCopy = { /* re-copy handled below */ recopy(it) },
                onEdit = { openStudio(it) },
            )
        }
    }

    /**
     * A short message above the taskbar, in its own untouchable window: what a toast would say.
     * Android drops a background app's toasts while its notifications are off (logged as
     * "Suppressing toast … by user request"), and this service is always in the background, so a
     * toast from here can silently vanish. False when there's no live service object to show it.
     */
    fun notice(text: String, long: Boolean = false): Boolean {
        if (!alive) return false
        if (freezing) { main.postDelayed({ if (!notice(text, long)) parkNotice(text) }, NOTICE_FREEZE_WAIT_MS); return true }
        Log.i(TAG, "notice: $text")
        val density = resources.displayMetrics.density
        val metrics = getSystemService(WindowManager::class.java).maximumWindowMetrics
        val screenH = metrics.bounds.height()
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars())
        val h = (NOTICE_HEIGHT_DP * density).toInt()
        // Next to the bar while it's open, where the click that caused the message just was:
        // above it when it floats over the taskbar, below it when it's docked at the top. With no
        // bar, above the taskbar. (The bar's overlay is full screen, so its gaps are from the edges.)
        val barEdge = ((if (settings.barAtTop) BAR_TOP_GAP_DP else BAR_BOTTOM_GAP_DP) + BAR_HEIGHT_DP + NOTICE_GAP_DP) * density
        val y = when {
            barShown() && settings.barAtTop -> barEdge.toInt() + h / 2 - screenH / 2
            barShown() -> screenH / 2 - barEdge.toInt() - h / 2
            else -> {
                // The reported inset underreports the taskbar, so keep a floor under it.
                val bottom = maxOf(insets.bottom, (NOTICE_TASKBAR_FLOOR_DP * density).toInt())
                screenH / 2 - bottom - h / 2
            }
        }
        // Full width (it takes no clicks), centered, so the pill in it can size to its text.
        val ov = noticeOverlay ?: ComposeOverlay(
            this,
            heightSpec = h,
            gravity = Gravity.CENTER,
            noLimits = false,
            touchable = false,
        ).also { noticeOverlay = it }
        // Re-added each time, so it sits above whatever window came up since (the bar, say).
        ov.dismiss()
        ov.moveTo(0, y)
        ov.show { NoticeRoot(text, dark = isNight()) }
        main.removeCallbacks(hideNotice)
        main.postDelayed(hideNotice, if (long) NOTICE_LONG_MS else NOTICE_SHORT_MS)
        return true
    }

    private fun removeCard(id: Long) {
        cards.removeAll { it.id == id }
        if (cards.isEmpty()) { cardsOverlay?.dismiss() }
    }

    private fun recopy(card: CardData) {
        // The card holds only an ImageBitmap; re-copy is a no-op stub until Phase 2 keeps the file.
        Log.i(TAG, "recopy requested for ${card.label}")
    }

    private fun dismissCapture() {
        captureOverlay?.dismiss()
        session = null
        updateBubble()
    }

    fun closeBar() { dismissCapture() }
    /** The bar is on screen and taking input (not hidden behind a permission dialog). */
    fun barShown(): Boolean = captureOverlay?.let { it.shown && !it.hidden } == true

    // ---- test harness (safe synthetic content; never the user's screen) ----

    fun openBarTest() {
        if (barShown()) return
        val s = newSession()
        session = s
        s.onFrozen(testBitmap(), Source.AREA)
        val ov = captureOverlay ?: ComposeOverlay(this).also { captureOverlay = it }
        ov.show { CaptureRoot(s, dark = isNight(), barAtTop = settings.barAtTop) }
        Log.i(TAG, "test bar shown")
    }

    fun debugSelect(a: Int, b: Int, w: Int, h: Int) {
        session?.let { it.selection = Rect(a.toFloat(), b.toFloat(), (a + w).toFloat(), (b + h).toFloat()); it.phase = io.github.kuscher.studiosnap.capture.SelPhase.ADJUST }
    }

    fun debugGrab(a: Int, b: Int, w: Int, h: Int) {
        session?.captureArea(Rect(a.toFloat(), b.toFloat(), (a + w).toFloat(), (b + h).toFloat()), CaptureKind.AREA)
    }

    fun debugStudio(demo: Boolean = false) {
        val f = java.io.File(cacheDir, "captures").listFiles()?.maxByOrNull { it.lastModified() } ?: return
        startActivity(
            android.content.Intent(this, io.github.kuscher.studiosnap.StudioActivity::class.java)
                .putExtra(io.github.kuscher.studiosnap.StudioActivity.EXTRA_PATH, f.absolutePath)
                .putExtra(io.github.kuscher.studiosnap.StudioActivity.EXTRA_DEMO, demo)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun debugShotWindow(substr: String, tag: String) {
        val win = listWindows().firstOrNull { it.label.contains(substr, true) } ?: run { Log.w(TAG, "no window '$substr'"); return }
        captureWindow(win.id) { bmp ->
            if (bmp == null) { Log.w(TAG, "shotwin null"); return@captureWindow }
            val dir = java.io.File(cacheDir, "shots").apply { mkdirs() }
            val f = java.io.File(dir, "$tag.png")
            java.io.FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Log.i(TAG, "shotwin '${win.label}' ${bmp.width}x${bmp.height} -> ${f.absolutePath}")
        }
    }

    fun debugText(a: Int, b: Int, w: Int, h: Int) {
        onText(null, Rect(a.toFloat(), b.toFloat(), (a + w).toFloat(), (b + h).toFloat()))
    }

    fun debugRecord() { startRecordFlow() }
    fun debugRecStop() { io.github.kuscher.studiosnap.record.RecordingBus.controller?.stop() }
    /** adb-only camera bubble checks: "cam on|off", "test on|off", "corner N", "size", "shape", "cutout", "switch", "info". */
    fun debugBubble(args: List<String>) {
        when (args.getOrNull(0)) {
            "cam" -> { recOptions.set(RecToggle.CAMERA, args.getOrNull(1) == "on"); updateBubble() }
            "test" -> CameraBubble.switchTestPattern(args.getOrNull(1) != "off")
            "corner" -> bubble.moveToCorner(args.getOrNull(1)?.toIntOrNull() ?: 3)
            "size" -> bubble.toggleSize()
            "shape" -> bubble.toggleShape()
            "cutout" -> bubble.toggleCutout()
            "switch" -> bubble.switchCamera()
        }
        Log.i(TAG, bubble.describe())
    }

    /** adb-only: set the audio toggles without the bar ("mic", "sys", "both" or "off"). */
    fun debugRecOptions(which: String) {
        recOptions.set(RecToggle.MIC, which == "mic" || which == "both")
        recOptions.set(RecToggle.SYSTEM_AUDIO, which == "sys" || which == "both")
        Log.i(TAG, "rec options mic=${recOptions.mic} system=${recOptions.systemAudio}")
    }
    fun debugRecFrame(tag: String) {
        val proj = arrayOf(android.provider.MediaStore.Video.Media._ID)
        contentResolver.query(
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj,
            "${android.provider.MediaStore.Video.Media.RELATIVE_PATH} LIKE ?", arrayOf("%StudioSnap%"),
            "${android.provider.MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { cur ->
            if (!cur.moveToFirst()) { Log.w(TAG, "recframe: no video"); return }
            val id = cur.getLong(0)
            val uri = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
            val r = android.media.MediaMetadataRetriever()
            r.setDataSource(this, uri)
            val dur = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            val f = r.getFrameAtTime(1_000_000)
            r.release()
            if (f == null) { Log.w(TAG, "recframe: null frame"); return }
            val dir = java.io.File(cacheDir, "shots").apply { mkdirs() }
            val out = java.io.File(dir, "$tag.png")
            java.io.FileOutputStream(out).use { f.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Log.i(TAG, "recframe ${f.width}x${f.height} dur=${dur}ms -> ${out.absolutePath}")
        }
    }

    fun debugHover(a: Int, b: Int, w: Int, h: Int, element: Boolean) {
        session?.let {
            it.phase = io.github.kuscher.studiosnap.capture.SelPhase.AIM
            it.selection = null
            it.hover = io.github.kuscher.studiosnap.capture.Hover(
                Rect(a.toFloat(), b.toFloat(), (a + w).toFloat(), (b + h).toFloat()),
                if (element) "Download GPX" else "Window · Field Notes", element, 0,
            )
        }
    }

    fun debugMode(m: String) {
        session?.changeMode(if (m == "rec") io.github.kuscher.studiosnap.ui.CaptureMode.REC else io.github.kuscher.studiosnap.ui.CaptureMode.SHOT)
    }

    fun debugAim(x: Int, y: Int) {
        session?.let {
            it.changeSource(Source.AREA)
            it.selection = null; it.hover = null
            it.phase = io.github.kuscher.studiosnap.capture.SelPhase.AIM
            it.pointer = androidx.compose.ui.geometry.Offset(x.toFloat(), y.toFloat())
        }
    }

    /** adb-only: drives the REAL ScrollCapture loop (overlap + stitch) with synthetic frames cut
     *  from a tall source at known offsets, then checks the reconstruction matches the source. No
     *  screen content involved. */
    fun debugScrollSelfTest() {
        val w = 540; val hs = 2400; val vh = 819
        val src = Bitmap.createBitmap(w, hs, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(src); val pnt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        for (y in 0 until hs) {
            pnt.color = android.graphics.Color.rgb(30 + y * 200 / hs, 60, 200 - y * 160 / hs)
            c.drawRect(0f, y.toFloat(), w.toFloat(), (y + 1).toFloat(), pnt)
        }
        pnt.color = 0x33FFFFFF; var yb = 0; while (yb < hs) { c.drawRect(0f, yb.toFloat(), w.toFloat(), (yb + 2).toFloat(), pnt); yb += 40 }
        pnt.color = android.graphics.Color.WHITE; pnt.textSize = 34f
        var yy = 44; while (yy < hs) { c.drawText("row $yy", 24f, yy.toFloat(), pnt); yy += 80 }

        val offs = intArrayOf(0, 600, 1200, 1581)
        val frames = offs.map { Bitmap.createBitmap(src, 0, it, w, vh) }
        var idx = 0
        val fakeCapture: (Int, (Bitmap?) -> Unit) -> Unit = { _, cb -> cb(frames.getOrNull(idx)) }
        val fakeScroll: () -> Boolean = { if (idx < frames.size - 1) { idx++; true } else false }
        ScrollCapture(main, 0, android.graphics.Rect(0, 0, w, vh), fakeCapture, fakeScroll, log = { Log.i(TAG, "selftest: $it") })
            .run { out ->
                if (out == null) { Log.w(TAG, "selftest: null"); return@run }
                val hh = minOf(out.height, hs); var diff = 0L; var n = 0
                var y = 0; while (y < hh) { var x = 0; while (x < w) {
                    diff += Math.abs(((out.getPixel(x, y) shr 16) and 0xFF) - ((src.getPixel(x, y) shr 16) and 0xFF)); n++; x += 60 }; y += 30 }
                val dir = java.io.File(cacheDir, "shots").apply { mkdirs() }
                val f = java.io.File(dir, "scrollself.png")
                java.io.FileOutputStream(f).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
                Log.i(TAG, "selftest: out=${out.width}x${out.height} expected=${w}x$hs meanRedDiff=${if (n > 0) diff / n else -1} -> ${f.absolutePath}")
            }
    }

    /** adb-only: renders text to a bitmap and runs OCR on it, to verify the on-device recognizer. */
    fun debugOcr() {
        val w = 900; val h = 260
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp); c.drawColor(android.graphics.Color.WHITE)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = android.graphics.Color.BLACK; p.textSize = 52f
        c.drawText("StudioSnap OCR test 12345", 30f, 90f, p)
        p.textSize = 40f
        c.drawText("The quick brown fox.", 30f, 170f, p)
        OcrEngine.recognize(bmp) { text -> Log.i(TAG, "ocr result: <<${text.replace("\n", " / ")}>>") }
    }

    private fun testBitmap(): Bitmap {
        val w = 1920; val h = 1200
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp); val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(0xFF9CC0FF.toInt(), 0xFFFFC3AD.toInt(), 0xFF98E0C4.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
        p.color = 0x22000000; p.strokeWidth = 1f
        var x = 0; while (x <= w) { c.drawLine(x.toFloat(), 0f, x.toFloat(), h.toFloat(), p); x += 100 }
        var y = 0; while (y <= h) { c.drawLine(0f, y.toFloat(), w.toFloat(), y.toFloat(), p); y += 100 }
        p.color = 0xCC101216.toInt(); p.textSize = 22f
        x = 0; while (x <= w) { y = 0; while (y <= h) { c.drawText("$x,$y", x + 4f, y + 24f, p); y += 200 }; x += 200 }
        p.textSize = 60f; p.color = 0xFF14161A.toInt()
        c.drawText("StudioSnap TEST FRAME", 70f, 120f, p)
        return bmp
    }

    private fun isNight(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Debug/visual-check: screenshots ONLY StudioSnap's own overlay window (never the user's apps). */
    fun captureOverlayShot(tag: String) {
        // The camera bubble is an overlay too: never screenshot it while it shows a real face.
        if (bubble.showingCamera) {
            Log.w(TAG, "shot refused: the camera bubble is live (debug bubble test on first)")
            return
        }
        val overlays = ArrayList<AccessibilityWindowInfo>()
        val all = windowsOnAllDisplays
        for (i in 0 until all.size()) for (w in all.valueAt(i))
            if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) overlays.add(w)
        val target = overlays.maxByOrNull { it.layer } ?: run { Log.w(TAG, "shot: no overlay window"); return }
        takeScreenshotOfWindow(target.id, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hb = result.hardwareBuffer
                val bmp = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                hb.close()
                if (bmp == null) { Log.w(TAG, "shot: null bitmap"); return }
                val dir = java.io.File(cacheDir, "shots").apply { mkdirs() }
                val f = java.io.File(dir, "$tag.png")
                java.io.FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                Log.i(TAG, "shot OK ${bmp.width}x${bmp.height} -> ${f.absolutePath}")
            }
            override fun onFailure(code: Int) { Log.w(TAG, "shot FAIL code=$code") }
        })
    }

    // ---- text capture (exact text from accessibility nodes) ----

    private fun onText(bmp: Bitmap?, rect: Rect) {
        bg.execute {
            val nodeText = extractText(rect)
            if (nodeText.length >= 2 || bmp == null) {
                deliverText(nodeText, "a11y")
            } else {
                // Nothing selectable in the accessibility tree (image / canvas / PDF / remote
                // desktop) — read the pixels with OCR.
                OcrEngine.recognize(bmp) { ocr -> deliverText(ocr, "ocr") }
            }
        }
    }

    private fun deliverText(text: String, src: String) {
        Output.copyText(this, text)
        main.post { showText(text) }
        Log.i(TAG, "text[$src] ${text.length} chars -> clipboard")
    }

    private fun extractText(rect: Rect): String {
        // Only the topmost app window overlapping the selection, so occluded windows underneath
        // don't leak their text into the result.
        val all = windowsOnAllDisplays
        var target: AccessibilityWindowInfo? = null
        val wr = android.graphics.Rect()
        for (i in 0 until all.size()) for (w in all.valueAt(i)) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            w.getBoundsInScreen(wr)
            val overlaps = wr.right > rect.left && rect.right > wr.left && wr.bottom > rect.top && rect.bottom > wr.top
            if (overlaps && (target == null || w.layer > target!!.layer)) target = w
        }
        val root = target?.root ?: return ""
        val hits = ArrayList<Triple<Int, Int, String>>()
        val r = android.graphics.Rect()
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root); var n = 0
        while (q.isNotEmpty() && n < 8000) {
            val node = q.removeFirst(); n++
            val t = node.text
            if (!t.isNullOrBlank() && node.isVisibleToUser) {
                node.getBoundsInScreen(r)
                if (rect.contains(Offset(r.exactCenterX(), r.exactCenterY()))) hits.add(Triple(r.top, r.left, t.toString()))
            }
            for (c in 0 until node.childCount) node.getChild(c)?.let { q.add(it) }
        }
        hits.sortWith(compareBy({ it.first / 24 }, { it.second }))
        return hits.joinToString("\n") { it.third }
    }

    private fun showText(text: String) {
        val ov = textOverlay ?: ComposeOverlay(this).also { textOverlay = it }
        ov.show {
            TextRoot(text, dark = isNight(),
                onCopy = { Output.copyText(this, text) },
                onSearch = { webSearch(text) },
                onClose = { textOverlay?.dismiss() })
        }
    }

    private fun webSearch(text: String) {
        if (text.isBlank()) return
        startActivity(
            android.content.Intent.createChooser(
                android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_TEXT, text),
                "Search or share text",
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        textOverlay?.dismiss()
    }

    // ---- recording ----

    /** A Record-mode toggle was tapped. Turning one on may first need a runtime permission. */
    private fun toggleRec(t: RecToggle) {
        if (t != RecToggle.CAMERA && (recordPending || io.github.kuscher.studiosnap.record.RecordingBus.active)) {
            // The audio sources are fixed when a recording starts: a toggle flipped now would show
            // the mic as off while it's still being recorded. (The camera bubble does act live.)
            notice("Stop the recording to change the mic or system audio.")
            return
        }
        val on = !recOptions.isOn(t)
        if (on && checkSelfPermission(t.permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // Android's permission dialog draws under our full-screen overlay, so the bar steps
            // aside (hidden, not closed) and comes back as it was once the user has answered.
            // Granting a permission makes Android restart this service, which removes our windows,
            // so the bar is also parked process-wide for whichever service object is alive then.
            parkBar()
            captureOverlay?.setHidden(true)
            updateBubble() // the bubble steps aside with the bar
            startActivity(io.github.kuscher.studiosnap.PermissionActivity.intent(this, t))
            return
        }
        recOptions.set(t, on)
        updateBubble()
        Log.i(TAG, "rec toggle ${t.name}=$on")
    }

    /** Applies a permission answer that [io.github.kuscher.studiosnap.PermissionActivity] parked. */
    fun consumePendingPermission() {
        if (!alive) return // the new service object applies it when it connects
        val (t, granted) = pendingPermission ?: return
        pendingPermission = null
        onPermissionResult(t, granted)
    }

    private fun onPermissionResult(t: RecToggle, granted: Boolean) {
        Log.i(TAG, "permission for ${t.name}: granted=$granted")
        recOptions.reload()  // PermissionActivity already saved the toggle on a grant
        // The bar first: a message shown before it could end up under a bar re-added on top.
        restoreBar()
        if (!granted) notice("${t.label} is off. Allow it for StudioSnap in App info › Permissions.", long = true)
    }

    /** Remembers the open bar (frozen screen, mode, source) so it can come back as it was. */
    private fun parkBar() {
        val s = session ?: return
        parkedBar = ParkedBar(s.frozenBitmap, s.mode, s.source, SystemClock.elapsedRealtime())
    }

    /**
     * Brings a parked bar back: the same window if this service object still has it, otherwise a
     * new bar on the same frozen screen. Never while a permission dialog is up.
     */
    private fun restoreBar() {
        if (io.github.kuscher.studiosnap.PermissionActivity.showing) return
        val p = parkedBar ?: return
        parkedBar = null
        if (SystemClock.elapsedRealtime() - p.at > PARK_TTL_MS) {
            // Too old to bring back: close it rather than leave an invisible bar holding the keys.
            if (captureOverlay?.hidden == true) dismissCapture()
            return
        }
        val ov = captureOverlay
        if (ov != null && ov.shown && ov.hidden && session != null) {
            ov.setHidden(false)
            session?.holdRecord()
            updateBubble()
            Log.i(TAG, "bar back from behind the dialog")
        } else {
            openBar(initialSource = p.source, initialMode = p.mode, frozen = p.frozen)
            Log.i(TAG, "bar restored (${p.mode})")
        }
    }

    /**
     * The camera bubble shows while it's wanted: the camera toggle is on and permitted, and the
     * bar is in Record mode or a recording is starting or running.
     */
    private fun updateBubble() {
        if (!alive) { bubble.hide(); return }
        val permitted = checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val recording = recordPending || io.github.kuscher.studiosnap.record.RecordingBus.active
        val barInRec = barShown() && session?.mode == CaptureMode.REC
        if (recOptions.camera && permitted && (recording || barInRec)) bubble.show() else bubble.hide()
    }

    private fun startRecordFlow() {
        if (recordPending || io.github.kuscher.studiosnap.record.RecordService.instance != null) {
            // One recording at a time: a second start would replace the running session.
            Log.i(TAG, "record ignored: a recording is already starting, running or saving")
            dismissCapture()
            notice("Already recording")
            return
        }
        recordPending = true
        dismissCapture()
        startActivity(
            android.content.Intent(this, io.github.kuscher.studiosnap.RecordActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun onRecordingStarted() {
        recordPending = false
        showRecordingControls()
    }

    /**
     * The recording pill, in a window only as big as the pill (top centre), so the rest of the
     * screen stays clickable while recording: a full-screen overlay takes every touch, even where
     * it's transparent. Accessibility overlays ignore gravity, so it's centred and offset upward.
     */
    private fun showRecordingControls() {
        if (!alive) return
        val density = resources.displayMetrics.density
        val h = (PILL_HEIGHT_DP * density).toInt()
        val screenH = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds.height()
        val top = (PILL_TOP_DP * density).toInt()
        val ov = recordOverlay ?: ComposeOverlay(
            this,
            widthSpec = WindowManager.LayoutParams.WRAP_CONTENT,
            heightSpec = h,
            gravity = Gravity.CENTER,
            noLimits = false,
            offsetY = top + h / 2 - screenH / 2,
        ).also { recordOverlay = it }
        ov.show { RecordRoot(dark = isNight()) }
        // The pill and the bubble don't overlap, so the bubble needn't be re-added (a re-add
        // re-binds the camera); but after a service reconnect it has to be recreated.
        updateBubble()
    }

    /** Stop was pressed: the bubble goes now (the camera closes), while the file is still saving. */
    fun onRecordingStopping() = updateBubble()

    fun onRecordingSaved(ok: Boolean, durationMs: Long, thumb: Bitmap?) {
        recordOverlay?.dismiss()
        recordPending = false
        updateBubble()
        if (!ok) return
        val img = (thumb ?: Bitmap.createBitmap(600, 380, Bitmap.Config.ARGB_8888).also { it.eraseColor(0xFF2A2D33.toInt()) }).asImageBitmap()
        val s = durationMs / 1000
        cards.add(CardData(cardId++, img, "Recording · %d:%02d".format(s / 60, s % 60), copied = false, saved = true, filePath = null))
        while (cards.size > 1) cards.removeAt(0)
        ensureCardsOverlay()
    }

    companion object {
        const val TAG = "StudioSnap"
        private const val PILL_HEIGHT_DP = 52
        private const val PILL_TOP_DP = 24
        private const val NOTICE_HEIGHT_DP = 56
        private const val NOTICE_TASKBAR_FLOOR_DP = 72
        /** Space between the bar and a message next to it (dp). */
        private const val NOTICE_GAP_DP = 4
        private const val NOTICE_SHORT_MS = 2500L
        private const val NOTICE_LONG_MS = 4000L
        private const val NOTICE_FREEZE_WAIT_MS = 100L
        private const val PENDING_NOTICE_TTL_MS = 10_000L

        /** A message for the next service object to show (Android recreates this service around dialogs). */
        @Volatile var pendingNotice: Pair<String, Long>? = null
            private set

        fun parkNotice(text: String) { pendingNotice = text to SystemClock.uptimeMillis() }
        @Volatile var instance: SnapService? = null
            private set
        /**
         * Record was pressed and the recorder hasn't started (the consent dialog is up). Process-
         * wide, so it survives the service being recreated; cleared when the recording starts,
         * when consent is refused, or when [io.github.kuscher.studiosnap.RecordActivity] goes away
         * without an answer (say, its app window was closed from the taskbar).
         */
        @Volatile var recordPending = false

        /** An open bar to bring back after a permission dialog or a service restart. */
        class ParkedBar(val frozen: Bitmap?, val mode: CaptureMode, val source: Source, val at: Long)
        @Volatile var parkedBar: ParkedBar? = null
        private const val PARK_TTL_MS = 60_000L

        /** A permission answer waiting for the service (it may be re-binding when it arrives). */
        @Volatile var pendingPermission: Pair<RecToggle, Boolean>? = null
    }
}
