package com.instadown.app

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

class CopyErrorReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_ERROR = "error"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val error = intent.getStringExtra(EXTRA_ERROR) ?: return
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("InstaDown error", error))
        Toast.makeText(context, "Error copied to clipboard", Toast.LENGTH_SHORT).show()
        context.getSystemService(NotificationManager::class.java).cancel(1002)
    }
}
