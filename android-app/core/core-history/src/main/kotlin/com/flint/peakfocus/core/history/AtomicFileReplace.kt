package com.flint.peakfocus.core.history

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Crash-safe replacement for app-private history files on API 23+.
 *
 * A completed temporary file is promoted in place when the filesystem supports
 * atomic rename. Otherwise the bytes are staged, synchronised and verified in
 * the same directory, the previous file is moved aside, and only then is the
 * staged file promoted. The previous valid file is restored if promotion fails,
 * so a partial copy can never become the visible history.
 */
internal object AtomicFileReplace {
    /** Test seams: production uses the platform file API. */
    internal var rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) }
    internal var copy: (File, File) -> Unit = ::copyAndSync

    fun replace(temporary: File, target: File): Boolean {
        if (rename(temporary, target)) return true

        val staged = sibling(target, ".staged")
        val backup = sibling(target, ".bak")
        staged.delete()
        // Only a backup this call moved aside may be deleted afterwards. A pre-existing
        // backup can be the only valid copy after an interrupted recovery.
        var movedTarget = false
        try {
            copy(temporary, staged)
            check(staged.length() == temporary.length()) { "Staged history write is incomplete" }
            if (target.exists()) {
                if (!rename(target, backup)) {
                    throw IOException("Could not move the previous history file aside")
                }
                movedTarget = true
            }
            if (!rename(staged, target)) {
                if (movedTarget) rename(backup, target)
                throw IOException("Could not promote the staged history file")
            }
            if (movedTarget) backup.delete()
            temporary.delete()
            return true
        } catch (failure: Exception) {
            staged.delete()
            if (movedTarget && !target.exists() && backup.exists()) rename(backup, target)
            temporary.delete()
            if (failure is IOException) throw failure
            throw IOException("History replacement failed", failure)
        }
    }

    /**
     * Restore a valid file left behind by an interrupted replacement.
     *
     * @return false when a backup exists but cannot be restored yet. The backup is kept, so
     * callers must treat the file as unreadable instead of starting from an empty history.
     */
    fun recover(target: File): Boolean {
        val staged = sibling(target, ".staged")
        val backup = sibling(target, ".bak")
        val temporary = sibling(target, ".tmp")
        if (!target.exists() && backup.exists()) {
            // Never delete the only copy.
            if (!rename(backup, target)) return false
        }
        if (target.exists()) {
            backup.delete()
            staged.delete()
            temporary.delete()
        }
        return true
    }

    private fun sibling(target: File, suffix: String) = File(target.parentFile, target.name + suffix)

    private fun copyAndSync(from: File, to: File) {
        FileInputStream(from).use { input ->
            FileOutputStream(to).use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync()
            }
        }
    }
}
