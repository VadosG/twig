package com.twig.app.ui

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import com.twig.app.share.ShareConfig
import com.twig.app.share.ShareScope
import com.twig.app.share.ShareStore
import com.twig.app.share.WebShare
import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.net.ServerSocket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ShareBackgroundTest {
    @Test fun `background shortcut shares current directory with saved options and a toast`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val host = Robolectric.buildActivity(TrashUiTest.Host::class.java).setup()
        val directory = File(app.cacheDir, "share-background").apply { mkdirs() }
        val port = ServerSocket(0).use { it.localPort }
        FsRegistry.register(LocalFileSystem())
        ShareStore.save(app, ShareConfig(ShareScope.AllSources, port = port, readOnly = false, deviceName = "My Twig"))
        val previous = ShadowDialog.getLatestDialog()
        try {
            ShareDialogs.startBackground(host.get(), XFile("file", directory.path, true))
            val deadline = System.nanoTime() + 5_000_000_000
            while (ShadowToast.getTextOfLatestToast() == null && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            val session = requireNotNull(WebShare.session)
            assertEquals(directory.path, (session.cfg.scope as ShareScope.Dir).path)
            assertEquals(port, session.cfg.port)
            assertFalse(session.cfg.readOnly)
            assertEquals("My Twig", session.cfg.deviceName)
            assertEquals(app.getString(R.string.share_notif_title, session.primaryUrl()), ShadowToast.getTextOfLatestToast())
            assertSame(previous, ShadowDialog.getLatestDialog())
            ShareDialogs.startBackground(host.get(), null)
            assertSame(session, WebShare.session)
        } finally {
            WebShare.stop(app)
            app.getSharedPreferences(ShareStore.FILE, 0).edit().clear().commit()
            directory.deleteRecursively()
            host.pause().stop().destroy()
        }
    }
}
