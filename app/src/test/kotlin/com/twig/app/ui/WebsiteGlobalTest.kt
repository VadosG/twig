package com.twig.app.ui

import android.app.Application
import android.net.Uri
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import com.twig.app.Website
import com.twig.app.WebsiteStore
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
class WebsiteGlobalTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    @Before fun clear() { WebsiteStore.preferences(app).edit().clear().commit() }

    @Test fun `global additions merge without changing stored sites and local agent overrides global`() {
        val local = Website(name = "Site", url = "https://example.com", userAgent = "LocalAgent",
            script = "const x = 'local';", style = "body { color: blue; }",
            hiddenElements = ".shared\n.local", blockedRequests = "shared.test\nlocal.test")
        WebsiteStore.save(app, local)
        WebsiteStore.saveDefaults(app, Website(name = "", url = "", userAgent = "GlobalAgent",
            script = "const x = 'global';", style = "body { color: red; }",
            hiddenElements = ".global\n.shared", blockedRequests = "global.test\nshared.test"))
        val merged = WebsiteStore.effective(app, local)
        assertEquals("LocalAgent", merged.userAgent)
        assertEquals("GlobalAgent", WebsiteStore.effective(app, local.copy(userAgent = "")).userAgent)
        assertTrue(merged.script.contains(local.script))
        assertTrue(merged.script.contains("const x = 'global';"))
        assertTrue(merged.script.indexOf("'global'") < merged.script.indexOf("'local'"))
        assertEquals("body { color: red; }\nbody { color: blue; }", merged.style)
        assertEquals(".global\n.shared\n.local", merged.hiddenElements)
        val blocker = requireNotNull(WebsiteRequestBlocker.parse(merged.blockedRequests))
        for (host in listOf("global.test", "shared.test", "local.test")) assertTrue(blocker.blocks("https://$host/a"))
        assertEquals(listOf(local), WebsiteStore.all(app))
    }

    @Test fun `changing globals updates open bookmarks and temporary pages without copying fields`() {
        val local = Website(name = "Site", url = "https://example.com", style = ".local { color: blue; }",
            blockedRequests = "local.test")
        WebsiteStore.save(app, local)
        val controllers = listOf(
            Robolectric.buildActivity(WebsiteActivity::class.java, WebsiteActivity.intent(app, local.id)).setup(),
            Robolectric.buildActivity(WebsiteActivity::class.java,
                WebsiteActivity.browserIntent(app, "https://browser.example")).setup())
        val webs = controllers.map { it.get().findViewById<WebView>(R.id.web) }
        val global = Website(name = "", url = "", userAgent = "NewGlobalAgent",
            script = "window.globalScript = true;", style = ".global { display: none; }",
            hiddenElements = ".globalHidden", blockedRequests = "global.test")
        WebsiteStore.saveDefaults(app, global)
        shadowOf(Looper.getMainLooper()).idle()
        for (web in webs) {
            assertEquals("NewGlobalAgent", web.settings.userAgentString)
            assertTrue(shadowOf(web).reloadInvocations > 0)
            assertNotNull(web.webViewClient.shouldInterceptRequest(web, resource("https://global.test/ad")))
            web.webViewClient.onPageFinished(web, web.url!!)
            assertTrue(shadowOf(web).lastEvaluatedJavascript.contains("window.globalScript = true;"))
        }
        WebsiteStore.saveDefaults(app, global.copy(script = "", style = "", hiddenElements = "", blockedRequests = ""))
        shadowOf(Looper.getMainLooper()).idle()
        for (web in webs) assertNull(web.webViewClient.shouldInterceptRequest(web, resource("https://global.test/ad")))
        assertNotNull(webs.first().webViewClient.shouldInterceptRequest(webs.first(), resource("https://local.test/ad")))
        assertEquals(listOf(local), WebsiteStore.all(app))
        controllers.forEach { it.pause().stop().destroy() }
    }

    @Test fun `debug CSS edits keep global CSS applied without persisting it into the site`() {
        val local = Website(name = "Site", url = "https://example.com", style = ".local { color: blue; }")
        WebsiteStore.save(app, local)
        WebsiteStore.saveDefaults(app, Website(name = "", url = "", style = ".global { color: red; }"))
        val controller = Robolectric.buildActivity(WebsiteActivity::class.java,
            WebsiteActivity.intent(app, local.id)).setup()
        val activity = controller.get()
        val web = activity.findViewById<WebView>(R.id.web)
        val toolbar = activity.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        val menu = toolbar.menu as androidx.appcompat.view.menu.MenuBuilder
        val item = (0 until menu.size()).map { menu.getItem(it) }
            .single { it.title == activity.getString(R.string.website_debug) }
        menu.performItemAction(item, 0)
        val panel = activity.findViewById<android.view.View>(R.id.debug_host)
        panel.findViewWithTag<android.view.View>(R.string.website_style).performClick()
        val editor = panel.findViewWithTag<android.widget.EditText>(R.string.website_debug_css_input)
        assertEquals(local.style, editor.text.toString())
        editor.setText(".changed { color: green; }")
        panel.findViewWithTag<android.view.View>(R.string.website_debug_preview).performClick()
        assertTrue(shadowOf(web).lastEvaluatedJavascript.contains(".global"))
        assertTrue(shadowOf(web).lastEvaluatedJavascript.contains(".changed"))
        panel.findViewWithTag<android.view.View>(R.string.viewer_save).performClick()
        assertEquals(".changed { color: green; }", WebsiteStore.all(app).single().style)
        assertTrue(shadowOf(web).lastEvaluatedJavascript.contains(".global"))
        controller.pause().stop().destroy()
    }

    private fun resource(url: String) = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(url)
        override fun isForMainFrame() = false
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }
}
