package io.github.kuscher.studiosnap

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import io.github.kuscher.studiosnap.record.RecOptions
import io.github.kuscher.studiosnap.record.RecToggle
import io.github.kuscher.studiosnap.service.SnapService
import io.github.kuscher.studiosnap.util.Settings

/**
 * Transparent trampoline that asks for the runtime permission a Record-mode toggle needs (the
 * accessibility service can't show the system dialog itself). A grant turns the toggle on in
 * [Settings]; then [SnapService] reopens the bar.
 */
class PermissionActivity : Activity() {
    private lateinit var toggle: RecToggle
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showing = true
        toggle = RecToggle.valueOf(intent.getStringExtra(EXTRA_TOGGLE) ?: RecToggle.MIC.name)
        if (checkSelfPermission(toggle.permission) == PackageManager.PERMISSION_GRANTED) {
            done(true)
        } else {
            requestPermissions(arrayOf(toggle.permission), REQUEST)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        done(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
    }

    override fun onDestroy() {
        showing = false
        // Gone without an answer (say, its window was closed from the taskbar): count it as a
        // refusal, so the bar hidden behind the dialog comes back instead of staying hidden.
        if (!answered && !isChangingConfigurations && ::toggle.isInitialized) answer(false)
        super.onDestroy()
    }

    private fun done(granted: Boolean) {
        answer(granted)
        finish()
    }

    private fun answer(granted: Boolean) {
        answered = true
        showing = false // the dialog is gone: the bar may come back now
        // Save the toggle here, not in the service: Android can briefly re-bind the accessibility
        // service around the permission dialog, so SnapService.instance may be null right now.
        // The answer is parked, then applied now or when the service reconnects.
        if (granted) RecOptions.persist(Settings(this), toggle, true)
        SnapService.pendingPermission = toggle to granted
        SnapService.instance?.consumePendingPermission()
    }

    companion object {
        private const val REQUEST = 42
        private const val EXTRA_TOGGLE = "toggle"

        /** A permission dialog is up: a restored bar must wait, or it would cover the dialog. */
        @Volatile var showing = false
            private set

        fun intent(ctx: Context, t: RecToggle): Intent =
            Intent(ctx, PermissionActivity::class.java)
                .putExtra(EXTRA_TOGGLE, t.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
