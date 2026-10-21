package com.twig.app.ui

import android.content.Context
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.twig.app.R
import com.twig.app.TrashFileSystem
import com.twig.app.secure.Secrets
import com.twig.core.FsRegistry
import com.twig.core.XFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Management actions stay in the pane; no second screen or navigation stack. */
class TrashDialogs(private val ctx: Context, private val owner: LifecycleOwner, private val changed: () -> Unit) {
    private val fs get() = FsRegistry.of("trash") as TrashFileSystem
    private val store get() = fs.store
    private fun active() = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !Secrets.locked(ctx)
    private fun AlertDialog.track(): AlertDialog {
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { dismiss() }
        }
        owner.lifecycle.addObserver(observer)
        setOnDismissListener { owner.lifecycle.removeObserver(observer) }
        return this
    }
    private fun error(e: Throwable) = Toast.makeText(ctx, e.message ?: ctx.getString(R.string.err_failed), Toast.LENGTH_LONG).show()
    fun show(file: XFile, extraActions: List<MenuAct> = emptyList()) {
        if (file.path == "/") {
            AlertDialog.Builder(ctx).setTitle(R.string.trash_title)
                .setItems(arrayOf(ctx.getString(R.string.action_refresh), ctx.getString(R.string.trash_empty))) { _, which ->
                    if (which == 0) changed() else confirmEmpty()
                }.show().track()
        } else {
            owner.lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { fs.item(file) } }
                if (!active()) return@launch
                result.fold(onSuccess = { item ->
                    val actions = ArrayList<MenuAct>()
                    if (item.entry.original != null) actions.item(ctx.getString(R.string.trash_restore), R.drawable.ic_up) {
                        restoreName(item, file.name)
                    }
                    actions += extraActions
                    actions.item(ctx.getString(R.string.trash_delete_permanently), R.drawable.ic_delete, DANGER) {
                        DeleteUi.confirmation(ctx, listOf(file.name))
                            .setPositiveButton(R.string.strip_delete) { _, _ -> work { store.purgeItem(item.entry, item.relative) } }
                            .show().track()
                    }
                    AlertDialog.Builder(ctx).setTitle(file.name)
                        .setAdapter(menuAdapter(ctx, actions)) { _, which -> actions[which].run() }.show().track()
                }, onFailure = ::error)
            }
        }
    }
    private fun restoreName(item: TrashFileSystem.Item, defaultName: String) {
        val input = EditText(ctx).apply { setSingleLine(); setText(defaultName) }
        AlertDialog.Builder(ctx).setTitle(R.string.trash_restore_name).setView(input)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.trash_restore) { _, _ ->
                val name = input.text.toString()
                owner.lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching {
                        val parent = File(store.originalPath(item.entry, item.relative)).parentFile!!
                        parent.takeUnless { it.isDirectory }
                    } }
                    if (!active()) return@launch
                    result.fold(onSuccess = { missingParent ->
                        if (missingParent == null) work { store.restoreItem(item.entry, item.relative, name) }
                        else AlertDialog.Builder(ctx).setMessage(ctx.getString(R.string.trash_restore_parent, missingParent.path))
                            .setNegativeButton(R.string.dialog_cancel, null)
                            .setPositiveButton(R.string.trash_restore) { _, _ -> work { store.restoreItem(item.entry, item.relative, name, true) } }
                            .show().track()
                    }, onFailure = ::error)
                }
            }.show().track()
    }
    private fun confirmEmpty() {
        owner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { store.snapshot().entries } }
            if (!active()) return@launch
            result.fold(onSuccess = { entries ->
                if (entries.isEmpty()) return@fold
                DeleteUi.confirmation(ctx, entries.map { it.name })
                    .setPositiveButton(R.string.trash_empty) { _, _ -> work { store.empty(entries) } }.show().track()
            }, onFailure = ::error)
        }
    }
    private fun work(block: () -> Unit) {
        if (!active()) return
        val progress = AlertDialog.Builder(ctx).setMessage(R.string.trash_busy).setCancelable(false).show().track()
        owner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { runCatching(block) }
                if (active()) result.exceptionOrNull()?.let(::error)
            } finally { progress.dismiss(); changed() }
        }
    }
}
