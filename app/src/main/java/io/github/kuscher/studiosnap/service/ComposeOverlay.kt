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
 * Hosts a Compose UI in a TYPE_ACCESSIBILITY_OVERLAY window. These windows sit above apps, the
 * status bar and the taskbar (verified on the Googlebook). The host provides its own lifecycle,
 * saved-state and viewmodel owners so Compose can run outside an Activity.
 */
class ComposeOverlay(
    private val context: Context,
    private val fullScreen: Boolean = true,
    private val blurBehind: Boolean = true,
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

    val params: WindowManager.LayoutParams = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        format = PixelFormat.TRANSLUCENT
        // Touchable (so the UI works) but not focusable, so it never steals IME focus; the
        // accessibility service still receives every key through onKeyEvent.
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (fullScreen) {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.TOP or Gravity.START
        } else {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }.also { p ->
        if (blurBehind && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            p.blurBehindRadius = if (fullScreen) 0 else 24
        }
    }

    fun show(content: @Composable () -> Unit) {
        if (shown) {
            view?.setContent(content)
            return
        }
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

    /** Re-apply layout params (e.g. after toggling blur or focusability). */
    fun update() {
        view?.let { runCatching { wm.updateViewLayout(it, params) } }
    }

    fun dismiss() {
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
