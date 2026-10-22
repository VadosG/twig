package com.twig.app.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.twig.app.Prefs
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThumbnailResolutionTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val source by lazy { File(app.cacheDir, "thumbnail-resolution.png") }

    @Before fun setup() {
        FsRegistry.register(LocalFileSystem())
        Prefs.setThumbsEmbedded(app, false)
        Thumbs.clearCache(app)
    }

    @After fun cleanup() {
        source.delete()
        Thumbs.clearCache(app)
        FsRegistry.unregister("file")
    }

    private fun bitmap(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        .apply { eraseColor(Color.BLUE) }

    @Test fun `resizing uses a 320 pixel long edge and preserves aspect without enlarging small images`() {
        val scale = Thumbs::class.java.getDeclaredMethod("scaleTo", Bitmap::class.java).apply { isAccessible = true }
        for ((w, h) in listOf(1280 to 720, 720 to 1280)) {
            val resized = scale.invoke(Thumbs, bitmap(w, h)) as Bitmap
            assertEquals(if (w > h) 320 else 180, resized.width)
            assertEquals(if (w > h) 180 else 320, resized.height)
        }
        val small = bitmap(160, 120)
        assertSame(small, scale.invoke(Thumbs, small))
    }

    @Test fun `existing 256 pixel cache stays usable until explicit thumbnail refresh`() {
        source.outputStream().use { bitmap(1280, 720).compress(Bitmap.CompressFormat.PNG, 100, it) }
        val file = XFile("file", source.path, false, size = source.length(), lastModified = source.lastModified())
        val oldSeed = "${file.name}:${file.size}:${file.lastModified}"
        val oldKey = MessageDigest.getInstance("MD5").digest(oldSeed.toByteArray()).joinToString("") { "%02x".format(it) }
        val oldCache = File(File(app.cacheDir, "thumbs").apply { mkdirs() }, oldKey)
        oldCache.outputStream().use { bitmap(256, 144).compress(Bitmap.CompressFormat.PNG, 100, it) }
        val key = Thumbs::class.java.getDeclaredMethod("keyOf", XFile::class.java)
            .apply { isAccessible = true }.invoke(Thumbs, file) as String
        assertEquals(oldKey, key)
        val load = Thumbs::class.java.getDeclaredMethod("load", Context::class.java, XFile::class.java, String::class.java)
            .apply { isAccessible = true }
        val cached = load.invoke(Thumbs, app, file, key) as Bitmap
        assertEquals(256, cached.width)
        assertEquals(144, cached.height)
        Thumbs.invalidate(app, file)
        assertFalse(oldCache.exists())
        val result = load.invoke(Thumbs, app, file, key) as Bitmap
        assertEquals(320, result.width)
        assertEquals(180, result.height)
        val copy = file.copy(scheme = "other-source", path = "/other/${file.name}")
        assertEquals(key, Thumbs::class.java.getDeclaredMethod("keyOf", XFile::class.java)
            .apply { isAccessible = true }.invoke(Thumbs, copy))
    }
}
