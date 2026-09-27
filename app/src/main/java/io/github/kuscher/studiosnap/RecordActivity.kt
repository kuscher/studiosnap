package io.github.kuscher.studiosnap

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import io.github.kuscher.studiosnap.record.RecordService
import io.github.kuscher.studiosnap.service.SnapService

/**
 * Transparent trampoline that asks for MediaProjection consent (Android shows its picker once per
 * session, or approves instantly when the PROJECT_MEDIA app-op is granted), then hands the token
 * to [RecordService].
 */
class RecordActivity : Activity() {
    private val request = 41

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(pm.createScreenCaptureIntent(), request)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == request && resultCode == RESULT_OK && data != null) {
            RecordService.start(this, resultCode, data)
        } else {
            Log.i(SnapService.TAG, "recording consent cancelled")
            SnapService.instance?.onRecordingSaved(false, 0L, null)
        }
        finish()
    }
}
