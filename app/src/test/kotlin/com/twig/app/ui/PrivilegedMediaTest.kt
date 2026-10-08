package com.twig.app.ui

import android.app.Application
import android.content.pm.ProviderInfo
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.core.app.ApplicationProvider
import com.twig.app.CacheDirs
import com.twig.app.StreamProvider
import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.core.RandomSource
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PrivilegedMediaTest {
    private lateinit var app: Application
    private var previous: FileSystem? = null
    private val bytes = ByteArray(300_000) { (it * 17).toByte() }
    private var opens = 0
    private fun file(ext: String) = XFile("file", "/sdcard/Android/data/twig.media.test/clip.$ext", isDir = false, size = bytes.size.toLong())

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        previous = FsRegistry.unregister("file")
        AudioCache.releaseAll()
        FsRegistry.register(object : FileSystem by LocalFileSystem() {
            // Exercise the provider's materialize fallback without a device-only proxy fd.
            override fun randomAccessEfficient() = false
            override fun openInput(file: XFile) = ByteArrayInputStream(bytes)
            override fun openRandom(file: XFile): RandomSource {
                opens++
                return object : RandomSource {
                    override fun length() = bytes.size.toLong()
                    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                        if (position >= bytes.size) return -1
                        val n = minOf(length, bytes.size - position.toInt())
                        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
                        return n
                    }
                    override fun close() {}
                }
            }
        })
    }

    @After fun cleanup() {
        AudioCache.releaseAll()
        FsRegistry.unregister("file")
        previous?.let(FsRegistry::register)
    }

    private fun dataSource(source: MediaSource): DataSource =
        (ProgressiveMediaSource::class.java.getDeclaredField("dataSourceFactory")
            .apply { isAccessible = true }.get(source) as DataSource.Factory).createDataSource()

    private fun verifySeek(source: MediaSource) {
        val ds = dataSource(source)
        val uri = requireNotNull(source.mediaItem.localConfiguration).uri
        for (position in listOf(299_900L, 7L)) {
            assertEquals(100L, ds.open(DataSpec.Builder().setUri(uri).setPosition(position).setLength(100).build()))
            try {
                val buffer = ByteArray(100)
                var filled = 0
                while (filled < buffer.size) {
                    val n = ds.read(buffer, filled, buffer.size - filled)
                    assertTrue(n > 0)
                    filled += n
                }
                assertArrayEquals(bytes.copyOfRange(position.toInt(), position.toInt() + 100), buffer)
            } finally { ds.close() }
        }
    }

    @Test fun restrictedVideoReadsAndSeeksThroughFilesystem() {
        val (source, shared) = MediaSources.networkEager(file("mp4"))
        try { verifySeek(source) } finally { shared.close() }
        assertEquals(1, opens)
    }

    @Test fun restrictedAudioOpensLazilyAndSeeksThroughFilesystem() {
        val source = MediaSources.lazy(file("mp3"))
        assertEquals(0, opens)
        verifySeek(source)
        assertEquals(1, opens)
    }

    @Test fun readableAudioUsesOriginalLocalFile() {
        val local = File(app.cacheDir, "direct-audio.mp3").apply { writeBytes(bytes) }
        try {
            val source = MediaSources.lazy(XFile("file", local.path, isDir = false, size = local.length()))
            verifySeek(source)
            assertEquals(0, opens)
        } finally { local.delete() }
    }

    @Test fun externalOpenOfRestrictedLocalFileUsesFilesystemFallback() {
        val provider = StreamProvider().apply {
            attachInfo(app, ProviderInfo().apply { authority = "${app.packageName}.stream" })
        }
        val restricted = file("mp4")
        assertFalse(File(restricted.path).canRead())
        val fd = provider.openFile(StreamProvider.uriFor(app, restricted), "r")
        try {
            val copy = CacheDirs.dir(app, CacheDirs.OPEN).listFiles().orEmpty().single { it.name.endsWith("clip.mp4") }
            assertArrayEquals(bytes, copy.readBytes())
        } finally { runCatching { fd.close() } }
    }
}
