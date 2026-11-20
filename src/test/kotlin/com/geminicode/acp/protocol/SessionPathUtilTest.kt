package com.geminicode.acp.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class SessionPathUtilTest {

    @Test
    fun `normalizeSessionCwd decodes percent-encoded Windows drive paths`() {
        val norm1 = SessionPathUtil.normalizeSessionCwd("/d%3A/antigravityProject").replace('\\', '/')
        val norm2 = SessionPathUtil.normalizeSessionCwd("/D:/ideaProject/haidong").replace('\\', '/')

        assertEquals("d:/antigravityProject", norm1)
        assertEquals("D:/ideaProject/haidong", norm2)
    }

    @Test
    fun `isSameProject matches case-insensitively and across nested paths`() {
        assertTrue(SessionPathUtil.isSameProject("/d%3A/antigravityProject", "D:\\antigravityProject"))
        assertTrue(SessionPathUtil.isSameProject("/d%3A/antigravityProject", "D:\\antigravityProject\\gemini-idea-acp-plugin"))
        assertFalse(SessionPathUtil.isSameProject("/D:/ideaProject/haidong", "D:\\antigravityProject"))
    }

    @Test
    fun `formatUpdatedAt parses ISO-8601 UTC timestamp cleanly`() {
        val formatted = SessionPathUtil.formatUpdatedAt("2025-05-01T08:30:00Z", ZoneOffset.UTC)
        assertTrue(formatted.contains("2025-05-01 08:30"))
    }
}
