package com.twig.app

import android.content.Context
import android.os.Environment
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import android.util.AtomicFile
import com.twig.core.XFile
import com.twig.fs.local.LocalFileSystem
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/** Same-volume, unprivileged moves only. No copy/delete fallback, expiry, or background service. */
class TrashStore(
    private val ctx: Context,
    private val volumes: () -> List<File> = {
        listOf(Environment.getExternalStorageDirectory()) + StorageVolumes.cached(ctx).map { File(it.path) }
    },
    private val rename: (File, File) -> Boolean = TrashMoves::move,
) {
    data class Target(val file: XFile, val volume: File?)
    data class Entry(val volume: File, val directory: File, val original: String?, val deletedAt: Long) {
        val name: String get() = original?.let { File(it).name } ?: directory.name
    }
    data class Snapshot(val entries: List<Entry>, val damaged: List<String>)

    private fun fail(path: String): Nothing = throw IOException(ctx.getString(R.string.trash_failed, path))
    private fun stat(file: File) = try { Os.lstat(file.path) } catch (e: ErrnoException) {
        if (e.errno == OsConstants.ENOENT || e.errno == OsConstants.ENOTDIR) null else fail(file.path)
    }
    private fun exists(file: File) = stat(file) != null
    private fun inside(file: File, root: File) = file.path.startsWith(root.path + "/")
    private fun canonicalRoots() = volumes().mapNotNull { runCatching { it.canonicalFile }.getOrNull() }.distinct()
    private fun normalized(file: File): File = File(file.parentFile!!.canonicalFile, file.name)
    private fun inTrash(file: File, root: File) = file == File(root, DIRECTORY) || inside(file, File(root, DIRECTORY))

    /** Called on IO before confirmation; the resulting decision never upgrades itself to permanent deletion. */
    fun plan(files: List<XFile>): List<Target> = synchronized(lock) {
        val roots = canonicalRoots()
        val normalized = files.distinctBy { it.scheme to it.path }.map { file ->
            val local = if (file.scheme == "file") runCatching { normalized(File(file.path)) }.getOrNull() else null
            val root = local?.let { f -> roots.filter { inside(f, it) }.maxByOrNull { it.path.length } }
            val supported = root != null && !inTrash(local, root) &&
                LocalFileSystem.documentAccess?.invoke(file.path) == null &&
                root.canWrite() && local.parentFile?.canWrite() == true && exists(local) &&
                runCatching { Os.stat(local.parent).st_dev == Os.stat(root.path).st_dev }.getOrDefault(false)
            Target(if (local == null) file else file.copy(path = local.path), root.takeIf { supported })
        }
        normalized.filter { target ->
            normalized.none { parent -> parent !== target && parent.file.scheme == target.file.scheme &&
                parent.file.scheme == "file" && parent.file.isDir &&
                target.file.path.startsWith(parent.file.path.trimEnd('/') + "/") }
        }
    }

    private fun bucket(volume: File, create: Boolean): File {
        if (!volume.isDirectory || (create && !volume.canWrite())) fail(volume.path)
        val bucket = File(volume, DIRECTORY)
        if (!exists(bucket)) {
            if (!create || !bucket.mkdir()) fail(bucket.path)
            writeJson(File(bucket, MARKER), JSONObject().put("version", 1))
        }
        // Never adopt an unrelated directory or follow a .TwigTrash symlink.
        if (!bucket.isDirectory || bucket.canonicalFile != bucket.absoluteFile) fail(bucket.path)
        val version = try { readJson(File(bucket, MARKER)).getInt("version") }
            catch (e: Exception) { throw IOException(ctx.getString(R.string.trash_failed, bucket.path), e) }
        if (version != 1) fail(bucket.path)
        if (create && !File(bucket, ".nomedia").exists() && !File(bucket, ".nomedia").createNewFile()) fail(bucket.path)
        return bucket
    }

    private fun writeJson(file: File, value: JSONObject) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(value.toString().toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            atomic.finishWrite(stream) // flush + fsync before the source can move
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }
    private fun readJson(file: File): JSONObject = AtomicFile(file).openRead().use {
        if (file.canonicalFile != file.absoluteFile || file.length() > 64 * 1024) fail(file.path)
        JSONObject(it.bufferedReader().readText())
    }

    fun move(target: Target): Entry = synchronized(lock) {
        val volume = target.volume ?: fail(target.file.path)
        val checked = plan(listOf(target.file)).singleOrNull() ?: fail(target.file.path)
        if (checked.volume != volume) fail(target.file.path)
        val bucket = bucket(volume, true)
        val directory = File(bucket, UUID.randomUUID().toString())
        if (!directory.mkdir()) fail(directory.path)
        val entry = Entry(volume, directory, target.file.path, System.currentTimeMillis())
        writeJson(File(directory, INFO), JSONObject().put("original", entry.original).put("deletedAt", entry.deletedAt))
        val source = File(target.file.path)
        val payload = File(directory, PAYLOAD)
        // A failed rename retains both the source and its recovery record. Never copy or delete it instead.
        if (exists(payload) || !rename(source, payload)) fail(source.path)
        runCatching { LocalFileSystem.changed?.invoke(source.path) }
        entry
    }

    fun snapshot(): Snapshot = synchronized(lock) {
        val entries = ArrayList<Entry>()
        val damaged = ArrayList<String>()
        for (root in canonicalRoots()) {
            val dir = File(root, DIRECTORY)
            if (!root.isDirectory) continue
            if (!root.canRead()) { damaged += dir.path; continue }
            if (!exists(dir)) continue
            try {
                val bucket = bucket(root, false)
                val children = bucket.listFiles() ?: fail(bucket.path)
                for (child in children) {
                    if (child.name == MARKER || child.name == ".nomedia") continue
                    if (!validDirectory(child, bucket)) { damaged += child.path; continue }
                    val payload = File(child, PAYLOAD)
                    // Crash before the move, or after a completed restore: metadata alone is not a deleted file.
                    if (!exists(payload)) {
                        if (child.listFiles()?.any { it.name != INFO && it.name != "$INFO.bak" } == true) damaged += child.path
                        continue
                    }
                    val info = runCatching { readJson(File(child, INFO)) }.getOrNull()
                    val original = info?.optString("original")?.takeIf { validOriginal(it, root) }
                    entries += Entry(root, child, original, info?.optLong("deletedAt") ?: 0L)
                }
            } catch (_: Exception) { damaged += dir.path }
        }
        Snapshot(entries.sortedByDescending { it.deletedAt }, damaged)
    }

    private fun validDirectory(dir: File, bucket: File): Boolean =
        dir.parentFile == bucket && runCatching { UUID.fromString(dir.name).toString() == dir.name }.getOrDefault(false) &&
            OsConstants.S_ISDIR(stat(dir)?.st_mode ?: 0) && dir.canonicalFile == dir.absoluteFile

    private fun validOriginal(path: String, root: File): Boolean = runCatching {
        val file = File(path)
        file.isAbsolute && file.name != "." && file.name != ".." && normalized(file) == file &&
            inside(file, root) && !inTrash(file, root)
    }.getOrDefault(false)

    private fun checkEntry(entry: Entry) {
        val bucket = bucket(entry.volume, false)
        if (!validDirectory(entry.directory, bucket)) fail(entry.directory.path)
    }

    /** Validate every ancestor without following links; a final symlink may only be moved or purged. */
    fun payload(entry: Entry, relative: String = ""): File = synchronized(lock) {
        checkEntry(entry)
        val root = File(entry.directory, PAYLOAD)
        var current = root
        if (relative.isNotEmpty()) {
            val parts = relative.split('/')
            if (parts.any { it.isEmpty() || it == "." || it == ".." || '\u0000' in it }) fail(relative)
            for (part in parts) {
                if (!OsConstants.S_ISDIR(stat(current)?.st_mode ?: 0)) fail(current.path)
                current = File(current, part)
            }
        }
        if (!exists(current)) fail(current.path)
        current
    }

    fun originalPath(entry: Entry, relative: String = ""): String = synchronized(lock) {
        payload(entry, relative)
        val stored = readJson(File(entry.directory, INFO)).getString("original")
        if (stored != entry.original || !validOriginal(stored, entry.volume)) fail(entry.directory.path)
        if (relative.isEmpty()) stored else File(stored, relative).path
    }

    fun parentMissing(entry: Entry, relative: String = ""): Boolean = synchronized(lock) {
        !File(originalPath(entry, relative)).parentFile!!.isDirectory
    }

    fun restore(entry: Entry, name: String = entry.name, createParents: Boolean = false) =
        restoreItem(entry, "", name, createParents)

    /** A nested restore leaves its siblings and recovery record intact. Never overwrite an existing name. */
    fun restoreItem(entry: Entry, relative: String, name: String, createParents: Boolean = false) = synchronized(lock) {
        val source = payload(entry, relative)
        val stored = originalPath(entry, relative)
        if (name.isBlank() || name == "." || name == ".." || name.any { it == '/' || it == '\\' || it == '\u0000' }) fail(name)
        val parent = File(stored).parentFile!!
        // Validate before mkdirs too: an original ancestor may have become an external symlink.
        if (!validOriginal(File(parent, name).path, entry.volume)) fail(parent.path)
        if (!parent.isDirectory && (!createParents || !parent.mkdirs())) fail(parent.path)
        val destination = File(parent.canonicalFile, name)
        if (!validOriginal(destination.path, entry.volume) || LocalFileSystem.documentAccess?.invoke(destination.path) != null ||
            Os.stat(parent.path).st_dev != Os.stat(entry.volume.path).st_dev) fail(destination.path)
        if (exists(destination)) throw IOException(ctx.getString(R.string.batch_conflict, name))
        if (!rename(source, destination)) fail(destination.path)
        if (relative.isEmpty()) {
            // An interrupted cleanup leaves metadata only; snapshot ignores it.
            File(entry.directory, INFO).delete()
            entry.directory.delete()
        }
        runCatching { LocalFileSystem.changed?.invoke(destination.path) }
    }

    fun purgeItem(entry: Entry, relative: String) = synchronized(lock) {
        if (relative.isEmpty()) purge(entry) else deleteNoFollow(payload(entry, relative))
    }

    fun purge(entry: Entry) = synchronized(lock) {
        checkEntry(entry)
        // Keep the recovery record until all payload bytes have been removed successfully.
        val children = entry.directory.listFiles() ?: fail(entry.directory.path)
        children.filter { it.name != INFO && it.name != "$INFO.bak" }.forEach(::deleteNoFollow)
        children.filter { it.name == INFO || it.name == "$INFO.bak" }.forEach(::deleteNoFollow)
        if (!entry.directory.delete()) fail(entry.directory.path)
    }

    /** Deletes only entries that belonged to the confirmed snapshot; never recursively clears the volume. */
    fun empty(entries: List<Entry>) = synchronized(lock) { entries.forEach(::purge) }

    private fun deleteNoFollow(file: File) {
        val pending = ArrayDeque<Pair<File, Boolean>>()
        pending.addLast(file to false)
        while (pending.isNotEmpty()) {
            val (current, visited) = pending.removeLast()
            val st = stat(current) ?: continue
            if (OsConstants.S_ISDIR(st.st_mode) && !visited) {
                val children = current.listFiles() ?: fail(current.path)
                pending.addLast(current to true)
                children.forEach { pending.addLast(it to false) }
            } else if (!current.delete()) fail(current.path)
        }
    }

    companion object {
        /** Compare/sync must never copy or erase a volume's recovery store as ordinary content. */
        fun isManagedDirectory(file: XFile): Boolean {
            if (file.scheme != "file" || !file.isDir || file.name != DIRECTORY) return false
            return runCatching {
                val directory = File(file.path)
                if (directory.canonicalFile != directory.absoluteFile) return@runCatching false
                val marker = File(directory, MARKER)
                AtomicFile(marker).openRead().use { input ->
                    if (marker.canonicalFile != marker.absoluteFile) return@use false
                    val bytes = ByteArray(64 * 1024 + 1)
                    var length = 0
                    while (length < bytes.size) {
                        val n = input.read(bytes, length, bytes.size - length)
                        if (n <= 0) break
                        length += n
                    }
                    length < bytes.size && JSONObject(String(bytes, 0, length, Charsets.UTF_8)).optInt("version") > 0
                }
            }.getOrDefault(false)
        }

        const val DIRECTORY = ".TwigTrash"
        private const val MARKER = "format.json"
        private const val INFO = "info.json"
        private const val PAYLOAD = "data"
        private val lock = Any()
    }
}
