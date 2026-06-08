package com.instadown.app

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

class InstaDownApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        ThemePrefs.init(this)
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
    }

    companion object {
        lateinit var instance: InstaDownApp
            private set
    }
}
