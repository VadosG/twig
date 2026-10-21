package com.twig.app

import android.app.Application
import android.os.Environment
import android.provider.DocumentsContract
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], application = Application::class)
class AppPackageFoldersTest {
    private fun folder(path: String, scheme: String = "file") = XFile(scheme, path, true)

    @Test fun `external package icons cover storage aliases media data and expansion files`() {
        val roots = listOf(
            Environment.getExternalStorageDirectory().path, "/sdcard", "/mnt/sdcard",
            "/storage/emulated/0", "/storage/emulated/10", "/storage/self/primary",
            "/storage/ABCD-1234", "/mnt/media_rw/ABCD-1234",
            "/mnt/user/0/primary", "/mnt/user/10/emulated/10",
            "/mnt/pass_through/0/emulated/0", "/mnt/runtime/default/emulated/0",
            "/mnt/runtime/read/ABCD-1234", "/mnt/runtime/write/emulated/10",
            "/mnt/runtime/full/emulated/0",
        )
        for (root in roots) for (area in listOf("data", "media", "obb")) {
            val path = "$root/Android/$area/com.example"
            assertEquals(path, "com.example", AppPackageFolders.packageOf(folder(path)))
            assertNull(path, AppPackageFolders.packageOf(folder("$path/files")))
        }
    }

    @Test fun `private package directories cover multiple users adopted storage and mirrors`() {
        for (root in listOf(
            "/data/data", "/data/user/0", "/data/user/10", "/data/user_de/0", "/data/user_de/10",
            "/mnt/expand/1234-abcd/user/0", "/mnt/expand/1234-abcd/user_de/10",
            "/data_mirror/data_ce/null/0", "/data_mirror/data_de/null/10",
            "/data_mirror/data_ce/1234-abcd/0",
        )) {
            assertEquals(root, "com.example", AppPackageFolders.packageOf(folder("$root/com.example")))
            assertNull(root, AppPackageFolders.packageOf(folder("$root/com.example/cache")))
            assertNull(root, AppPackageFolders.packageOf(folder(root)))
        }
        assertEquals("android", AppPackageFolders.packageOf(folder("/data/user/0/android")))
    }

    @Test fun `external document IDs are decoded without treating other providers as local storage`() {
        for (volume in listOf("primary", "ABCD-1234")) for (area in listOf("data", "media", "obb")) {
            val id = "$volume:Android/$area/com.example"
            val tree = DocumentsContract.buildTreeDocumentUri(AndroidDataAccess.AUTHORITY, "$volume:Android/$area")
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
            assertEquals("com.example", AppPackageFolders.packageOf(folder(uri, "saf")))
            assertNull(AppPackageFolders.packageOf(folder(
                DocumentsContract.buildDocumentUriUsingTree(tree, "$id/files").toString(), "saf")))
            assertNull(AppPackageFolders.packageOf(folder(uri.replace(AndroidDataAccess.AUTHORITY, "other.provider"), "saf")))
        }
    }

    @Test fun `ordinary folders files remote paths and traversal do not get local app icons`() {
        for (path in listOf(
            "/Downloads/com.example", "/backup/Android/media/com.example", "/data/local/tmp/com.example",
            "/data/user/name/com.example", "/storage/emulated/0/Android/media",
            "/storage/emulated/0/Android/media/..", "/data/user/0/../com.example",
        )) assertNull(path, AppPackageFolders.packageOf(folder(path)))
        val valid = folder("/data/data/com.example")
        assertNull(AppPackageFolders.packageOf(valid.copy(isDir = false)))
        for (scheme in listOf("smb123", "sftp123", "zip"))
            assertNull(AppPackageFolders.packageOf(valid.copy(scheme = scheme)))
    }
}
