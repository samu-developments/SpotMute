package com.developments.samu.muteforspotify

import android.app.ActivityManager
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.DialogFragment
import androidx.preference.PreferenceManager
import com.developments.samu.muteforspotify.service.LoggerService
import com.developments.samu.muteforspotify.utilities.AppUtil
import com.developments.samu.muteforspotify.utilities.Spotify
import com.developments.samu.muteforspotify.utilities.isPackageInstalled
import com.developments.samu.muteforspotify.utilities.supportsSkip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import dev.doubledot.doki.ui.DokiActivity
import kotlinx.android.synthetic.main.activity_main.*


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
            handleSwitched(isChecked)
        }
        card_view_status.setOnClickListener {
            switch_mute.toggle()
        }

        card_view_help.setOnClickListener {
            startActivity(Intent(this@MainActivity, DokiThemedActivity::class.java))
        }
        tv_help_dkma.text = getString(R.string.mute_info_dkma, Build.MANUFACTURER)

        // if user updates Spotify, disable skipping
        if (!supportsSkip(packageManager)) {
            PreferenceManager.getDefaultSharedPreferences(this).edit(true) {
                putBoolean(LoggerService.ENABLE_SKIP_KEY, false)
            }
        }
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
        setToggleEnabled()
        prefs.edit(true) {
            putBoolean(MainActivity.IS_FIRST_LAUNCH_KEY, false)
        }
    }

    private fun showCompatibilityDialog() = when {
        isPackageInstalled(packageManager, Spotify.PACKAGE_NAME) ->
            showDialog(BroadcastDialogFragment(), BroadcastDialogFragment.TAG)
        isPackageInstalled(packageManager, Spotify.PACKAGE_NAME_LITE) ->
            showDialog(SpotifyLiteDialogFragment(), SpotifyLiteDialogFragment.TAG)
        else -> showDialog(SpotifyNotInstalledDialogFragment(), SpotifyNotInstalledDialogFragment.TAG)
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
        intent.extras?.keySet()?.contains(LoggerService.NOTIFICATION_KEY) ?: let {
            if (prefs.getBoolean(PREF_KEY_LAUNCH_SPOTIFY_KEY, PREF_KEY_LAUNCH_SPOTIFY_DEFAULT)) {
                packageManager.getLaunchIntentForPackage(Spotify.PACKAGE_NAME)?.let {
                    startActivity(it)
                }
            }
        }
        intent.removeExtra(LoggerService.NOTIFICATION_KEY)

        val adsMuted = prefs.getInt(PREF_KEY_ADS_MUTED_COUNTER, 0)
        tv_ad_counter.text = getString(R.string.mute_info_ad_counter, adsMuted)
        if (prefs.getBoolean(IS_FIRST_LAUNCH_KEY, true)) showCompatibilityDialog()
        else setToggleEnabled()

    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        with(menu) {
            findItem(R.id.menu_skip).apply {
                isChecked = prefs.getBoolean(
                    LoggerService.ENABLE_SKIP_KEY,
                    LoggerService.ENABLE_SKIP_DEFAULT
                )
            }
            findItem(R.id.menu_dkma).apply {
                setOnMenuItemClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppUtil.DKMA_URL)))
                    true
                }
            }
            findItem(R.id.menu_launch_spotify).apply {
                isChecked = prefs.getBoolean(
                    PREF_KEY_LAUNCH_SPOTIFY_KEY,
                    PREF_KEY_LAUNCH_SPOTIFY_DEFAULT
                )
            }
            if (supportsSkip(packageManager)) {
                findItem(R.id.menu_skip).apply {
                    isVisible = true
                }
            }
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_delay_unmute -> {
                showDialog(DelayUnmuteDialogFragment(), DelayUnmuteDialogFragment.TAG)
                true
            }
            R.id.menu_delay_mute -> {
                showDialog(DelayMuteDialogFragment(), DelayMuteDialogFragment.TAG)
                true
            }
            R.id.menu_launch_spotify -> {
                item.isChecked = !item.isChecked  // pressing checkbox toggles it
                prefs.edit(true) { putBoolean(PREF_KEY_LAUNCH_SPOTIFY_KEY, item.isChecked) }
                true
            }
            R.id.menu_skip -> {
                item.isChecked = !item.isChecked  // pressing checkbox toggles it
                prefs.edit(true) { putBoolean(LoggerService.ENABLE_SKIP_KEY, item.isChecked) }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setToggleEnabled() {
        // should start enabled
        if (!switch_mute.isChecked) {
            switch_mute.isChecked = true  // service will start by toggle callback
        } else if (!LoggerService.isServiceRunning()) {
            toggleLoggerService(on = true)  // service turned off from outside activity, but toggle is true: just enable service
        }
    }

    // subject to this: https://issuetracker.google.com/issues/113122354
    private fun toggleLoggerService(on: Boolean) {
        if (on) {
            startServiceSafe()  // try to start service in a safe way
            tv_status.text = getString(R.string.status_enabled)
            card_view_status.setCardBackgroundColor(
                ContextCompat.getColor(
                    this,
                    R.color.colorOk
                )
            )
        } else {
            this.stopService(loggerServiceIntentForeground)
            tv_status.text = getString(R.string.status_disabled)
            card_view_status.setCardBackgroundColor(
                ContextCompat.getColor(
                    this,
                    R.color.colorWarning
                )
            )

        }
    }

    // workaround https://issuetracker.google.com/issues/11312235421 https://stackoverflow.com/a/55376015
    fun startServiceSafe() {
        val runningAppProcesses: List<ActivityManager.RunningAppProcessInfo> =
            (applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getRunningAppProcesses()
        val importance = runningAppProcesses[0].importance
        // higher importance has lower number (?)
        if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) this.startService(loggerServiceIntentForeground)
        else {
            try {
                Handler(Looper.getMainLooper()).postDelayed({
                    this.startService(loggerServiceIntentForeground)
                }, 500)
            } catch (_: IllegalStateException) {}
        }
    }

    private fun handleSwitched(isChecked: Boolean) {
        toggleLoggerService(isChecked)
    }

    companion object {
        const val IS_FIRST_LAUNCH_KEY = "first_launch"
        const val PREF_KEY_ADS_MUTED_COUNTER = "ads_muted_counter"
        const val PREF_KEY_LAUNCH_SPOTIFY_KEY = "launch_spotify"
        const val PREF_KEY_LAUNCH_SPOTIFY_DEFAULT = false

    }
}

class DelayUnmuteDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let {
            val inflatedView = layoutInflater.inflate(R.layout.dialog_delay, null)
            val prefs = PreferenceManager.getDefaultSharedPreferences(it)
            val edDelay = inflatedView.findViewById<TextInputEditText>(R.id.edit_text_delay).apply {
                hint = prefs.getLong(LoggerService.UNMUTE_DELAY_BUFFER_KEY, LoggerService.UNMUTE_DELAY_BUFFER_DEFAULT).toString()
            }

            return MaterialAlertDialogBuilder(it).apply {
                setTitle(getString(R.string.dialog_delay_unmute_title))
                setMessage(getString(R.string.dialog_delay_unmute_message))
                setView(inflatedView)
                setPositiveButton(getString(R.string.dialog_delay_unmute_positive)) { _, _ ->
                    edDelay.text.toString().toLongOrNull()?.let {
                        prefs.edit(true) { putLong(LoggerService.UNMUTE_DELAY_BUFFER_KEY, it) }
                    }
                }
                setNegativeButton(getString(R.string.dialog_delay_unmute_negative)) { _, _ ->
                    dismiss()
                }
            }.create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    companion object {
        const val TAG = "delay_unmute_tag"
    }
}

class DelayMuteDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let {
            val inflatedView = layoutInflater.inflate(R.layout.dialog_delay, null)
            val prefs = PreferenceManager.getDefaultSharedPreferences(it)
            val edDelay = inflatedView.findViewById<TextInputEditText>(R.id.edit_text_delay).apply {
                hint = prefs.getLong(LoggerService.MUTE_DELAY_BUFFER_KEY, LoggerService.MUTE_DELAY_BUFFER_DEFAULT).toString()
            }

            return MaterialAlertDialogBuilder(it).apply {
                setTitle(getString(R.string.dialog_delay_mute_title))
                setMessage(getString(R.string.dialog_delay_mute_message))
                setView(inflatedView)
                setPositiveButton(getString(R.string.dialog_delay_mute_positive)) { _, _ ->
                    edDelay.text.toString().toLongOrNull()?.let {
                        prefs.edit(true) { putLong(LoggerService.MUTE_DELAY_BUFFER_KEY, it) }
                    }
                }
                setNegativeButton(getString(R.string.dialog_delay_mute_negative)) { _, _ ->
                    dismiss()
                }
            }.create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    companion object {
        const val TAG = "delay_mute_tag"
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
                setCancelable(false)  // force user to take an action
            }.create()
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
                setCancelable(false)  // force user to take an action
            }.create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    companion object {
        const val TAG = "installed_tag"
    }
}

class BroadcastDialogFragment: DialogFragment() {

    internal lateinit var listener: BroadcastDialogListener

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
                setCancelable(false)  // force user to take an action
            }.create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    companion object {
        const val TAG = "broadcast_tag"
    }
}





