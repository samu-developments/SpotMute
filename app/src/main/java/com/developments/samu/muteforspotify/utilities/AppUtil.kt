package com.developments.samu.muteforspotify.utilities

import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import com.developments.samu.muteforspotify.MainActivity
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

val supportsOpeningSpotifySettingsDirectly = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

fun LocalDateTime.toReadableString(): String = this.format(
    DateTimeFormatter.ofLocalizedTime(
        FormatStyle.MEDIUM
    )
)

fun Long.toLocalDateTime(): LocalDateTime =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(this), ZoneId.systemDefault())

class AppUtil {
    companion object {
        val DKMA_URL = "https://dontkillmyapp.com/"
        val SPOTMUTE_PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=com.developments.samu.muteforspotify"
    }
}

fun SharedPreferences.hasDbsEnabled() = getBoolean(
    LoggerService.PREF_DEVICE_BROADCAST_ENABLED_KEY,
    LoggerService.PREF_DEVICE_BROADCAST_ENABLED_DEFAULT
)

fun SharedPreferences.hasSeenReviewFlow() = getBoolean(
    MainActivity.PREF_KEY_REVIEW_FLOW,
    MainActivity.PREF_REVIEW_FLOW_DEFAULT
)
