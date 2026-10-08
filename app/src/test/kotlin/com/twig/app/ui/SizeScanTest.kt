package com.twig.app.ui

import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class SizeScanTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `fast local scan preserves nested totals and descending size order`() {
        FsRegistry.register(LocalFileSystem())
        val root = temp.newFolder("scan")
        File(root, "small").writeText("123")
        val sub = File(root, "sub").apply { mkdir() }
        File(sub, "big").writeText("1234567890")
        File(sub, "empty").mkdir()
        val scanner = TreemapScanner(temp.root)
        val tree = scanner.scanRoot(XFile("file", root.path, true))

        assertEquals(13L, tree.size)
        assertEquals(13L, scanner.bytes)
        assertEquals(4, scanner.scanned)
        assertEquals(listOf("sub", "small"), tree.children!!.map { it.name })
        assertEquals(listOf(10L, 0L), tree.children!![0].children!!.map { it.size })
        assertTrue(tree.children!![0].children!![1].isDir)
    }

    @Test
    fun `action metadata restores real local permissions and missing files cannot be modified`() {
        val fs = LocalFileSystem()
        FsRegistry.register(fs)
        val root = temp.newFolder("permissions")
        val file = File(root, "data").apply { writeText("abc") }
        val scanned = listForSize(XFile("file", root.path, true)).single()
        assertEquals(fs.stat(file.path), sizeScanActionFile(scanned))
        file.delete()
        val missing = sizeScanActionFile(scanned)
        assertEquals(false, missing.canRead)
        assertEquals(false, missing.canWrite)
    }

    @Test
    fun `remote scan continues using backend listing without opening files`() {
        val fs = FakeFileSystem("size-test", mapOf("/" to listOf("sub", "a"),
            "/sub" to listOf("b")), mapOf("/a" to "123", "/sub/b" to "12345"))
        FsRegistry.register(fs)
        try {
            val scanner = TreemapScanner(temp.root)
            val tree = scanner.scanRoot(fs.root())
            assertEquals(8L, tree.size)
            assertEquals(3, scanner.scanned)
            assertEquals(listOf("/", "/sub"), fs.listed)
            assertTrue(fs.opened.isEmpty())
        } finally {
            FsRegistry.unregister(fs.scheme)
        }
    }
}
