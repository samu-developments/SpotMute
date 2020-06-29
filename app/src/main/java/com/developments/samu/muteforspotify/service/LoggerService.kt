package com.developments.samu.muteforspotify.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

private const val TAG = "LoggerService"

class LoggerService : Service() {


    @Volatile
    private var isMuted = false
        set(value) {
            Log.d(TAG, "-- Setting isMuted to $value")
            field = value
        }

    private var adsMutedCounter = 0

    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private val audioManager by lazy { applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private val spotifyReceiver = Spotify.spotifyReceiver(::log)
    private val handler =
        Handler(Looper.getMainLooper())  // Handler() deprecated, causes too many errors

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
    private fun createActionMute() =
        NotificationCompat.Action.Builder(
            if (isMuted) R.drawable.ic_volume_unmute else R.drawable.ic_volume_mute,
            if (isMuted) getString(R.string.notif_action_title_unmute) else getString(R.string.notif_action_title_mute),
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

    // notification action 'mute'
    private fun actionMute() {
        handler.removeCallbacksAndMessages(null)
        // mute without delay (also updates notification)
        mute()
    }

    // notification action 'unmute'. Sets a new mute timer if song is not finished
    private fun actionUnmute() {
        handler.removeCallbacksAndMessages(null)
        // unmute without delay (also updates notification)
        setUnmuteTimer(delay = 0L)

        // check if song is not finished playing, in that case set a new mute timer
        val endTime = lastSong.timeSent + (lastSong.length - lastSong.playbackPosition)
        val timeLeft = endTime - System.currentTimeMillis()
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
        createBaseNotification().apply {
            setContentTitle(getString(R.string.notif_error_detecting_ads))  // not detected any songs yet, show warning
        }.also {
            startForeground(NOTIFICATION_ID, it.build())
        }
        running = true
        updateWidgets(this)

        // Unmute to begin with
        // unmute() this updates the notification warning of not detecting any ads, comment out for now
    }

    private fun getMusicVolume() = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    /*
    Here is where most of the fun happens; the main muting/unmuting logic. Things to consider:
     1. Spotify sends broadcast when playback changes; play/pause/next track (see https://developer.spotify.com/documentation/android/guides/android-media-notifications/)
     2. Spotify does not tell you when an ad is playing..
     3. ..but we can figure out when the song is supposed to end easily by taking current time + remaining playback time.
     4. Then just mute when the song finishes and no new song is detected, and finally unmute when a new song is detected. Easy right?

     The fun stuff:
     5. Spotify often send multiple intents, with only 10-100 ms in between. They can arrive in random order and seems to overload the muting logic if it is not handled
     6. The intent arrives before the current song/ad is finished playing; unmuting needs a small delay, otherwise the last ~.5 sec of an ad is heard. In the end this delay is not very deterministic,
        it depends on how fast the intent arrived, which is device/environment specific. Too long delay mutes the start of the new song.
     7. Similarly mute a bit before the current song is supposed to finish, otherwise the user might hear an ad.
     8. Muting with a timer (handler + delayed execution), canceling, posting new tasks, seems not to be too reliable.
     9. Users have to whitelist the app otherwise the service is killed and can't do anything.

     10. The need for two handlers, one for muting and one for unmuting is because we want to delay the
     unmuting, but at the same time set a new timer for muting (for the new song).
     */

    // check if a new song is deemed in a 'reset' state; user playing a song presses 'previous',
    // and the same song is started again.
    private fun isSongReset(new: Song, old: Song): Boolean {
        return new.playing &&
                new.playbackPosition < AppUtil.ONE_SECOND_MS &&  // song just started
                new.timeSent - old.timeSent > AppUtil.ONE_SECOND_MS  // song logged 1 sec+ after lastSong
    }

    private fun log(song: Song) {
        Log.d(TAG, "log: $song")

        // Logic to find out if Spotify is spamming broadcasts
        if (song.id == lastSong.id &&  // If same song logged twice,
            song.playing == lastSong.playing && // both playing or both paused,
            !isSongReset(song, lastSong)) return  // song is not reset -> then return early

        lastSong = song  // keep track of the last logged song
        setNotificationStatus(song)  // update detected song

        when {
            song.playing -> handleNewSongPlaying(song)  // ned to set new timer, and after an ad unmute device
            else -> handleSongNotPlaying()  // need to remove timer
        }
    }

    // remove all timers.
    private fun handleSongNotPlaying() {
        Log.d(TAG, "handleSongNotPlaying:not playing")
        handler.removeCallbacksAndMessages(null)
    }

    private fun handleNewSongPlaying(song: Song) {
        handler.removeCallbacksAndMessages(null)
        if (isMuted) {  // is muted -> unmute
            Log.d(TAG, "handleNewSongPlaying:isMuted, (so unmute either with delay or immediately)")
            // If skip is on, then we know the ad was skipped and we can unmute directly
            if (prefs.getBoolean(ENABLE_SKIP_KEY, ENABLE_SKIP_DEFAULT)) {
                setUnmuteTimer(delay = 0L)
            } else {
                // We need to correct for the Spotify broadcast propagation delay to get
                // a more consistent unmute delay
                val propagationDelay = System.currentTimeMillis() - song.timeSent  // usually around 10-20 ms, not much..
                setUnmuteTimer(delay = prefs.getLong(MUTE_DELAY_BUFFER_KEY, MUTE_DELAY_BUFFER_DEFAULT) - propagationDelay)
            }
        }
        // start new mute timer
        Log.d(TAG, "handleNewSongPlaying:set mute timer")

        val remaining = (song.length - song.playbackPosition).toLong()
        setMuteTimer(remaining)
    }

    // wrapper for unmute, removes callbacks
    private fun setUnmuteTimer(delay: Long) {
        Log.d(TAG, "setUnmuteTimer:delay: ${delay}")

        // Spotify sends an intent of a new playing song before the ad is completed -> wait some hundred ms before unmuting
        handler.postDelayed({
            unmute()
        }, delay)
    }

    // set a delayed muting
    private fun setMuteTimer(delay: Long) {
        Log.d(TAG, "setMuteTimer:Setting mute timer in ${delay}")
        // remove any pending mute requests
        handler.postDelayed({
            Log.d(TAG, "setMuteTimer:Now muting")
            mute(false)
            handler.postDelayed({
                Log.d(TAG, "setMuteTimer:Now logging delayed muting counter")
                skipAd()
                logAdMuted()
                setNotificationStatus(lastSong)
            }, 1000)
        }, delay)
    }

    @Synchronized
    private fun mute(updateNotification: Boolean = true) {
        Log.d(TAG, "mute:Muted")
        isMuted = true
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        if (updateNotification) setNotificationStatus(lastSong)  // show that currently muting ad
    }

    @Synchronized
    private fun unmute() {
        Log.d(TAG, "unmute:Unmuted")
        isMuted = false
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        setNotificationStatus(lastSong)
    }

    private fun logAdMuted() {
        adsMutedCounter++
        prefs.edit(true) {
            putInt(PREF_KEY_ADS_MUTED_COUNTER, prefs.getInt(PREF_KEY_ADS_MUTED_COUNTER, 0) + 1)
        }
    }

    private fun next() {
        Log.d(TAG, "NEXT CALLED -----------")
        val actionDown = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT)
        val actionUp = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT)
        with (audioManager) {
            dispatchMediaKeyEvent(actionDown)
            dispatchMediaKeyEvent(actionUp)
        }
    }

    private fun skipAd() {
        if (prefs.getBoolean(ENABLE_SKIP_KEY, ENABLE_SKIP_DEFAULT)) {
            next()
            handler.postDelayed({ skipAd() }, 500) // Skip multiple ads. TODO: Lowered?
        }
    }

    // Show notification status based on 'isMuted'. If song is passed, show it as the last detected song
    private fun setNotificationStatus(song: Song?) {
        createBaseNotification().apply {
            setContentTitle(if (isMuted) getString(R.string.notif_content_muting) else getString(
                    R.string.notif_content_listening,
                    adsMutedCounter
                ))
            song?.let { setContentText("${getString(R.string.notif_last_detected_song)} ${it.track}") }
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
        handler.removeCallbacksAndMessages(null)
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
        const val MUTE_DELAY_BUFFER_DEFAULT = 1500L
        const val MUTE_DELAY_BUFFER_KEY = "delay"
        const val ENABLE_SKIP_DEFAULT = false
        const val ENABLE_SKIP_KEY = "skip"

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
