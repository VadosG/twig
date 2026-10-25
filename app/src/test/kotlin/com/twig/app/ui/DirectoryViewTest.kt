package com.twig.app.ui

import android.app.Application
import android.content.Context
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.twig.app.*
import com.twig.core.FsRegistry
import com.twig.core.XFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DirectoryViewTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()
    private lateinit var vm: PaneViewModel
    private lateinit var dir: XFile
    private val reverse = SortSpec(reversed = true)

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        for (name in listOf("twig_sort", "twig_prefs", DirectoryViewStore.FILE)) {
            app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        val conn = SavedConnection(type = "ftp", host = "directory-views.test", user = "u")
        ConnectionStore.save(app, conn)
        val scheme = Connections.schemeOf(conn)
        FsRegistry.register(FakeFileSystem(scheme,
            dirs = mapOf("/" to listOf("photos", "z.txt", "a.txt"),
                "/photos" to listOf("child", "z.txt", "a.txt"),
                "/photos/child" to listOf("z.txt", "a.txt")),
            files = listOf("/z.txt", "/a.txt", "/photos/z.txt", "/photos/a.txt",
                "/photos/child/z.txt", "/photos/child/a.txt").associateWith { "x" }))
        dir = XFile(scheme, "/photos", isDir = true)
        vm = PaneViewModel(app).apply { io = dispatcher }
    }

    @After
    fun teardown() { Dispatchers.resetMain() }

    private fun files(path: String) = vm.state.value.rows.filterIsInstance<PaneViewModel.FileNode>()
        .filter { !it.file.isDir && it.file.scheme == dir.scheme && it.file.parentPath == path }

    @Test
    fun `parent and child keep independent sort and actual rendered view`() = runTest(dispatcher) {
        DirectoryViewStore.save(app, dir, DirectoryView(reverse, 2))
        vm.refreshSortAndView()
        vm.revealPath(dir.copy(path = "/photos/child"))
        advanceUntilIdle()
        assertEquals(listOf("z.txt", "a.txt"), files("/photos").map { it.file.name })
        assertEquals(listOf("a.txt", "z.txt"), files("/photos/child").map { it.file.name })
        val parent = files("/photos").first()
        val child = files("/photos/child").first()
        val adapter = FileAdapter(1, {}, {}, {}, { _, _ -> }, {})
        adapter.submitList(listOf(parent, child))
        assertTrue(adapter.isCellAt(0))
        assertFalse(adapter.isCellAt(1))
        val container = FrameLayout(ContextThemeWrapper(app, R.style.Theme_Twig))
        val cell = adapter.createViewHolder(container, adapter.getItemViewType(0))
        val row = adapter.createViewHolder(container, adapter.getItemViewType(1))
        adapter.bindViewHolder(cell, 0)
        adapter.bindViewHolder(row, 1)
        assertNotEquals(cell.itemView.javaClass, row.itemView.javaClass)
    }

    @Test
    fun `sort-only override follows changed default view and reset removes the record`() = runTest(dispatcher) {
        DirectoryViewStore.save(app, dir, DirectoryView(sort = reverse))
        vm.refreshSortAndView()
        vm.revealPath(dir)
        advanceUntilIdle()
        assertEquals(listOf("z.txt", "a.txt"), files("/photos").map { it.file.name })
        assertTrue(files("/photos").all { it.gridMode == 0 })
        Prefs.setThumbsGrid(app, 2)
        vm.refreshSortAndView()
        assertTrue(files("/photos").all { it.gridMode == 2 })
        DirectoryViewStore.save(app, dir, DirectoryView())
        vm.refreshSortAndView()
        assertEquals(listOf("a.txt", "z.txt"), files("/photos").map { it.file.name })
        assertTrue(app.getSharedPreferences(DirectoryViewStore.FILE, Context.MODE_PRIVATE).all.isEmpty())
    }

    @Test
    fun `server shortcut and favorite use the same underlying directory settings`() = runTest(dispatcher) {
        val root = dir.copy(path = "/")
        DirectoryViewStore.save(app, root, DirectoryView(reverse, 2))
        vm.refreshSortAndView()
        vm.revealPath(root)
        advanceUntilIdle()
        assertEquals(listOf("z.txt", "a.txt"), files("/").map { it.file.name })
        assertTrue(files("/").all { it.gridMode == 2 })
        val conn = ConnectionStore.all(app).first { Connections.schemeOf(it) == dir.scheme }
        val fav = Favorite(label = "photos", kind = "conn", path = "/", connLabel = conn.label())
        FavoritesStore.add(app, fav)
        vm = PaneViewModel(app).apply { io = dispatcher }
        vm.bootstrap(listOf("group\tfav"))
        advanceUntilIdle()
        val node = vm.state.value.rows.filterIsInstance<PaneViewModel.FavoriteNode>().first { it.fav.id == fav.id }
        vm.toggleFavorite(node, password = null) { _, _ -> }
        advanceUntilIdle()
        assertEquals(listOf("z.txt", "a.txt"), files("/").map { it.file.name })
        assertTrue(files("/").all { it.gridMode == 2 })
    }

    @Test
    fun `records survive reload and distinguish backends and exact directory paths`() {
        DirectoryViewStore.save(app, dir.copy(path = "/photos/"), DirectoryView(reverse, 2))
        val loaded = DirectoryViewStore.load(app)
        assertEquals(DirectoryView(reverse, 2), DirectoryViewStore.get(loaded, dir))
        assertNull(DirectoryViewStore.get(loaded, dir.copy(scheme = "other")))
        assertNull(DirectoryViewStore.get(loaded, dir.copy(path = "/photos/child")))
        assertNull(DirectoryViewStore.get(loaded, dir.copy(path = "/photos2")))
    }
}
