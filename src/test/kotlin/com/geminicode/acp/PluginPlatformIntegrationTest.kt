package com.geminicode.acp

import com.geminicode.acp.protocol.AcpClientService
import com.geminicode.acp.settings.GeminiAcpConfigurable
import com.geminicode.acp.settings.GeminiAcpSettingsState
import com.geminicode.acp.settings.GeminiExecutableResolver
import com.geminicode.acp.ui.GeminiChatPanel
import com.geminicode.acp.ui.GeminiToolWindowFactory
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * 在真实 IntelliJ Platform 测试容器内验证插件服务注册、设置面板、侧边栏生命周期及防死锁行为。
 */
class PluginPlatformIntegrationTest : BasePlatformTestCase() {

    fun testServicesAndConfigurableLoadCleanly() {
        val settings = GeminiAcpSettingsState.getInstance()
        assertNotNull(settings)
        assertEquals("oauth-personal", settings.state.authMethodId)

        val acpService = AcpClientService.getInstance(project)
        assertNotNull(acpService)

        val configurable = GeminiAcpConfigurable()
        assertEquals("Gemini 对话编程助手 (ACP)", configurable.displayName)
        val component = configurable.createComponent()
        assertNotNull(component)
        assertFalse(configurable.isModified)
    }

    fun testChatPanelDisposesAndClearsProjectUserData() {
        val panel = GeminiChatPanel(project)
        project.putUserData(GeminiToolWindowFactory.CHAT_PANEL_KEY, panel)
        assertSame(panel, project.getUserData(GeminiToolWindowFactory.CHAT_PANEL_KEY))

        Disposer.dispose(panel)
        assertNull(project.getUserData(GeminiToolWindowFactory.CHAT_PANEL_KEY))
    }

    fun testSendPromptWhenDisconnectedDoesNotDeadlock() {
        val settings = GeminiAcpSettingsState.getInstance().state
        val oldPath = settings.geminiExecutablePath
        val oldFallback = settings.allowNpxFallback
        try {
            settings.geminiExecutablePath = "Z:/nonexistent_path_for_test/gemini_missing_xyz.cmd"
            settings.allowNpxFallback = false
            val service = AcpClientService.getInstance(project)
            val future = service.sendPrompt("你好")

            var caught: Throwable? = null
            try {
                future.get(5, TimeUnit.SECONDS)
            } catch (e: ExecutionException) {
                caught = e.cause
            }
            assertNotNull("断开状态下直接发送应快速返回错误，而不应在单线程线程池中死锁", caught)
        } finally {
            settings.geminiExecutablePath = oldPath
            settings.allowNpxFallback = oldFallback
        }
    }

    fun testBuildCommandDirectNodeAndNpxFallback() {
        val service = AcpClientService.getInstance(project)

        val agyRes = GeminiExecutableResolver.ResolutionResult(
            found = true,
            executablePath = "C:/Users/dev/AppData/Local/agy/bin/agy.exe",
            needsCmdWrapper = false,
            checkedLocations = emptyList(),
            backendKind = GeminiExecutableResolver.BackendKind.ANTIGRAVITY_AGY
        )
        assertEquals(
            listOf("C:/Users/dev/AppData/Local/agy/bin/agy.exe", "acp"),
            service.buildCommand(agyRes, "--acp")
        )

        val directRes = GeminiExecutableResolver.ResolutionResult(
            found = true,
            executablePath = "C:/npm/gemini.cmd",
            needsCmdWrapper = true,
            checkedLocations = emptyList(),
            viaNpxFallback = false,
            directNodePath = "C:/nodejs/node.exe",
            directScriptPath = "C:/npm/node_modules/@google/gemini-cli/dist/index.js",
            backendKind = GeminiExecutableResolver.BackendKind.GEMINI_CLI
        )
        assertEquals(
            listOf("C:/nodejs/node.exe", "C:/npm/node_modules/@google/gemini-cli/dist/index.js", "--acp"),
            service.buildCommand(directRes, "--acp")
        )

        val npxRes = GeminiExecutableResolver.ResolutionResult(
            found = true,
            executablePath = "C:/Program Files/nodejs/npx.cmd",
            needsCmdWrapper = true,
            checkedLocations = emptyList(),
            viaNpxFallback = true,
            backendKind = GeminiExecutableResolver.BackendKind.NPX_GEMINI_CLI
        )
        assertEquals(
            listOf("cmd.exe", "/c", "call", "C:/Program Files/nodejs/npx.cmd", "-y", "@google/gemini-cli", "--acp"),
            service.buildCommand(npxRes, "--acp")
        )
    }

    fun testRenameSessionPersistsCustomTitleAndSupportsReset() {
        val settings = GeminiAcpSettingsState.getInstance()
        val service = AcpClientService.getInstance(project)
        val sid = "test-session-rename-001"
        try {
            assertEquals("原始标题", settings.resolveDisplayTitle(sid, "原始标题"))

            val renamed = service.renameSession(sid, "我的重构任务", "原始标题").get(5, TimeUnit.SECONDS)
            assertEquals("我的重构任务", renamed)
            assertEquals("我的重构任务", settings.resolveDisplayTitle(sid, "原始标题"))

            val restored = service.renameSession(sid, "   ", "原始标题").get(5, TimeUnit.SECONDS)
            assertEquals("原始标题", restored)
            assertNull(settings.getCustomTitle(sid))
            assertEquals("原始标题", settings.resolveDisplayTitle(sid, "原始标题"))
        } finally {
            settings.setCustomTitle(sid, null)
        }
    }
}
