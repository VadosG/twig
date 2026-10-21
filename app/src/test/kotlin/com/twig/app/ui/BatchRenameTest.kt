package com.twig.app.ui

import com.twig.core.FileSystem
import com.twig.core.FsRegistry
import com.twig.core.XFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BatchRenameTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun file(name: String, dir: Boolean = false) = XFile("batch", "/$name", dir)
    private fun generate(
        files: List<XFile>, part: BatchRenameNames.Part = BatchRenameNames.Part.NAME,
        numbering: Boolean = false, find: String = "old", replacement: String = "new",
        regex: Boolean = false, matchCase: Boolean = false, width: Int = 1,
        start: String = "1", numberFirst: Boolean = false,
        prefix: String = "", suffix: String = "", letterCase: BatchRenameNames.LetterCase = BatchRenameNames.LetterCase.KEEP,
        template: String? = null,
    ) = BatchRenameNames.generate(files, part, numbering, find, replacement, regex, matchCase, width, start, numberFirst, prefix, suffix, letterCase, template)

    @Test fun `preview columns stay aligned on first measurement and after rebinding long names`() {
        val row = BatchRenameDialog.NameColumns(context)
        val before = android.widget.TextView(context)
        val after = android.widget.TextView(context)
        row.addView(before, android.widget.LinearLayout.LayoutParams(0, -2))
        row.addView(after, android.widget.LinearLayout.LayoutParams(0, -2))
        for ((old, new) in listOf("a" to "a very long new filename.txt",
            "a very long original filename that wraps onto several lines.txt" to "new.txt",
            "short.txt" to "another.txt")) {
            before.text = old
            after.text = new
            for (mode in listOf(android.view.View.MeasureSpec.AT_MOST, android.view.View.MeasureSpec.EXACTLY)) {
                row.measure(android.view.View.MeasureSpec.makeMeasureSpec(401, mode),
                    android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
                row.layout(0, 0, row.measuredWidth, row.measuredHeight)
                assertEquals(401, row.measuredWidth)
                assertEquals(200, after.left)
                assertEquals(before.top, after.top)
                assertEquals(200, before.width)
                assertEquals(201, after.width)
            }
        }
    }

    @Test fun `replacement respects scope and case without changing extension capitalization`() {
        val files = listOf(file("Old.old"), file("old.OLD"))
        assertEquals(listOf("new.old", "new.OLD"), generate(files))
        assertEquals(listOf("Old.old", "new.OLD"), generate(files, matchCase = true))
        assertEquals(listOf("Old.new", "old.new"), generate(files, BatchRenameNames.Part.EXTENSION))
        assertEquals(listOf("new.new", "new.new"), generate(files, BatchRenameNames.Part.FULL))
    }

    @Test fun `regex replacement supports capture groups and literal replacement preserves dollar signs`() {
        assertEquals(listOf("Episode 07.MKV"), generate(listOf(file("ep07.MKV")), find = "ep([0-9]+)",
            replacement = "Episode $1", regex = true))
        assertEquals(listOf("$1.txt"), generate(listOf(file("old.txt")), replacement = "$1"))
        assertTrue(runCatching { generate(listOf(file("old.txt")), find = "[", regex = true) }.isFailure)
    }

    @Test fun `numbering follows input order from padded start and preserves extension`() {
        val files = listOf(file("z.JPG"), file("a.JPG"))
        assertEquals(listOf("z - 0004.JPG", "a - 0005.JPG"), generate(files, numbering = true, width = 4, start = "0004"))
        assertEquals(listOf("04 - z.JPG", "05 - a.JPG"), generate(files, numbering = true, width = 2, start = "0004", numberFirst = true))
        assertEquals(listOf("z - 9999.JPG", "a - 10000.JPG"), generate(files, numbering = true, width = 4, start = "9999"))
    }

    @Test fun `dotfiles and dotted directories keep their full names and extension-only skips directories`() {
        val files = listOf(file(".old"), file("old.dir", true), file("old"))
        assertEquals(listOf(".new", "new.dir", "new"), generate(files))
        assertEquals(listOf(".old", "old.dir", "old"), generate(files, BatchRenameNames.Part.EXTENSION))
        listOf("", ".", "..", "a/b", "a\\b", "a\u0000b").forEach { assertFalse(BatchRenameNames.valid(it)) }
    }

    @Test fun `switching rename methods ignores hidden invalid rules and validates template numbers`() {
        val activity = org.robolectric.Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        try {
            BatchRenameDialog.show(activity, activity, listOf(file("Old.JPG")), { null }) {}
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            fun descendants(view: android.view.View): List<android.view.View> = listOf(view) +
                if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
                else emptyList()
            val views = descendants(dialog.window!!.decorView)
            val spinners = views.filterIsInstance<android.widget.Spinner>()
            val method = spinners.single { it.adapter.getItem(0) == activity.getString(com.twig.app.R.string.batch_replace) }
            val fields = views.filterIsInstance<android.widget.EditText>()
            fun byHint(id: Int) = fields.single { it.hint?.toString() == activity.getString(id) }
            val regex = views.filterIsInstance<android.widget.CheckBox>().single {
                it.text.toString() == activity.getString(com.twig.app.R.string.batch_regex)
            }
            val apply = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            fun mode(index: Int) {
                method.setSelection(index)
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            }
            regex.isChecked = true
            byHint(com.twig.app.R.string.batch_find).setText("[")
            assertFalse(apply.isEnabled)
            mode(3)
            assertTrue("Hidden invalid regex must not block case conversion", apply.isEnabled)
            val letterCase = spinners.single { it.adapter.getItem(0) == activity.getString(com.twig.app.R.string.batch_lowercase) }
            assertEquals(activity.getString(com.twig.app.R.string.batch_titlecase), letterCase.adapter.getItem(2))
            letterCase.setSelection(2)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse("Old.JPG is already title case with the extension excluded", apply.isEnabled)
            mode(2)
            byHint(com.twig.app.R.string.batch_prefix).setText("pre_")
            assertTrue(apply.isEnabled)
            mode(4)
            val template = fields.single { it.text.toString() == "{name}{ext}" }
            val start = fields.single { it.inputType == android.text.InputType.TYPE_CLASS_NUMBER }
            start.setText("")
            template.setText("{name}_copy{ext}")
            assertTrue("A template without numbering needs no start value", apply.isEnabled)
            template.setText("{number}_{name}{ext}")
            assertFalse(apply.isEnabled)
            start.setText("7")
            assertTrue(apply.isEnabled)
            template.setText("{unknown}")
            assertFalse(apply.isEnabled)
            dialog.dismiss()
        } finally { activity.finish() }
    }

    @Test fun `affixes apply to the selected part and preserve extension and literal tokens`() {
        val files = listOf(file("Photo.JPG"), file(".hidden"), file("Folder.v1", true))
        assertEquals(listOf("pre_Photo_done.JPG", "pre_.hidden_done", "pre_Folder.v1_done"),
            generate(files, find = "", prefix = "pre_", suffix = "_done"))
        assertEquals(listOf("Photo.JPG.bak"), generate(files.take(1), BatchRenameNames.Part.FULL, find = "", suffix = ".bak"))
        assertEquals(listOf("Photo.xJPG", ".hidden.x", "Folder.v1"),
            generate(files, BatchRenameNames.Part.EXTENSION, find = "", prefix = "x"))
        assertEquals(listOf("{name}Photo.JPG"), generate(files.take(1), find = "", prefix = "{name}"))
    }

    @Test fun `case conversion is independent of device locale and respects scope`() {
        val previous = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
        try {
            val files = listOf(file("IMAGE.JPG"), file("image.jpg"))
            assertEquals(listOf("image.JPG", "image.jpg"), generate(files, letterCase = BatchRenameNames.LetterCase.LOWER))
            assertEquals(listOf("IMAGE.JPG", "IMAGE.jpg"), generate(files, letterCase = BatchRenameNames.LetterCase.UPPER))
            assertEquals(listOf("IMAGE.jpg", "image.jpg"), generate(files, BatchRenameNames.Part.EXTENSION,
                letterCase = BatchRenameNames.LetterCase.LOWER))
        } finally { java.util.Locale.setDefault(previous) }
    }

    @Test fun `title case keeps minor words lowercase except at title and subtitle boundaries`() {
        val cases = linkedMapOf(
            "THE LORD OF THE RINGS" to "The Lord of the Rings",
            "a walk in the woods" to "A Walk in the Woods",
            "what is it good for" to "What Is It Good For",
            "what is it for: an introduction to the subject" to "What Is It For: An Introduction to the Subject",
            "a journey through the woods" to "A Journey Through the Woods",
            "the art of war — a guide to strategy" to "The Art of War — A Guide to Strategy",
            "to be or not to be" to "To Be or Not to Be",
            "the" to "The",
        )
        assertEquals(cases.values.map { "$it.MKV" }, generate(cases.keys.map { file("$it.MKV") },
            letterCase = BatchRenameNames.LetterCase.TITLE))
    }

    @Test fun `title case preserves filename separators contractions and selected scope`() {
        val cases = linkedMapOf(
            "state-of-the-art" to "State-of-the-Art",
            "the_lord_of_the_rings" to "The_Lord_of_the_Rings",
            "  (THE ROAD TO SUCCESS!)  " to "  (The Road to Success!)  ",
            "DON’T PANIC" to "Don’t Panic",
            "IT'S A WONDERFUL LIFE" to "It's a Wonderful Life",
            "中文 and ENGLISH" to "中文 and English",
            "..." to "...",
            "123 - the road to nowhere" to "123 - The Road to Nowhere",
        )
        assertEquals(cases.values.toList(), generate(cases.keys.map { file(it, true) },
            letterCase = BatchRenameNames.LetterCase.TITLE))
        assertEquals(listOf("report.Txt"), generate(listOf(file("report.TXT")), BatchRenameNames.Part.EXTENSION,
            letterCase = BatchRenameNames.LetterCase.TITLE))
        val previous = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
        try {
            assertEquals(listOf("Image in the City.JPG"), generate(listOf(file("IMAGE IN THE CITY.JPG")),
                letterCase = BatchRenameNames.LetterCase.TITLE))
        } finally { java.util.Locale.setDefault(previous) }
    }

    @Test fun `templates preserve literal names and expand padded sequence and modification date`() {
        val time = java.util.GregorianCalendar(2026, java.util.Calendar.OCTOBER, 21, 12, 0).timeInMillis
        val files = listOf(file("Photo.JPG"), file("README"), file("Folder.v1", true), file("{number}.txt"))
            .map { it.copy(lastModified = time) }
        assertEquals(files.map { it.name }, generate(files, template = "{name}{ext}"))
        assertEquals(listOf("20261021_09_Photo.JPG", "20261021_10_README", "20261021_11_Folder.v1", "20261021_12_{number}.txt"),
            generate(files, width = 2, start = "9", template = "{date}_{number}_{name}{ext}"))
        for (invalid in listOf("{unknown}", "{name", "name}", "{{name}}")) {
            assertTrue(runCatching { generate(files, template = invalid) }.isFailure)
        }
        assertTrue(runCatching { generate(files, start = "-1", template = "{number}{ext}") }.isFailure)
        assertTrue(runCatching { generate(listOf(file("no-date")), template = "{date}") }.isFailure)
        assertEquals(listOf("no-date"), generate(listOf(file("no-date")), start = "", template = "{name}{ext}"))
    }

    /** Opaque handles ensure no path slicing can accidentally pass a parent or conflict check. */
    private class WritableFs : FileSystem by FakeFileSystem("batch", emptyMap()) {
        val parent = XFile("batch", "content://tree/document/root%2Ffolder", true, displayName = "folder")
        var entries = listOf("old-a.txt", "old-b.txt", "taken.txt").mapIndexed { i, name ->
            XFile("batch", "content://tree/document/item$i", false, displayName = name)
        }
        val renamed = mutableListOf<Pair<XFile, String>>()
        var failListing = false
        var failSecond = false
        override fun writable() = true
        override fun list(dir: XFile): List<XFile> {
            check(dir == parent)
            check(!failListing) { "listing failed" }
            return entries
        }
        override fun parentOf(file: XFile): XFile? = null
        override fun rename(file: XFile, newName: String): XFile {
            check(!failSecond || renamed.isEmpty()) { "connection lost" }
            renamed += file to newName
            return file.copy(displayName = newName)
        }
    }

    private fun withFs(block: (WritableFs) -> Unit) {
        val fs = WritableFs()
        FsRegistry.register(fs)
        try { block(fs) } finally { FsRegistry.unregister(fs.scheme) }
    }

    @Test fun `all conflicts and failed listings are rejected before any rename`() = withFs { fs ->
        val files = fs.entries.take(2)
        val parents = files.associateWith { fs.parent }
        for (names in listOf(listOf("safe.txt", "TAKEN.txt"), listOf("same.txt", "same.txt"))) {
            assertTrue(runCatching { BatchRenameDialog.execute(context, files, names, parents) {} }.isFailure)
            assertTrue(fs.renamed.isEmpty())
        }
        fs.failListing = true
        assertTrue(runCatching { BatchRenameDialog.execute(context, files, listOf("a.txt", "b.txt"), parents) {} }.isFailure)
        assertTrue(fs.renamed.isEmpty())
    }

    @Test fun `execution uses preview names with opaque handles and reports partial completion`() = withFs { fs ->
        val files = fs.entries.take(2)
        val names = generate(files, numbering = true, width = 3, start = "4")
        fs.failSecond = true
        var completed = 0
        val result = runCatching { BatchRenameDialog.execute(context, files, names, files.associateWith { fs.parent }) { completed++ } }
        assertTrue(result.isFailure)
        assertEquals(1, completed)
        assertEquals(listOf(files[0] to "old-a - 004.txt"), fs.renamed)
    }

    @Test fun `successful batch uses both preview names and skips unchanged entries`() = withFs { fs ->
        val files = fs.entries
        var completed = 0
        BatchRenameDialog.execute(context, files, listOf("a.txt", "b.txt", "taken.txt"), files.associateWith { fs.parent }) { completed++ }
        assertEquals(2, completed)
        assertEquals(listOf(files[0] to "a.txt", files[1] to "b.txt"), fs.renamed)
    }

    @Test fun `readonly selection is rejected before mutating any writable entries`() = withFs { fs ->
        val files = fs.entries.take(2).mapIndexed { i, file -> if (i == 1) file.copy(canWrite = false) else file }
        assertTrue(runCatching {
            BatchRenameDialog.execute(context, files, listOf("a.txt", "b.txt"), files.associateWith { fs.parent }) {}
        }.isFailure)
        assertTrue(fs.renamed.isEmpty())
    }

    @Test fun `selected ancestor is rejected even across an unselected middle folder`() = withFs { fs ->
        val ancestor = XFile("batch", "opaque:grandparent", true, displayName = "ancestor")
        val root = XFile("batch", "opaque:root", true)
        val files = listOf(ancestor, fs.entries.first())
        // A second backend view supplies listings for the ancestor's parent too.
        val original = fs
        FsRegistry.register(object : FileSystem by original {
            override fun list(dir: XFile) = if (dir == root) listOf(ancestor) else original.list(dir)
        })
        val parents = mapOf(files[1] to fs.parent, fs.parent to ancestor, ancestor to root)
        assertTrue(runCatching { BatchRenameDialog.execute(context, files, listOf("renamed", "new.txt"), parents) {} }.isFailure)
        assertTrue(fs.renamed.isEmpty())
    }
}
