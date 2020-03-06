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
        val registeredTime: Long,
        var timeLeft: Long = 0L,
        var endTime: Long = 0L,
        var timeMuted: Long = 0L)
