package io.github.kuscher.studiosnap.util

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings as SysSettings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import io.github.kuscher.studiosnap.record.RecordService

/**
 * Notifications for StudioSnap, asked from an activity (home, first run, Settings). They carry the
 * recording notification with its Stop button, and a fresh install never has them: Android only
 * grants them when asked. Create it while the activity is being created (it registers for a
 * result); [onChange] hears the new state after an answer.
 */
class NotificationAccess(private val activity: ComponentActivity, private val onChange: (Boolean) -> Unit) {
    private val settings = Settings(activity)
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { onChange(isOn(activity)) }

    /**
     * Android's dialog while it still shows it; else (refused twice, or turned off in Settings) the
     * app's notification settings, where the switch is; or, when only the recording channel is
     * off, that channel's settings.
     */
    fun request() {
        val perm = Manifest.permission.POST_NOTIFICATIONS
        val granted = activity.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
        val final = settings.notificationsAsked && !activity.shouldShowRequestPermissionRationale(perm)
        val nm = activity.getSystemService(NotificationManager::class.java)
        if (granted && nm.areNotificationsEnabled() && !channelOn(activity)) {
            activity.startActivity(
                Intent(SysSettings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(SysSettings.EXTRA_APP_PACKAGE, activity.packageName)
                    .putExtra(SysSettings.EXTRA_CHANNEL_ID, RecordService.CHANNEL),
            )
        } else if (granted || final) {
            activity.startActivity(
                Intent(SysSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(SysSettings.EXTRA_APP_PACKAGE, activity.packageName),
            )
        } else {
            settings.notificationsAsked = true
            launcher.launch(perm)
        }
    }

    companion object {
        /**
         * Allowed, not switched off in the app's notification settings, and the recording channel
         * (which the user can turn off on its own) not off either.
         */
        fun isOn(ctx: Context): Boolean =
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
                ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
                channelOn(ctx)

        /** The recording notification's channel is on; not created yet (no recording so far) counts as on. */
        private fun channelOn(ctx: Context): Boolean {
            val ch = ctx.getSystemService(NotificationManager::class.java).getNotificationChannel(RecordService.CHANNEL) ?: return true
            return ch.importance != NotificationManager.IMPORTANCE_NONE
        }
    }
}
