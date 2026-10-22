package com.twig.app.ui

import android.app.Application
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import com.twig.app.Website
import com.twig.app.WebsiteStore
import com.twig.app.secure.Backup
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebsiteDefaultsTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    @Before fun clear() { WebsiteStore.preferences(app).edit().clear().commit() }

    @Test fun `new bookmark inherits defaults while editing retains its own settings`() {
        WebsiteStore.saveDefaults(app, Website(name = "", url = "", userAgent = "DefaultAgent",
            script = "window.defaultScript = true", style = "body { color: red; }",
            hiddenElements = ".advert", blockedRequests = "ads.example.com",
            headers = "Secret: private", basicAuthPassword = "private",
            openFullscreen = true, fullscreen = true, hideStatusBar = true, hideNavigationBar = true,
            pullToRefresh = true, disableTextSelection = true, lockScrollAxis = true))
        val original = Website(name = "Saved", url = "https://example.com", userAgent = "SiteAgent")
        WebsiteStore.save(app, original)
        val controller = Robolectric.buildActivity(WebsiteActivity::class.java,
            WebsiteActivity.intent(app, original.id)).setup()
        WebsiteDialogs.edit(controller.get())
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        var dialog = ShadowDialog.getLatestDialog() as AlertDialog
        fun field(key: Int) = dialog.window!!.decorView.findViewWithTag<EditText>(key)
        assertEquals("DefaultAgent", field(R.string.website_user_agent).text.toString())
        assertEquals("", field(R.string.website_headers).text.toString())
        // The defaults editor saves without requiring a name or URL.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val defaults = ShadowDialog.getLatestDialog() as AlertDialog
        defaults.window!!.decorView.findViewWithTag<EditText>(R.string.website_user_agent).setText("ChangedAgent")
        defaults.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals("ChangedAgent", field(R.string.website_user_agent).text.toString())
        val url = (dialog.window!!.decorView as android.view.ViewGroup).let { root ->
            fun find(view: android.view.View): EditText? {
                if (view is EditText && view.hint == app.getString(R.string.website_url)) return view
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) {
                    find(view.getChildAt(i))?.let { return it }
                }
                return null
            }
            requireNotNull(find(root))
        }
        url.setText("new.example.com")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        val added = WebsiteStore.all(app).last()
        assertEquals("ChangedAgent", added.userAgent)
        assertEquals(".advert", added.hiddenElements)
        assertEquals("ads.example.com", added.blockedRequests)
        assertTrue(added.openFullscreen)
        assertTrue(added.fullscreen)
        assertTrue(added.hideStatusBar)
        assertTrue(added.hideNavigationBar)
        assertTrue(added.pullToRefresh)
        assertTrue(added.disableTextSelection)
        assertTrue(added.lockScrollAxis)
        assertEquals(original, WebsiteStore.all(app).first())
        WebsiteDialogs.edit(controller.get(), original)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals("SiteAgent", field(R.string.website_user_agent).text.toString())
        dialog.dismiss()
        controller.pause().stop().destroy()
    }

    @Test fun `configuration backup includes default settings without copying credentials`() {
        WebsiteStore.saveDefaults(app, Website(name = "", url = "", script = "test()", headers = "Private: secret"))
        val out = ByteArrayOutputStream()
        Backup.export(app, out, null)
        clear()
        Backup.import(app, out.toByteArray(), null)
        assertEquals("test()", WebsiteStore.defaults(app).script)
        assertEquals("", WebsiteStore.defaults(app).headers)
        assertTrue(WebsiteStore.all(app).isEmpty())
    }
}
