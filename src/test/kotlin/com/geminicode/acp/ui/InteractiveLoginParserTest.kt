package com.geminicode.acp.ui

import com.geminicode.acp.protocol.AcpModels
import com.geminicode.acp.settings.GeminiAcpSettingsState
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractiveLoginParserTest {

    @Test
    fun `extractAuthUrl and extractLoggedInEmail parse real agy acp login output`() {
        val sampleOutput = """
            Open this link in a browser to log in with Google:

              https://accounts.google.com/o/oauth2/auth?access_type=offline&client_id=123&redirect_uri=https%3A%2F%2Fantigravity.google%2Foauth-callback&response_type=code&state=abc123

            Then paste the authorization code, or the full URL of the page you land on, and press Enter:
            4/0AUn6Hd_sample_code
            Logged in as developer@example.com.
        """.trimIndent()

        assertEquals(
            "https://accounts.google.com/o/oauth2/auth?access_type=offline&client_id=123&redirect_uri=https%3A%2F%2Fantigravity.google%2Foauth-callback&response_type=code&state=abc123",
            AgyLoginOutputParser.extractAuthUrl(sampleOutput)
        )
        assertEquals("developer@example.com", AgyLoginOutputParser.extractLoggedInEmail(sampleOutput))
    }

    @Test
    fun `extractLoggedInEmail returns null before login finishes`() {
        val pendingOutput = "Open this link in a browser to log in with Google:\nThen paste the authorization code:"
        assertNull(AgyLoginOutputParser.extractLoggedInEmail(pendingOutput))
        assertNull(AgyLoginOutputParser.extractAuthUrl(pendingOutput))
    }

    @Test
    fun `normalizeCodeSubmission strips line breaks and surrounding spaces`() {
        val wrappedCode = "  4/0AUn6Hd_firstLinePart\r\nSecondLinePart  \n"
        assertEquals("4/0AUn6Hd_firstLinePartSecondLinePart", AgyLoginOutputParser.normalizeCodeSubmission(wrappedCode))

        val callbackUrl = " https://antigravity.google/oauth-callback?code=4/0Aabc&state=xyz \r\n"
        assertEquals(
            "https://antigravity.google/oauth-callback?code=4/0Aabc&state=xyz",
            AgyLoginOutputParser.normalizeCodeSubmission(callbackUrl)
        )
    }

    @Test
    fun `buildWindowsWrapperScript configures utf8 proxy auto-exit and error pause`() {
        val script = TerminalLoginLauncher.buildWindowsWrapperScript(
            commandLine = "\"C:\\Tools\\agy.exe\" acp --login",
            proxyUrl = "http://127.0.0.1:7890",
            stripGeminiApiKeyEnv = true
        )

        assertTrue(script.contains("chcp 65001 >nul"))
        assertTrue(script.contains("set \"HTTP_PROXY=http://127.0.0.1:7890\""))
        assertTrue(script.contains("set \"HTTPS_PROXY=http://127.0.0.1:7890\""))
        assertTrue(script.contains("set \"GEMINI_API_KEY=\""))
        assertTrue(script.contains("\"C:\\Tools\\agy.exe\" acp --login"))
        assertTrue(script.contains("exit /b 0"))
        assertTrue(script.contains("pause"))

        val noProxyScript = TerminalLoginLauncher.buildWindowsWrapperScript(
            commandLine = "\"C:\\Tools\\agy.exe\" acp --login",
            proxyUrl = "",
            stripGeminiApiKeyEnv = false
        )
        assertFalse(noProxyScript.contains("HTTP_PROXY"))
        assertFalse(noProxyScript.contains("GEMINI_API_KEY"))
    }

    @Test
    fun `extractUserEmail handles current tier-only response and future email metadata`() {
        val currentAgyAuth = JsonParser.parseString(
            """{"_meta":{"antigravity":{"tier":{"name":"Google AI Pro","id":"g1-pro-tier"}}}}"""
        )
        assertNull(AcpModels.extractUserEmail(currentAgyAuth))

        val withAntigravityUserEmail = JsonParser.parseString(
            """{"_meta":{"antigravity":{"user":{"email":"alice@example.com"}}}}"""
        )
        assertEquals("alice@example.com", AcpModels.extractUserEmail(withAntigravityUserEmail))

        val withTopLevelAccountEmail = JsonParser.parseString(
            """{"account":{"email":"bob@example.com"}}"""
        )
        assertEquals("bob@example.com", AcpModels.extractUserEmail(withTopLevelAccountEmail))
    }

    @Test
    fun `settings state persists and clears lastLoggedInEmail`() {
        val settings = GeminiAcpSettingsState()
        assertNull(settings.getLastLoggedInEmail())

        settings.setLastLoggedInEmail("  user@gmail.com  ")
        assertEquals("user@gmail.com", settings.getLastLoggedInEmail())

        settings.setLastLoggedInEmail(null)
        assertNull(settings.getLastLoggedInEmail())
    }
}
