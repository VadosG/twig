package com.twig.app.ui

import android.net.Uri
import android.content.Intent
import android.os.Looper
import android.view.MenuItem
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.widget.Toolbar
import com.twig.app.R
import com.twig.core.FsRegistry
import com.twig.fs.local.LocalFileSystem
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Exercise the actual preview client: a blocked request must never return null (network fallback). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreviewResourcesTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var activity: TextViewerActivity
    private lateinit var web: WebView

    private fun showPreview(show: Boolean) {
        TextViewerActivity::class.java.getDeclaredField("previewMode").apply { isAccessible = true }
            .setBoolean(activity, show)
        TextViewerActivity::class.java.getDeclaredMethod("syncEditMenu").apply { isAccessible = true }
            .invoke(activity)
    }

    private fun resourceItem(): MenuItem {
        val menu = activity.findViewById<Toolbar>(R.id.toolbar).menu
        return (0 until menu.size()).map { menu.getItem(it) }
            .first { it.title == activity.getString(R.string.viewer_load_external_resources) }
    }

    private fun loadResources() {
        val menu = activity.findViewById<Toolbar>(R.id.toolbar).menu as MenuBuilder
        assertTrue(menu.performItemAction(resourceItem(), 0))
    }

    @Before
    fun setUp() {
        FsRegistry.register(LocalFileSystem())
        val file = temp.newFile("README.md")
        file.writeText("# Preview")
        activity = Robolectric.buildActivity(
            TextViewerActivity::class.java,
            Intent(org.robolectric.RuntimeEnvironment.getApplication(), TextViewerActivity::class.java)
                .putExtra("scheme", "file")
                .putExtra("path", file.path)
                .putExtra("name", file.name)
                .putExtra("preview", true),
        ).setup().visible().get()
        web = activity.findViewById(R.id.webview)
    }

    private fun request(value: String) = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(value)
        override fun isForMainFrame() = false
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }

    @Test
    fun `external images CSS and fonts never fall through to the network loader`() {
        assertTrue(web.settings.blockNetworkLoads)
        for (url in listOf(
            "https://example.org/pixel.png", "http://example.org/badge.svg",
            "https://example.org/theme.css", "https://example.org/font.woff2",
        )) {
            val response = web.webViewClient.shouldInterceptRequest(web, request(url))
            assertNotNull(url, response)
            assertEquals(url, "", response!!.data.bufferedReader().use { it.readText() })
        }
    }

    @Test
    fun `relative resources still read through the document filesystem`() {
        val image = temp.newFile("image.svg")
        image.writeText("<svg></svg>")
        val response = web.webViewClient.shouldInterceptRequest(
            web, request("https://twig.local${image.path}"),
        )!!
        assertEquals("<svg></svg>", response.data.bufferedReader().use { it.readText() })
    }

    @Test
    fun `a missing relative resource cannot fall back to DNS for the fake host`() {
        val response = web.webViewClient.shouldInterceptRequest(
            web, request("https://twig.local${temp.root.path}/missing.png"),
        )
        assertNotNull(response)
        assertEquals("", response!!.data.bufferedReader().use { it.readText() })
    }

    @Test
    fun `external links still open in the browser`() {
        val url = "https://example.org/docs"
        assertTrue(web.webViewClient.shouldOverrideUrlLoading(web, request(url)))
        assertEquals(url, org.robolectric.Shadows.shadowOf(activity).nextStartedActivity.data.toString())
    }

    @Test
    fun `the ordinary menu action enables external resources only for this viewer`() {
        showPreview(true)
        assertTrue(resourceItem().isVisible)
        assertFalse(resourceItem().isCheckable)
        loadResources()
        assertFalse(resourceItem().isChecked)
        assertFalse(web.settings.blockNetworkLoads)
        assertNull(web.webViewClient.shouldInterceptRequest(web, request("https://example.org/image.png")))
        assertNull(web.webViewClient.shouldInterceptRequest(web, request("http://example.org/theme.css")))
        assertNotNull(web.webViewClient.shouldInterceptRequest(web, request("content://example.org/private")))

        loadResources() // Repeating the action reloads; it does not turn permission off.
        assertFalse(resourceItem().isChecked)
        assertFalse(web.settings.blockNetworkLoads)
        assertNull(web.webViewClient.shouldInterceptRequest(web, request("https://example.org/image.png")))
        showPreview(false)
        assertFalse(resourceItem().isVisible)
    }

    @Test
    fun `loading external resources reloads in-memory content and restores the previous scroll position`() {
        showPreview(true)
        val marker = "Only in memory"
        TextViewerActivity::class.java.getDeclaredField("raw").apply { isAccessible = true }.set(activity, marker)
        web.scrollTo(12, 240)
        loadResources()
        assertTrue(shadowOf(web).lastLoadDataWithBaseURL.data.contains(marker))
        web.scrollTo(0, 0) // Simulate the page reload resetting its position.
        web.webViewClient.onPageFinished(web, "https://twig.local/")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(12, web.scrollX)
        assertEquals(240, web.scrollY)
    }

    @Test
    fun `opening the same document again does not remember permission to load external resources`() {
        showPreview(true)
        loadResources()
        val next = Robolectric.buildActivity(TextViewerActivity::class.java, activity.intent).create().get()
        val nextWeb = next.findViewById<WebView>(R.id.webview)
        assertTrue(nextWeb.settings.blockNetworkLoads)
        assertNotNull(nextWeb.webViewClient.shouldInterceptRequest(nextWeb, request("https://example.org/pixel.png")))
    }
}
