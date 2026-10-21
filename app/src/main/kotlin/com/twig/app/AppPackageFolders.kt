package com.twig.app

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.twig.core.XFile

/** Icon hints for direct package directories only; never probes paths or changes access routing. */
internal object AppPackageFolders {
    private val externalPackage = Regex("Android/(?:data|media|obb)/([^/]+)")
    private val externalPath = Regex(listOf(
        "/sdcard", "/mnt/sdcard",
        "/storage/(?:emulated/[0-9]+|self/primary|[A-Za-z0-9-]+)",
        "/mnt/media_rw/[A-Za-z0-9-]+",
        "/mnt/(?:user|pass_through)/[0-9]+/(?:primary|emulated/[0-9]+|[A-Za-z0-9-]+)",
        "/mnt/runtime/(?:default|read|write|full)/(?:emulated/[0-9]+|[A-Za-z0-9-]+)",
    ).joinToString("|", "(?:", ")/Android/(?:data|media|obb)/([^/]+)"))
    private val privatePath = Regex(listOf(
        "/data/data",
        "/data/(?:user|user_de)/[0-9]+",
        "/mnt/expand/[A-Za-z0-9-]+/(?:user|user_de)/[0-9]+",
        "/data_mirror/(?:data_ce|data_de)/[A-Za-z0-9-]+/[0-9]+",
    ).joinToString("|", "(?:", ")/([^/]+)"))

    fun packageOf(file: XFile): String? {
        if (!file.isDir) return null
        val pkg = when (file.scheme) {
            "file" -> {
                val path = file.path.trimEnd('/')
                if (path.split('/').any { it == "." || it == ".." }) return null
                val primary = Environment.getExternalStorageDirectory().path.trimEnd('/') + "/"
                privatePath.matchEntire(path)?.groupValues?.get(1)
                    ?: externalPath.matchEntire(path)?.groupValues?.get(1)
                    ?: if (path.startsWith(primary)) {
                        externalPackage.matchEntire(path.removePrefix(primary))?.groupValues?.get(1)
                    } else null
            }
            SafFileSystem.SCHEME -> {
                val id = runCatching {
                    val uri = Uri.parse(file.path)
                    if (uri.scheme == "content" && uri.authority == AndroidDataAccess.AUTHORITY)
                        DocumentsContract.getDocumentId(uri) else null
                }.getOrNull() ?: return null
                externalPackage.matchEntire(id.substringAfter(':', ""))?.groupValues?.get(1)
            }
            else -> null
        }
        return pkg?.takeIf { it != "." && it != ".." }
    }
}
