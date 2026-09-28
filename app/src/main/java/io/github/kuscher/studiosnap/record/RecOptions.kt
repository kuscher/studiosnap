package io.github.kuscher.studiosnap.record

import android.Manifest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.kuscher.studiosnap.PermissionActivity
import io.github.kuscher.studiosnap.util.Settings

/** The Record-mode toggles in the capture bar, and the runtime permission each one needs. */
enum class RecToggle(val permission: String, val label: String) {
    MIC(Manifest.permission.RECORD_AUDIO, "Microphone"),
    SYSTEM_AUDIO(Manifest.permission.RECORD_AUDIO, "System audio"),
}

/**
 * Observable (for the bar) and persisted (in [Settings]) state of the Record-mode toggles.
 * [Settings] is the source of truth: [PermissionActivity] writes it directly, and [reload] picks
 * that up when the bar next opens.
 */
class RecOptions(private val settings: Settings) {
    var mic by mutableStateOf(settings.recMic)
        private set
    var systemAudio by mutableStateOf(settings.recSystemAudio)
        private set

    fun isOn(t: RecToggle): Boolean = when (t) {
        RecToggle.MIC -> mic
        RecToggle.SYSTEM_AUDIO -> systemAudio
    }

    fun set(t: RecToggle, on: Boolean) {
        persist(settings, t, on)
        reload()
    }

    fun reload() {
        mic = settings.recMic
        systemAudio = settings.recSystemAudio
    }

    companion object {
        fun persist(settings: Settings, t: RecToggle, on: Boolean) = when (t) {
            RecToggle.MIC -> settings.recMic = on
            RecToggle.SYSTEM_AUDIO -> settings.recSystemAudio = on
        }
    }
}
