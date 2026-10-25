package com.twig.app.ui

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import com.twig.app.R
import com.twig.app.databinding.ActivityMusicPlayerBinding
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "port-mdpi")
class MusicVolumeGestureTest {
    private class Screen {
        val activity = Robolectric.buildActivity(MusicPlayerActivity::class.java).get()
        val binding = ActivityMusicPlayerBinding.inflate(LayoutInflater.from(ContextThemeWrapper(activity, R.style.Theme_Twig)))
        val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        init {
            ReflectionHelpers.setField(activity, "b", binding)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "setupCenterGestures")
            binding.centerArea.layout(0, 0, 400, 400)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, max / 2, 0)
        }
        fun volume() = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        fun touch(action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(100, if (action == MotionEvent.ACTION_DOWN) 100 else 150, action, x, y, 0)
            try { binding.centerArea.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }

    @Test fun `vertical cover drags increase and decrease music volume with bounds`() {
        val s = Screen()
        s.touch(MotionEvent.ACTION_DOWN, 200f, 300f)
        s.touch(MotionEvent.ACTION_MOVE, 200f, 200f)
        assertTrue(s.volume() > s.max / 2)
        s.touch(MotionEvent.ACTION_UP, 200f, -400f)
        assertEquals(s.max, s.volume())
        assertEquals(View.GONE, s.binding.lyrics.visibility)
        s.touch(MotionEvent.ACTION_DOWN, 200f, 100f)
        s.touch(MotionEvent.ACTION_UP, 200f, 900f)
        assertEquals(0, s.volume())
        assertEquals(View.GONE, s.binding.lyrics.visibility)
    }

    @Test fun `horizontal swipe remains horizontal after a diagonal movement`() {
        val s = Screen()
        s.touch(MotionEvent.ACTION_DOWN, 300f, 200f)
        s.touch(MotionEvent.ACTION_MOVE, 200f, 195f)
        s.touch(MotionEvent.ACTION_UP, 150f, 50f)
        assertEquals(s.max / 2, s.volume())
        assertEquals(View.GONE, s.binding.lyrics.visibility)
    }

    @Test fun `tap still shows lyrics and lyric drags leave volume unchanged`() {
        val s = Screen()
        s.touch(MotionEvent.ACTION_DOWN, 200f, 200f)
        s.touch(MotionEvent.ACTION_UP, 200f, 200f)
        assertEquals(View.VISIBLE, s.binding.lyrics.visibility)
        s.touch(MotionEvent.ACTION_DOWN, 200f, 300f)
        s.touch(MotionEvent.ACTION_MOVE, 200f, 30f)
        s.touch(MotionEvent.ACTION_UP, 200f, 30f)
        assertEquals(s.max / 2, s.volume())
    }

    @Test fun `cancelled cover touch never toggles lyrics`() {
        val s = Screen()
        s.touch(MotionEvent.ACTION_DOWN, 200f, 200f)
        s.touch(MotionEvent.ACTION_CANCEL, 200f, 200f)
        assertEquals(View.GONE, s.binding.lyrics.visibility)
        assertEquals(s.max / 2, s.volume())
    }
}
