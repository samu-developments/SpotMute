# SpotMute
Mute Spotify ads

https://play.google.com/store/apps/details?id=com.developments.samu.muteforspotify

### Documentation

SpotMute is a *permission free* app that can mute Spotify ads. It does this by
reading Spotify's media notifications, and naively figuring out when an
ad is supposed to play.

Spotify documentation: https://developer.spotify.com/documentation/android/guides/android-media-notifications/

All muting logic is done in [LoggerService] (app/src/main/java/com/developments/samu/muteforspotify/service/LoggerService.kt)


Spotify media intent:

| id               | String  | A Spotify URI for the track         |
| ---------------- | ------- | ----------------------------------- |
| artist           | String  | The track artist                    |
| album            | String  | The album name                      |
| track            | String  | The track name                      |
| length           | Integer | Length of the track, in seconds     |
| playing          | Boolean | True if playing, false if paused    |
| playbackPosition | Integer | The current playback position in ms |
| timeSent         | Long    | Time broadcast posted               |


Basic concept:

* Have a broadcast receiver listen and read intents
* Find playback time left: length - playbackPosition
* Mute volume at that time
* If a new song starts playing (and new intent received); unmute
* If no new song is detected; an ad is playing. Unmute when next song is detected



Note that media notifications need to be enabled manually in the Spotify app.