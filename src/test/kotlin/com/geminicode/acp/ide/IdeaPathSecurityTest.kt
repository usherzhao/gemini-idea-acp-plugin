package com.geminicode.acp.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IdeaPathSecurityTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun `isWithinWorkspace prevents path traversal outside project root`() {
        val root = tempFolder.newFolder("workspace").toPath()
        val inside = IdeaPathSecurity.resolveProjectPath(root.toString(), "src/Main.kt")
        val escaped = IdeaPathSecurity.resolveProjectPath(root.toString(), "../secret.txt")

        assertTrue(IdeaPathSecurity.isWithinWorkspace(root.toString(), inside))
        assertFalse(IdeaPathSecurity.isWithinWorkspace(root.toString(), escaped))
    }

    @Test
    fun `sliceLines respects 1-based start line and limit`() {
        val sample = "line1\nline2\nline3\nline4\nline5"
        assertEquals("line2\nline3", IdeaPathSecurity.sliceLines(sample, startLine = 2, limit = 2))
        assertEquals("line4\nline5", IdeaPathSecurity.sliceLines(sample, startLine = 4, limit = null))
        assertEquals("", IdeaPathSecurity.sliceLines(sample, startLine = 99, limit = 5))
        assertEquals(sample, IdeaPathSecurity.sliceLines(sample, startLine = null, limit = null))
    }
}
