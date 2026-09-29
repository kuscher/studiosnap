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
    }
}
