package com.developments.samu.muteforspotify.utilities

import android.content.pm.PackageManager
import android.util.Log
import com.g00fy2.versioncompare.Version

private const val TAG = "AppUtil"
/*
  Welcome to the class with the weird stuff.
 */

fun isPackageInstalled(packageName: String, packageManager: PackageManager): Boolean {
    return try {
        packageManager.getPackageInfo(packageName, 0) != null
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}

fun supportsSkip(packageManager: PackageManager): Boolean {
    return try {
        val app = packageManager.getPackageInfo(Spotify.PACKAGE_NAME, 0) ?: return false
        Version(Spotify.VERSION_SKIP_SUPPORTED).isAtLeast(app.versionName)
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}

class AppUtil {
    companion object {
        val ONE_SECOND_MS = 1000
        val DKMA_URL = "https://dontkillmyapp.com/"
    }
}
