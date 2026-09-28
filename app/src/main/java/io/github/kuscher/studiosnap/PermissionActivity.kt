package io.github.kuscher.studiosnap

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import io.github.kuscher.studiosnap.record.RecToggle
import io.github.kuscher.studiosnap.service.SnapService

/**
 * Transparent trampoline that asks for the runtime permission a Record-mode toggle needs (the
 * accessibility service can't show the system dialog itself), then reports back to [SnapService],
 * which turns the toggle on and reopens the bar.
 */
class PermissionActivity : Activity() {
    private lateinit var toggle: RecToggle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    private fun done(granted: Boolean) {
        SnapService.instance?.onPermissionResult(toggle, granted)
        finish()
    }

    companion object {
        private const val REQUEST = 42
        private const val EXTRA_TOGGLE = "toggle"

        fun intent(ctx: Context, t: RecToggle): Intent =
            Intent(ctx, PermissionActivity::class.java)
                .putExtra(EXTRA_TOGGLE, t.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
