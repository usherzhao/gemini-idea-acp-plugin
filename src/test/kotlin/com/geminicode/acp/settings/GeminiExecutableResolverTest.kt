package com.geminicode.acp.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class GeminiExecutableResolverTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun `readCachedAccountInfo reads non-sensitive account status`() {
        val fakeHome = tempFolder.newFolder("home").toPath()
        val geminiDir = fakeHome.resolve(".gemini")
        Files.createDirectories(geminiDir)
        Files.writeString(geminiDir.resolve("oauth_creds.json"), "{}")
        Files.writeString(geminiDir.resolve("google_accounts.json"), """{"active":"dev@example.com"}""")
        Files.writeString(geminiDir.resolve("settings.json"), """{"security":{"auth":{"selectedType":"oauth-personal"}}}""")

        val info = GeminiExecutableResolver.readCachedAccountInfo(fakeHome)
        assertTrue(info.hasOAuthCreds)
        assertEquals("dev@example.com", info.activeEmail)
        assertEquals("oauth-personal", info.selectedAuthType)
    }

    @Test
    fun `resolveExecutable accepts explicit valid file path`() {
        val exeFile = tempFolder.newFile("gemini.cmd")
        exeFile.setExecutable(true)
        val result = GeminiExecutableResolver.resolveExecutable(exeFile.absolutePath)
        assertTrue(result.found)
        assertEquals(exeFile.absolutePath, result.executablePath)
    }

    @Test
    fun `resolveExecutable identifies agy executable as ANTIGRAVITY_AGY backend`() {
        val exeFile = tempFolder.newFile("agy.exe")
        exeFile.setExecutable(true)
        val result = GeminiExecutableResolver.resolveExecutable(exeFile.absolutePath)
        assertTrue(result.found)
        assertEquals(GeminiExecutableResolver.BackendKind.ANTIGRAVITY_AGY, result.backendKind)
    }

    @Test
    fun `readCachedAccountInfo reports false when gemini directory does not exist`() {
        val emptyHome = tempFolder.newFolder("empty").toPath()
        val info = GeminiExecutableResolver.readCachedAccountInfo(emptyHome)
        assertFalse(info.hasOAuthCreds)
        assertEquals(null, info.activeEmail)
    }
}
