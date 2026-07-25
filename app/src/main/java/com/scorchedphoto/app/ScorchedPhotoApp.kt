package com.scorchedphoto.app

import android.app.Application
import com.scorchedphoto.app.crash.CrashHandler
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ScorchedPhotoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this))
    }
}
