package com.twig.app.ui

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.twig.app.FileSortKey
import com.twig.app.FolderSortKey
import com.twig.app.SortSpec
import com.twig.app.R
import com.twig.app.databinding.DialogSortBinding

/** File-tree sorting shared by the main screen and share destination picker. */
object SortDialog {
    fun show(activity: AppCompatActivity, onChanged: (SortSpec) -> Unit) {
        val d = DialogSortBinding.inflate(activity.layoutInflater)
        val cur = SortSpec.load(activity)
        d.rgBy.check(
            when (cur.by) {
                FileSortKey.NAME -> R.id.rb_name
                FileSortKey.SIZE -> R.id.rb_size
                FileSortKey.EXT -> R.id.rb_ext
                FileSortKey.DATE -> R.id.rb_date
            },
        )
        d.cbReversed.isChecked = cur.reversed
        d.rgFolder.check(
            when (cur.folderBy) {
                FolderSortKey.NAME -> R.id.rf_name
                FolderSortKey.DATE_OLD -> R.id.rf_date_old
                FolderSortKey.DATE_NEW -> R.id.rf_date_new
            },
        )
        AlertDialog.Builder(activity)
            .setTitle(R.string.action_sort)
            .setView(d.root)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val spec = SortSpec(
                    by = when (d.rgBy.checkedRadioButtonId) {
                        R.id.rb_size -> FileSortKey.SIZE
                        R.id.rb_ext -> FileSortKey.EXT
                        R.id.rb_date -> FileSortKey.DATE
                        else -> FileSortKey.NAME
                    },
                    reversed = d.cbReversed.isChecked,
                    folderBy = when (d.rgFolder.checkedRadioButtonId) {
                        R.id.rf_date_old -> FolderSortKey.DATE_OLD
                        R.id.rf_date_new -> FolderSortKey.DATE_NEW
                        else -> FolderSortKey.NAME
                    },
                )
                SortSpec.save(activity, spec)
                onChanged(spec)
            }
            .show()
    }
}
