package io.github.kuscher.studiosnap.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import io.github.kuscher.studiosnap.ui.CaptureMode
import io.github.kuscher.studiosnap.ui.CaptureRoot
import io.github.kuscher.studiosnap.ui.Source

/**
 * The always-ready service. It filters keyboard shortcuts (so StudioSnap can own the Screenshot
 * key) and hosts every overlay. Key handling returns immediately; UI work is posted to the main
 * thread, because Android waits up to 500 ms on the service for each key.
 */
class SnapService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var overlay: ComposeOverlay? = null

    // Settings come from DataStore in a later phase; Phase 0 defaults to key takeover on.
    private var keyTakeover = true

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "connected: flags=0x${Integer.toHexString(serviceInfo.flags)} caps=0x${Integer.toHexString(serviceInfo.capabilities)}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        closeBar()
        overlay?.destroy()
        overlay = null
        instance = null
        return super.onUnbind(intent)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        val code = event.keyCode
        // Close the bar on Esc while it is open.
        if (barShown()) {
            if (down && code == KeyEvent.KEYCODE_ESCAPE) { main.post { closeBar() }; return true }
        }
        if (!keyTakeover) return false
        val screenshotKey = code == KeyEvent.KEYCODE_SYSRQ || code == KeyEvent.KEYCODE_SCREENSHOT
        val metaShiftS = code == KeyEvent.KEYCODE_S && event.isMetaPressed && event.isShiftPressed
        if (screenshotKey || metaShiftS) {
            if (down) {
                val shift = event.isShiftPressed && screenshotKey
                val alt = event.isAltPressed && screenshotKey
                val ctrl = event.isCtrlPressed && screenshotKey
                main.post { openBar(if (shift) Source.SCREEN else if (alt) Source.WINDOW else null, ctrl) }
            }
            return true // consume every phase of the trigger so the system never also fires
        }
        return false
    }

    // ---- overlay control (main thread) ----

    fun openBar(initialSource: Source? = null, repeatLast: Boolean = false) {
        val t0 = SystemClock.elapsedRealtime()
        val ov = overlay ?: ComposeOverlay(this, fullScreen = true, blurBehind = false).also { overlay = it }
        ov.show {
            CaptureRoot(
                onPrimary = { state ->
                    Log.i(TAG, "capture requested: mode=${state.mode} source=${state.source} (Phase 1 will capture)")
                    closeBar()
                },
                onClose = { closeBar() },
            )
        }
        Log.i(TAG, "bar shown in ${SystemClock.elapsedRealtime() - t0}ms (source=$initialSource repeatLast=$repeatLast)")
    }

    fun closeBar() { overlay?.dismiss() }
    fun barShown(): Boolean = overlay?.shown == true

    /**
     * Debug/visual-check helper: screenshots ONLY StudioSnap's own overlay window (never the
     * user's apps) and saves a PNG to the app cache. Used to verify HUD rendering over adb.
     */
    fun captureOverlayShot(tag: String) {
        val overlays = ArrayList<android.view.accessibility.AccessibilityWindowInfo>()
        val all = windowsOnAllDisplays
        for (i in 0 until all.size()) for (w in all.valueAt(i))
            if (w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) overlays.add(w)
        val target = overlays.maxByOrNull { it.layer }
        if (target == null) { Log.w(TAG, "shot: no overlay window found"); return }
        takeScreenshotOfWindow(target.id, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hb = result.hardwareBuffer
                val bmp = android.graphics.Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                    ?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                hb.close()
                if (bmp == null) { Log.w(TAG, "shot: null bitmap"); return }
                val dir = java.io.File(cacheDir, "shots").apply { mkdirs() }
                val f = java.io.File(dir, "$tag.png")
                java.io.FileOutputStream(f).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
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
