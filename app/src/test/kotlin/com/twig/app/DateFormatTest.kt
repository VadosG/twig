package com.twig.app

import android.app.Application
import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DateFormatTest {
    private val ctx: Application get() = ApplicationProvider.getApplicationContext()
    private val originalConfig = Configuration(Resources.getSystem().configuration)
    private val originalZone = TimeZone.getDefault()
    // 2026-10-04 12:34 UTC, with unequal day/month to catch reversed ordering.
    private val timestamp = 1791117240000L

    @Before
    fun setup() {
        ctx.getSharedPreferences("twig_prefs", 0).edit().clear().apply()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun cleanup() {
        Resources.getSystem().updateConfiguration(originalConfig, null)
        TimeZone.setDefault(originalZone)
    }

    private fun region(locale: Locale) {
        val config = Configuration(Resources.getSystem().configuration)
        config.setLocale(locale)
        Resources.getSystem().updateConfiguration(config, null)
    }

    @Test
    fun `system region controls dates even when app language differs`() {
        val appConfig = Configuration(ctx.resources.configuration).apply { setLocale(Locale.CHINESE) }
        val chineseUi = ctx.createConfigurationContext(appConfig)
        region(Locale.GERMANY)
        assertEquals("04.10.26 12:34", Format.time(timestamp, chineseUi))
        region(Locale.US)
        assertEquals("10/4/26 12:34", Format.time(timestamp, chineseUi))
    }

    @Test
    fun `changing explicit format takes effect without restarting`() {
        region(Locale.US)
        Prefs.setDateFormat(ctx, 1)
        assertEquals("26-10-04 12:34", Format.time(timestamp, ctx))
        Prefs.setDateFormat(ctx, 2)
        assertEquals("04.10.26 12:34", Format.time(timestamp, ctx))
        Prefs.setDateFormat(ctx, 3)
        assertEquals("10/04/26 12:34", Format.time(timestamp, ctx))
        Prefs.setDateFormat(ctx, 0)
        assertEquals("10/4/26 12:34", Format.time(timestamp, ctx))
    }

    @Test
    fun `timezone changes refresh cached formatter and unknown dates stay empty`() {
        Prefs.setDateFormat(ctx, 2)
        assertEquals("04.10.26 12:34", Format.time(timestamp, ctx))
        TimeZone.setDefault(TimeZone.getTimeZone("GMT+08:00"))
        assertEquals("04.10.26 20:34", Format.time(timestamp, ctx))
        assertEquals("", Format.time(0, ctx))
        assertEquals("", Format.time(-1, ctx))
    }
}
