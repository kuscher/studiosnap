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
import io.github.kuscher.studiosnap.capture.ElementInfo
import io.github.kuscher.studiosnap.capture.Output
import io.github.kuscher.studiosnap.capture.WinInfo
import io.github.kuscher.studiosnap.ui.CaptureMode
import io.github.kuscher.studiosnap.ui.CaptureRoot
import io.github.kuscher.studiosnap.ui.CardData
import io.github.kuscher.studiosnap.ui.CardStack
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
    private var session: CaptureSession? = null
    private val cards = mutableStateListOf<CardData>()
    private var cardId = 0L

    private val settings by lazy { Settings(this) }

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "connected: flags=0x${Integer.toHexString(serviceInfo.flags)} caps=0x${Integer.toHexString(serviceInfo.capabilities)}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        closeBar()
        captureOverlay?.destroy(); captureOverlay = null
        cardsOverlay?.destroy(); cardsOverlay = null
        textOverlay?.destroy(); textOverlay = null
        recordOverlay?.destroy(); recordOverlay = null
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

    // ---- overlay control ----

    /** [dry] = don't freeze the screen (transparent backdrop); used only for visual checks so an
     *  overlay screenshot shows StudioSnap's own UI and never the user's apps. */
    fun openBar(initialSource: Source? = null, dry: Boolean = false) {
        if (barShown()) return
        val t0 = SystemClock.elapsedRealtime()
        val present = { bmp: Bitmap? ->
            val s = CaptureSession(this, ::listWindows, ::elementsIn, ::captureWindow, ::onResult, ::onText, ::startRecordFlow, ::dismissCapture)
            session = s
            s.onFrozen(bmp, initialSource)
            val ov = captureOverlay ?: ComposeOverlay(this).also { captureOverlay = it }
            ov.show { CaptureRoot(s, dark = isNight(), barAtTop = settings.barAtTop) }
            Log.i(TAG, "bar shown in ${SystemClock.elapsedRealtime() - t0}ms frozen=${bmp != null} source=$initialSource")
        }
        if (dry) present(null) else captureFullScreen(1) { bmp -> present(bmp) }
    }

    private fun onResult(bmp: Bitmap, kind: CaptureKind, label: String) {
        val name = Output.defaultName()
        val img = bmp.asImageBitmap()
        val working = Output.saveWorkingFile(this, bmp, name)
        val copy = settings.copyAfter; val save = settings.saveAfter
        if (settings.showCard) {
            cards.add(CardData(cardId++, img, "$label · ${bmp.width} × ${bmp.height}", copied = copy, saved = save, filePath = working.absolutePath))
            while (cards.size > 3) cards.removeAt(0)
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
        val density = resources.displayMetrics.density
        val ov = cardsOverlay ?: ComposeOverlay(
            this,
            widthSpec = (340 * density).toInt(),
            heightSpec = (560 * density).toInt(),
            gravity = Gravity.BOTTOM or Gravity.START,
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
    }

    fun closeBar() { dismissCapture() }
    fun barShown(): Boolean = captureOverlay?.shown == true

    // ---- test harness (safe synthetic content; never the user's screen) ----

    fun openBarTest() {
        if (barShown()) return
        val s = CaptureSession(this, ::listWindows, ::elementsIn, ::captureWindow, ::onResult, ::onText, ::startRecordFlow, ::dismissCapture)
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

    fun debugStudio() {
        val f = java.io.File(cacheDir, "captures").listFiles()?.maxByOrNull { it.lastModified() } ?: return
        startActivity(
            android.content.Intent(this, io.github.kuscher.studiosnap.StudioActivity::class.java)
                .putExtra(io.github.kuscher.studiosnap.StudioActivity.EXTRA_PATH, f.absolutePath)
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
        onText(Rect(a.toFloat(), b.toFloat(), (a + w).toFloat(), (b + h).toFloat()))
    }

    fun debugRecord() { startRecordFlow() }
    fun debugRecStop() { io.github.kuscher.studiosnap.record.RecordingBus.controller?.stop() }
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

    fun debugAim(x: Int, y: Int) {
        session?.let {
            it.changeSource(Source.AREA)
            it.selection = null; it.hover = null
            it.phase = io.github.kuscher.studiosnap.capture.SelPhase.AIM
            it.pointer = androidx.compose.ui.geometry.Offset(x.toFloat(), y.toFloat())
        }
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

    private fun onText(rect: Rect) {
        bg.execute {
            val text = extractText(rect)
            Output.copyText(this, text)
            main.post { showText(text) }
            Log.i(TAG, "text ${text.length} chars -> clipboard")
        }
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

    private fun startRecordFlow() {
        dismissCapture()
        startActivity(
            android.content.Intent(this, io.github.kuscher.studiosnap.RecordActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun onRecordingStarted() {
        val ov = recordOverlay ?: ComposeOverlay(this).also { recordOverlay = it }
        ov.show { RecordRoot(dark = isNight()) }
    }

    fun onRecordingSaved(ok: Boolean, durationMs: Long, thumb: Bitmap?) {
        recordOverlay?.dismiss()
        if (!ok) return
        val img = (thumb ?: Bitmap.createBitmap(600, 380, Bitmap.Config.ARGB_8888).also { it.eraseColor(0xFF2A2D33.toInt()) }).asImageBitmap()
        val s = durationMs / 1000
        cards.add(CardData(cardId++, img, "Recording · %d:%02d".format(s / 60, s % 60), copied = false, saved = true, filePath = null))
        while (cards.size > 3) cards.removeAt(0)
        ensureCardsOverlay()
    }

    companion object {
        const val TAG = "StudioSnap"
        @Volatile var instance: SnapService? = null
            private set
    }
}
