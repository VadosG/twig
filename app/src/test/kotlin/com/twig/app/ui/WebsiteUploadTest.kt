package com.twig.app.ui

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import com.twig.app.StreamProvider
import com.twig.app.WebsiteStore
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebsiteUploadTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun clear() { WebsiteStore.preferences(app).edit().clear().commit() }

    private fun params(multiple: Boolean) = object : FileChooserParams() {
        override fun getMode() = if (multiple) MODE_OPEN_MULTIPLE else MODE_OPEN
        override fun getAcceptTypes() = arrayOf("image/*")
        override fun isCaptureEnabled() = false
        override fun getTitle(): CharSequence? = null
        override fun getFilenameHint(): String? = null
        override fun createIntent() = Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
    }

    @Test fun `website uploads use Twig and return every selected stream URI`() {
        for (multiple in listOf(false, true)) {
            val controller = Robolectric.buildActivity(WebsiteActivity::class.java,
                WebsiteActivity.browserIntent(app, "https://example.com")).setup()
            try {
                val activity = controller.get()
                val web = activity.findViewById<WebView>(R.id.web)
                var received: Array<Uri>? = null
                var calls = 0
                assertTrue(web.webChromeClient!!.onShowFileChooser(web, ValueCallback {
                    calls++
                    received = it
                }, params(multiple)))
                val launched = shadowOf(activity).nextStartedActivityForResult.intent
                assertEquals(PickerActivity::class.java.name, launched.component!!.className)
                assertEquals(multiple, launched.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
                assertEquals("image/*", launched.type)
                val first = StreamProvider.uriFor(app, XFile("file", "/sdcard/first.jpg", false))
                val second = StreamProvider.uriFor(app, XFile("smb:test", "/second.jpg", false))
                val result = Intent().setData(first)
                if (multiple) result.clipData = ClipData.newRawUri("files", first).apply {
                    addItem(ClipData.Item(second))
                }
                shadowOf(activity).receiveResult(launched, Activity.RESULT_OK, result)
                assertEquals(1, calls)
                assertArrayEquals(if (multiple) arrayOf(first, second) else arrayOf(first), received)
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun `canceling and closing complete pending upload callbacks once`() {
        val controller = Robolectric.buildActivity(WebsiteActivity::class.java,
            WebsiteActivity.browserIntent(app, "https://example.com")).setup()
        val activity = controller.get()
        val web = activity.findViewById<WebView>(R.id.web)
        val results = mutableListOf<Array<Uri>?>()
        val callback = ValueCallback<Array<Uri>> { results.add(it) }
        web.webChromeClient!!.onShowFileChooser(web, callback, params(false))
        val launched = shadowOf(activity).nextStartedActivityForResult.intent
        shadowOf(activity).receiveResult(launched, Activity.RESULT_CANCELED, null)
        assertEquals(1, results.size)
        assertNull(results[0])
        web.webChromeClient!!.onShowFileChooser(web, callback, params(false))
        controller.pause().stop().destroy()
        assertEquals(2, results.size)
        assertNull(results[1])
    }
}
