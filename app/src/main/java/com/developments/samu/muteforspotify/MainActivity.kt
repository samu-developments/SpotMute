package com.developments.samu.muteforspotify

import android.app.ActivityManager
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.developments.samu.muteforspotify.service.LoggerService
import com.developments.samu.muteforspotify.utilities.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


private const val TAG = "MainActivity"


class MainActivity : AppCompatActivity(), BroadcastDialogFragment.BroadcastDialogListener {
    private val loggerServiceIntentForeground by lazy {
        Intent(
            LoggerService.ACTION_START_FOREGROUND,
            Uri.EMPTY,
            this,
            LoggerService::class.java
        )
    }
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        switch_mute.setOnCheckedChangeListener { _, isChecked ->
            handleToggleClicked(on = isChecked)
        }
        card_view_status.setOnClickListener {
            switch_mute.toggle()
        }

        card_view_counter.setOnClickListener {
            Toast.makeText(this, getString(R.string.toast_counter), Toast.LENGTH_LONG).show()
        }

        card_view_help.setOnClickListener {
            startActivity(Intent(this@MainActivity, DokiThemedActivity::class.java))
        }
        tv_help_dkma.text = getString(R.string.mute_info_dkma, Build.MANUFACTURER)
    }

    override fun onBroadcastDialogPositiveClick(dialog: DialogFragment) {
        // Intent.ACTION_APPLICATION_PREFERENCES added in api 24. On API < 24 it will just open Spotify.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) startActivity(Spotify.INTENT_SPOTIFY_SETTINGS)
        else {
            packageManager.getLaunchIntentForPackage(Spotify.PACKAGE_NAME)?.let {
                startActivity(it)
            }
        }
    }

    override fun onBroadcastDialogNegativeClick(dialog: DialogFragment) {
        switch_mute.isChecked = true
    }

    private fun showCompatibilityDialog() = when {
        isPackageInstalled(packageManager, Spotify.PACKAGE_NAME) ->
            showDialog(BroadcastDialogFragment(), BroadcastDialogFragment.TAG)
        isPackageInstalled(packageManager, Spotify.PACKAGE_NAME_LITE) ->
            showDialog(SpotifyLiteDialogFragment(), SpotifyLiteDialogFragment.TAG)
        isPackageInstalled(packageManager, Spotify.PACKAGE_NAME_ATV) ->
            showDialog(SpotifyLiteDialogFragment(), SpotifyLiteDialogFragment.TAG)
        else -> showDialog(
            SpotifyNotInstalledDialogFragment(),
            SpotifyNotInstalledDialogFragment.TAG
        )
    }

    private fun showDialog(dialog: DialogFragment, tag: String) {
        if (supportFragmentManager.findFragmentByTag(tag) != null) return
        dialog.show(supportFragmentManager, tag)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        intent.extras?.keySet()?.contains(LoggerService.NOTIFICATION_KEY) ?: kotlin.run {
            if (prefs.getBoolean(getString(R.string.launch_spotify_key), PREF_KEY_LAUNCH_SPOTIFY_DEFAULT)) {
                packageManager.getLaunchIntentForPackage(Spotify.PACKAGE_NAME)?.let {
                    startActivity(it)
                }
            }
        }
        intent.removeExtra(LoggerService.NOTIFICATION_KEY)

        val adsMuted = prefs.getInt(PREF_KEY_ADS_MUTED_COUNTER, 0)
        tv_ad_counter.text = getString(R.string.mute_info_ad_counter, adsMuted)

        if (!prefs.hasDbsEnabled() || prefs.getBoolean(IS_FIRST_LAUNCH_KEY, false)) showCompatibilityDialog() // first_launch for compatibility
        else startServiceAndSetToggle()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        with(menu) {
            findItem(R.id.menu_dkma).apply {
                setOnMenuItemClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppUtil.DKMA_URL)))
                    true
                }
            }
            findItem(R.id.menu_rate).apply {
                setOnMenuItemClickListener {
                    Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse(AppUtil.SPOTMUTE_PLAY_STORE_URL)
                        setPackage("com.android.vending")
                    }.let { startActivity(it) }
                    true
                }
            }
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_settings -> {
                val myIntent = Intent(this, SettingsActivity::class.java)
                startActivity(myIntent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun handleToggleClicked(on: Boolean) {
        if (on) {
            lifecycleScope.launch {
                startServiceAndSetToggle()
            }
        } else {
            this.stopService(loggerServiceIntentForeground)
            updateUiFromToggleState(toggleOn = false)
        }
    }

    // subject to this: https://issuetracker.google.com/issues/113122354
    private fun updateUiFromToggleState(toggleOn: Boolean) {
        switch_mute.isChecked = toggleOn

        tv_status.text = getString(
            if (toggleOn) R.string.status_enabled
            else R.string.status_disabled
        )

        card_view_status.setCardBackgroundColor(
            ContextCompat.getColor(
                this,
                if (toggleOn) R.color.colorOk
                else R.color.colorWarning
            )
        )
    }

    private fun startServiceAndSetToggle() {
        lifecycleScope.launch {
            val enabledSuccessfully = startServiceSafe()
            if (!enabledSuccessfully) {
                Toast.makeText(this@MainActivity, getString(R.string.toast_service_could_not_start_error), Toast.LENGTH_LONG).show()
            }
            updateUiFromToggleState(toggleOn = enabledSuccessfully)
        }
    }

    // workaround https://issuetracker.google.com/issues/11312235421 https://stackoverflow.com/a/55376015
    private suspend fun startServiceSafe(): Boolean {
        val runningAppProcesses: List<ActivityManager.RunningAppProcessInfo> =
            (applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).runningAppProcesses
        // higher importance has lower number (?)
        return if (runningAppProcesses[0].importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
            this.startService(loggerServiceIntentForeground)
            true
        } else {
            try {
                delay(1000)  // hopefully wait for application to run in foreground
                applicationContext.startService(loggerServiceIntentForeground)
                true
            } catch (_: IllegalStateException) {
                false
            }
        }
    }

    companion object {
        const val IS_FIRST_LAUNCH_KEY = "first_launch"
        const val PREF_KEY_ADS_MUTED_COUNTER = "ads_muted_counter"
        const val PREF_KEY_LAUNCH_SPOTIFY_DEFAULT = false

    }
}

class SpotifyLiteDialogFragment: DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let {
            return MaterialAlertDialogBuilder(it).apply {
                setTitle(getString(R.string.dialog_lite_title))
                setMessage(getString(R.string.dialog_lite_message))
                setNegativeButton(getString(R.string.dialog_lite_negative)) { _, _ ->
                    it.finish()
                }
            }.create().also { dialog ->
                dialog.setCanceledOnTouchOutside(false)
            }
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    companion object {
        const val TAG = "lite_tag"
    }
}

class SpotifyNotInstalledDialogFragment: DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let {
            return MaterialAlertDialogBuilder(it).apply {
                setTitle(getString(R.string.dialog_package_title))
                setMessage(getString(R.string.dialog_package_message))
                setNegativeButton(getString(R.string.dialog_package_negative)) { _, _ ->
                    it.finish()
                }
            }.create().also { dialog ->
                dialog.setCanceledOnTouchOutside(false)
            }
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    companion object {
        const val TAG = "installed_tag"
    }
}

class BroadcastDialogFragment: DialogFragment() {

    private lateinit var listener: BroadcastDialogListener

    interface BroadcastDialogListener {
        fun onBroadcastDialogPositiveClick(dialog: DialogFragment)
        fun onBroadcastDialogNegativeClick(dialog: DialogFragment)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = context as BroadcastDialogListener  // register listener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let {
            return MaterialAlertDialogBuilder(it).apply {
                setTitle(getString(R.string.dialog_broadcast_title))
                setMessage(getString(R.string.dialog_broadcast_message))
                setNegativeButton(getString(R.string.dialog_broadcast_negative)) { _, _ ->
                    listener.onBroadcastDialogNegativeClick(this@BroadcastDialogFragment)
                }
                setPositiveButton(getString(R.string.dialog_broadcast_positive)) { dialog, _ ->
                    listener.onBroadcastDialogPositiveClick(this@BroadcastDialogFragment)
                }
            }.create().also { dialog ->
                dialog.setCanceledOnTouchOutside(false)
            }
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    companion object {
        const val TAG = "broadcast_tag"
    }
}





