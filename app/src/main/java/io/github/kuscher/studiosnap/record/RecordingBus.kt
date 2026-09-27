package io.github.kuscher.studiosnap.record

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Controls the active recording, called from the recording pill. */
interface RecordController {
    fun stop()
    fun discard()
}

/** Shared, observable recording state so the pill (in the accessibility service) can reflect and
 *  control the recorder (a separate foreground service). */
object RecordingBus {
    var active by mutableStateOf(false)
    var elapsedMs by mutableStateOf(0L)
    var controller: RecordController? = null
}
