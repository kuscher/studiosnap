package io.github.kuscher.studiosnap.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.kuscher.studiosnap.service.SnapService
import io.github.kuscher.studiosnap.ui.Source

/**
 * adb-only test hooks. Guarded by android.permission.DUMP in the manifest, so only the shell can
 * send these. Usage:
 *   adb shell am broadcast --user 10 -n <pkg>/.util.DebugReceiver -a io.github.kuscher.studiosnap.DEBUG --es c "open area"
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val args = (intent.getStringExtra("c") ?: "").trim().split(" ")
        val svc = SnapService.instance
        Log.i(SnapService.TAG, "DEBUG ${args.joinToString(" ")} service=${svc != null}")
        when (args.getOrNull(0)) {
            "ping" -> {}
            "open" -> svc?.openBar(sourceOf(args.getOrNull(1)))
            "opendry" -> svc?.openBar(sourceOf(args.getOrNull(1)), dry = true)
            "close" -> svc?.closeBar()
            "shot" -> svc?.captureOverlayShot(args.getOrNull(1) ?: "overlay")
            "opentest" -> svc?.openBarTest()
            "sel" -> svc?.debugSelect(i(args, 1), i(args, 2), i(args, 3), i(args, 4))
            "grab" -> svc?.debugGrab(i(args, 1), i(args, 2), i(args, 3), i(args, 4))
            "aim" -> svc?.debugAim(i(args, 1), i(args, 2))
            "hoverel" -> svc?.debugHover(i(args, 1), i(args, 2), i(args, 3), i(args, 4), true)
            "hoverwin" -> svc?.debugHover(i(args, 1), i(args, 2), i(args, 3), i(args, 4), false)
            "studio" -> svc?.debugStudio()
            "shotwin" -> svc?.debugShotWindow(args.getOrNull(1) ?: "studiosnap", args.getOrNull(2) ?: "win")
            "text" -> svc?.debugText(i(args, 1), i(args, 2), i(args, 3), i(args, 4))
            "rec" -> svc?.debugRecord()
            "recstop" -> svc?.debugRecStop()
            "recframe" -> svc?.debugRecFrame(args.getOrNull(1) ?: "recframe")
            else -> Log.w(SnapService.TAG, "unknown debug command")
        }
    }

    private fun i(a: List<String>, n: Int): Int = a.getOrNull(n)?.toIntOrNull() ?: 0

    private fun sourceOf(s: String?): Source? = when (s) {
        "area" -> Source.AREA; "window" -> Source.WINDOW; "screen" -> Source.SCREEN
        "scroll" -> Source.SCROLL; "text" -> Source.TEXT; else -> null
    }
}
