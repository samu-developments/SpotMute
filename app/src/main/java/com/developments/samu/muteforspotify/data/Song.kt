package com.developments.samu.muteforspotify.data


data class Song(
        val id: String,
        val artist: String,
        val album: String,
        val track: String,
        val length: Int,
        val playbackPosition: Int,
        val playing: Boolean,
        val timeSent: Long,
        val registeredTime: Long) {

        val timeRemaining = (length - playbackPosition).toLong()
        val timeFinish = timeSent + timeRemaining
        fun propagation() = System.currentTimeMillis() - timeSent
}

