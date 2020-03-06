package com.developments.samu.muteforspotify

import android.app.Application
import android.os.Build
import com.developments.samu.muteforspotify.service.LoggerService

class MuteApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            LoggerService.createNotificationChannel(this)
        }
    }
}