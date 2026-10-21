package com.twig.app.ui

import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.core.XFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
class ContentComparisonTest {
    private val registered = mutableListOf<String>()
    private val options = CompareOptions(contentLimitNetwork = Long.MAX_VALUE, contentOnlyIfTimeDiffers = false)

    @After fun tearDown() { registered.forEach(FsRegistry::unregister) }

    private fun register(scheme: String, input: () -> InputStream): FileSystem {
        val base = FakeFileSystem(scheme, mapOf("/" to listOf("data.bin")), mapOf("/data.bin" to "abc"))
        return object : FileSystem by base {
            override fun openInput(file: XFile) = input()
        }.also { FsRegistry.register(it); registered += scheme }
    }

    @Test fun `open and mid-read failures are errors and skipped by sync in both directions`() = runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        val failures: List<() -> InputStream> = listOf(
            { throw IOException("Permission denied") },
            { object : InputStream() {
                private var delivered = false
                override fun read(): Int = throw IOException("Connection lost")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (delivered) throw IOException("Connection lost")
                    delivered = true
                    b[off] = 97
                    return 1
                }
            } },
        )
        for ((index, fail) in failures.withIndex()) {
            val bad = register("bad$index", fail)
            val good = register("good$index") { "abc".byteInputStream() }
            for ((left, right) in listOf(bad to good, good to bad)) {
                val events = scanCompare(left.root(), right.root(), options, io).toList()
                val row = events.filterIsInstance<CompareEvent.Children>().single().rows.single()
                assertEquals(PairState.ERROR, row.state)
                for (from in 0..1) {
                    val plan = buildSyncPlan(left.root(), right.root(), options, from, io)
                    assertEquals(listOf("data.bin"), plan.unreadable)
                    assertTrue(plan.copies.isEmpty())
                    assertTrue(plan.deletes.isEmpty())
                }
            }
        }
    }

    @Test fun `cancellation propagates instead of becoming a difference or read error`() = runTest {
        val bad = register("cancelled") { throw CancellationException("Stopped by user") }
        val good = register("good") { "abc".byteInputStream() }
        try {
            compareFiles(bad.resolve("/data.bin"), good.resolve("/data.bin"), options)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun `large files stream in bounded blocks and tolerate different short reads`() = runTest {
        val size = (20L shl 20) + 7
        var largestRead = 0
        var closed = 0
        fun input(chunk: Int, changeLastByte: Boolean = false) = object : InputStream() {
            var position = 0L
            override fun read(): Int = error("Comparison should use block reads")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                largestRead = maxOf(largestRead, len)
                if (position == size) return -1
                val count = minOf(len.toLong(), chunk.toLong(), size - position).toInt()
                b.fill(42, off, off + count)
                position += count
                if (changeLastByte && position == size) b[off + count - 1] = 43
                return count
            }
            override fun close() { closed++ }
        }
        register("largeL") { input(8191) }
        register("largeR") { input(16381) }
        val l = XFile("largeL", "/data.bin", false, size)
        val r = XFile("largeR", "/data.bin", false, size)
        assertFalse(contentComparable(l, r, options.copy(contentLimitNetwork = 16L shl 20)))
        assertTrue(contentComparable(l, r, options))
        assertEquals(PairState.SAME, compareFiles(l, r, options))
        register("changed") { input(4093, changeLastByte = true) }
        assertEquals(PairState.DIFF, compareFiles(l, r.copy(scheme = "changed"), options))
        assertTrue("The request size must stay bounded independently of file size", largestRead <= 64 * 1024)
        assertEquals(4, closed)
    }

    @Test fun `streams are closed when reading the second side fails`() = runTest {
        var closed = false
        val left = register("left") { object : java.io.ByteArrayInputStream("abc".toByteArray()) {
            override fun close() { closed = true }
        } }
        val right = register("right") { throw IOException("Permission denied") }
        assertEquals(PairState.ERROR, compareFiles(left.resolve("/data.bin"), right.resolve("/data.bin"), options))
        assertTrue(closed)
    }
}
