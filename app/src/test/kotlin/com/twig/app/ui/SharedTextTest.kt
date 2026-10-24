package com.twig.app.ui

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.app.ShareSourceFileSystem
import com.twig.core.CopyEngine
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SharedTextTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private fun send(text: CharSequence) = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, text)

    @Test fun `plain and styled text retain whitespace and Unicode without interpreting URLs`() {
        val text = "  中文 🙂\r\nhttps://example.com/?q=a&b=c\n"
        assertEquals(text, ShareTargetActivity.sharedText(send(SpannableString(text))))
        assertEquals(" \n", ShareTargetActivity.sharedText(send(" \n")))
        assertNull(ShareTargetActivity.sharedText(send("")))
        assertEquals(text, ShareTargetActivity.sharedText(Intent(Intent.ACTION_SEND)
            .apply { clipData = ClipData.newPlainText("text", text) }))
    }

    @Test fun `attachments and rejected private URIs never fall back to saving a caption`() {
        val intent = send("caption").putExtra(Intent.EXTRA_STREAM, Uri.fromFile(File(app.filesDir, "secret")))
        assertNull(ShareTargetActivity.sharedText(intent))
        assertNull(ShareTargetActivity.sharedText(send("caption").setAction(Intent.ACTION_SEND_MULTIPLE)))
        assertNull(ShareTargetActivity.sharedText(send("caption").apply {
            clipData = ClipData.newRawUri("file", Uri.parse("content://other/file"))
        }))
        assertFalse(IncomingUri.allowed(app, Uri.parse("data:text/plain;base64,c2VjcmV0")))
    }

    @Test fun `text copies as exact UTF8 bytes and existing files keep the normal conflict policy`() {
        val fs = ShareSourceFileSystem(app)
        FsRegistry.register(fs)
        FsRegistry.register(LocalFileSystem())
        val dir = File(app.cacheDir, "shared-text-test").apply { mkdirs() }
        try {
            val text = "中文 🙂\r\n  line two\n"
            val source = fs.wrapText(text, "notes.txt")
            assertEquals(text.toByteArray(Charsets.UTF_8).size.toLong(), source.size)
            assertEquals("notes.txt", source.name)
            val target = File(dir, source.name).apply { writeText("keep me") }
            var conflicts = 0
            CopyEngine.transfer(listOf(source), XFile("file", dir.path, true), move = false, resolver = { _, _ ->
                conflicts++
                CopyEngine.Decision.RENAME
            })
            assertEquals(1, conflicts)
            assertEquals("keep me", target.readText())
            val saved = dir.listFiles()!!.single { it != target }
            assertArrayEquals(text.toByteArray(Charsets.UTF_8), saved.readBytes())
            // A fresh source instance can read the same item: no Activity or temporary-file lifetime.
            assertEquals(text, ShareSourceFileSystem(app).openInput(source).bufferedReader().use { it.readText() })
        } finally {
            dir.deleteRecursively()
            FsRegistry.unregister("share")
            FsRegistry.unregister("file")
        }
    }

    @Test fun `filename survives Activity recreation and text shares expose Save`() {
        shadowOf(app).grantPermissions(android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        Prefs.setRememberLocation(app, false)
        val controller = Robolectric.buildActivity(ShareTargetActivity::class.java, send("some text"))
        try {
            controller.setup()
            val activity = controller.get()
            val field = activity.findViewById<EditText>(R.id.text_filename)
            assertEquals(android.view.View.VISIBLE, field.visibility)
            assertEquals(activity.getString(R.string.viewer_save),
                activity.findViewById<android.widget.TextView>(R.id.btn_confirm).text)
            field.setText("my notes.txt")
            val state = Bundle()
            controller.saveInstanceState(state).pause().stop().destroy()
            val restored = Robolectric.buildActivity(ShareTargetActivity::class.java, send("some text"))
            try {
                restored.create(state).start().restoreInstanceState(state).resume().visible()
                assertEquals("my notes.txt", restored.get().findViewById<EditText>(R.id.text_filename).text.toString())
            } finally { restored.pause().stop().destroy() }
        } finally {
            if (!controller.get().isDestroyed) controller.pause().stop().destroy()
        }
    }
}
