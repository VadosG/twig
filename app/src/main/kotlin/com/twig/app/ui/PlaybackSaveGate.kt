package com.twig.app.ui

/** A seek/open must be followed by ten uninterrupted seconds of actual playback. */
internal class PlaybackSaveGate {
    private var playingSince: Long? = null
    private var qualified = false

    fun reset() {
        playingSince = null
        qualified = false
    }

    fun seek(nowMs: Long, isPlaying: Boolean) {
        reset()
        onPlayingChanged(isPlaying, nowMs)
    }

    fun onPlayingChanged(isPlaying: Boolean, nowMs: Long) {
        // Latch qualification before a pause/end callback clears the running clock.
        canSave(nowMs)
        if (!isPlaying) playingSince = null
        else if (playingSince == null) playingSince = nowMs
    }

    fun canSave(nowMs: Long): Boolean {
        if (playingSince?.let { nowMs - it >= 10_000L } == true) qualified = true
        return qualified
    }
}
