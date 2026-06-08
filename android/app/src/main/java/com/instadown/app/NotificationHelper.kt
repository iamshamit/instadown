package com.instadown.app

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object NotificationHelper {
    const val CHANNEL_ID = "instadown_downloads"
    const val NOTIF_ID = 1001        // foreground / progress — cancelled when service stops
    private const val NOTIF_RESULT_ID = 1002  // success or failure — separate ID, survives service stop

    fun buildProgress(context: Context, current: Int, total: Int): android.app.Notification {
        val text = when {
            total > 0 -> "Downloading $current / $total photos…"
            current > 0 -> "Downloading $current photos…"
            else -> "Downloading…"
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setProgress(total, current, total == 0)
            .setOngoing(true)
            .build()
    }

    fun showProgress(context: Context, current: Int, total: Int) {
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildProgress(context, current, total))
    }

    fun showComplete(context: Context, saved: Int) {
        val label = ThemePrefs.getStorageLabel()
        val text = if (saved == 1) "1 photo saved to $label" else "$saved photos saved to $label"
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIF_RESULT_ID, notif)
    }

    fun showFailure(context: Context, error: String) {
        val copyIntent = Intent(context, CopyErrorReceiver::class.java).apply {
            putExtra(CopyErrorReceiver.EXTRA_ERROR, error)
        }
        val pi = PendingIntent.getBroadcast(
            context, 0, copyIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Download failed — tap to copy error")
            .setContentText(error)
            .setStyle(NotificationCompat.BigTextStyle().bigText(error))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIF_RESULT_ID, notif)
    }
}
