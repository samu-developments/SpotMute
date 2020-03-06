package com.developments.samu.muteforspotify

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.preference.PreferenceManager
import com.developments.samu.muteforspotify.service.LoggerService
import com.developments.samu.muteforspotify.utilities.Spotify
import com.developments.samu.muteforspotify.utilities.applyPref
import com.developments.samu.muteforspotify.utilities.isPackageInstalled
import kotlinx.android.synthetic.main.activity_main.*


class MainActivity : AppCompatActivity() {

    private val loggerServiceIntentForeground by lazy { Intent(LoggerService.ACTION_START_FOREGROUND, Uri.EMPTY, this, LoggerService::class.java) }
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        switch_mute.setOnCheckedChangeListener { _, isChecked ->
            handleSwitched(isChecked)
        }

    }

    // If Spotify is installed; show a 'enable broadcast' dialog. If not
    private fun showCompatibilityDialog() = when {
        isPackageInstalled(Spotify.PACKAGE_NAME, this.packageManager) -> showEnableBroadcastDialog()
        isPackageInstalled(Spotify.PACKAGE_NAME_LITE, this.packageManager) -> showLiteNotSupportedDialog()
        else -> showSpotifyNotInstalledDialog()
    }

    private fun showEnableBroadcastDialog() {
        dialog = AlertDialog.Builder(ContextThemeWrapper(this, R.style.DialogTheme)).apply {
            setTitle(getString(R.string.dialog_broadcast_title))
            setMessage(getString(R.string.dialog_broadcast_message))
            setPositiveButton(getString(R.string.dialog_broadcast_positive)) { _, _ ->
                prefs.applyPref(Pair(IS_FIRST_LAUNCH_KEY, false))
                // Intent.ACTION_APPLICATION_PREFERENCES added in api 24. On API < 24 it will just open Spotify.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) startActivity(Spotify.INTENT_SPOTIFY_SETTINGS)
                else {
                    val intent = packageManager.getLaunchIntentForPackage(Spotify.PACKAGE_NAME)
                    if (intent != null) startActivity(intent)
                }

            }
            setNegativeButton(getString(R.string.dialog_broadcast_negative)) { dialog, _ ->
                dialog.dismiss()
                toggleHelper()
                prefs.applyPref(Pair(IS_FIRST_LAUNCH_KEY, false))
            }
        }.create().also {
            it.show()
        }
    }

    private fun showLiteNotSupportedDialog() {
        dialog = AlertDialog.Builder(ContextThemeWrapper(this, R.style.DialogTheme)).apply {
            setOnCancelListener {
                onForceExit()
            }
            setTitle(getString(R.string.dialog_lite_title))
            setMessage(getString(R.string.dialog_lite_message))
            setNegativeButton(getString(R.string.dialog_lite_negative)) { dialog, _ ->
                onForceExit()
            }
        }.create().also {
            it.show()
        }
    }

    private fun showSpotifyNotInstalledDialog() {
        dialog = AlertDialog.Builder(this).apply {
            setTitle(getString(R.string.dialog_package_title))
            setMessage(getString(R.string.dialog_package_message))
            setNegativeButton(getString(R.string.dialog_package_negative)) { dialog, _ ->
                onForceExit()
            }
        }.create().also {
            it.show()
        }
    }

    private fun onForceExit() {
        this.finish()
    }

    override fun onPause() {
        dialog?.dismiss()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()

        val adsMuted = prefs.getInt(PREF_KEY_ADS_MUTED_COUNTER, 0)

        tv_ad_counter.text = getString(R.string.mute_info_ad_counter, adsMuted)
        if (prefs.getBoolean(IS_FIRST_LAUNCH_KEY, true)) showCompatibilityDialog()
        else toggleHelper()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        return when (item?.itemId) {
            R.id.menu_delay -> {
                showDelayDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showDelayDialog() {

        val inflatedView = layoutInflater.inflate(R.layout.dialog_delay, null)
        val edDelay = inflatedView.findViewById<EditText>(R.id.et_delay).apply {
            hint = prefs.getInt(LoggerService.MUTE_DELAY_BUFFER_KEY, LoggerService.MUTE_DELAY_BUFFER_DEFAULT).toString()
        }

        dialog = AlertDialog.Builder(this).apply {
            setTitle(getString(R.string.dialog_delay_title))
            setMessage(getString(R.string.dialog_delay_message))
            setView(inflatedView)
            setPositiveButton(getString(R.string.dialog_delay_positive)) { _, _ ->
                edDelay.text.toString().toIntOrNull()?.let {
                    prefs.applyPref(Pair(LoggerService.MUTE_DELAY_BUFFER_KEY, it))
                }
            }
            setNegativeButton(getString(R.string.dialog_delay_negative)) { dialog, _ ->
                dialog.dismiss()
            }
        }.create().also {
            it.show()
        }

    }

    private fun toggleHelper() {
        // should start enabled
        if (!switch_mute.isChecked) {
            switch_mute.isChecked = true  // service will start by toggle callback
        } else if (!LoggerService.isServiceRunning()) {
            toggleLoggerService(on=true)  // service turned off from outside activity, but toggle is true: just enable service
        }
    }

    // subject to this: https://issuetracker.google.com/issues/113122354
    private fun toggleLoggerService(on: Boolean) {
        if (on) {
            this.startService(loggerServiceIntentForeground)
            tv_status.text = getString(R.string.status_enabled)
        } else {
            this.stopService(loggerServiceIntentForeground)
            tv_status.text = getString(R.string.status_disabled)
        }
    }

    private fun handleSwitched(isChecked: Boolean) {
        toggleLoggerService(isChecked)
    }

    companion object {
        const val IS_FIRST_LAUNCH_KEY = "first_launch"
        const val SWITCH_IS_TOGGLED_KEY = "switch_toggled"
        const val PREF_KEY_ADS_MUTED_COUNTER = "ads_muted_counter"

    }
}
