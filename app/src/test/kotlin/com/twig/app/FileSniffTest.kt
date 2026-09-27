package com.twig.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Magic-byte detection for extensionless files ([FileSniff.detect]). */
class FileSniffTest {

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun ascii(s: String, pad: Int = 0) = (s.toByteArray(Charsets.ISO_8859_1) + ByteArray(pad))

    private fun riff(form: String) = ascii("RIFF") + bytes(0, 0, 0, 0) + ascii(form, 16)
    private fun ftyp(brand: String) = bytes(0, 0, 0, 0x20) + ascii("ftyp") + ascii(brand, 16)

    @Test
    fun `images`() {
        assertEquals("jpg", FileSniff.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10)))
        assertEquals("png", FileSniff.detect(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0)))
        assertEquals("gif", FileSniff.detect(ascii("GIF89a", 8)))
        assertEquals("webp", FileSniff.detect(riff("WEBP")))
        val bmp = ascii("BM") + ByteArray(12) + bytes(40, 0, 0, 0) + ByteArray(20)
        assertEquals("bmp", FileSniff.detect(bmp))
    }

    @Test
    fun `text starting with BM is still text`() {
        assertEquals("txt", FileSniff.detect(ascii("BM is a bitmap, and this is a sentence about it.\n")))
    }

    @Test
    fun `iso media brands split video, audio and unsupported images`() {
        assertEquals("mp4", FileSniff.detect(ftyp("isom")))
        assertEquals("mov", FileSniff.detect(ftyp("qt  ")))
        assertEquals("m4a", FileSniff.detect(ftyp("M4A ")))
        assertNull(FileSniff.detect(ftyp("heic")))
    }

    @Test
    fun `riff forms`() {
        assertEquals("avi", FileSniff.detect(riff("AVI ")))
        assertEquals("wav", FileSniff.detect(riff("WAVE")))
    }

    @Test
    fun `matroska and webm`() {
        val ebml = bytes(0x1A, 0x45, 0xDF, 0xA3, 0x9F, 0x42, 0x82, 0x84)
        assertEquals("mkv", FileSniff.detect(ebml + ascii("matroska", 16)))
        assertEquals("webm", FileSniff.detect(ebml + ascii("webm", 16)))
    }

    @Test
    fun `transport streams need repeated sync bytes`() {
        val ts = ByteArray(188 * 3).also { for (i in 0 until 3) it[i * 188] = 0x47 }
        assertEquals("ts", FileSniff.detect(ts))
        val m2ts = ByteArray(192 * 3).also { for (i in 0 until 3) it[i * 192 + 4] = 0x47 }
        assertEquals("m2ts", FileSniff.detect(m2ts))
        // One lone 'G' is a text file, not a stream.
        assertEquals("txt", FileSniff.detect(ascii("G".repeat(1) + "o away.\n")))
    }

    @Test
    fun `audio`() {
        assertEquals("mp3", FileSniff.detect(ascii("ID3", 8)))
        assertEquals("mp3", FileSniff.detect(bytes(0xFF, 0xFB, 0x90, 0x44)))
        assertEquals("aac", FileSniff.detect(bytes(0xFF, 0xF1, 0x50, 0x80)))
        assertEquals("flac", FileSniff.detect(ascii("fLaC", 8)))
        assertEquals("ogg", FileSniff.detect(ascii("OggS", 8)))
    }

    @Test
    fun `a UTF-16LE BOM is text, not an mp3 frame sync`() {
        val utf16 = bytes(0xFF, 0xFE) + "hello".toByteArray(Charsets.UTF_16LE)
        assertEquals("txt", FileSniff.detect(utf16))
    }

    @Test
    fun `pdf`() {
        assertEquals("pdf", FileSniff.detect(ascii("%PDF-1.7\n")))
    }

    @Test
    fun `text versus binary`() {
        assertEquals("txt", FileSniff.detect("#!/bin/sh\necho 你好\n".toByteArray()))
        assertEquals("txt", FileSniff.detect("中文".toByteArray(charset("GBK"))))
        // ELF: NUL bytes right in the header.
        assertNull(FileSniff.detect(bytes(0x7F, 0x45, 0x4C, 0x46, 2, 1, 1, 0, 0, 0)))
        assertNull(FileSniff.detect(ByteArray(0)))
    }

    @Test
    fun `only extensionless files apply`() {
        assertTrue(FileSniff.applies(com.twig.core.XFile("file", "/a/README", isDir = false)))
        assertTrue(FileSniff.applies(com.twig.core.XFile("file", "/a/.bashrc", isDir = false)))
        assertFalse(FileSniff.applies(com.twig.core.XFile("file", "/a/x.dat", isDir = false)))
        assertFalse(FileSniff.applies(com.twig.core.XFile("file", "/a/dir", isDir = true)))
    }
}
