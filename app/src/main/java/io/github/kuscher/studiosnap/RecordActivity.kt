package io.github.kuscher.studiosnap

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
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
 * was revoked in Settings), it asks for that first; a refusal just records without sound. Before
 * the first recording it also asks, once, for notifications: without them Android hides the
 * recording notification and its Stop action (the service still shows under Active apps).
 */
class RecordActivity : Activity() {
    private val request = 41
    private val permsRequest = 43
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = Settings(this)
        val perms = buildList {
            if ((s.recMic || s.recSystemAudio) && !granted(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (!s.askedNotifications && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                s.askedNotifications = true
                s.notificationsAsked = true
            }
        }
        if (perms.isNotEmpty()) requestPermissions(perms.toTypedArray(), permsRequest)
        else askConsent()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permsRequest) askConsent()
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun askConsent() {
        val pm = getSystemService(MediaProjectionManager::class.java)
        // The camera bubble is an overlay on the screen, not part of any app, so a "single app"
        // recording would leave it out. With the camera on, ask for the entire screen.
        val intent = if (Settings(this).recCamera) {
            pm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            pm.createScreenCaptureIntent()
        }
        startActivityForResult(intent, request)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        answered = true
        if (requestCode == request && resultCode == RESULT_OK && data != null) {
            RecordService.start(this, resultCode, data)
        } else {
            Log.i(SnapService.TAG, "recording consent cancelled")
            SnapService.recordPending = false
            SnapService.instance?.onRecordingSaved(false, 0L, null)
        }
        finish()
    }

    override fun onDestroy() {
        // Closed without an answer (its app window closed from the taskbar, say): nothing else
        // will ever report back, so cancel the pending recording here.
        if (!answered && !isChangingConfigurations) {
            Log.i(SnapService.TAG, "recording consent abandoned")
            SnapService.recordPending = false
            SnapService.instance?.onRecordingSaved(false, 0L, null)
        }
        super.onDestroy()
    }
}
