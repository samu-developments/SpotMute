package com.developments.samu.muteforspotify.utilities

import android.content.SharedPreferences
import android.content.pm.PackageManager
import com.developments.samu.muteforspotify.service.LoggerService
import org.threeten.bp.Instant
import org.threeten.bp.LocalDateTime
import org.threeten.bp.ZoneId
import org.threeten.bp.format.DateTimeFormatter
import org.threeten.bp.format.FormatStyle

private const val TAG = "AppUtil"

fun isPackageInstalled(packageManager: PackageManager, packageName: String): Boolean {
    return try {
        packageManager.getPackageInfo(packageName, 0) != null
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}

fun LocalDateTime.toReadableString(): String = this.format(
    DateTimeFormatter.ofLocalizedTime(
        FormatStyle.MEDIUM))

fun Long.toLocalDateTime(): LocalDateTime =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(this), ZoneId.systemDefault())

class AppUtil {
    companion object {
        val ONE_SECOND_MS = 1000
        val THREE_SECOND_MS = 3000
        val AD_TIME_MS = 10000 // 10 second (an ad is supposed to be ~30 sec, but could be less?
        val DKMA_URL = "https://dontkillmyapp.com/"
        val SPOTMUTE_PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=com.developments.samu.muteforspotify"
    }
}

fun SharedPreferences.hasDbsEnabled() = getBoolean(
    LoggerService.PREF_DEVICE_BROADCAST_ENABLED_KEY,
    LoggerService.PREF_DEVICE_BROADCAST_ENABLED_DEFAULT
)

fun SharedPreferences.getUnmuteDelay() = getLong(
    LoggerService.PREF_UNMUTE_DELAY_BUFFER_KEY,
    LoggerService.PREF_UNMUTE_DELAY_BUFFER_DEFAULT
)

fun SharedPreferences.getMuteDelay() = getLong(
    LoggerService.PREF_MUTE_DELAY_BUFFER_KEY,
    LoggerService.PREF_MUTE_DELAY_BUFFER_DEFAULT
)
