package io.github.kuscher.studiosnap

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import io.github.kuscher.studiosnap.record.RecordService
import io.github.kuscher.studiosnap.service.SnapService
import io.github.kuscher.studiosnap.util.Settings

/**
 * Transparent trampoline that asks for MediaProjection consent (Android shows its picker once per
 * session, or approves instantly when the PROJECT_MEDIA app-op is granted), then hands the token
 * to [RecordService]. When an audio toggle is on but the microphone permission is missing (say it
 * was revoked in Settings), it asks for that first; a refusal just records without sound.
 */
class RecordActivity : Activity() {
    private val request = 41
    private val audioRequest = 43

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = Settings(this)
        val needsAudio = (s.recMic || s.recSystemAudio) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        if (needsAudio) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), audioRequest)
        else askConsent()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == audioRequest) askConsent()
    }

    private fun askConsent() {
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
