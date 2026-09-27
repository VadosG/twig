package com.twig.app

import com.twig.core.FsRegistry
import com.twig.core.XFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Content type of a file **with no extension**, read from its first bytes, given back as the
 * extension it would have had ("jpg", "mkv", "txt"…) so every extension-keyed rule downstream
 * works on it unchanged.
 *
 * ★ Only files with no extension at all. A file named `x.dat` or `x.bin` said what it is, even
 * if that is "anything"; second-guessing a name the user (or a program) chose costs more in
 * surprises than it wins. An extensionless file said nothing, so its bytes are the only answer.
 *
 * Deliberately left out: archives and apks — whether a row expands in the tree is decided by
 * extension in `Archives` / `PaneViewModel.expandableArchive`, and an archive icon on a row
 * that does not expand is a lie.
 *
 * All of it sits behind [Prefs.sniffTypes] (default off). Who sniffs: the row icon at bind time
 * (every source — over the network that is one 4 KB read per extensionless row that gets bound)
 * and a tap on the file. Thumbnails only ever read the cache ([com.twig.app.ui.Thumbs]); the
 * row asks for a rebind once its type is known, which is when they appear.
 *
 * Results are cached per file (including "nothing recognised") so the icon of an extensionless
 * row is sniffed once, not on every bind.
 */
object FileSniff {

    /** Enough for every magic below and for a fair text/binary call. */
    const val HEAD_BYTES = 4096

    private class Entry(val size: Long, val mtime: Long, val ext: String?)

    private val cache = ConcurrentHashMap<String, Entry>()

    private fun keyOf(file: XFile) = "${file.scheme}\u0000${file.path}"

    /** Forget every verdict — the preference was turned off. */
    fun clear() = cache.clear()

    /** Whether [file] is a candidate at all: a file (not a directory) with no extension. */
    fun applies(file: XFile): Boolean = !file.isDir && file.extension.isEmpty()

    /**
     * Cached verdict: the sniffed extension, `""` for "sniffed, nothing recognised", or null
     * for "not sniffed yet" (or sniffed while the file had a different size/time).
     *
     * A file rebuilt from an intent's scheme+path (the viewers do that) carries no size or
     * time; it takes whatever was sniffed for that path.
     */
    fun cached(file: XFile): String? {
        if (!applies(file)) return null
        val e = cache[keyOf(file)] ?: return null
        val known = file.size != 0L || file.lastModified != 0L
        if (known && (e.size != file.size || e.mtime != file.lastModified)) return null
        return e.ext ?: ""
    }

    /**
     * [file] as seen by extension-keyed rules once sniffed as [ext] — same scheme and path, so it
     * still resolves to the same bytes. For type decisions only; never show its name.
     */
    fun typedTwin(file: XFile, ext: String): XFile = file.copy(displayName = "${file.name}.$ext")

    /** Reads the head of [file] and caches the verdict. Blocking IO; worker thread only. */
    fun sniff(file: XFile): String? {
        if (!applies(file)) return null
        cached(file)?.let { return it.ifEmpty { null } }
        // ★ A FIFO or device node is "a file with no extension" too, and opening one blocks
        // forever — on the single icon thread that would stall every icon after it.
        if (file.scheme == "file" && !java.io.File(file.path).isFile) return null
        // A failed read is not cached: it says nothing about the bytes, and a dropped
        // connection would otherwise pin "unrecognised" on the file until it changes.
        val head = runCatching {
            FsRegistry.of(file).openInput(file).use { input ->
                val buf = ByteArray(HEAD_BYTES)
                var n = 0
                while (n < buf.size) {
                    val r = input.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
                buf.copyOf(n)
            }
        }.getOrNull() ?: return null
        val ext = detect(head)
        cache[keyOf(file)] = Entry(file.size, file.lastModified, ext)
        return ext
    }

    /** Magic-byte table. Pure; returns null when nothing is recognised. */
    fun detect(h: ByteArray): String? {
        if (h.isEmpty()) return null
        fun at(off: Int, vararg b: Int) =
            h.size >= off + b.size && b.indices.all { h[off + it].toInt() and 0xFF == b[it] }
        fun ascii(off: Int, s: String) = at(off, *s.map { it.code }.toIntArray())
        fun u8(i: Int) = h[i].toInt() and 0xFF
        return when {
            at(0, 0xFF, 0xD8, 0xFF) -> "jpg"
            at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "png"
            ascii(0, "GIF87a") || ascii(0, "GIF89a") -> "gif"
            ascii(0, "BM") && h.size >= 18 && u8(14) in BMP_DIB_SIZES && at(15, 0, 0, 0) -> "bmp"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "webp"
            ascii(0, "RIFF") && ascii(8, "AVI ") -> "avi"
            ascii(0, "RIFF") && ascii(8, "WAVE") -> "wav"
            ascii(4, "ftyp") && h.size >= 12 -> ftyp(String(h, 8, 4, Charsets.ISO_8859_1))
            at(0, 0x1A, 0x45, 0xDF, 0xA3) ->
                if (String(h, 0, minOf(h.size, 64), Charsets.ISO_8859_1).contains("webm")) "webm" else "mkv"
            ascii(0, "FLV") && at(3, 0x01) -> "flv"
            tsSync(h, 0, 188) -> "ts"
            tsSync(h, 4, 192) -> "m2ts"
            ascii(0, "fLaC") -> "flac"
            ascii(0, "OggS") -> "ogg"
            ascii(0, "MThd") -> "mid"
            ascii(0, "ID3") -> "mp3"
            // Before the frame-sync rules: a UTF-16LE BOM (FF FE) also parses as MPEG audio.
            at(0, 0xFF, 0xFE) || at(0, 0xFE, 0xFF) -> "txt"
            // Bare frame sync (12 set bits for ADTS, 11 for MPEG audio). ADTS always has
            // layer 00, which MPEG audio reserves, so the layer tells the two apart.
            h.size >= 2 && u8(0) == 0xFF && u8(1) and 0xF6 == 0xF0 -> "aac"
            h.size >= 2 && u8(0) == 0xFF && u8(1) and 0xE0 == 0xE0 && u8(1) and 0x06 != 0 -> "mp3"
            ascii(0, "%PDF-") -> "pdf"
            looksText(h) -> "txt"
            else -> null
        }
    }

    /** ISO BMFF: audio-only brands are m4a; HEIF/AVIF are images we do not decode everywhere. */
    private fun ftyp(brand: String): String? = when (brand) {
        "M4A ", "M4B ", "M4P " -> "m4a"
        "heic", "heix", "hevc", "mif1", "msf1", "avif", "avis" -> null
        "qt  " -> "mov"
        else -> "mp4"
    }

    /** MPEG-TS: sync byte 0x47 at [first] and every [stride] after it, at least twice. */
    private fun tsSync(h: ByteArray, first: Int, stride: Int): Boolean {
        if (h.size < first + stride + 1) return false
        var i = first
        while (i < h.size) {
            if (h[i].toInt() != 0x47) return false
            i += stride
        }
        return true
    }

    private val BMP_DIB_SIZES = setOf(12, 40, 52, 56, 64, 108, 124)

    /**
     * Text if it has no NUL byte and almost no C0 control characters (UTF-16, which is full of
     * NULs, is recognised by its BOM before this).
     * Not UTF-8 validation on purpose: GBK/Big5 files are text too, and [TextCodec] sorts
     * out which encoding when the viewer opens it.
     */
    private fun looksText(h: ByteArray): Boolean {
        var controls = 0
        for (b in h) {
            val c = b.toInt() and 0xFF
            if (c == 0) return false
            if (c < 0x20 && c != 0x09 && c != 0x0A && c != 0x0D && c != 0x0C && c != 0x1B) controls++
        }
        return controls * 100 <= h.size
    }
}
