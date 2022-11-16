package com.developments.samu.muteforspotify.data

import org.threeten.bp.Duration
import kotlin.math.absoluteValue

data class Song(
    val id: String = "",
    val artist: String = "",
    val album: String = "",
    val track: String = "",
    val length: Int = 0,
    val playbackPosition: Int = 0,
    val playing: Boolean = false,
    val timeSent: Long = 0L,
    val registeredTime: Long = 0L
) {
    fun propagation() = System.currentTimeMillis() - timeSent
    fun systemTimeLeft() = timeFinish - System.currentTimeMillis()
    val timeFinish = timeSent + (length - playbackPosition)
}

fun Song.isDuplicateOf(old: Song): Boolean {
    return this.id == old.id && // "Same song" if: Same id
        this.playing == old.playing && // both playing or both paused,
        (this.timeSent - old.timeSent).absoluteValue < Duration.ofSeconds(5).toMillis() // the timeSent delta is not too long
}
