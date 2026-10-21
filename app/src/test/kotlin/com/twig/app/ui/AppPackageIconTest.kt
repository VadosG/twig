package com.twig.app.ui

import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import com.twig.app.R
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppPackageIconTest {
    @Test fun `package rows show cached app icons with thumbnails on or off and recycle back to folders`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val cacheField = FileIcons::class.java.getDeclaredField("cache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(FileIcons) as MutableMap<String, Drawable?>
        val appIcon = ColorDrawable(Color.MAGENTA)
        synchronized(cache) {
            cache["pkg:com.twig.icon.test"] = appIcon
            cache["pkg:com.twig.icon.missing"] = null
        }
        try {
            for (thumbs in listOf(false, true)) {
                val adapter = FileAdapter(1, {}, {}, {}, { _, _ -> }, {}, thumbs = thumbs)
                val paths = listOf(
                    "/sdcard/Android/media/com.twig.icon.test", "/data/user_de/10/com.twig.icon.test",
                    "/data/data/com.twig.icon.missing", "/data/data/com.twig.icon.test/cache",
                )
                adapter.submitList(paths.map { PaneViewModel.FileNode(XFile("file", it, true), 1, false, false) })
                val parent = FrameLayout(ContextThemeWrapper(app, R.style.Theme_Twig))
                val holder = adapter.createViewHolder(parent, adapter.getItemViewType(0))
                val icon = holder.itemView.findViewById<ImageView>(R.id.icon)
                for (i in paths.indices) {
                    adapter.bindViewHolder(holder, i)
                    if (i < 2) assertSame(appIcon, icon.drawable)
                    else assertEquals(R.drawable.ic_folder, shadowOf(icon.drawable).createdFromResId)
                }
            }
        } finally {
            synchronized(cache) {
                cache.remove("pkg:com.twig.icon.test")
                cache.remove("pkg:com.twig.icon.missing")
            }
        }
    }
}
