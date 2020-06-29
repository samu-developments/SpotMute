package com.developments.samu.muteforspotify.utilities

import android.content.SharedPreferences
import android.content.pm.PackageManager

/*
  Welcome to the class with the weird stuff.
 */

fun isPackageInstalled(packageName: String, packageManager: PackageManager): Boolean =
        try {
            packageManager.getPackageInfo(packageName, 0) != null
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
class AppUtil {
    companion object {
        val ONE_SECOND_MS = 1000
        val DKMA_URL = "https://dontkillmyapp.com/"
    }
}
