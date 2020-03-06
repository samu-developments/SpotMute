package com.developments.samu.muteforspotify.utilities

import android.content.SharedPreferences
import android.content.pm.PackageManager

/*
  Welcome to the class with the weird stuff.
 */


fun SharedPreferences.applyPref(pref: Pair<String, Any>) {
    val editor = this.edit()
    when (pref.second) {
        is Boolean -> editor.putBoolean(pref.first, pref.second as Boolean)
        is Int -> editor.putInt(pref.first, pref.second as Int)
    }
    editor.apply()
}

fun Int.minutesToMillis() = this * 1000 * 60  // convert minutes to milliseconds


fun isPackageInstalled(packageName: String, packageManager: PackageManager): Boolean =
        try {
            packageManager.getPackageInfo(packageName, 0) != null
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
