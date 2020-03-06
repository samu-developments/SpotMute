package com.developments.samu.muteforspotify.utilities

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.developments.samu.muteforspotify.data.Song


class Spotify {

    companion object {

        val LOG_TAG: String = Spotify::class.java.simpleName

        const val PACKAGE_NAME = "com.spotify.music"
        const val PACKAGE_NAME_LITE = "com.spotify.lite"
        const val PLAYBACK_STATE_CHANGED = "com.spotify.music.playbackstatechanged"
        const val METADATA_CHANGED = "com.spotify.music.metadatachanged"
        val INTENT_FILTER = IntentFilter().apply {
            addAction(PLAYBACK_STATE_CHANGED)
            //addAction(METADATA_CHANGED)
        }

        val INTENT_SPOTIFY_SETTINGS = Intent("android.intent.action.APPLICATION_PREFERENCES").apply {
            this.`package` = PACKAGE_NAME
        }

        // returns a broadcastreceiver with implemented callback
        fun spotifyReceiver(callback: ((Song) -> Unit)): BroadcastReceiver =
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (intent == null) return
                        createSongFromIntent(intent)?.let { callback(it) }
                    }
                }

        fun createSongFromIntent(intent: Intent): Song? {
           /* with (intent.extras) {
                this?.keySet()?.forEach {
                    Log.d(LOG_TAG, "key: $it, ${this.get(it)}")
                }
            }*/
            with (intent) {
                try {  // Do not trust Spotify. Ever.
                    val song = Song(
                            getStringExtra("id"),
                            getStringExtra("artist"),
                            getStringExtra("album"),
                            getStringExtra("track"),
                            getIntExtra("length", -1),
                            getIntExtra("playbackPosition", -1),
                            intent.getBooleanExtra("playing", false),
                            getLongExtra("timeSent", -1L),
                            System.currentTimeMillis()
                    )
                    //Log.d("intent", "action: ${intent.action}, song: ${song.track}")

                    return if (song.id.isEmpty()) null else song
                } catch (e: IllegalStateException) { e.printStackTrace() }
            }
            return null
        }
    }

}

