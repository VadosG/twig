package com.twig.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackHistoryTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val file = XFile("file", "/movies/1.mp4", false, size = 123456L)
    private val conn = SavedConnection("webdav", "https://playback-history.invalid")

    @Before fun setup() {
        PlaybackStore.clear(app)
        Prefs.setResumePlayback(app, true)
    }

    @After fun cleanup() {
        PlaybackStore.clear(app)
        ConnectionStore.remove(app, conn)
        Connections.drop(Connections.schemeOf(conn))
        Prefs.setResumePlayback(app, true)
    }

    @Test fun `old progress resumes but is not listed until played again`() {
        val old = JSONObject().apply {
            put("key", PlaybackStore.keyOf(file))
            put("name", file.name)
            put("pos", 30000L)
            put("dur", 180000L)
            put("at", 1L)
        }
        app.getSharedPreferences("twig_playback", 0).edit()
            .putString("list", JSONArray().put(old).toString()).commit()
        assertEquals(30000L, PlaybackStore.positionFor(app, file))
        assertTrue(PlaybackStore.recent(app).isEmpty())
        PlaybackStore.save(app, file, 40000L, 180000L)
        assertEquals(file.path, PlaybackStore.recent(app).single().path)
    }

    @Test fun `matching video across sources shares progress and updates reopen location`() {
        ConnectionStore.save(app, conn)
        val scheme = Connections.schemeOf(conn)
        // Reuse a fake registered filesystem so this test never makes a network call.
        FsRegistry.register(object : FileSystem by LocalFileSystem() {
            override val scheme = scheme
        })
        Connections.ensure(app, conn)
        PlaybackStore.save(app, file, 30000L, 180000L)
        val remote = file.copy(scheme = scheme, path = "/videos/1.mp4")
        assertEquals(30000L, PlaybackStore.positionFor(app, remote))
        PlaybackStore.save(app, remote, 60000L, 180000L)
        val mark = PlaybackStore.recent(app).single()
        assertEquals(remote.path, mark.path)
        assertEquals(conn.label(), mark.connLabel)
        assertEquals(mark, PlaybackMark.fromJson(mark.toJson()))
        assertEquals(60000L, PlaybackStore.positionFor(app, file))
        assertEquals(0L, PlaybackStore.positionFor(app, file.copy(size = file.size + 1)))
        // Offline or deleted connections must not silently remove the saved entry.
        Connections.drop(scheme)
        ConnectionStore.remove(app, conn)
        assertEquals(mark, PlaybackStore.recent(app).single())
        PlaybackStore.save(app, file, 70000L, 180000L)
        assertEquals("", PlaybackStore.recent(app).single().connLabel)
        assertEquals(file.path, PlaybackStore.recent(app).single().path)
    }

    @Test fun `removing and clearing history preserve progress and another play unhides it`() {
        val other = file.copy(path = "/movies/2.mp4")
        PlaybackStore.save(app, file, 30000L, 180000L)
        PlaybackStore.save(app, other, 40000L, 180000L)
        PlaybackStore.hide(app, PlaybackStore.keyOf(file))
        assertEquals(other.name, PlaybackStore.recent(app).single().name)
        assertEquals(30000L, PlaybackStore.positionFor(app, file))
        PlaybackStore.hideRecent(app)
        assertTrue(PlaybackStore.recent(app).isEmpty())
        assertEquals(40000L, PlaybackStore.positionFor(app, other))
        PlaybackStore.save(app, file, 50000L, 180000L)
        assertEquals(file.name, PlaybackStore.recent(app).single().name)
    }

    @Test fun `finished barely started and short videos leave no progress or history`() {
        for ((pos, duration) in listOf(180000L to 180000L, 170000L to 180000L,
            15000L to 80000L, 14000L to 180000L, 3540000L to 3600000L)) {
            PlaybackStore.save(app, file, 30000L, 180000L)
            PlaybackStore.save(app, file, pos, duration)
            assertTrue("$pos / $duration", PlaybackStore.all(app).isEmpty())
            assertTrue(PlaybackStore.recent(app).isEmpty())
        }
    }

    @Test fun `session-only sources retain progress without a reopenable entry`() {
        val temporary = file.copy(scheme = "archive-session")
        PlaybackStore.save(app, temporary, 30000L, 180000L)
        assertEquals(30000L, PlaybackStore.positionFor(app, temporary))
        assertTrue(PlaybackStore.recent(app).isEmpty())
    }

    @Test fun `disabling resume hides entries without deleting saved progress`() {
        PlaybackStore.save(app, file, 30000L, 180000L)
        Prefs.setResumePlayback(app, false)
        assertTrue(PlaybackStore.recent(app).isEmpty())
        assertEquals(0L, PlaybackStore.positionFor(app, file))
        Prefs.setResumePlayback(app, true)
        assertEquals(file.path, PlaybackStore.recent(app).single().path)
        assertEquals(30000L, PlaybackStore.positionFor(app, file))
    }
}
