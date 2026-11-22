package com.geminicode.acp

import com.geminicode.acp.ide.CodeReference
import com.geminicode.acp.ide.ImageAttachmentHelper
import com.geminicode.acp.protocol.AcpClientService
import com.geminicode.acp.settings.GeminiAcpConfigurable
import com.geminicode.acp.settings.GeminiAcpSettingsState
import com.geminicode.acp.settings.GeminiExecutableResolver
import com.geminicode.acp.ui.GeminiChatPanel
import com.geminicode.acp.ui.GeminiToolWindowFactory
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
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

    fun testRightClickActionsAndDirectQuoteIntoInputBox() {
        val actionManager = ActionManager.getInstance()
        assertNotNull(actionManager.getAction("GeminiCodeAcp.QuoteProjectFile"))
        assertNotNull(actionManager.getAction("GeminiCodeAcp.QuoteSelection"))
        assertNotNull(actionManager.getAction("GeminiCodeAcp.EditorGroup"))

        val panel = GeminiChatPanel(project)
        try {
            val ref1 = CodeReference(
                absolutePath = "${project.basePath}/src/main/java/OrderService.java",
                relativePath = "src/main/java/OrderService.java"
            )
            val ref2 = CodeReference(
                absolutePath = "${project.basePath}/docs/sub/my spec.md",
                relativePath = "docs/sub/my spec.md",
                startLine = 12,
                endLine = 28
            )
            panel.addCodeReferences(listOf(ref1, ref2))
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

            val textAfterFirst = panel.currentInputText()
            // 对话框内仅展示文件名或文件名+行号，不显示全路径
            assertTrue("Expected @OrderService.java in input box, got: $textAfterFirst", textAfterFirst.contains("@OrderService.java"))
            assertFalse("Should not show full relative path in input box, got: $textAfterFirst", textAfterFirst.contains("src/main/java/"))
            assertTrue("Expected quoted whitespace filename with line range, got: $textAfterFirst", textAfterFirst.contains("@\"my spec.md\"#L12-L28"))
            assertFalse("Should not show parent folder for unique filename, got: $textAfterFirst", textAfterFirst.contains("docs/sub/"))

            // 但解析出的引用必须完整还原真实绝对路径和相对路径供 AI 使用
            val resolved = panel.currentResolvedReferences()
            assertEquals(2, resolved.size)
            assertEquals("src/main/java/OrderService.java", resolved[0].relativePath)
            assertEquals("docs/sub/my spec.md", resolved[1].relativePath)
            assertEquals(12, resolved[1].startLine)
            assertEquals(28, resolved[1].endLine)

            // 重复引用同一个文件不应重复插入
            panel.addCodeReference(ref1)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            assertEquals(textAfterFirst, panel.currentInputText())

            // 同名不同目录的文件自动用最少父目录区分且都能准确还原完整路径
            val dup1 = CodeReference("${project.basePath}/docs/a/SKILL.md", "docs/a/SKILL.md")
            val dup2 = CodeReference("${project.basePath}/docs/b/SKILL.md", "docs/b/SKILL.md")
            panel.addCodeReferences(listOf(dup1, dup2))
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val textWithDup = panel.currentInputText()
            assertTrue(textWithDup.contains("@SKILL.md"))
            assertTrue(textWithDup.contains("@b/SKILL.md"))
            val resolvedAll = panel.currentResolvedReferences()
            assertEquals(4, resolvedAll.size)
            assertTrue(resolvedAll.any { it.relativePath == "docs/a/SKILL.md" })
            assertTrue(resolvedAll.any { it.relativePath == "docs/b/SKILL.md" })

            // 验证多模态图片附件加入与计数
            val dummyImg = ImageAttachmentHelper.ImageAttachment(
                displayName = "shot.png",
                mimeType = "image/png",
                base64Data = "iVBORw0KGgo=",
                width = 100,
                height = 80,
                sizeBytes = 1024
            )
            panel.addImageAttachments(listOf(dummyImg))
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            assertEquals(1, panel.pendingImageCount())
        } finally {
            Disposer.dispose(panel)
        }
    }

    fun testAgyCliFlagsAndProjectAdditionalDirectoriesPersistence() {
        val service = AcpClientService.getInstance(project)
        val settings = GeminiAcpSettingsState.getInstance()
        val extraDir = java.nio.file.Files.createTempDirectory("acp-extra-dir").toFile()
        try {
            val agyRes = GeminiExecutableResolver.ResolutionResult(
                found = true,
                executablePath = "C:/Users/dev/AppData/Local/agy/bin/agy.exe",
                needsCmdWrapper = false,
                checkedLocations = emptyList(),
                backendKind = GeminiExecutableResolver.BackendKind.ANTIGRAVITY_AGY
            )
            val customState = GeminiAcpSettingsState.State(
                agySandbox = true,
                agyDisableTelemetry = true,
                agyAgentName = "coder"
            )
            val cmd = service.buildCommand(agyRes, "--acp", customState, listOf(extraDir.absolutePath))
            assertEquals(
                listOf(
                    "C:/Users/dev/AppData/Local/agy/bin/agy.exe",
                    "acp",
                    "--sandbox",
                    "--disable-telemetry",
                    "--agent",
                    "coder",
                    "--add-dir",
                    extraDir.absolutePath
                ),
                cmd
            )

            val fakeProj = "D:/TempTestProj/service-a"
            settings.setAdditionalDirectories(fakeProj, listOf(extraDir.absolutePath, extraDir.absolutePath))
            assertEquals(1, settings.getAdditionalDirectories(fakeProj).size)
            settings.setAdditionalDirectories(fakeProj, emptyList())
            assertTrue(settings.getAdditionalDirectories(fakeProj).isEmpty())
        } finally {
            extraDir.deleteRecursively()
        }
    }
}
