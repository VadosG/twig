package com.twig.app.ui

import android.content.Context
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.twig.app.Prefs
import com.twig.app.R
import com.twig.app.TrashStore
import com.twig.core.FsRegistry
import com.twig.core.XFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One confirmation and execution path for manual deletion from every file viewer. */
object DeleteUi {
    internal fun confirmation(context: Context, names: List<String>): AlertDialog.Builder {
        val icon = ContextCompat.getDrawable(context, R.drawable.ic_delete)?.mutate()?.apply {
            setTint(ContextCompat.getColor(context, R.color.menu_icon_danger))
        }
        return AlertDialog.Builder(context)
            .setTitle(R.string.strip_delete)
            .setIcon(icon)
            .setMessage(if (names.size == 1) names.single() else context.getString(R.string.title_selected_count, names.size))
            .setNegativeButton(R.string.dialog_cancel, null)
    }

    fun confirm(
        context: Context, owner: LifecycleOwner, files: List<XFile>,
        onDismiss: (() -> Unit)? = null, onFinished: (Boolean) -> Unit,
    ) = confirm(context, owner.lifecycleScope, files, onDismiss, onFinished)

    fun confirm(
        context: Context, scope: CoroutineScope, files: List<XFile>,
        onDismiss: (() -> Unit)? = null, onFinished: (Boolean) -> Unit,
    ) {
        val app = context.applicationContext
        scope.launch {
            val store = TrashStore(app)
            val planned = withContext(Dispatchers.IO) { runCatching { store.plan(files) } }
            val plan = planned.getOrElse {
                Toast.makeText(context, it.message ?: context.getString(R.string.err_failed), Toast.LENGTH_LONG).show()
                return@launch
            }
            if (plan.isEmpty()) return@launch
            if (context is LifecycleOwner && !context.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
                return@launch
            val enabled = Prefs.trashEnabled(app)
            val eligible = plan.count { it.volume != null }
            val useTrash = CheckBox(context).apply {
                id = android.R.id.checkbox
                text = context.getString(R.string.trash_use)
                isChecked = true
                visibility = if (enabled && eligible > 0) android.view.View.VISIBLE else android.view.View.GONE
            }
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                val dp = resources.displayMetrics.density
                val pad = (24 * dp).toInt()
                setPadding(pad, (8 * dp).toInt(), pad, 0)
                addView(useTrash)
            }
            confirmation(context, plan.map { it.file.name })
                .setView(content.takeIf { enabled && eligible > 0 })
                .setOnDismissListener { onDismiss?.invoke() }
                .setPositiveButton(R.string.strip_delete) { _, _ ->
                    val recycle = enabled && useTrash.isChecked
                    scope.launch {
                        val busy = AlertDialog.Builder(context).setMessage(R.string.trash_busy).setCancelable(false).show()
                        val result = try {
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    var firstError: Throwable? = null
                                    var failed = 0
                                    for (target in plan) {
                                        runCatching {
                                            if (recycle && target.volume != null) store.move(target)
                                            else FsRegistry.of(target.file).delete(target.file)
                                        }.exceptionOrNull()?.let {
                                            if (firstError == null) firstError = it
                                            failed++
                                        }
                                    }
                                    if (failed > 0) throw java.io.IOException(
                                        context.getString(R.string.compare_sync_delete_failed, failed) + "\n" +
                                            firstError?.message.orEmpty(), firstError)
                                }
                            }
                        } finally { busy.dismiss() }
                        result.exceptionOrNull()?.let {
                            Toast.makeText(context, it.message ?: context.getString(R.string.err_failed), Toast.LENGTH_LONG).show()
                        }
                        // Refresh even after a partial batch failure; successful earlier moves remain recoverable.
                        onFinished(result.isSuccess)
                    }
                }.show()
        }
    }
}
