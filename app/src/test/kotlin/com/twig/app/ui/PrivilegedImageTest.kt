package com.twig.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.twig.app.OpenFiles
import com.twig.app.Prefs
import com.twig.core.FileSystem
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrivilegedImageTest {
    private lateinit var app: Application
    private var previous: FileSystem? = null
    private lateinit var png: ByteArray
    private var reads = 0
    private lateinit var restricted: XFile

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        previous = FsRegistry.unregister("file")
        png = ByteArrayOutputStream().use { out ->
            val bitmap = Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.RED)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
        // Invisible to File APIs, but readable through the filesystem's fallback.
        restricted = XFile("file", "/sdcard/Android/data/twig.image.test/picture.png", isDir = false, size = png.size.toLong())
        FsRegistry.register(object : FileSystem by LocalFileSystem() {
            override fun openInput(file: XFile): java.io.InputStream {
                assertEquals(restricted.path, file.path)
                reads++
                // Like a shell pipe: no seek or mark/reset support.
                return object : FilterInputStream(ByteArrayInputStream(png)) {
                    override fun markSupported() = false
                    override fun reset(): Unit = throw java.io.IOException("Not seekable")
                }
            }
        })
    }

    @After fun cleanup() {
        FsRegistry.unregister("file")
        previous?.let(FsRegistry::register)
    }

    @Test fun restrictedImageOpensAndZoomsFromReadableCache() {
        assertFalse(File(restricted.path).canRead())
        val decoded = requireNotNull(decodeImage(app, restricted))
        assertEquals(2048, decoded.origW)
        assertEquals(1024, decoded.origH)
        assertEquals(Color.RED, decoded.bmp.getPixel(0, 0))
        assertEquals(1, reads)
        assertNotEquals(restricted.path, decoded.path)
        assertArrayEquals(png, File(decoded.path).readBytes())
        // Force a base downsample ratio so this exercises the region decoder.
        val base = Decoded(decoded.bmp, 4f, 0, decoded.path, 2048, 1024)
        val region = requireNotNull(decodeRegion(base, 0, RectF(0f, 0f, 128f, 64f), 4f, 512, 256))
        assertEquals(512, region.width)
        assertEquals(256, region.height)
        // Robolectric's region decoder verifies file access and dimensions, but
        // creates a blank bitmap rather than decoding pixels (even in native mode).
        region.recycle()
        decoded.bmp.recycle()
    }

    @Test fun readableLocalImageKeepsOriginalPathWithoutFallback() {
        val local = File(app.cacheDir, "direct-image.png").apply { writeBytes(png) }
        try {
            val file = XFile("file", local.absolutePath, isDir = false, size = local.length())
            assertEquals(local, OpenFiles.materialize(app, file))
            val decoded = requireNotNull(decodeImage(app, file))
            assertEquals(local.absolutePath, decoded.path)
            assertEquals(0, reads)
            decoded.bmp.recycle()
        } finally { local.delete() }
    }

    @Test fun restrictedImageThumbnailDecodesFromNonSeekableFallbackStream() {
        Prefs.setThumbsNetwork(app, false)
        val generate = Thumbs::class.java.getDeclaredMethod("genImage", android.content.Context::class.java, XFile::class.java)
            .apply { isAccessible = true }
        val bitmap = requireNotNull(generate.invoke(Thumbs, app, restricted) as Bitmap?)
        assertEquals(256, bitmap.width)
        assertEquals(128, bitmap.height)
        assertEquals(Color.RED, bitmap.getPixel(0, 0))
        assertEquals(2, reads)
        bitmap.recycle()
    }
}
