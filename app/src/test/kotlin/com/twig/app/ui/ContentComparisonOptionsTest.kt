package com.twig.app.ui

import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import com.twig.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContentComparisonOptionsTest {
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        else emptyList()

    @Test fun `unlimited content comparison can be selected and restored without enabling network reads`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        try {
            var applied: CompareOptions? = null
            showCompareOptions(activity, CompareOptions()) { applied = it }
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val controls = descendants(dialog.window!!.decorView)
            val caps = controls.filterIsInstance<Spinner>().filter {
                it.adapter.getItem(0) == activity.getString(R.string.compare_content_off)
            }
            assertEquals(2, caps.size)
            assertEquals("Network reads must remain off by default", 0, caps[1].selectedItemPosition)
            caps[0].setSelection(caps[0].adapter.count - 1)
            assertEquals(activity.getString(R.string.compare_content_unlimited), caps[0].selectedItem)
            controls.filterIsInstance<CheckBox>().single {
                it.text.toString() == activity.getString(R.string.compare_content_time_gate)
            }.isChecked = false
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(Long.MAX_VALUE, applied!!.contentLimitLocal)
            assertEquals(0L, applied!!.contentLimitNetwork)
            assertFalse(applied!!.contentOnlyIfTimeDiffers)
            saveCompareOptions(activity, applied!!)
            val restored = loadCompareOptions(activity)
            assertEquals(applied, restored)
            showCompareOptions(activity, restored) {}
            val reopened = ShadowDialog.getLatestDialog() as AlertDialog
            val restoredCaps = descendants(reopened.window!!.decorView).filterIsInstance<Spinner>().filter {
                it.adapter.getItem(0) == activity.getString(R.string.compare_content_off)
            }
            assertEquals(activity.getString(R.string.compare_content_unlimited), restoredCaps[0].selectedItem)
            assertEquals(0, restoredCaps[1].selectedItemPosition)
            reopened.dismiss()
        } finally { activity.finish() }
    }
}
