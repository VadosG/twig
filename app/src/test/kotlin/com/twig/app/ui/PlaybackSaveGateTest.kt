package com.twig.app.ui

import org.junit.Assert.*
import org.junit.Test

class PlaybackSaveGateTest {
    @Test fun `opening and waiting without playback never qualifies`() {
        val gate = PlaybackSaveGate()
        assertFalse(gate.canSave(100000L))
        gate.onPlayingChanged(false, 100000L)
        assertFalse(gate.canSave(200000L))
    }

    @Test fun `ten continuous seconds of actual playback qualify`() {
        val gate = PlaybackSaveGate()
        gate.onPlayingChanged(true, 1000L)
        assertFalse(gate.canSave(10999L))
        assertTrue(gate.canSave(11000L))
    }

    @Test fun `pause or buffering before ten seconds restarts the interval`() {
        val gate = PlaybackSaveGate()
        gate.onPlayingChanged(true, 0L)
        gate.onPlayingChanged(false, 4000L)
        assertFalse(gate.canSave(100000L))
        gate.onPlayingChanged(true, 100000L)
        assertFalse(gate.canSave(109999L))
        assertTrue(gate.canSave(110000L))
    }

    @Test fun `closing after ten seconds qualifies even without a periodic save`() {
        val gate = PlaybackSaveGate()
        gate.onPlayingChanged(true, 0L)
        gate.onPlayingChanged(false, 11000L)
        assertTrue(gate.canSave(11000L))
        assertTrue(gate.canSave(100000L))
    }

    @Test fun `every seek requires another uninterrupted ten seconds`() {
        val gate = PlaybackSaveGate()
        gate.onPlayingChanged(true, 0L)
        assertTrue(gate.canSave(10000L))
        gate.seek(10000L, isPlaying = true)
        assertFalse(gate.canSave(14000L))
        gate.seek(14000L, isPlaying = true)
        assertFalse(gate.canSave(18000L))
        assertFalse(gate.canSave(23999L))
        assertTrue(gate.canSave(24000L))
    }

    @Test fun `seeking while paused does not count time before playback resumes`() {
        val gate = PlaybackSaveGate()
        gate.onPlayingChanged(true, 0L)
        gate.onPlayingChanged(false, 11000L)
        gate.seek(12000L, isPlaying = false)
        assertFalse(gate.canSave(20000L))
        gate.onPlayingChanged(true, 20000L)
        assertFalse(gate.canSave(29999L))
        assertTrue(gate.canSave(30000L))
    }

    @Test fun `switching videos resets previous qualification`() {
        val gate = PlaybackSaveGate()
        gate.onPlayingChanged(true, 0L)
        assertTrue(gate.canSave(10000L))
        gate.reset()
        assertFalse(gate.canSave(100000L))
        gate.onPlayingChanged(true, 100000L)
        assertFalse(gate.canSave(109999L))
        assertTrue(gate.canSave(110000L))
    }

    @Test fun `repeated playing notifications do not restart continuous playback`() {
        val gate = PlaybackSaveGate()
        gate.onPlayingChanged(true, 0L)
        gate.onPlayingChanged(true, 3000L)
        gate.onPlayingChanged(true, 4500L)
        assertFalse(gate.canSave(9999L))
        assertTrue(gate.canSave(10000L))
    }
}
