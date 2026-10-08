package com.twig.app.ui

import com.twig.core.FsRegistry
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import com.twig.fs.local.priv.JvmPrivilegedProcess
import com.twig.fs.local.priv.PrivilegedFs
import com.twig.fs.local.priv.PrivilegedLauncher
import com.twig.fs.local.priv.PrivilegedShell
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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
    fun `unreadable subtree uses one privileged scan and folds streamed directory sizes`() = runBlocking {
        FsRegistry.register(LocalFileSystem())
        // The host cannot model Android's permission asymmetry. Supply the shell
        // side's records for a path the ordinary File API cannot list.
        val root = File(temp.root, "denied")
        val records = listOf(
            "drwxr-xr-x|0|1700000000.0|$root/sub",
            "drwxr-xr-x|0|1700000000.0|$root/sub/deep",
            "-rw-r--r--|7|1700000000.0|$root/sub/deep/data",
            "-rw-r--r--|3|1700000000.0|$root/small",
            "drwxr-xr-x|0|1700000000.0|$root/empty",
        ).joinToString("\\0", postfix = "\\0")
        var launches = 0
        val shell = PrivilegedShell(PrivilegedLauncher { cmd ->
            if (cmd == null) JvmPrivilegedProcess(ProcessBuilder("/bin/sh").start()) else {
                assertTrue(cmd.startsWith("exec find -H "))
                launches++
                JvmPrivilegedProcess(ProcessBuilder("/bin/sh", "-c",
                    "printf '%b' ${PrivilegedShell.quote(records)}").start())
            }
        })
        LocalFileSystem.elevation = PrivilegedFs(shell)
        try {
            val target = XFile("file", root.path, true)
            val scanner = TreemapScanner(temp.root)
            val tree = scanner.scanRoot(target)
            assertEquals(10L, tree.size)
            assertEquals(10L, scanner.bytes)
            assertEquals(5, scanner.scanned)
            assertEquals(listOf("sub", "small", "empty"), tree.children!!.map { it.name })
            assertEquals(7L, tree.children!![0].children!!.single().size)
            assertEquals(1, launches)
            assertEquals(DirStat(files = 2, dirs = 3, bytes = 10), scanDirStat(target).toList().last())
            assertEquals(2, launches)
            LocalFileSystem.withoutElevation {
                assertTrue(TreemapScanner(temp.root).scanRoot(target).children!!.isEmpty())
            }
            assertEquals("opt-out must not reach the shell", 2, launches)
        } finally {
            LocalFileSystem.elevation = null
            shell.close()
        }
    }

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
