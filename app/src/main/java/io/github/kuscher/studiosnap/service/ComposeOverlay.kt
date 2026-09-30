package io.github.kuscher.studiosnap.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Hosts a Compose UI in a TYPE_ACCESSIBILITY_OVERLAY window (above apps, status bar and taskbar).
 * Provides its own lifecycle / saved-state / viewmodel owners so Compose runs outside an Activity.
 */
class ComposeOverlay(
    private val context: Context,
    private val widthSpec: Int = WindowManager.LayoutParams.MATCH_PARENT,
    private val heightSpec: Int = WindowManager.LayoutParams.MATCH_PARENT,
    private val gravity: Int = Gravity.TOP or Gravity.START,
    private val blurRadius: Int = 0,
    // Full-screen overlays need NO_LIMITS to cover cutouts/edges.
    private val noLimits: Boolean = true,
    // For a full-screen overlay that only shows content in one corner (the result card): let pointer
    // events outside the touchable content fall through to the apps behind instead of being eaten.
    private val touchThrough: Boolean = false,
    // A window that only shows something (a message): every click goes to whatever is under it.
    private val touchable: Boolean = true,
    // Accessibility overlays ignore gravity (they always centre), so a corner window is placed by
    // offsetting from centre with these (pixels): +x right, +y down.
    private val offsetX: Int = 0,
    private val offsetY: Int = 0,
) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this).apply { performRestore(null) }
    override val viewModelStore = ViewModelStore()
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private var view: ComposeView? = null
    var shown = false
        private set
    /** Shown but hidden: invisible and untouchable, with its UI and state kept (see [setHidden]). */
    var hidden = false
        private set

    val params: WindowManager.LayoutParams = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        format = PixelFormat.TRANSLUCENT
        width = widthSpec
        height = heightSpec
        this.gravity = gravity
        x = offsetX
        y = offsetY
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            (if (noLimits) WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS else 0) or
            (if (touchThrough) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL else 0) or
            (if (!touchable) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)
        if (noLimits) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        if (blurRadius > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            blurBehindRadius = blurRadius
        }
    }

    fun show(content: @Composable () -> Unit) {
        val existing = view
        if (shown && existing != null) { existing.setContent(content); return }
        val cv = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@ComposeOverlay)
            setViewTreeViewModelStoreOwner(this@ComposeOverlay)
            setViewTreeSavedStateRegistryOwner(this@ComposeOverlay)
            setContent(content)
        }
        view = cv
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        wm.addView(cv, params)
        shown = true
    }

    /**
     * Hides a shown window without removing it (or brings it back): used while a system dialog has
     * to be seen and clicked, which an accessibility overlay would otherwise cover.
     */
    fun setHidden(h: Boolean) {
        val v = view ?: return
        hidden = h
        v.visibility = if (h) android.view.View.INVISIBLE else android.view.View.VISIBLE
        params.flags = if (h || !touchable) params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        runCatching { wm.updateViewLayout(v, params) }
    }

    /** Moves a shown window (offsets from center, see [offsetX]) without recreating its UI. */
    fun moveTo(x: Int, y: Int) {
        params.x = x; params.y = y
        view?.let { runCatching { wm.updateViewLayout(it, params) } }
    }

    fun resize(width: Int, height: Int) {
        params.width = width; params.height = height
        view?.let { runCatching { wm.updateViewLayout(it, params) } }
    }

    fun dismiss() {
        if (hidden) setHidden(false)
        val v = view ?: return
        runCatching { wm.removeViewImmediate(v) }
        view = null
        shown = false
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun destroy() {
        dismiss()
        viewModelStore.clear()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}
