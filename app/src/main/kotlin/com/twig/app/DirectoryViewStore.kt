package com.twig.app

import android.content.Context
import com.twig.core.XFile
import org.json.JSONObject

/** Only explicit overrides are stored; missing fields always follow the current defaults. */
data class DirectoryView(val sort: SortSpec? = null, val gridMode: Int? = null)

object DirectoryViewStore {
    const val FILE = "twig_directory_views"

    private fun key(dir: XFile): String = "${dir.scheme}:${dir.path.trimEnd('/').ifEmpty { "/" }}"

    /** One snapshot per settings refresh, never JSON parsing during row binding. */
    fun load(ctx: Context): Map<String, DirectoryView> =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).all.mapNotNull { (key, value) ->
            runCatching {
                val json = JSONObject(value as String)
                val sort = json.optJSONObject("sort")?.let {
                    SortSpec(
                        FileSortKey.valueOf(it.getString("by")),
                        it.getBoolean("reversed"),
                        FolderSortKey.valueOf(it.getString("folderBy")),
                    )
                }
                val grid = if (json.has("grid")) json.getInt("grid").takeIf { it in 0..2 } else null
                key to DirectoryView(sort, grid)
            }.getOrNull()
        }.toMap()

    fun get(views: Map<String, DirectoryView>, dir: XFile?): DirectoryView? = dir?.let { views[key(it)] }

    fun save(ctx: Context, dir: XFile, view: DirectoryView) {
        val editor = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
        if (view.sort == null && view.gridMode == null) {
            editor.remove(key(dir))
        } else {
            val json = JSONObject()
            view.sort?.let {
                json.put("sort", JSONObject().put("by", it.by.name)
                    .put("reversed", it.reversed).put("folderBy", it.folderBy.name))
            }
            view.gridMode?.let { json.put("grid", it.coerceIn(0, 2)) }
            editor.putString(key(dir), json.toString())
        }
        editor.apply()
    }
}
