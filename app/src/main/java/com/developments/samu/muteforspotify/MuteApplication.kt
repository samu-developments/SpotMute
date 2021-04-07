package com.developments.samu.muteforspotify

import android.app.Application
import android.os.Build
import com.developments.samu.muteforspotify.service.LoggerService
import com.jakewharton.threetenabp.AndroidThreeTen

class MuteApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidThreeTen.init(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            LoggerService.createNotificationChannel(this)
        }
    }
}