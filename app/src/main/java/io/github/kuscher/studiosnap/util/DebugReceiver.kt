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
            "notice" -> svc?.notice(args.drop(1).joinToString(" ").ifBlank { "Test notice" })
            "open" -> svc?.openBar(sourceOf(args.getOrNull(1)))
            "opendry" -> svc?.openBar(sourceOf(args.getOrNull(1)), dry = true)
            "close" -> svc?.closeBar()
            "shot" -> svc?.captureOverlayShot(args.getOrNull(1) ?: "overlay")
            "opentest" -> svc?.openBarTest()
            "mode" -> svc?.debugMode(args.getOrNull(1) ?: "shot")
            "sel" -> svc?.debugSelect(i(args, 1), i(args, 2), i(args, 3), i(args, 4))
            "grab" -> svc?.debugGrab(i(args, 1), i(args, 2), i(args, 3), i(args, 4))
            "aim" -> svc?.debugAim(i(args, 1), i(args, 2))
            "hoverel" -> svc?.debugHover(i(args, 1), i(args, 2), i(args, 3), i(args, 4), true)
            "hoverwin" -> svc?.debugHover(i(args, 1), i(args, 2), i(args, 3), i(args, 4), false)
            "studio" -> svc?.debugStudio(args.getOrNull(1) == "demo")
            "shotwin" -> svc?.debugShotWindow(args.getOrNull(1) ?: "studiosnap", args.getOrNull(2) ?: "win")
            "text" -> svc?.debugText(i(args, 1), i(args, 2), i(args, 3), i(args, 4))
            "rec" -> svc?.debugRecord()
            "recstop" -> svc?.debugRecStop()
            "recframe" -> svc?.debugRecFrame(args.getOrNull(1) ?: "recframe")
            "recopt" -> svc?.debugRecOptions(args.getOrNull(1) ?: "off")
            "recinfo" -> RecProbe.inspectLatest(ctx)
            "recpixel" -> RecProbe.patchColor(ctx, i(args, 1), i(args, 2))
            "bubble" -> svc?.debugBubble(args.drop(1))
            "tone" -> RecProbe.tone(i(args, 1).takeIf { it > 0 } ?: 3, i(args, 2).takeIf { it > 0 } ?: 440)
            "scrollcap" -> svc?.debugScroll(args.getOrNull(1) ?: "studiosnap")
            "scrollself" -> svc?.debugScrollSelfTest()
            "ocr" -> svc?.debugOcr()
            else -> Log.w(SnapService.TAG, "unknown debug command")
        }
    }

    private fun i(a: List<String>, n: Int): Int = a.getOrNull(n)?.toIntOrNull() ?: 0

    private fun sourceOf(s: String?): Source? = when (s) {
        "area" -> Source.AREA; "section" -> Source.SECTION; "window" -> Source.WINDOW; "screen" -> Source.SCREEN
        "scroll" -> Source.SCROLL; "text" -> Source.TEXT; else -> null
    }
}
