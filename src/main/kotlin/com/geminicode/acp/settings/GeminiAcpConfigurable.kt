package com.geminicode.acp.settings

import com.geminicode.acp.protocol.AcpClientService
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 插件设置页面：设置 -> 工具 -> Gemini 对话编程助手 (ACP)。
 */
class GeminiAcpConfigurable : Configurable {

    private var rootPanel: JPanel? = null
    private val executableField = TextFieldWithBrowseButton()
    private val acpFlagBox = ComboBox(arrayOf("--acp", "--experimental-acp"))
    private val authMethodBox = ComboBox(arrayOf("oauth-personal", "oauth-business", "gemini-api-key", "agent-platform", "vertex-ai"))
    private val allowNpxFallbackCheck = JBCheckBox("未找到 agy 与本地 gemini 时，允许回退到 npx -y @google/gemini-cli（仅适合 API Key）")
    private val stripApiKeyCheck = JBCheckBox("oauth-personal 模式下屏蔽环境变量 GEMINI_API_KEY（避免状态被覆盖）")
    private val requireDiffCheck = JBCheckBox("Agent 修改文件前弹出 IDEA 原生 Diff 预览确认")
    private val autoApproveReadCheck = JBCheckBox("自动放行只读工具权限请求（读取文件、搜索等）")
    private val includeActiveFileCheck = JBCheckBox("发送对话时默认附带当前编辑器文件/选区上下文")
    private val proxyField = JBTextField()
    private val gcpProjectField = JBTextField()
    private val detectStatusLabel = JBLabel()

    init {
        val descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
            .withTitle("选择 Antigravity CLI (agy) 或 Gemini CLI 可执行文件")
            .withDescription("留空则自动优先探测 agy.exe (Antigravity ACP)，其次探测 gemini CLI")
        executableField.addBrowseFolderListener(null, descriptor)
        (executableField.textField as? JBTextField)?.emptyText?.text = "留空则自动优先探测 agy (Antigravity CLI)，其次 gemini"
        proxyField.emptyText.text = "可选，例如 http://127.0.0.1:7890"
        gcpProjectField.emptyText.text = "可选，仅企业/GCP 项目需要时填写 GOOGLE_CLOUD_PROJECT"
    }

    override fun getDisplayName(): String = "Gemini 对话编程助手 (ACP)"

    override fun createComponent(): JComponent {
        val detectButton = JButton("检测 CLI 与登录状态").apply {
            addActionListener { refreshDetectionLabel(showDialogOnMissing = true) }
        }
        val detectRow = JPanel(BorderLayout(8, 0)).apply {
            add(detectButton, BorderLayout.WEST)
            add(detectStatusLabel, BorderLayout.CENTER)
        }

        rootPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("CLI 路径 (推荐 agy):", executableField, 1, false)
            .addComponent(allowNpxFallbackCheck, 1)
            .addLabeledComponent("旧版 gemini 启动参数:", acpFlagBox, 1, false)
            .addLabeledComponent("认证方式 (Auth Method):", authMethodBox, 1, false)
            .addComponent(stripApiKeyCheck, 1)
            .addSeparator(8)
            .addComponent(requireDiffCheck, 1)
            .addComponent(autoApproveReadCheck, 1)
            .addComponent(includeActiveFileCheck, 1)
            .addSeparator(8)
            .addLabeledComponent("代理地址 (HTTP/HTTPS_PROXY):", proxyField, 1, false)
            .addLabeledComponent("GCP 项目 ID (可选):", gcpProjectField, 1, false)
            .addComponent(detectRow, 8)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        reset()
        refreshDetectionLabel(showDialogOnMissing = false)
        return rootPanel!!
    }

    private fun refreshDetectionLabel(showDialogOnMissing: Boolean) {
        val resolved = GeminiExecutableResolver.resolveExecutable(
            executableField.text,
            allowNpxFallback = allowNpxFallbackCheck.isSelected
        )
        val cached = GeminiExecutableResolver.readCachedAccountInfo()
        val cliText = when {
            resolved.found && resolved.backendKind == GeminiExecutableResolver.BackendKind.ANTIGRAVITY_AGY ->
                "已检测到官方 Antigravity CLI (agy acp): ${resolved.executablePath}"
            resolved.found && resolved.backendKind == GeminiExecutableResolver.BackendKind.GEMINI_CLI ->
                "检测到旧版 gemini CLI: ${resolved.executablePath}（个人账号请迁移至 agy）"
            resolved.found && resolved.viaNpxFallback ->
                "将通过 npx 启动旧版 gemini-cli (${resolved.executablePath})"
            else ->
                "未检测到 agy 或 gemini CLI，请安装 Antigravity CLI (https://antigravity.google)"
        }
        val accountText = when {
            cached.activeEmail != null -> "账号：${cached.activeEmail}"
            cached.hasOAuthCreds -> "已检测到本地登录配置"
            else -> "未检测到本地缓存"
        }
        detectStatusLabel.text = "$cliText | $accountText"
        if (!resolved.found && showDialogOnMissing) {
            Messages.showInfoMessage(
                "未检测到 Antigravity CLI (agy) 或 gemini CLI。\n个人 Google 账号 (oauth-personal) 请从 https://antigravity.google 安装 Antigravity CLI。",
                "未找到 CLI"
            )
        }
    }

    override fun isModified(): Boolean {
        val state = GeminiAcpSettingsState.getInstance().state
        return executableField.text.trim() != state.geminiExecutablePath ||
            (acpFlagBox.selectedItem as? String ?: "--acp") != state.acpFlag ||
            (authMethodBox.selectedItem as? String ?: "oauth-personal") != state.authMethodId ||
            allowNpxFallbackCheck.isSelected != state.allowNpxFallback ||
            stripApiKeyCheck.isSelected != state.stripGeminiApiKeyEnv ||
            requireDiffCheck.isSelected != state.requireDiffBeforeWrite ||
            autoApproveReadCheck.isSelected != state.autoApproveReadTools ||
            includeActiveFileCheck.isSelected != state.includeActiveFileByDefault ||
            proxyField.text.trim() != state.proxyUrl ||
            gcpProjectField.text.trim() != state.googleCloudProject
    }

    override fun apply() {
        val state = GeminiAcpSettingsState.getInstance().state
        state.geminiExecutablePath = executableField.text.trim()
        state.acpFlag = acpFlagBox.selectedItem as? String ?: "--acp"
        state.authMethodId = authMethodBox.selectedItem as? String ?: "oauth-personal"
        state.allowNpxFallback = allowNpxFallbackCheck.isSelected
        state.stripGeminiApiKeyEnv = stripApiKeyCheck.isSelected
        state.requireDiffBeforeWrite = requireDiffCheck.isSelected
        state.autoApproveReadTools = autoApproveReadCheck.isSelected
        state.includeActiveFileByDefault = includeActiveFileCheck.isSelected
        state.proxyUrl = proxyField.text.trim()
        state.googleCloudProject = gcpProjectField.text.trim()

        ProjectManager.getInstance().openProjects.forEach { proj ->
            if (!proj.isDisposed) {
                AcpClientService.getInstance(proj).disconnect()
            }
        }
    }

    override fun reset() {
        val state = GeminiAcpSettingsState.getInstance().state
        executableField.text = state.geminiExecutablePath
        acpFlagBox.selectedItem = state.acpFlag
        authMethodBox.selectedItem = state.authMethodId
        allowNpxFallbackCheck.isSelected = state.allowNpxFallback
        stripApiKeyCheck.isSelected = state.stripGeminiApiKeyEnv
        requireDiffCheck.isSelected = state.requireDiffBeforeWrite
        autoApproveReadCheck.isSelected = state.autoApproveReadTools
        includeActiveFileCheck.isSelected = state.includeActiveFileByDefault
        proxyField.text = state.proxyUrl
        gcpProjectField.text = state.googleCloudProject
    }
}
