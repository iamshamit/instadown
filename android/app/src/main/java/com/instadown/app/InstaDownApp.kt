package com.instadown.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlin.concurrent.thread

class InstaDownApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        ThemePrefs.init(this)
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        createNotificationChannel()
        thread(isDaemon = true, name = "gdl-warmup") {
            try {
                Python.getInstance()
                    .getModule("instadown_android")
                    .callAttr("warmup")
            } catch (_: Throwable) {}
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NotificationHelper.CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                enableVibration(false)
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        lateinit var instance: InstaDownApp
            private set
    }
}
