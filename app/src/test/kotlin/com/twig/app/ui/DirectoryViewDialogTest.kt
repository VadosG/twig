package com.twig.app.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.twig.app.DirectoryView
import com.twig.app.DirectoryViewStore
import com.twig.app.FileSortKey
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.app.SortSpec
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DirectoryViewDialogTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dir = XFile("file", "/storage/emulated/0/Pictures", isDir = true)

    @Before
    fun setup() {
        for (name in listOf("twig_sort", "twig_prefs", DirectoryViewStore.FILE)) {
            app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun withDialog(directory: XFile? = dir, viewOnly: Boolean = false, run: (AlertDialog) -> Unit) {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            if (viewOnly) SortDialog.showView(controller.get(), directory) {}
            else SortDialog.show(controller.get(), directory) {}
            run(ShadowDialog.getLatestDialog() as AlertDialog)
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            ShadowDialog.getLatestDialog()?.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `scope drafts stay independent and cancel writes nothing`() {
        withDialog { dialog ->
            dialog.findViewById<RadioGroup>(R.id.rg_by)!!.check(R.id.rb_size)
            dialog.findViewById<RadioGroup>(R.id.rg_scope)!!.check(R.id.scope_directory)
            assertFalse(dialog.findViewById<RadioButton>(R.id.rb_date)!!.isEnabled)
            assertEquals(View.GONE, dialog.findViewById<View>(R.id.view_options)!!.visibility)
            dialog.findViewById<CheckBox>(R.id.cb_custom_sort)!!.isChecked = true
            dialog.findViewById<RadioGroup>(R.id.rg_by)!!.check(R.id.rb_date)
            dialog.findViewById<RadioGroup>(R.id.rg_scope)!!.check(R.id.scope_default)
            assertEquals(R.id.rb_size, dialog.findViewById<RadioGroup>(R.id.rg_by)!!.checkedRadioButtonId)
            dialog.findViewById<RadioGroup>(R.id.rg_scope)!!.check(R.id.scope_directory)
            assertEquals(R.id.rb_date, dialog.findViewById<RadioGroup>(R.id.rg_by)!!.checkedRadioButtonId)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        }
        assertEquals(FileSortKey.NAME, SortSpec.load(app).by)
        assertEquals(0, Prefs.thumbsGrid(app))
        assertTrue(DirectoryViewStore.load(app).isEmpty())
    }

    @Test
    fun `saving and resetting directory view preserve its sorting and default view`() {
        val sort = SortSpec(reversed = true)
        DirectoryViewStore.save(app, dir, DirectoryView(sort = sort))
        withDialog(viewOnly = true) { dialog ->
            assertEquals(View.GONE, dialog.findViewById<View>(R.id.sort_options)!!.visibility)
            dialog.findViewById<RadioGroup>(R.id.rg_scope)!!.check(R.id.scope_directory)
            dialog.findViewById<CheckBox>(R.id.cb_custom_view)!!.isChecked = true
            dialog.findViewById<RadioGroup>(R.id.rg_view)!!.check(R.id.view_all)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        assertEquals(DirectoryView(sort, 2), DirectoryViewStore.get(DirectoryViewStore.load(app), dir))
        assertEquals(0, Prefs.thumbsGrid(app))
        withDialog(viewOnly = true) { dialog ->
            dialog.findViewById<RadioGroup>(R.id.rg_scope)!!.check(R.id.scope_directory)
            assertEquals(R.id.view_all, dialog.findViewById<RadioGroup>(R.id.rg_view)!!.checkedRadioButtonId)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        assertEquals(DirectoryView(sort = sort), DirectoryViewStore.get(DirectoryViewStore.load(app), dir))
    }

    @Test
    fun `saving and resetting directory sorting preserve its view`() {
        DirectoryViewStore.save(app, dir, DirectoryView(gridMode = 2))
        withDialog { dialog ->
            dialog.findViewById<RadioGroup>(R.id.rg_scope)!!.check(R.id.scope_directory)
            dialog.findViewById<CheckBox>(R.id.cb_custom_sort)!!.isChecked = true
            dialog.findViewById<RadioGroup>(R.id.rg_by)!!.check(R.id.rb_size)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        assertEquals(DirectoryView(SortSpec(by = FileSortKey.SIZE), 2),
            DirectoryViewStore.get(DirectoryViewStore.load(app), dir))
        assertEquals(FileSortKey.NAME, SortSpec.load(app).by)
        withDialog { dialog ->
            dialog.findViewById<RadioGroup>(R.id.rg_scope)!!.check(R.id.scope_directory)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
        assertEquals(DirectoryView(gridMode = 2), DirectoryViewStore.get(DirectoryViewStore.load(app), dir))
    }

    @Test
    fun `directory scope is unavailable without a selected directory`() {
        withDialog(null) { dialog ->
            assertFalse(dialog.findViewById<RadioButton>(R.id.scope_directory)!!.isEnabled)
            assertEquals(R.id.scope_default, dialog.findViewById<RadioGroup>(R.id.rg_scope)!!.checkedRadioButtonId)
        }
    }
}
