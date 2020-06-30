package com.developments.samu.muteforspotify.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.*
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.developments.samu.muteforspotify.MainActivity
import com.developments.samu.muteforspotify.MainActivity.Companion.PREF_KEY_ADS_MUTED_COUNTER
import com.developments.samu.muteforspotify.MuteWidget
import com.developments.samu.muteforspotify.R
import com.developments.samu.muteforspotify.data.Song
import com.developments.samu.muteforspotify.utilities.AppUtil
import com.developments.samu.muteforspotify.utilities.Spotify
import kotlinx.coroutines.*

private const val TAG = "LoggerService"

class LoggerService : Service() {

    private val loggerScope = CoroutineScope(Dispatchers.Default)

    @Volatile
    private var isMuted = false

    private var adsMutedCounter = 0

    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private val audioManager by lazy { applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private val spotifyReceiver = Spotify.spotifyReceiver(::log)

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

    // when user clicks the notification
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
    private fun createActionMute(muted: Boolean) =
        NotificationCompat.Action.Builder(
            if (muted) R.drawable.ic_volume_unmute else R.drawable.ic_volume_mute,
            if (muted) getString(R.string.notif_action_title_unmute) else getString(R.string.notif_action_title_mute),
            notifPendingIntentMute
        )
            .build()

    // since the mute/umute action is dynamically created, the whole notification also needs to be dynamically created
    private fun createBaseNotification(muted: Boolean) = NotificationCompat.Builder(this, DEFAULT_CHANNEL).apply {
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
            setMuteTimer(timeLeft)  // set a new mute timer, if song still playing
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_FOREGROUND -> startLoggerService()
            ACTION_STOP -> stopSelf()
            ACTION_MUTE -> if (getMusicVolume() == 0) actionUnmute() else actionMute()
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
            setContentText(getString(R.string.notif_error_broadcast))
        }.also {
            startForeground(NOTIFICATION_ID, it.build())
        }
        running = true
        updateWidgets(this)
    }

    private fun getMusicVolume() = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    /*
    Here is where most of the fun happens; the main muting/unmuting logic. Things to consider:
     1. Spotify sends broadcast when playback changes; play/pause/next track (see https://developer.spotify.com/documentation/android/guides/android-media-notifications/)
     2. Spotify does not tell you when an ad is playing..
     3. ..but we can figure out when the song is supposed to end easily by taking current time + remaining playback time.
     4. Then just mute when the song finishes and no new song is detected, and finally unmute when a new song is detected. Easy right?

     The fun stuff:
     5. Spotify often send multiple intents, with only 10-100 ms in between. They can arrive in random order and seems to overload the muting logic if it is not handled.
     6. The intent can arrive both before and after the current song is finished playing. This buffer time is not very deterministic,
        it depends on how fast the intent arrived, which is device/environment specific.
        Unmuting:
            - Spotify can send intent before ad is finished, if muting without delay the user will hear the last part of an ad
                * Add delay to unmuting.
            - Spotify can send the intent of a new playing song a while after it started playing:
                * I've logged over 800ms one time.
                * Possible solution: consider playbackPosition when calling setUnmuteTimer
            - If the broadcast in Android is logged delayed, there is additional delay before SpotMute receives it and can unmute.
                * Seen 700ms one time, resulting in a total delay between songs of 1500ms.
                * Possible solution: consider propagation time when calling setUnmuteTimer
        Muting:
            - Muting too late -> hears ad, Muting too early -> last part of song skipped
                * worse than muting too early
                * add a delay: timeLeft minus propagation delay
                * add a user configurable delay

     9. Users have to whitelist the app otherwise the service is killed and can't do anything.

     */

    // check if a new song is deemed in a 'reset' state; user playing a song presses 'previous',
    // and the same song is started again.
    private fun isSongReset(new: Song, old: Song): Boolean {
        return new.playing &&
                new.playbackPosition < AppUtil.ONE_SECOND_MS &&  // song just started
                new.timeSent - old.timeSent > AppUtil.ONE_SECOND_MS  // song logged 1 sec+ after lastSong
    }

    private fun log(song: Song) {
        // Logic to find out if Spotify is spamming broadcasts
        if (song.id == lastSong.id &&  // If same song logged twice,
            song.playing == lastSong.playing && // both playing or both paused,
            !isSongReset(song, lastSong)) return  // song is not reset -> then return early

        Log.d(TAG, "log: $song")
        lastSong = song  // keep track of the last logged song

        when {
            song.playing -> handleNewSongPlaying(song)  // ned to set new timer, and after an ad unmute device
            else -> handleSongNotPlaying(song)  // need to remove timer
        }
    }

    // remove all timers.
    private fun handleSongNotPlaying(song: Song) {
        loggerScope.coroutineContext.cancelChildren()
        setNotificationStatus(song, isMuted)  // could be muted (user paused an ad)
    }

    private fun handleNewSongPlaying(song: Song) {
        loggerScope.coroutineContext.cancelChildren()
        setNotificationStatus(song, false)
        if (isMuted) {  // is muted -> unmute
            // If skip is on, then we know the ad was skipped and we can unmute directly
            if (prefs.getBoolean(ENABLE_SKIP_KEY, ENABLE_SKIP_DEFAULT)) setUnmuteTimer(wait = 0L)
            else {
                // Test with subtracting playback position and propagation delay
                setUnmuteTimer(wait = prefs.getLong(UNMUTE_DELAY_BUFFER_KEY, UNMUTE_DELAY_BUFFER_DEFAULT) - song.playbackPosition - song.propagation())
            }
        }
        setMuteTimer(song.timeRemaining - song.propagation())
    }

    private fun setUnmuteTimer(wait: Long) {
        Log.d(TAG, "setUnmuteTimer: $wait")
        // Spotify sends an intent of a new playing song before the ad is completed -> wait some hundred ms before unmuting
        loggerScope.launch {
            delay(wait)
            unmute()
        }
    }

    var lastSongMuteTime = 0L
    var prop = 0L
    private fun setMuteTimer(wait: Long) {
        val diff = System.currentTimeMillis() - lastSongMuteTime

        prop = lastSong.propagation()
        Log.d(TAG, "setMuteTimer: ${diff} $prop ${diff - prop} (diff | prop | diff-prop)")
        // next muting time
        lastSongMuteTime = System.currentTimeMillis() + wait + prefs.getLong(MUTE_DELAY_BUFFER_KEY, MUTE_DELAY_BUFFER_DEFAULT)

        loggerScope.launch {
            delay(wait + prefs.getLong(MUTE_DELAY_BUFFER_KEY, MUTE_DELAY_BUFFER_DEFAULT))
            Log.d(TAG, "setMuteTimer: -Now muting-")
            mute()

            delay(DELAY_LOG_NEW_AD)
            Log.d(TAG, "setMuteTimer:LOGGING AD MUTED ---------")
            logAdMuted()
            setNotificationStatus(lastSong, muted = true)
            if (prefs.getBoolean(ENABLE_SKIP_KEY, ENABLE_SKIP_DEFAULT)) skipAd()
        }
    }

    @Synchronized
    private fun mute() {
        isMuted = true
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
    }

    @Synchronized
    private fun unmute() {
        isMuted = false
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
    }

    private fun logAdMuted() {
        adsMutedCounter++
        prefs.edit(true) {
            putInt(PREF_KEY_ADS_MUTED_COUNTER, prefs.getInt(PREF_KEY_ADS_MUTED_COUNTER, 0) + 1)
        }
    }

    private fun next() {
        val actionDown = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT)
        val actionUp = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT)
        with (audioManager) {
            dispatchMediaKeyEvent(actionDown)
            dispatchMediaKeyEvent(actionUp)
        }
    }

    private fun skipAd() {
        next()
        loggerScope.launch {
            delay(SKIP_AD_DELAY)
            skipAd()
        }
    }

    // Show notification status based on 'isMuted'. If song is passed, show it as the last detected song
    private fun setNotificationStatus(song: Song, muted: Boolean) {
        createBaseNotification(muted).apply {
            setContentTitle(if (muted) getString(R.string.notif_content_muting) else getString(
                R.string.notif_content_listening,
                adsMutedCounter
            ))
            setContentText("${getString(R.string.notif_last_detected_song)} ${song.track}")
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
        const val UNMUTE_DELAY_BUFFER_DEFAULT = 1500L
        const val UNMUTE_DELAY_BUFFER_KEY = "delay"
        const val MUTE_DELAY_BUFFER_DEFAULT = 100L
        const val MUTE_DELAY_BUFFER_KEY = "mute_delay"
        const val ENABLE_SKIP_DEFAULT = false
        const val ENABLE_SKIP_KEY = "skip"
        const val DELAY_LOG_NEW_AD = 2000L
        const val SKIP_AD_DELAY = 100L

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
