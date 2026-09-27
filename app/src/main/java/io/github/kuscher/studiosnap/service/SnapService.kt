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
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asImageBitmap
import io.github.kuscher.studiosnap.capture.CaptureKind
import io.github.kuscher.studiosnap.capture.CaptureSession
import io.github.kuscher.studiosnap.capture.Output
import io.github.kuscher.studiosnap.capture.WinInfo
import io.github.kuscher.studiosnap.ui.CaptureMode
import io.github.kuscher.studiosnap.ui.CaptureRoot
import io.github.kuscher.studiosnap.ui.CardData
import io.github.kuscher.studiosnap.ui.CardStack
import io.github.kuscher.studiosnap.ui.Source
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
    private var session: CaptureSession? = null
    private val cards = mutableStateListOf<CardData>()
    private var cardId = 0L

    private var keyTakeover = true

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
        if (!keyTakeover) return false
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

    // ---- overlay control ----

    /** [dry] = don't freeze the screen (transparent backdrop); used only for visual checks so an
     *  overlay screenshot shows StudioSnap's own UI and never the user's apps. */
    fun openBar(initialSource: Source? = null, dry: Boolean = false) {
        if (barShown()) return
        val t0 = SystemClock.elapsedRealtime()
        val present = { bmp: Bitmap? ->
            val s = CaptureSession(this, ::listWindows, ::onResult, ::dismissCapture)
            session = s
            s.onFrozen(bmp, initialSource)
            val ov = captureOverlay ?: ComposeOverlay(this).also { captureOverlay = it }
            ov.show { CaptureRoot(s, dark = isNight()) }
            Log.i(TAG, "bar shown in ${SystemClock.elapsedRealtime() - t0}ms frozen=${bmp != null} source=$initialSource")
        }
        if (dry) present(null) else captureFullScreen(1) { bmp -> present(bmp) }
    }

    private fun onResult(bmp: Bitmap, kind: CaptureKind, label: String) {
        val name = Output.defaultName()
        val img = bmp.asImageBitmap()
        cards.add(CardData(cardId++, img, "$label · ${bmp.width} × ${bmp.height}", copied = true, saved = true))
        while (cards.size > 3) cards.removeAt(0)
        ensureCardsOverlay()
        bg.execute {
            Output.copyToClipboard(this, bmp, name)
            Output.saveToGallery(this, bmp, name)
            Log.i(TAG, "result $kind ${bmp.width}x${bmp.height} -> clipboard + Pictures/StudioSnap")
        }
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
                onEdit = { /* Studio arrives in Phase 2 */ },
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
        val s = CaptureSession(this, ::listWindows, ::onResult, ::dismissCapture)
        session = s
        s.onFrozen(testBitmap(), Source.AREA)
        val ov = captureOverlay ?: ComposeOverlay(this).also { captureOverlay = it }
        ov.show { CaptureRoot(s, dark = isNight()) }
        Log.i(TAG, "test bar shown")
    }

    fun debugSelect(a: Int, b: Int, w: Int, h: Int) {
        session?.let { it.selection = Rect(a.toFloat(), b.toFloat(), (a + w).toFloat(), (b + h).toFloat()); it.phase = io.github.kuscher.studiosnap.capture.SelPhase.ADJUST }
    }

    fun debugGrab(a: Int, b: Int, w: Int, h: Int) {
        session?.captureArea(Rect(a.toFloat(), b.toFloat(), (a + w).toFloat(), (b + h).toFloat()), CaptureKind.AREA)
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

    companion object {
        const val TAG = "StudioSnap"
        @Volatile var instance: SnapService? = null
            private set
    }
}
