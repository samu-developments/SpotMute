package com.developments.samu.muteforspotify.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.developments.samu.muteforspotify.MainActivity
import com.developments.samu.muteforspotify.MainActivity.Companion.PREF_KEY_ADS_MUTED_COUNTER
import com.developments.samu.muteforspotify.MainActivity.Companion.PREF_KEY_ADS_MUTED_COUNTER_SINCE_UPDATE
import com.developments.samu.muteforspotify.MuteWidget
import com.developments.samu.muteforspotify.R
import com.developments.samu.muteforspotify.data.Song
import com.developments.samu.muteforspotify.data.isDuplicateOf
import com.developments.samu.muteforspotify.utilities.*
import kotlinx.coroutines.*


private const val TAG = "LoggerService"

class LoggerService : Service() {

    private val loggerScope = CoroutineScope(Dispatchers.Default)

    @Volatile
    private var isMuted = false
    private var previousVolume = 0

    private var adsMutedCounter = 0

    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private val audioManager by lazy { applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private val spotifyReceiver = Spotify.spotifyReceiver(::handleSongIntent)

    private var lastSong = Song()

    // when user clicks the notification
    private val notifPendingIntentClick by lazy {
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(NOTIFICATION_KEY, NOTIFICATION_ID)
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
            PendingIntent.FLAG_UPDATE_CURRENT
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

    // pending intent for mute action. TODO: decouple this from 'currently muting ad' action
    private val notifPendingIntentMute by lazy {
        PendingIntent.getService(
            this,
            0,
            Intent(this, LoggerService::class.java).apply {
                action = ACTION_MUTE
            },
            PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    // dynamically create the notification action mute/unmute, and set the text based on if it is muted or not
    private fun createActionMute(muted: Boolean) =
        NotificationCompat.Action.Builder(
            if (muted) R.drawable.ic_tile_volume_on else R.drawable.ic_tile_volume_off,
            if (muted) getString(R.string.notif_action_title_unmute) else getString(R.string.notif_action_title_mute),
            notifPendingIntentMute
        )
            .build()

    // since the mute/umute action is dynamically created, the whole notification also needs to be dynamically created
    private fun createBaseNotification(muted: Boolean) =
        NotificationCompat.Builder(this, DEFAULT_CHANNEL).apply {
            setSmallIcon(R.drawable.ic_tile_volume_off)
            setContentIntent(notifPendingIntentClick)
            addAction(notifActionStop)
            addAction(createActionMute(muted))  // dynamically add mute/unmute action
        }

    // notification action 'mute'
    private fun actionMute() {
        loggerScope.coroutineContext.cancelChildren()
        mute()
        setNotificationStatus(lastSong, true)
    }

    // notification action 'unmute'. Sets a new mute timer if song is not finished
    private fun actionUnmute() {
        loggerScope.coroutineContext.cancelChildren()
        unmute()
        setNotificationStatus(lastSong, false)

        // check if song is not finished playing, in that case set a new mute timer
        val timeLeft = lastSong.timeFinish - System.currentTimeMillis()
        if (timeLeft > 0) {
            setMuteTimer(timeLeft + getMuteDelay())  // set a new mute timer, if song still playing
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_FOREGROUND -> startLoggerService()
            ACTION_STOP -> stopSelf()
            ACTION_MUTE -> if (isMuted || (getMusicVolume() == 1 && previousVolume != getMusicVolume())) actionUnmute() else actionMute()
        }
        return START_STICKY
    }

    private fun startLoggerService() {
        if (running) return
        registerReceiver(
            spotifyReceiver,
            Spotify.INTENT_FILTER
        )  // start backgroundReceiver for picking up Spotify intents
        createBaseNotification(muted = false).apply {
            setContentTitle(getString(R.string.notif_error_detecting_ads))  // not detected any songs yet, show warning
            setContentText(getString(R.string.notif_error_broadcast, getString(R.string.settings_broadcast_status_title)))
        }.also {
            startForeground(NOTIFICATION_ID, it.build())
        }
        running = true
        previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        updateWidgets(this)
    }

    private fun getMusicVolume() = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    // keep track of if we know that device broadcast status has been enabled by the user
    private fun handleDeviceBroadcastStatusState() {
        if (!prefs.hasDbsEnabled()) {
            prefs.edit(true) {
                putBoolean(PREF_DEVICE_BROADCAST_ENABLED_KEY, true)
            }
        }
    }

    /*
    Here is where most of the fun happens; the main muting/unmuting logic. Things to consider:
     1. Spotify sends broadcast when playback changes; play/pause/next track (see https://developer.spotify.com/documentation/android/guides/android-media-notifications/)
     2. Spotify does not tell you when an ad is playing..
     3. ..but we can figure out when the song is supposed to end easily by taking current time + remaining playback time.
     4. Then just mute when the song finishes and no new song is detected, and finally unmute when a new song is detected. Easy right?

     The fun stuff:
     1. Spotify often send multiple intents, with only 10-100 ms in between. They can arrive in random order and seems to overload the muting logic if it is not handled.
     2. The intent can arrive both before and after the current song is finished playing. This buffer time is not very deterministic,
        it depends on how fast the intent arrived, which is device/environment specific.
        Unmuting:
            - Spotify can send intent before ad is finished, if muting without delay the user will hear the last part of an ad
                * Add delay to unmuting.
            - Spotify can send the intent of a new playing song a while after it started playing:
                - I've logged over 800ms one time.
                * Possible solution: consider playbackPosition when calling setUnmuteTimer
            - If the broadcast in Android is logged delayed, there is additional delay before SpotMute receives it and can unmute.
                - Seen 700ms one time, resulting in a total delay between songs of 1500ms.
                * Possible solution: consider propagation time when calling setUnmuteTimer
        Muting:
            - Muting too late -> hears ad
                - worse than muting too early
                * add a delay: timeLeft minus propagation delay
                * add a user configurable delay
            - Muting too early ->
                - song is muted before finished
                 * better to mute early (no ad heard) -> always unmute with delay

     3. Users have to whitelist the app otherwise the service is killed and can't do anything.
     */

    private fun handleSongIntent(song: Song) {
        // Logic to find out if Spotify is spamming broadcasts, return early
        if (song.isDuplicateOf(lastSong)) return

        Log.d(TAG, "log: $song")

        handleDeviceBroadcastStatusState()

        lastSong = song  // keep track of the last logged song

        when {
            song.playing -> handleNewSongPlaying(song)  // ned to set new timer, and after an ad unmute device
            else -> handleSongNotPlaying(song)  // need to remove timer
        }
    }

    // remove all timers.
    private fun handleSongNotPlaying(song: Song) {
        Log.d(TAG, "Handle song not playing")
        loggerScope.coroutineContext.cancelChildren()
        setNotificationStatus(song, isMuted)  // could be muted (user paused an ad)
    }

    private fun handleNewSongPlaying(newSong: Song) {
        Log.d(TAG, "Handle song playing")
        loggerScope.coroutineContext.cancelChildren()
        setNotificationStatus(newSong, false)

        if (isMuted) {
            setUnmuteTimer(
                wait = getUnmuteDelay() - newSong.playbackPosition - newSong.propagation()
            )
        }
        setMuteTimer(newSong.systemTimeLeft() + getMuteDelay())
    }

    private fun getMuteDelay(): Long {
        return prefs.getString(getString(R.string.settings_mute_key), PREF_MUTE_DELAY_DEFAULT.toString())?.toLongOrNull() ?: PREF_MUTE_DELAY_DEFAULT
    }

    private fun getUnmuteDelay(): Long {
        return prefs.getString(getString(R.string.settings_unmute_key), PREF_UNMUTE_DELAY_DEFAULT.toString())?.toLongOrNull() ?: PREF_UNMUTE_DELAY_DEFAULT
    }

    // Spotify sends an intent of a new playing song before the ad is completed -> wait some hundred ms before unmuting
    private fun setUnmuteTimer(wait: Long) {
        Log.d(TAG, "Unmuting in $wait ms")
        loggerScope.launch {
            delay(wait)
            unmute()
        }
    }

    private fun setMuteTimer(wait: Long) {
        Log.d(TAG, "Muting in $wait ms")
        loggerScope.launch {
            delay(wait)
            mute()
            delay(DELAY_LOG_NEW_AD)
            logAdMuted()
            setNotificationStatus(lastSong, mutingSong = true)
        }
    }

    @Synchronized
    private fun mute() {
        isMuted = true

        if (shouldPlayAdsOnLowestVolume()) {
            previousVolume = getMusicVolume()
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 1, 0)
        } else {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        }
    }

    private fun shouldPlayAdsOnLowestVolume() = prefs.getBoolean(getString(R.string.settings_use_lowest_volume_key), PREF_USE_LOWEST_VOLUME_DEFAULT)

    @Synchronized
    private fun unmute() {
        isMuted = false

        if (shouldPlayAdsOnLowestVolume()) {
            if (previousVolume == 0 || getMusicVolume() != 1) return
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, previousVolume, 0)
        } else {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        }
    }

    private fun logAdMuted() {
        adsMutedCounter++
        prefs.edit(true) {
            putInt(PREF_KEY_ADS_MUTED_COUNTER, prefs.getInt(PREF_KEY_ADS_MUTED_COUNTER, 0) + 1)
            putInt(PREF_KEY_ADS_MUTED_COUNTER_SINCE_UPDATE, prefs.getInt(PREF_KEY_ADS_MUTED_COUNTER_SINCE_UPDATE, 0) + 1)
        }
    }

    // Show notification status based on 'isMuted'. If song is passed, show it as the last detected song
    private fun setNotificationStatus(song: Song, mutingSong: Boolean) {
        createBaseNotification(mutingSong).apply {
            setContentTitle(
                if (mutingSong) getString(R.string.notif_content_muting) else getString(
                    R.string.notif_content_listening,
                    adsMutedCounter
                )
            )
            setContentText(
                getString(
                    R.string.notif_last_detected_song_time,
                    song.timeSent.toLocalDateTime().toReadableString(),
                    song.track
                )
            )
        }.also { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, it.build()) }
    }

    private fun updateWidgets(context: Context) {
        Intent(context, MuteWidget::class.java).apply {
            action = MuteWidget.ON_UPDATE_WIDGET
        }.also { sendBroadcast(it) }
    }

    override fun onDestroy() {
        try {
            // Throws if not started
            unregisterReceiver(spotifyReceiver)
        } catch (e: Exception) {
        }
        running = false
        loggerScope.cancel()
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
        const val NOTIFICATION_KEY = "spotmute_notification"
        const val PREF_UNMUTE_DELAY_DEFAULT = 800L
        const val PREF_MUTE_DELAY_DEFAULT = 100L
        const val PREF_USE_LOWEST_VOLUME_DEFAULT = false
        const val PREF_DEVICE_BROADCAST_ENABLED_KEY = "device_broadcast_enabled"
        const val PREF_DEVICE_BROADCAST_ENABLED_DEFAULT = false
        const val DELAY_LOG_NEW_AD = 5000L

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
