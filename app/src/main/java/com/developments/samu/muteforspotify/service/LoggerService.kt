package com.developments.samu.muteforspotify.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.SystemClock
import androidx.annotation.RequiresApi

import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.preference.PreferenceManager
import com.developments.samu.muteforspotify.MainActivity
import com.developments.samu.muteforspotify.MainActivity.Companion.PREF_KEY_ADS_MUTED_COUNTER
import com.developments.samu.muteforspotify.MuteWidget
import com.developments.samu.muteforspotify.R
import com.developments.samu.muteforspotify.data.Song
import com.developments.samu.muteforspotify.utilities.Spotify
import com.developments.samu.muteforspotify.utilities.applyPref
import java.sql.Time


class LoggerService : Service() {

    private val LOG_TAG: String = LoggerService::class.java.simpleName

    private var isPaused = false
    private var isMuted = false
    private var previousVolume = 0
    private var adsMutedCounter = 0

    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private val audioManager by lazy { applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private val spotifyReceiver = Spotify.spotifyReceiver(::log)
    private val muteHandler = Handler()
    private val unMuteHandler = Handler()

    private var lastSong = Song(
        "",
        "",
        "",
        "",
        0,
        0,
        false,
        0L,
        0L
    )

    // when used clicks the notification
    private val notifPendingIntentClick by lazy {
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    // pending intent for stop button action
    private val notifPendingIntentStop by lazy {
        PendingIntent.getService(
            this,
            0,
            Intent(this, LoggerService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_CANCEL_CURRENT
        )
    }

    // stop button action
    private val notifActionStop by lazy {
        NotificationCompat.Action.Builder(
            R.drawable.ic_clear,
            getString(R.string.notif_action_title_stop),
            notifPendingIntentStop
        )
            .build()
    }

    // pending intent for mute action
    private val notifPendingIntentMute by lazy {
        PendingIntent.getService(
            this,
            0,
            Intent(this, LoggerService::class.java).apply {
                action = ACTION_MUTE
            },
            PendingIntent.FLAG_CANCEL_CURRENT
        )
    }

    // dynamically create the notification action mute/unmute, and set the text based on if it is muted or not
    private fun createActionMute() =
        NotificationCompat.Action.Builder(
            R.drawable.ic_clear,
            if (isMuted) getString(R.string.notif_action_title_unmute) else  getString(R.string.notif_action_title_mute),
            notifPendingIntentMute
        )
            .build()

    // since the mute/umute action is dynamically created, the whole notification also needs to be dynamically created
    private fun createBaseNotification() = NotificationCompat.Builder(this, DEFAULT_CHANNEL).apply {
        setSmallIcon(R.drawable.ic_tile_volume_off)
        setContentIntent(notifPendingIntentClick)
        addAction(notifActionStop)
        addAction(createActionMute())  // dynamically add mute/unmute action
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_FOREGROUND -> startLoggerService()
            ACTION_STOP -> stopSelf()
            ACTION_MUTE -> if (getMusicVolume() == 0) actionUnmute() else actionMute()
        }
        return START_STICKY
    }

    private fun actionMute() {
        setMute()
        notifStatus(lastSong)
    }

    private fun actionUnmute() {
        setUnmute(delay = false)
        if (lastSong.endTime - System.currentTimeMillis() > 0) {
            setMuteTimer(lastSong.endTime - System.currentTimeMillis())  // set a new mute timer, if song still playing
        }
        notifStatus(lastSong)
    }

    // start backgroundReceiver. Start foreground service if specified
    private fun startLoggerService() {
        if (running) return
        registerReceiver(spotifyReceiver, Spotify.INTENT_FILTER)  // start backgroundReceiver for picking up Spotify intents
        createBaseNotification().run {
            setContentTitle(getString(R.string.notif_error_detecting_ads))  // not detected any songs yet, show warning
            startForeground(NOTIFICATION_ID, this.build())
        }
        running = true
        previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        updateWidgets(this)
    }

    private fun getMusicVolume() = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    /**
     * If a NEW, PLAYING song is detected: check if need to toggle sound, add register song.
     * If the song was previously registered but paused (and now playing): register it and set isPaused to false
     * If the song is PAUSED (song.playing == false): unregister song
     */

    private fun log(song: Song) {
        if (song.playing) {
            if (song.id == lastSong.id && !isPaused) return  // spotify spamming; sending (almost) duplicate entries
            setUnmute(delay = true)  // turn on volume with delay
            isPaused = false
            setMuteTimer(song.length.toLong() - song.playbackPosition)  // user manually muted then unmuted, post new mute timer
            notifStatus(song)  // show that currently not muting ad, recently detected song
        } else {
            muteHandler.removeCallbacksAndMessages(null)  // song is paused, remove timer
            isPaused = true
        }
        song.endTime = song.timeSent + (song.length - song.playbackPosition)
        lastSong = song  // keep track of the last logged song

    }

    /**
     * We want to restore/mute the volume, but:
     * 1. It might be muted by the user -> keep it muted
     * 2. It might be muted by the service then turned up by the user during the ad -> keep at same level (do nothing). Or not muted between song skips.
     * 3. It might be muted by this service -> restore previous volume level
     * 4. This service asks to mute the volume
     *
     */
    // wrapper for unmute, removes callbacks
    private fun setUnmute(delay: Boolean = true) {
        isMuted = false
        // remove any pending unmute requests
        unMuteHandler.removeCallbacksAndMessages(null)

        if (delay) {
            // Spotify sends an intent of a new playing song before the ad is completed -> wait some hundred ms before unmuting
            unMuteHandler.postDelayed({
                unmute()
            }, prefs.getInt(MUTE_DELAY_BUFFER_KEY, MUTE_DELAY_BUFFER_DEFAULT).toLong())
        }
        else unmute()
    }

    private fun setMuteTimer(delay: Long) {
        // remove any pending mute requests
        muteHandler.removeCallbacksAndMessages(null)
        muteHandler.postDelayed({
            mute()
            notifStatus(null)
            muteHandler.postDelayed({ logAdMuted() }, 5000)
        }, delay - 700L)
    }

    private fun setMute() {
        // remove any pending mute requests
        muteHandler.removeCallbacksAndMessages(null)
        mute()
    }

    private fun mute() {
        isMuted = true
        previousVolume = getMusicVolume()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
    }

    private fun unmute() {
        isMuted = false
        if (previousVolume == 0 || getMusicVolume() != 0) return
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, previousVolume, 0)
    }

    private fun logAdMuted() {
        adsMutedCounter++
        prefs.applyPref(Pair(
            PREF_KEY_ADS_MUTED_COUNTER,
            prefs.getInt(PREF_KEY_ADS_MUTED_COUNTER, 0) + 1))
    }

    private fun notifStatus(song: Song? = null) {
        createBaseNotification().apply {
            setContentTitle(if (isMuted) getString(R.string.notif_content_muting) else getString(R.string.notif_content_listening, adsMutedCounter))
            song?.let { setContentText("${getString(R.string.notif_last_detected_song)} ${song.track}") }
        }.also { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, it.build()) }
    }

    private fun timeHelper() = "uptime in sec: ${SystemClock.uptimeMillis() / 1000} time: ${Time(System.currentTimeMillis())}"

    private fun updateWidgets(context: Context) {
        Intent(context, MuteWidget::class.java).apply {
            action = MuteWidget.ON_UPDATE_WIDGET
        }.also { sendBroadcast(it) }
    }

    override fun onDestroy() {
        try {
            // Throws if not started
            unregisterReceiver(spotifyReceiver)
        } catch (e: Exception) {}
        running = false
        muteHandler.removeCallbacksAndMessages(null)
        unMuteHandler.removeCallbacksAndMessages(null)
        updateWidgets(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder {
        TODO("Return the communication channel to the service.")
    }

    companion object {

        private var running = false
        const val ACTION_START_FOREGROUND = "START_FOREGROUND"
        const val ACTION_STOP = "STOP_SERVICE"
        const val ACTION_MUTE = "MUTE"
        const val DEFAULT_CHANNEL = "MUTE_DEFAULT_CHANNEL"
        const val NOTIFICATION_ID = 3246
        const val MUTE_DELAY_BUFFER_DEFAULT = 500
        const val MUTE_DELAY_BUFFER_KEY = "delay"

        fun isServiceRunning() = running  // used in tileservice etc.

        // Needs only to be called once (application startup)

        @RequiresApi(Build.VERSION_CODES.O)
        fun createNotificationChannel(context: Context) =
            NotificationChannel(
                DEFAULT_CHANNEL,
                context.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                description = context.getString(R.string.notif_channel_description)
            }.also { channel ->
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).run {
                    createNotificationChannel(channel)
                }
            }
    }
}