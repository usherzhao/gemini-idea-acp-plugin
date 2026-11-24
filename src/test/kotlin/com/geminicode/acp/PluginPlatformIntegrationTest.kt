package com.geminicode.acp

import com.geminicode.acp.ide.AccessLevel
import com.geminicode.acp.ide.CodeReference
import com.geminicode.acp.ide.DataPermissionConfig
import com.geminicode.acp.ide.IdeaFsHandler
import com.geminicode.acp.ide.ImageAttachmentHelper
import com.geminicode.acp.protocol.AcpClientService
import com.geminicode.acp.protocol.AcpModels
import com.geminicode.acp.protocol.JsonRpcTransport
import com.geminicode.acp.settings.GeminiAcpConfigurable
import com.geminicode.acp.settings.GeminiAcpSettingsState
import com.geminicode.acp.settings.GeminiExecutableResolver
import com.geminicode.acp.ui.GeminiChatPanel
import com.geminicode.acp.ui.GeminiToolWindowFactory
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.ui.ComboBox
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

    fun testConfigSelectorsRenderOnSingleCompactRow() {
        val panel = GeminiChatPanel(project)
        try {
            val metadata = AcpModels.SessionMetadata(
                sessionId = "sid-compact-row",
                configOptions = listOf(
                    AcpModels.ConfigOption(
                        id = "mode",
                        name = "Mode",
                        category = "mode",
                        type = "select",
                        currentValue = "default",
                        options = listOf(AcpModels.ConfigSelectOption("default", "Default"))
                    ),
                    AcpModels.ConfigOption(
                        id = "model",
                        name = "Model",
                        category = "model",
                        type = "select",
                        currentValue = "gemini-4-neon",
                        options = listOf(AcpModels.ConfigSelectOption("gemini-4-neon", "Gemini 4 Neon"))
                    ),
                    AcpModels.ConfigOption(
                        id = "thought_level",
                        name = "Thinking Level",
                        category = "thought_level",
                        type = "select",
                        currentValue = "high",
                        options = listOf(AcpModels.ConfigSelectOption("high", "High"))
                    )
                ),
                models = emptyList(),
                currentModelId = null,
                modes = emptyList(),
                currentModeId = null,
                tierName = null
            )
            panel.rebuildOfficialConfigSelectors(metadata)
            val lines = panel.selectorLinePanelsForTest()
            assertEquals("模型 / 思考强度 / 模式应放在同一行", 1, lines.size)

            val combos = lines[0].components.filterIsInstance<ComboBox<*>>()
            assertEquals(3, combos.size)
            // 模型顺序排首位且最大宽度受控，不会拉满整行
            assertEquals("Gemini 4 Neon", combos[0].selectedItem.toString())
            assertEquals("High", combos[1].selectedItem.toString())
            assertEquals("Default", combos[2].selectedItem.toString())
            assertTrue("模型下拉框宽度应保持紧凑，实际 preferredWidth=${combos[0].preferredSize.width}", combos[0].preferredSize.width <= 220)
            assertTrue("模型下拉框最大宽度应受限，实际 maximumWidth=${combos[0].maximumSize.width}", combos[0].maximumSize.width <= 260)
        } finally {
            Disposer.dispose(panel)
        }
    }

    fun testDataPermissionControlsEnforceReadWriteAndCommandPolicies() {
        val settings = GeminiAcpSettingsState.getInstance()
        val original = settings.getDataPermissionConfig()
        val panel = GeminiChatPanel(project)
        val logs = mutableListOf<String>()
        val handler = IdeaFsHandler(project) { logs.add(it) }
        val stdOptions = listOf(
            AcpModels.PermissionOption("opt_allow_once", "允许一次", "allow_once"),
            AcpModels.PermissionOption("opt_allow_always", "总是允许", "allow_always"),
            AcpModels.PermissionOption("opt_reject_once", "拒绝", "reject_once")
        )

        try {
            // 1. 切换为只读模式：禁止写入与终端命令，读文件自动放行
            panel.applyDataPermissionConfig(DataPermissionConfig.READ_ONLY)
            assertEquals("🔒 只读模式 ▾", panel.permissionButtonTextForTest())

            var writeBlocked = false
            try {
                handler.handleWriteTextFile(AcpModels.FsWriteTextFileParams(path = "src/Test.kt", content = "hello"))
            } catch (e: JsonRpcTransport.JsonRpcRemoteException) {
                writeBlocked = e.code == -32001
            }
            assertTrue("只读模式下必须直接拦截 fs/write_text_file", writeBlocked)

            val editRes = handler.handlePermissionRequest(
                AcpModels.PermissionRequestParams(
                    toolCall = AcpModels.PermissionToolCall(title = "Edit file", kind = "edit"),
                    options = stdOptions
                )
            )
            assertEquals("opt_reject_once", editRes.getAsJsonObject("outcome").get("optionId").asString)

            val cmdRes = handler.handlePermissionRequest(
                AcpModels.PermissionRequestParams(
                    toolCall = AcpModels.PermissionToolCall(title = "Run shell command", kind = "execute"),
                    options = stdOptions
                )
            )
            assertEquals("opt_reject_once", cmdRes.getAsJsonObject("outcome").get("optionId").asString)

            val readRes = handler.handlePermissionRequest(
                AcpModels.PermissionRequestParams(
                    toolCall = AcpModels.PermissionToolCall(title = "Read file", kind = "read"),
                    options = stdOptions
                )
            )
            assertEquals("opt_allow_once", readRes.getAsJsonObject("outcome").get("optionId").asString)

            // 2. 禁止读文件：直接拦截 fs/read_text_file 与 read 权限请求
            panel.applyDataPermissionConfig(
                DataPermissionConfig(
                    readOnlyMode = false,
                    readFile = AccessLevel.DENY,
                    writeFile = AccessLevel.ASK,
                    command = AccessLevel.DENY
                )
            )
            assertEquals("🛡️ 读:禁 写:问 命:禁 ▾", panel.permissionButtonTextForTest())

            var readBlocked = false
            try {
                handler.handleReadTextFile(AcpModels.FsReadTextFileParams(path = "README.md"))
            } catch (e: JsonRpcTransport.JsonRpcRemoteException) {
                readBlocked = e.code == -32001
            }
            assertTrue("禁止读文件策略必须拦截 fs/read_text_file", readBlocked)

            val deniedReadPerm = handler.handlePermissionRequest(
                AcpModels.PermissionRequestParams(
                    toolCall = AcpModels.PermissionToolCall(title = "Search code", kind = "search"),
                    options = stdOptions
                )
            )
            assertEquals("opt_reject_once", deniedReadPerm.getAsJsonObject("outcome").get("optionId").asString)

            // 3. 全自动模式：自动放行编辑与终端命令
            panel.applyDataPermissionConfig(DataPermissionConfig.FULL_AUTO)
            assertEquals("⚡ 权限: 全自动 ▾", panel.permissionButtonTextForTest())

            val autoEditRes = handler.handlePermissionRequest(
                AcpModels.PermissionRequestParams(
                    toolCall = AcpModels.PermissionToolCall(title = "Edit file", kind = "edit"),
                    options = stdOptions
                )
            )
            assertEquals("opt_allow_once", autoEditRes.getAsJsonObject("outcome").get("optionId").asString)

            val autoCmdRes = handler.handlePermissionRequest(
                AcpModels.PermissionRequestParams(
                    toolCall = AcpModels.PermissionToolCall(title = "Run gradle test", kind = "execute"),
                    options = stdOptions
                )
            )
            assertEquals("opt_allow_once", autoCmdRes.getAsJsonObject("outcome").get("optionId").asString)
            assertTrue("拦截记录应写入诊断日志", logs.isNotEmpty())
        } finally {
            settings.setDataPermissionConfig(original)
            Disposer.dispose(panel)
        }
    }
}
