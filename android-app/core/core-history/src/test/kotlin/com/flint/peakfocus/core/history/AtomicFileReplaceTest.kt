package com.flint.peakfocus.core.history

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicFileReplaceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val renameBackup = AtomicFileReplace.rename
    private val copyBackup = AtomicFileReplace.copy

    private fun restoreSeams() {
        AtomicFileReplace.rename = renameBackup
        AtomicFileReplace.copy = copyBackup
    }

    @Test
    fun successfulRenameReplacesTheTarget() {
        val target = temporaryFolder.newFile("history.json")
        target.writeText("old")
        val temp = temporaryFolder.newFile("history.json.tmp")
        temp.writeText("new")
        try {
            assertTrue(AtomicFileReplace.replace(temp, target))
        } finally {
            restoreSeams()
        }
        assertEquals("new", target.readText())
        assertFalse(temp.exists())
    }

    @Test
    fun fallbackCopyIsVerifiedBeforePromotion() {
        val target = temporaryFolder.newFile("history.json")
        target.writeText("old")
        val temp = temporaryFolder.newFile("history.json.tmp")
        temp.writeText("new-content")
        AtomicFileReplace.rename = { from, to ->
            // Force the staged path, then allow the remaining promotions.
            if (from == temp && to == target) false else from.renameTo(to)
        }
        try {
            assertTrue(AtomicFileReplace.replace(temp, target))
        } finally {
            restoreSeams()
        }
        assertEquals("new-content", target.readText())
        assertFalse(File(target.parentFile, target.name + ".staged").exists())
        assertFalse(File(target.parentFile, target.name + ".bak").exists())
    }

    @Test
    fun interruptedCopyKeepsThePreviousHistory() {
        val target = temporaryFolder.newFile("history.json")
        target.writeText("old-valid")
        val temp = temporaryFolder.newFile("history.json.tmp")
        temp.writeText("new-content")
        AtomicFileReplace.rename = { _, _ -> false }
        AtomicFileReplace.copy = { _, to ->
            FileOutputStream(to).use { it.write("partial".toByteArray()) }
            throw IOException("storage full")
        }
        assertThrows(IOException::class.java) {
            AtomicFileReplace.replace(temp, target)
        }
        restoreSeams()
        assertEquals("old-valid", target.readText())
        assertFalse(File(target.parentFile, target.name + ".staged").exists())
        assertFalse(temp.exists())
    }

    @Test
    fun failedPromotionRestoresThePreviousHistory() {
        val target = temporaryFolder.newFile("history.json")
        target.writeText("old-valid")
        val temp = temporaryFolder.newFile("history.json.tmp")
        temp.writeText("new-content")
        val staged = File(target.parentFile, target.name + ".staged")
        val backup = File(target.parentFile, target.name + ".bak")
        AtomicFileReplace.rename = { from, to ->
            when {
                from == temp && to == target -> false
                from == staged && to == target -> false
                else -> from.renameTo(to)
            }
        }
        assertThrows(IOException::class.java) {
            AtomicFileReplace.replace(temp, target)
        }
        restoreSeams()
        assertEquals("old-valid", target.readText())
        assertFalse(staged.exists())
        assertFalse(backup.exists())
    }

    @Test
    fun recoveryRestoresASavedBackup() {
        val target = File(temporaryFolder.root, "history.json")
        val backup = File(temporaryFolder.root, target.name + ".bak")
        backup.writeText("recovered")
        AtomicFileReplace.rename = { from, to -> from.renameTo(to) }
        AtomicFileReplace.recover(target)
        restoreSeams()
        assertEquals("recovered", target.readText())
        assertFalse(backup.exists())
    }

    @Test
    fun recoveryKeepsTheBackupWhenItCannotRestore() {
        val target = File(temporaryFolder.root, "history.json")
        val backup = File(temporaryFolder.root, target.name + ".bak")
        backup.writeText("recovered")
        AtomicFileReplace.rename = { _, _ -> false }
        AtomicFileReplace.recover(target)
        restoreSeams()
        assertFalse(target.exists())
        assertTrue(backup.exists())
        assertEquals("recovered", backup.readText())
    }

    @Test
    fun recoveryClearsStaleStagingOnceTargetExists() {
        val target = File(temporaryFolder.root, "history.json")
        target.writeText("current")
        val staged = File(temporaryFolder.root, target.name + ".staged")
        staged.writeText("stale")
        AtomicFileReplace.recover(target)
        assertEquals("current", target.readText())
        assertFalse(staged.exists())
    }
}
