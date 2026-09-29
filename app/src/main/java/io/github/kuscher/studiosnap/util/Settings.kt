package io.github.kuscher.studiosnap.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Simple synchronous settings (SharedPreferences) so the accessibility service can read flags on
 * the key path without suspending. Defaults match the plan's recommended setup.
 */
class Settings(ctx: Context) {
    private val p: SharedPreferences = ctx.applicationContext.getSharedPreferences("studiosnap", Context.MODE_PRIVATE)

    var keyTakeover: Boolean
        get() = p.getBoolean(KEY_TAKEOVER, true)
        set(v) = p.edit().putBoolean(KEY_TAKEOVER, v).apply()

    var copyAfter: Boolean
        get() = p.getBoolean(COPY, true)
        set(v) = p.edit().putBoolean(COPY, v).apply()

    var saveAfter: Boolean
        get() = p.getBoolean(SAVE, true)
        set(v) = p.edit().putBoolean(SAVE, v).apply()

    var showCard: Boolean
        get() = p.getBoolean(CARD, true)
        set(v) = p.edit().putBoolean(CARD, v).apply()

    var barAtTop: Boolean
        get() = p.getBoolean(BAR_TOP, false)
        set(v) = p.edit().putBoolean(BAR_TOP, v).apply()

    /** Record the microphone with screen recordings (a voice-over). Needs RECORD_AUDIO. */
    var recMic: Boolean
        get() = p.getBoolean(REC_MIC, false)
        set(v) = p.edit().putBoolean(REC_MIC, v).apply()

    /** Record the sound apps play (media, games) with screen recordings. Needs RECORD_AUDIO. */
    var recSystemAudio: Boolean
        get() = p.getBoolean(REC_SYSTEM, false)
        set(v) = p.edit().putBoolean(REC_SYSTEM, v).apply()

    /** Show the floating camera bubble in Record mode (recorded with the screen). Needs CAMERA. */
    var recCamera: Boolean
        get() = p.getBoolean(REC_CAMERA, false)
        set(v) = p.edit().putBoolean(REC_CAMERA, v).apply()

    var bubbleLarge: Boolean
        get() = p.getBoolean(BUBBLE_LARGE, false)
        set(v) = p.edit().putBoolean(BUBBLE_LARGE, v).apply()

    /** Rounded square instead of a circle. */
    var bubbleSquare: Boolean
        get() = p.getBoolean(BUBBLE_SQUARE, false)
        set(v) = p.edit().putBoolean(BUBBLE_SQUARE, v).apply()

    /** Cut-out mode: remove the background so only the person floats over the screen. */
    var bubbleCutout: Boolean
        get() = p.getBoolean(BUBBLE_CUTOUT, false)
        set(v) = p.edit().putBoolean(BUBBLE_CUTOUT, v).apply()

    /**
     * 0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right (the default, like ChromeOS), or -1
     * when the bubble was left somewhere else (see [bubbleFreeX] / [bubbleFreeY]).
     */
    var bubbleCorner: Int
        get() = p.getInt(BUBBLE_CORNER, 3)
        set(v) = p.edit().putInt(BUBBLE_CORNER, v).apply()

    /** Where a free (not cornered) bubble's center is, as a fraction of the screen's width. */
    var bubbleFreeX: Float
        get() = p.getFloat(BUBBLE_FREE_X, 0.5f)
        set(v) = p.edit().putFloat(BUBBLE_FREE_X, v).apply()

    /** ... and of its height. */
    var bubbleFreeY: Float
        get() = p.getFloat(BUBBLE_FREE_Y, 0.5f)
        set(v) = p.edit().putFloat(BUBBLE_FREE_Y, v).apply()

    /** Camera2 id of the bubble's camera; null picks the front camera. */
    var bubbleCameraId: String?
        get() = p.getString(BUBBLE_CAMERA, null)
        set(v) = p.edit().putString(BUBBLE_CAMERA, v).apply()

    /** Set once the user finishes (or skips) the first-run onboarding. */
    var onboardingDone: Boolean
        get() = p.getBoolean(ONBOARDED, false)
        set(v) = p.edit().putBoolean(ONBOARDED, v).apply()

    companion object {
        private const val KEY_TAKEOVER = "keyTakeover"
        private const val COPY = "copyAfter"
        private const val SAVE = "saveAfter"
        private const val CARD = "showCard"
        private const val BAR_TOP = "barAtTop"
        private const val ONBOARDED = "onboardingDone"
        private const val REC_MIC = "recMic"
        private const val REC_SYSTEM = "recSystemAudio"
        private const val REC_CAMERA = "recCamera"
        private const val BUBBLE_LARGE = "bubbleLarge"
        private const val BUBBLE_SQUARE = "bubbleSquare"
        private const val BUBBLE_CORNER = "bubbleCorner"
        private const val BUBBLE_CAMERA = "bubbleCameraId"
        private const val BUBBLE_CUTOUT = "bubbleCutout"
        private const val BUBBLE_FREE_X = "bubbleFreeX"
        private const val BUBBLE_FREE_Y = "bubbleFreeY"
    }
}
