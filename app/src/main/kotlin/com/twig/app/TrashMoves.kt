package com.twig.app

import java.io.File

/** A check followed by File.renameTo can overwrite a destination created between the two calls. */
internal object TrashMoves {
    @Volatile private var loaded = false

    fun move(source: File, destination: File): Boolean {
        if (!loaded) synchronized(this) {
            if (!loaded) { System.loadLibrary("twigpty"); loaded = true }
        }
        // JNI's modified UTF-8 breaks supplementary characters in filenames; pass actual UTF-8 bytes.
        return nativeRename(source.path.toByteArray(Charsets.UTF_8), destination.path.toByteArray(Charsets.UTF_8)) == 0
    }

    /** Linux errno on failure; unsupported kernels/filesystems fail safely without a copy or overwrite fallback. */
    private external fun nativeRename(source: ByteArray, destination: ByteArray): Int
}
