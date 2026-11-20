package com.geminicode.acp.ui

import com.geminicode.acp.ide.CodeReference
import com.geminicode.acp.ide.EditorDiffApplyUtil
import com.geminicode.acp.ide.IdeaContextCollector
import com.geminicode.acp.ide.ProjectFileIndexHelper
import com.geminicode.acp.ide.SkillDiscoveryService
import com.geminicode.acp.protocol.AcpClientService
import com.geminicode.acp.protocol.AcpModels
import com.geminicode.acp.settings.GeminiAcpConfigurable
import com.geminicode.acp.settings.GeminiAcpSettingsState
import com.geminicode.acp.settings.GeminiExecutableResolver
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import javax.swing.border.CompoundBorder
import javax.swing.border.LineBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.HyperlinkEvent

/**
 * Gemini 助手主面板：对接官方 Antigravity CLI (agy acp) 的 oauth-personal 登录，
 * 支持 `@` 文件引用补全、`/` 已安装 Skills 补全、编辑器行号轻量引用、历史对话切换、
 * 底部官方动态模型/思考强度/模式选择以及 IDEA 原生 Diff 预览。
 */
class GeminiChatPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val acpService = AcpClientService.getInstance(project)
    private val completionExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "GeminiAcp-Completion").apply { isDaemon = true }
    }
    private val completionSeq = AtomicLong(0)

    private val statusLabel = JBLabel("未连接")
    private val accountLabel = JBLabel()
    private val sessionTitleLabel = JBLabel("当前对话：新对话").apply {
        foreground = JBColor.GRAY
        font = font.deriveFont(Font.PLAIN, 11f)
    }
    private val renameCurrentButton = JButton("重命名").apply {
        font = font.deriveFont(11f)
        margin = Insets(1, 6, 1, 6)
        toolTipText = "修改当前对话名称"
    }
    private val connectButton = JButton("登录/连接").apply {
        toolTipText = "通过官方 ACP (oauth-personal) 连接或复用当前账号登录"
    }
    private val switchAccountButton = JButton("切换账号").apply {
        toolTipText = "退出当前账号并通过官方 ACP 重新登录 / 切换 Google 账号"
    }
    private val terminalLoginButton = JButton("终端登录").apply {
        toolTipText = "打开终端执行 agy acp --login 完成交互式浏览器授权"
    }
    private val historyButton = JButton("历史记录")
    private val newSessionButton = JButton("新对话")
    private val logToggleButton = JButton("日志")
    private val settingsButton = JButton("设置")

    /**
     * 底部动态配置栏：完全由官方服务端返回的 configOptions（model / thought_level / mode 等）渲染。
     */
    private val selectorRow = JPanel(GridBagLayout()).apply {
        isOpaque = false
        isVisible = false
        border = JBUI.Borders.empty(4, 2, 2, 2)
    }
    private var updatingSelectors = false

    private val bannerPanel = JPanel(BorderLayout(8, 4))
    private val bannerText = JBLabel()
    private val bannerActionBtn = JButton("打开授权页")
    private var detectedAuthUrl: String? = null

    private val logArea = JBTextArea(6, 40).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = UIUtil.getLabelFont().deriveFont(11f)
    }
    private val logScrollPane = JBScrollPane(logArea).apply {
        isVisible = false
        preferredSize = Dimension(320, 120)
        border = CompoundBorder(
            JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
            JBUI.Borders.empty(4)
        )
    }

    private val messagesContainer = object : JPanel(), Scrollable {
        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
            background = UIUtil.getPanelBackground()
        }

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = 24
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = 120
        override fun getScrollableTracksViewportWidth(): Boolean = true
        override fun getScrollableTracksViewportHeight(): Boolean = false
    }
    private val scrollPane = JBScrollPane(
        messagesContainer,
        ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    ).apply {
        border = JBUI.Borders.empty()
    }

    private val attachContextCheck = JBCheckBox("自动引用当前文件/行号", true)
    private val contextBadgeLabel = JBLabel("")

    /**
     * 与输入框内精简 `@文件名` 实时同步的轻量级代码位置引用条，以及精简展示名 -> 真实完整路径的绑定表。
     * 对话框中仅显示文件名/行号，发送给 AI 时自动还原为精确完整路径。
     */
    private val knownInlinePathBindings = LinkedHashMap<String, CodeReference.ResolvedFileBinding>()
    private val referencesBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
        isOpaque = false
        isVisible = false
    }

    private val inputArea = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = "输入需求，支持 @ 模糊搜索引用文件、/ 选择已安装 Skills；Enter 发送，Shift+Enter 换行"
    }
    private val sendOrStopButton = JButton("发送")

    private var activeTrigger: InputTriggerParser.ActiveTrigger? = null
    private var suppressTriggerRefresh = false
    private lateinit var completionPopup: InputCompletionPopup

    @Volatile
    private var cachedLocalSkills: List<AcpModels.AvailableCommand> = emptyList()

    private var activeAssistantCard: AssistantTurnCard? = null
    private var activeReplayUserCard: UserTurnCard? = null
    private var replayedMessageCount = 0
    private var isStreaming = false
    private var isLoadingHistory = false
    private var loggedOutExplicitly = false

    private fun runOnUi(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed) {
                action()
            }
        }, ModalityState.any())
    }

    private val eventListener = object : AcpClientService.AcpEventListener {
        override fun onStateChanged(state: AcpClientService.ConnectionState, detail: String) {
            runOnUi {
                isStreaming = state == AcpClientService.ConnectionState.STREAMING
                statusLabel.text = detail
                statusLabel.foreground = when (state) {
                    AcpClientService.ConnectionState.READY -> JBColor(Color(0x15803D), Color(0x4ADE80))
                    AcpClientService.ConnectionState.STREAMING,
                    AcpClientService.ConnectionState.CONNECTING,
                    AcpClientService.ConnectionState.AUTHENTICATING -> JBColor(Color(0xB45309), Color(0xFBBF24))
                    AcpClientService.ConnectionState.ERROR -> JBColor(Color(0xB91C1C), Color(0xF87171))
                    AcpClientService.ConnectionState.DISCONNECTED -> JBColor.GRAY
                }
                sendOrStopButton.text = if (isStreaming) "停止" else "发送"
                sendOrStopButton.isEnabled = !isLoadingHistory
                val busyAuth = state == AcpClientService.ConnectionState.CONNECTING ||
                    state == AcpClientService.ConnectionState.AUTHENTICATING
                connectButton.isEnabled = !busyAuth
                switchAccountButton.isEnabled = !busyAuth && !isStreaming && !isLoadingHistory
                historyButton.isEnabled = !isStreaming && !isLoadingHistory
                if (state == AcpClientService.ConnectionState.READY) {
                    loggedOutExplicitly = false
                    bannerPanel.isVisible = false
                }
                refreshAccountStatus()
            }
        }

        override fun onSessionMetadataChanged(metadata: AcpModels.SessionMetadata) {
            runOnUi {
                rebuildOfficialConfigSelectors(metadata)
            }
        }

        override fun onAvailableCommandsChanged(commands: List<AcpModels.AvailableCommand>) {
            runOnUi {
                appendDiagnosticLog("已同步官方 Skills / 斜杠命令列表：共 ${commands.size} 项")
            }
        }

        override fun onSessionTitleChanged(title: String) {
            runOnUi {
                sessionTitleLabel.text = "当前对话：$title"
            }
        }

        override fun onSessionLoadingStarted(session: AcpModels.SessionSummary) {
            runOnUi {
                isLoadingHistory = true
                sendOrStopButton.isEnabled = false
                historyButton.isEnabled = false
                switchAccountButton.isEnabled = false
                activeAssistantCard = null
                activeReplayUserCard = null
                replayedMessageCount = 0
                messagesContainer.removeAll()
                val resolvedTitle = GeminiAcpSettingsState.getInstance()
                    .resolveDisplayTitle(session.sessionId, session.title)
                sessionTitleLabel.text = "正在加载对话：$resolvedTitle"
                messagesContainer.revalidate()
                messagesContainer.repaint()
            }
        }

        override fun onSessionLoadingFinished(metadata: AcpModels.SessionMetadata?, errorMessage: String?) {
            runOnUi {
                isLoadingHistory = false
                sendOrStopButton.isEnabled = true
                historyButton.isEnabled = true
                switchAccountButton.isEnabled = !isStreaming
                activeAssistantCard?.finalizeTurn(null)
                activeAssistantCard = null
                activeReplayUserCard = null

                if (errorMessage != null) {
                    val card = AssistantTurnCard()
                    addCard(card)
                    card.appendBody("⚠️ 加载历史对话失败：$errorMessage")
                    card.finalizeTurn("error")
                } else if (replayedMessageCount == 0) {
                    addHistoryBannerCard("已切换到历史对话（${acpService.activeSessionTitle ?: "历史会话"}），可直接继续输入消息。")
                }
                scrollToBottom()
            }
        }

        override fun onSessionUpdate(event: AcpModels.SessionUpdateEvent, isReplay: Boolean) {
            runOnUi {
                if (isReplay) {
                    handleReplayEvent(event)
                } else {
                    handleLiveEvent(event)
                }
                scrollToBottom()
            }
        }

        override fun onTurnFinished(stopReason: String?) {
            runOnUi {
                activeAssistantCard?.finalizeTurn(stopReason)
                activeAssistantCard = null
                scrollToBottom()
            }
        }

        override fun onLoggedOut() {
            runOnUi {
                loggedOutExplicitly = true
                detectedAuthUrl = null
                selectorRow.removeAll()
                selectorRow.isVisible = false
                selectorRow.revalidate()
                selectorRow.repaint()
                sessionTitleLabel.text = "当前对话：新对话"
                refreshAccountStatus()
            }
        }

        override fun onAuthUrlDetected(url: String) {
            runOnUi {
                detectedAuthUrl = url
                bannerText.text = "<html>需要 Google 授权登录，点击右侧按钮在浏览器完成 <code>oauth-personal</code> 授权：</html>"
                bannerActionBtn.text = "打开授权页"
                bannerPanel.isVisible = true
            }
        }

        override fun onLogMessage(message: String) {
            runOnUi {
                appendDiagnosticLog(message)
                if (message.contains("error", ignoreCase = true) || message.contains("PERMISSION_DENIED", true)) {
                    activeAssistantCard?.appendSystemNote(message)
                }
            }
        }
    }

    init {
        attachContextCheck.isSelected = GeminiAcpSettingsState.getInstance().state.includeActiveFileByDefault
        completionPopup = InputCompletionPopup(inputArea) { chosen ->
            applyCompletionItem(chosen)
        }
        buildLayout()
        wireActions()
        acpService.addListener(eventListener)
        acpService.lastMetadata?.let { rebuildOfficialConfigSelectors(it) }
        acpService.activeSessionTitle?.let { sessionTitleLabel.text = "当前对话：$it" }
        preloadLocalSkillsAsync()
        refreshAccountStatus()
        refreshContextPreview()
        addWelcomeCard()
    }

    private fun preloadLocalSkillsAsync() {
        completionExecutor.submit {
            cachedLocalSkills = SkillDiscoveryService.discoverLocalSkills(project.basePath)
        }
    }

    private fun handleReplayEvent(event: AcpModels.SessionUpdateEvent) {
        when (event) {
            is AcpModels.SessionUpdateEvent.UserMessageChunk -> {
                activeAssistantCard?.let {
                    it.finalizeTurn(null)
                    activeAssistantCard = null
                }
                val existingUser = activeReplayUserCard
                if (existingUser != null) {
                    existingUser.appendText(event.text)
                } else {
                    val userCard = UserTurnCard(event.text, emptyList())
                    activeReplayUserCard = userCard
                    replayedMessageCount++
                    addCard(userCard)
                }
            }
            is AcpModels.SessionUpdateEvent.AgentMessageChunk -> {
                activeReplayUserCard = null
                val card = ensureAssistantCardForReplay()
                card.appendBody(event.text)
            }
            is AcpModels.SessionUpdateEvent.AgentThoughtChunk -> {
                activeReplayUserCard = null
                val card = ensureAssistantCardForReplay()
                card.appendThought(event.text)
            }
            is AcpModels.SessionUpdateEvent.ToolCallEvent -> {
                activeReplayUserCard = null
                val card = ensureAssistantCardForReplay()
                card.upsertToolCall(event)
            }
            is AcpModels.SessionUpdateEvent.PlanEvent -> {
                activeReplayUserCard = null
                val card = ensureAssistantCardForReplay()
                card.appendPlan(event.summary)
            }
            is AcpModels.SessionUpdateEvent.ConfigOptionsUpdated,
            is AcpModels.SessionUpdateEvent.AvailableCommandsUpdated,
            is AcpModels.SessionUpdateEvent.SessionInfoUpdated,
            is AcpModels.SessionUpdateEvent.UsageEvent,
            is AcpModels.SessionUpdateEvent.Other -> Unit
        }
    }

    private fun ensureAssistantCardForReplay(): AssistantTurnCard {
        val existing = activeAssistantCard
        if (existing != null) return existing
        val created = AssistantTurnCard(showPendingPlaceholder = false)
        activeAssistantCard = created
        replayedMessageCount++
        addCard(created)
        return created
    }

    private fun handleLiveEvent(event: AcpModels.SessionUpdateEvent) {
        val card = activeAssistantCard ?: return
        when (event) {
            is AcpModels.SessionUpdateEvent.AgentMessageChunk -> card.appendBody(event.text)
            is AcpModels.SessionUpdateEvent.AgentThoughtChunk -> card.appendThought(event.text)
            is AcpModels.SessionUpdateEvent.ToolCallEvent -> card.upsertToolCall(event)
            is AcpModels.SessionUpdateEvent.PlanEvent -> card.appendPlan(event.summary)
            is AcpModels.SessionUpdateEvent.UserMessageChunk,
            is AcpModels.SessionUpdateEvent.ConfigOptionsUpdated,
            is AcpModels.SessionUpdateEvent.AvailableCommandsUpdated,
            is AcpModels.SessionUpdateEvent.SessionInfoUpdated,
            is AcpModels.SessionUpdateEvent.UsageEvent,
            is AcpModels.SessionUpdateEvent.Other -> Unit
        }
    }

    private fun buildLayout() {
        val topBar = JPanel(BorderLayout(6, 4)).apply {
            border = CompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.empty(6, 8)
            )
        }

        val statusRow = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            add(statusLabel, BorderLayout.CENTER)
            add(accountLabel, BorderLayout.EAST)
        }

        val titleRow = JPanel(BorderLayout(6, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 0)
            add(sessionTitleLabel, BorderLayout.CENTER)
            add(renameCurrentButton, BorderLayout.EAST)
        }

        val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            isOpaque = false
            add(connectButton)
            add(switchAccountButton)
            add(terminalLoginButton)
            add(historyButton)
            add(newSessionButton)
            add(logToggleButton)
            add(settingsButton)
        }

        bannerPanel.apply {
            isVisible = false
            border = CompoundBorder(
                JBUI.Borders.customLine(JBColor(Color(0xF59E0B), Color(0xD97706)), 1),
                JBUI.Borders.empty(6, 8)
            )
            background = JBColor(Color(0xFEF3C7), Color(0x451A03))
            add(bannerText, BorderLayout.CENTER)
            add(bannerActionBtn, BorderLayout.EAST)
        }

        val headerStack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(statusRow)
            add(titleRow)
            add(buttonRow)
            add(bannerPanel)
        }
        topBar.add(headerStack, BorderLayout.CENTER)

        val composer = JPanel(BorderLayout(6, 4)).apply {
            border = CompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
                JBUI.Borders.empty(8)
            )
        }

        val refreshCtxBtn = JButton("刷新位置").apply {
            addActionListener { refreshContextPreview() }
        }
        val contextBar = JPanel(BorderLayout(6, 0)).apply {
            isOpaque = false
            add(attachContextCheck, BorderLayout.WEST)
            add(contextBadgeLabel, BorderLayout.CENTER)
            add(refreshCtxBtn, BorderLayout.EAST)
        }

        val composerTopStack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            contextBar.alignmentX = LEFT_ALIGNMENT
            referencesBar.alignmentX = LEFT_ALIGNMENT
            add(contextBar)
            add(referencesBar)
        }

        val inputScroll = JBScrollPane(inputArea).apply {
            border = LineBorder(JBColor.border(), 1)
        }

        val inputActionRow = JPanel(BorderLayout(6, 0)).apply {
            isOpaque = false
            add(inputScroll, BorderLayout.CENTER)
            add(sendOrStopButton, BorderLayout.EAST)
        }

        composer.add(composerTopStack, BorderLayout.NORTH)
        composer.add(inputActionRow, BorderLayout.CENTER)
        composer.add(selectorRow, BorderLayout.SOUTH)

        val southStack = JPanel(BorderLayout()).apply {
            add(logScrollPane, BorderLayout.NORTH)
            add(composer, BorderLayout.SOUTH)
        }

        add(topBar, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(southStack, BorderLayout.SOUTH)
    }

    private fun rebuildOfficialConfigSelectors(metadata: AcpModels.SessionMetadata) {
        updatingSelectors = true
        try {
            selectorRow.removeAll()
            val selectOptions = metadata.configOptions.filter { it.type == "select" && it.options.isNotEmpty() }

            if (selectOptions.isNotEmpty()) {
                var row = 0
                val primaryModel = selectOptions.firstOrNull { (it.category ?: it.id) == "model" }
                if (primaryModel != null) {
                    addFullWidthConfigSelector(primaryModel, row++)
                }
                val others = selectOptions.filter { it !== primaryModel }
                var col = 0
                for (cfg in others) {
                    addPairedConfigSelector(cfg, row, col)
                    col++
                    if (col >= 2) {
                        col = 0
                        row++
                    }
                }
                selectorRow.isVisible = true
            } else if (metadata.models.isNotEmpty() || metadata.modes.isNotEmpty()) {
                var row = 0
                if (metadata.models.isNotEmpty()) {
                    val modelCombo = ComboBox(DefaultComboBoxModel(metadata.models.toTypedArray()))
                    metadata.models.firstOrNull { it.modelId == metadata.currentModelId }?.let {
                        modelCombo.selectedItem = it
                    }
                    modelCombo.addActionListener {
                        if (updatingSelectors) return@addActionListener
                        val item = modelCombo.selectedItem as? AcpModels.ModelInfo ?: return@addActionListener
                        acpService.setModel(item.modelId)
                    }
                    addFullWidthRow("模型：", modelCombo, row++)
                }
                if (metadata.modes.isNotEmpty()) {
                    val modeCombo = ComboBox(DefaultComboBoxModel(metadata.modes.toTypedArray()))
                    metadata.modes.firstOrNull { it.id == metadata.currentModeId }?.let {
                        modeCombo.selectedItem = it
                    }
                    modeCombo.addActionListener {
                        if (updatingSelectors) return@addActionListener
                        val item = modeCombo.selectedItem as? AcpModels.ModeInfo ?: return@addActionListener
                        acpService.setMode(item.id)
                    }
                    addFullWidthRow("模式：", modeCombo, row)
                }
                selectorRow.isVisible = true
            } else {
                selectorRow.isVisible = false
            }

            selectorRow.revalidate()
            selectorRow.repaint()
        } finally {
            updatingSelectors = false
        }
    }

    private fun createOfficialCombo(cfg: AcpModels.ConfigOption): ComboBox<AcpModels.ConfigSelectOption> {
        val combo = ComboBox(DefaultComboBoxModel(cfg.options.toTypedArray()))
        val selected = cfg.options.firstOrNull { it.value == cfg.currentValue }
        if (selected != null) {
            combo.selectedItem = selected
            combo.toolTipText = selected.description
        }
        combo.addActionListener {
            if (updatingSelectors) return@addActionListener
            val chosen = combo.selectedItem as? AcpModels.ConfigSelectOption ?: return@addActionListener
            combo.toolTipText = chosen.description
            acpService.setConfigOption(cfg.id, chosen.value).whenComplete { _, err ->
                if (err != null) {
                    runOnUi {
                        appendDiagnosticLog("切换 ${cfg.name} 失败: ${err.message}")
                        acpService.lastMetadata?.let { rebuildOfficialConfigSelectors(it) }
                    }
                }
            }
        }
        return combo
    }

    private fun addFullWidthConfigSelector(cfg: AcpModels.ConfigOption, row: Int) {
        addFullWidthRow(formatConfigOptionLabel(cfg), createOfficialCombo(cfg), row)
    }

    private fun addFullWidthRow(labelText: String, combo: ComboBox<*>, row: Int) {
        selectorRow.add(
            JBLabel(labelText),
            GridBagConstraints().apply {
                gridx = 0
                gridy = row
                anchor = GridBagConstraints.WEST
                insets = Insets(2, 2, 2, 4)
            }
        )
        selectorRow.add(
            combo,
            GridBagConstraints().apply {
                gridx = 1
                gridy = row
                gridwidth = 3
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(2, 0, 2, 2)
            }
        )
    }

    private fun addPairedConfigSelector(cfg: AcpModels.ConfigOption, row: Int, pairIndex: Int) {
        val baseX = pairIndex * 2
        selectorRow.add(
            JBLabel(formatConfigOptionLabel(cfg)),
            GridBagConstraints().apply {
                gridx = baseX
                gridy = row
                anchor = GridBagConstraints.WEST
                insets = Insets(2, if (pairIndex == 0) 2 else 8, 2, 4)
            }
        )
        selectorRow.add(
            createOfficialCombo(cfg),
            GridBagConstraints().apply {
                gridx = baseX + 1
                gridy = row
                weightx = 0.5
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(2, 0, 2, 2)
            }
        )
    }

    private fun formatConfigOptionLabel(cfg: AcpModels.ConfigOption): String {
        return when (cfg.category ?: cfg.id) {
            "model" -> "模型："
            "thought_level" -> "思考强度："
            "mode" -> "模式："
            else -> "${cfg.name}："
        }
    }

    private fun wireActions() {
        connectButton.addActionListener { triggerConnect() }
        switchAccountButton.addActionListener { triggerSwitchAccount() }
        terminalLoginButton.addActionListener { TerminalLoginLauncher.launchInteractiveGeminiLogin(project) }
        historyButton.addActionListener { openHistoryDialog() }
        renameCurrentButton.addActionListener { renameCurrentSession() }
        logToggleButton.addActionListener {
            logScrollPane.isVisible = !logScrollPane.isVisible
            revalidate()
            repaint()
        }
        newSessionButton.addActionListener {
            if (isStreaming || isLoadingHistory) {
                Messages.showInfoMessage(project, "请等待当前操作结束或先点击停止。", "Gemini 助手")
                return@addActionListener
            }
            acpService.newSession().whenComplete { _, err ->
                runOnUi {
                    if (err != null) {
                        Messages.showErrorDialog(project, err.message ?: "新建对话失败", "Gemini 助手")
                    } else {
                        messagesContainer.removeAll()
                        addWelcomeCard()
                        messagesContainer.revalidate()
                        messagesContainer.repaint()
                    }
                }
            }
        }
        settingsButton.addActionListener {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, GeminiAcpConfigurable::class.java)
            refreshAccountStatus()
        }
        bannerActionBtn.addActionListener {
            val url = detectedAuthUrl
            if (!url.isNullOrBlank()) {
                BrowserUtil.browse(url)
            } else {
                TerminalLoginLauncher.launchInteractiveGeminiLogin(project)
            }
        }
        sendOrStopButton.addActionListener {
            if (isStreaming) {
                acpService.cancelCurrentTurn()
            } else {
                sendFromInputBox()
            }
        }

        inputArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = scheduleCompletionCheck()
            override fun removeUpdate(e: DocumentEvent?) = scheduleCompletionCheck()
            override fun changedUpdate(e: DocumentEvent?) = Unit
        })
        inputArea.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent?) {
                SwingUtilities.invokeLater {
                    if (!inputArea.isFocusOwner) {
                        completionPopup.hide()
                    }
                }
            }
        })
        inputArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (completionPopup.isVisible) {
                    when (e.keyCode) {
                        KeyEvent.VK_DOWN -> {
                            completionPopup.moveSelection(1)
                            e.consume()
                            return
                        }
                        KeyEvent.VK_UP -> {
                            completionPopup.moveSelection(-1)
                            e.consume()
                            return
                        }
                        KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> {
                            if (completionPopup.confirmSelection()) {
                                e.consume()
                                return
                            }
                        }
                        KeyEvent.VK_ESCAPE -> {
                            completionPopup.hide()
                            e.consume()
                            return
                        }
                    }
                }

                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown && !isStreaming && !isLoadingHistory) {
                    e.consume()
                    sendFromInputBox()
                }
            }
        })
    }

    private fun scheduleCompletionCheck() {
        if (suppressTriggerRefresh) return
        SwingUtilities.invokeLater {
            if (project.isDisposed || suppressTriggerRefresh) return@invokeLater
            val text = inputArea.text
            val caret = inputArea.caretPosition
            val trigger = InputTriggerParser.detectTrigger(text, caret)
            activeTrigger = trigger
            val activeMentionRange = if (trigger?.kind == InputTriggerParser.TriggerKind.FILE_MENTION) {
                trigger.tokenStart until trigger.caretOffset
            } else {
                null
            }
            syncReferencesBarFromInput(activeMentionRange)

            if (trigger == null) {
                completionPopup.hide()
                return@invokeLater
            }

            val seq = completionSeq.incrementAndGet()
            when (trigger.kind) {
                InputTriggerParser.TriggerKind.FILE_MENTION -> {
                    completionExecutor.submit {
                        val files = ProjectFileIndexHelper.searchProjectFiles(
                            project = project,
                            query = trigger.query,
                            limit = 30,
                            isCancelled = { completionSeq.get() != seq }
                        )
                        val items = files.map { f ->
                            val openTag = if (f.openInEditor) " [已打开]" else ""
                            val ref = CodeReference(
                                absolutePath = f.absolutePath,
                                relativePath = f.relativePath
                            )
                            InputCompletionPopup.CompletionItem(
                                iconPrefix = "📄",
                                primaryText = f.fileName + openTag,
                                secondaryText = f.relativePath,
                                insertText = "${ref.inlineToken()} ",
                                payload = ref
                            )
                        }
                        runOnUi {
                            if (completionSeq.get() != seq) return@runOnUi
                            completionPopup.showItems("模糊搜索项目文件 (@) — 支持文件名/驼峰/路径，↑↓选择，Enter/Tab确认", items)
                        }
                    }
                }
                InputTriggerParser.TriggerKind.SKILL_COMMAND -> {
                    completionExecutor.submit {
                        if (cachedLocalSkills.isEmpty()) {
                            cachedLocalSkills = SkillDiscoveryService.discoverLocalSkills(project.basePath)
                        }
                        val merged = SkillDiscoveryService.mergeCommands(
                            acpService.availableCommands,
                            cachedLocalSkills
                        )
                        val q = trigger.query.trim().lowercase()
                        val filtered = if (q.isEmpty()) {
                            merged.take(30)
                        } else {
                            merged.mapNotNull { cmd ->
                                val nameLower = cmd.name.lowercase()
                                val descLower = cmd.description.orEmpty().lowercase()
                                val score = when {
                                    nameLower == q -> 5_000
                                    nameLower.startsWith(q) -> 4_000
                                    nameLower.contains(q) -> 3_000
                                    descLower.contains(q) -> 2_000
                                    else -> {
                                        val fuzzy = ProjectFileIndexHelper.fuzzySubsequenceScore(cmd.name, q)
                                        if (fuzzy >= 0) 1_000 + fuzzy else -1
                                    }
                                }
                                if (score >= 0) score to cmd else null
                            }.sortedByDescending { it.first }
                                .take(30)
                                .map { it.second }
                        }

                        val items = filtered.map { cmd ->
                            InputCompletionPopup.CompletionItem(
                                iconPrefix = "⚡",
                                primaryText = "/${cmd.name}",
                                secondaryText = cmd.description.orEmpty(),
                                insertText = "/${cmd.name} ",
                                payload = cmd
                            )
                        }
                        runOnUi {
                            if (completionSeq.get() != seq) return@runOnUi
                            completionPopup.showItems("已安装的 Skills 与命令 (/) — 支持模糊搜索，↑↓选择，Enter/Tab确认", items)
                        }
                    }
                }
            }
        }
    }

    private fun applyCompletionItem(item: InputCompletionPopup.CompletionItem) {
        val trigger = activeTrigger ?: return
        val currentText = inputArea.text
        if (trigger.tokenStart > currentText.length || trigger.caretOffset > currentText.length) return

        val insertText = when (val payload = item.payload) {
            is CodeReference -> "${registerDisplayBinding(payload).inlineToken()} "
            else -> item.insertText
        }

        suppressTriggerRefresh = true
        try {
            val before = currentText.substring(0, trigger.tokenStart)
            val after = currentText.substring(trigger.caretOffset)
            val newText = before + insertText + after
            inputArea.text = newText
            inputArea.caretPosition = (before.length + insertText.length).coerceAtMost(newText.length)
            activeTrigger = null
        } finally {
            suppressTriggerRefresh = false
        }
        syncReferencesBarFromInput()
        inputArea.requestFocusInWindow()
    }

    private fun registerDisplayBinding(ref: CodeReference): CodeReference {
        val uniqueDisplay = CodeReference.resolveUniqueDisplayPath(ref, knownInlinePathBindings)
        knownInlinePathBindings[uniqueDisplay.lowercase()] = CodeReference.ResolvedFileBinding(
            absolutePath = ref.absolutePath,
            relativePath = ref.relativePath,
            displayPath = uniqueDisplay,
            isDirectory = ref.isDirectory
        )
        return ref.copy(displayPath = uniqueDisplay)
    }

    /**
     * 将编辑器某一行/多行或项目树/标签页右键选中的文件/目录作为轻量 `@文件名`（或 `@文件名#L起-L止`）引用直接插入对话框内（不复制源码）。
     */
    fun addCodeReference(ref: CodeReference) = addCodeReferences(listOf(ref))

    internal fun currentInputText(): String = inputArea.text

    internal fun currentResolvedReferences(): List<CodeReference> =
        CodeReference.extractInlineReferences(inputArea.text, project.basePath, bindings = knownInlinePathBindings)

    fun addCodeReferences(refs: List<CodeReference>) {
        if (refs.isEmpty()) return
        runOnUi {
            completionPopup.hide()
            activeTrigger = null
            val existingKeys = CodeReference.extractInlineReferences(
                inputArea.text,
                project.basePath,
                bindings = knownInlinePathBindings
            ).map { it.dedupKey() }.toMutableSet()
            val tokensToInsert = mutableListOf<String>()
            for (ref in refs) {
                if (existingKeys.add(ref.dedupKey())) {
                    val boundRef = registerDisplayBinding(ref)
                    tokensToInsert.add(boundRef.inlineToken())
                }
            }
            if (tokensToInsert.isNotEmpty()) {
                val current = inputArea.text
                val caret = inputArea.caretPosition.coerceIn(0, current.length)
                val before = current.substring(0, caret)
                val after = current.substring(caret)
                val leadingSpace = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
                val trailingSpace = if (after.isEmpty() || !after.first().isWhitespace()) " " else ""
                val inserted = leadingSpace + tokensToInsert.joinToString(" ") + trailingSpace

                suppressTriggerRefresh = true
                try {
                    val updated = before + inserted + after
                    inputArea.text = updated
                    inputArea.caretPosition = (before.length + inserted.length).coerceAtMost(updated.length)
                } finally {
                    suppressTriggerRefresh = false
                }
            }
            syncReferencesBarFromInput()
            SwingUtilities.invokeLater {
                if (!project.isDisposed) {
                    inputArea.requestFocusInWindow()
                }
            }
        }
    }

    private fun syncReferencesBarFromInput(excludeRange: IntRange? = null) {
        val refs = CodeReference.extractInlineReferences(
            inputArea.text,
            project.basePath,
            excludeRange = excludeRange,
            bindings = knownInlinePathBindings
        )
        if (excludeRange == null) {
            val activeAliases = refs.map { it.shortName().lowercase() }.toSet()
            knownInlinePathBindings.keys.retainAll(activeAliases)
        }
        referencesBar.removeAll()
        if (refs.isEmpty()) {
            referencesBar.isVisible = false
            referencesBar.revalidate()
            referencesBar.repaint()
            return
        }

        for (ref in refs) {
            val icon = if (ref.isDirectory) "📁" else "📌"
            val chip = JButton("$icon ${ref.chipLabel()}  ×").apply {
                font = font.deriveFont(11f)
                margin = Insets(1, 6, 1, 6)
                toolTipText = "<html>点击移除该引用（不粘贴源码，仅传递路径与行号）<br/>相对路径：${ref.relativePath}<br/>完整路径：${ref.absolutePath}</html>"
                addActionListener {
                    suppressTriggerRefresh = true
                    try {
                        val updated = CodeReference.removeReferenceToken(
                            inputArea.text,
                            ref,
                            project.basePath,
                            bindings = knownInlinePathBindings
                        )
                        inputArea.text = updated
                        inputArea.caretPosition = updated.length
                    } finally {
                        suppressTriggerRefresh = false
                    }
                    syncReferencesBarFromInput()
                    inputArea.requestFocusInWindow()
                }
            }
            referencesBar.add(chip)
        }
        if (refs.size > 1) {
            val clearAllBtn = JButton("清空引用").apply {
                font = font.deriveFont(11f)
                margin = Insets(1, 6, 1, 6)
                addActionListener {
                    suppressTriggerRefresh = true
                    try {
                        val updated = CodeReference.removeAllReferenceTokens(inputArea.text)
                        inputArea.text = updated
                        inputArea.caretPosition = updated.length
                    } finally {
                        suppressTriggerRefresh = false
                    }
                    knownInlinePathBindings.clear()
                    syncReferencesBarFromInput()
                    inputArea.requestFocusInWindow()
                }
            }
            referencesBar.add(clearAllBtn)
        }
        referencesBar.isVisible = true
        referencesBar.revalidate()
        referencesBar.repaint()
    }

    private fun renameCurrentSession() {
        val sid = acpService.activeSessionId
        if (sid.isNullOrBlank()) {
            Messages.showInfoMessage(project, "请先连接或开始一个对话后再修改名称。", "修改对话名称")
            return
        }
        val currentTitle = acpService.activeSessionTitle.orEmpty()
        val input = Messages.showInputDialog(
            project,
            "请输入新的对话名称（留空确定可恢复默认标题）：",
            "修改对话名称",
            Messages.getQuestionIcon(),
            currentTitle,
            null
        ) ?: return
        acpService.renameSession(sid, input)
    }

    private fun openHistoryDialog() {
        if (isStreaming || isLoadingHistory) {
            Messages.showInfoMessage(project, "请先等待当前生成完成或点击停止，再切换对话。", "历史记录")
            return
        }
        val dialog = SessionHistoryDialog(project, acpService)
        if (dialog.showAndGet()) {
            val target = dialog.selectedSession ?: return
            acpService.loadSession(target).whenComplete { _, err ->
                if (err != null) {
                    runOnUi {
                        appendDiagnosticLog("切换历史会话失败: ${err.message}")
                    }
                }
            }
        }
    }

    private fun triggerSwitchAccount() {
        if (isStreaming || isLoadingHistory) {
            Messages.showInfoMessage(project, "请先等待当前生成或历史加载完成，再切换账号。", "切换账号")
            return
        }
        val settings = GeminiAcpSettingsState.getInstance().state
        val resolved = GeminiExecutableResolver.resolveExecutable(
            settings.geminiExecutablePath,
            allowNpxFallback = settings.allowNpxFallback
        )
        if (!resolved.found) {
            bannerText.text = "<html>未检测到 <code>agy</code> (Antigravity CLI)，请先安装 https://antigravity.google 或在设置中指定路径。</html>"
            bannerActionBtn.text = "获取 Antigravity CLI"
            detectedAuthUrl = "https://antigravity.google"
            bannerPanel.isVisible = true
            return
        }

        val choice = Messages.showDialog(
            project,
            "将通过官方 ACP 协议退出当前账号并重新授权登录：\n\n" +
                "• 「切换账号并重新登录」：先登出当前账号，再自动拉起 Google 账号授权并开启新对话；\n" +
                "• 「仅退出登录」：仅退出当前账号并断开连接。",
            "切换账号 / 重新登录",
            arrayOf("切换账号并重新登录", "仅退出登录", "取消"),
            0,
            Messages.getQuestionIcon()
        )
        when (choice) {
            0 -> {
                detectedAuthUrl = null
                acpService.switchAccount().whenComplete { _, err ->
                    runOnUi {
                        if (err == null) {
                            messagesContainer.removeAll()
                            addWelcomeCard()
                            addHistoryBannerCard("已切换账号并重新登录成功，当前已开启全新对话。")
                            messagesContainer.revalidate()
                            messagesContainer.repaint()
                            refreshAccountStatus()
                            scrollToBottom()
                        } else {
                            val root = (err as? java.util.concurrent.CompletionException)?.cause ?: err
                            if (root is AcpClientService.LogoutNotSupportedException) {
                                detectedAuthUrl = null
                                bannerText.text = "<html>当前 CLI 后端未支持 ACP <code>logout</code>，请点击右侧打开终端重新登录：</html>"
                                bannerActionBtn.text = "打开终端登录"
                                bannerPanel.isVisible = true
                            } else if (detectedAuthUrl == null) {
                                bannerText.text = "<html>已退出旧账号，若浏览器未自动打开授权页，请点击右侧通过终端执行 <code>agy acp --login</code>：</html>"
                                bannerActionBtn.text = "打开终端登录"
                                bannerPanel.isVisible = true
                            }
                            val card = AssistantTurnCard()
                            addCard(card)
                            card.appendBody(
                                "⚠️ 切换账号 / 重新登录未完成：${root.message}\n\n" +
                                    "排查与操作建议：\n" +
                                    "1. 若浏览器已弹出 Google 登录页，请完成账号选择授权后点击顶部 **「登录/连接」**；\n" +
                                    "2. 若未自动弹出浏览器，请点击顶部 **「终端登录」** 运行 `agy acp --login` 完成新账号授权，再点击 **「登录/连接」**；\n" +
                                    "3. 如需代理访问 Google 服务，请点击 **「设置」** 填写 `HTTP/HTTPS_PROXY`。"
                            )
                            card.finalizeTurn("error")
                            scrollToBottom()
                        }
                    }
                }
            }
            1 -> {
                acpService.logout().whenComplete { serverLoggedOut, err ->
                    runOnUi {
                        if (err != null) {
                            val root = (err as? java.util.concurrent.CompletionException)?.cause ?: err
                            Messages.showErrorDialog(project, root.message ?: "退出登录失败", "退出登录")
                        } else {
                            messagesContainer.removeAll()
                            addWelcomeCard()
                            val msg = if (serverLoggedOut) {
                                "已退出当前账号登录状态。点击顶部 <b>「登录/连接」</b> 或 <b>「切换账号」</b> 即可使用其他账号重新登录。"
                            } else {
                                "已断开当前连接（旧版 CLI 不支持 ACP <code>logout</code>，请使用顶部 <b>「终端登录」</b> 重新授权）。"
                            }
                            addHistoryBannerCard(msg)
                            messagesContainer.revalidate()
                            messagesContainer.repaint()
                            scrollToBottom()
                        }
                    }
                }
            }
        }
    }

    private fun triggerConnect() {
        val settings = GeminiAcpSettingsState.getInstance().state
        val resolved = GeminiExecutableResolver.resolveExecutable(
            settings.geminiExecutablePath,
            allowNpxFallback = settings.allowNpxFallback
        )
        if (!resolved.found) {
            bannerText.text = "<html>未检测到 <code>agy</code> (Antigravity CLI)，请先安装 https://antigravity.google 或在设置中指定路径。</html>"
            bannerActionBtn.text = "获取 Antigravity CLI"
            detectedAuthUrl = "https://antigravity.google"
            bannerPanel.isVisible = true
            return
        }
        acpService.connectAndAuthenticate().whenComplete { _, err ->
            if (err != null) {
                runOnUi {
                    val card = AssistantTurnCard()
                    addCard(card)
                    card.appendBody(
                        "⚠️ 连接或 `oauth-personal` 登录失败：${err.message}\n\n" +
                            "排查建议：\n" +
                            "1. 点击顶部 **「终端登录」** 运行 `agy acp --login` 完成 Google 浏览器登录；\n" +
                            "2. 如需代理访问 Google 服务，请点击 **「设置」** 填写 `HTTP/HTTPS_PROXY`；\n" +
                            "3. 点击 **「日志」** 查看详细输出。"
                    )
                    card.finalizeTurn("error")
                }
            }
        }
    }

    fun sendExternalPrompt(userPrompt: String, references: List<CodeReference>) {
        runOnUi {
            executePromptTurn(userPrompt, references)
        }
    }

    private fun sendFromInputBox() {
        completionPopup.hide()
        activeTrigger = null
        val text = inputArea.text.trim()
        if (text.isEmpty()) return

        val collectedRefs = mutableListOf<CodeReference>()
        collectedRefs.addAll(
            CodeReference.extractInlineReferences(text, project.basePath, bindings = knownInlinePathBindings)
        )

        knownInlinePathBindings.clear()
        suppressTriggerRefresh = true
        try {
            inputArea.text = ""
        } finally {
            suppressTriggerRefresh = false
        }
        syncReferencesBarFromInput()

        if (attachContextCheck.isSelected) {
            IdeaContextCollector.captureActiveEditorReference(project, fallbackToCaretLine = false)?.let {
                collectedRefs.add(it)
            }
        }
        refreshContextPreview()

        executePromptTurn(text, collectedRefs)
    }

    private fun executePromptTurn(userPrompt: String, references: List<CodeReference>) {
        if (isStreaming || isLoadingHistory) {
            Messages.showInfoMessage(project, "正在处理中，请稍候或先点击停止。", "Gemini 助手")
            return
        }
        val uniqueRefs = references.distinctBy {
            Triple(it.absolutePath.replace('\\', '/').lowercase(), it.startLine, it.endLine)
        }
        addCard(UserTurnCard(userPrompt, uniqueRefs.map { it.chipLabel() }))
        val assistantCard = AssistantTurnCard()
        activeAssistantCard = assistantCard
        addCard(assistantCard)
        scrollToBottom()

        val fullPrompt = CodeReference.formatPromptWithReferences(userPrompt, uniqueRefs)
        acpService.sendPrompt(fullPrompt).whenComplete { _, err ->
            if (err != null) {
                runOnUi {
                    assistantCard.appendBody("\n\n❌ 请求失败：${err.message}\n\n可点击顶部「日志」查看详细原因。")
                    assistantCard.finalizeTurn("error")
                    if (activeAssistantCard === assistantCard) {
                        activeAssistantCard = null
                    }
                }
            }
        }
    }

    private fun appendDiagnosticLog(message: String) {
        if (logArea.text.length > 20_000) {
            logArea.text = logArea.text.takeLast(10_000)
        }
        logArea.append("$message\n")
        logArea.caretPosition = logArea.document.length
    }

    private fun refreshAccountStatus() {
        val state = acpService.currentState
        val tier = acpService.lastMetadata?.tierName
        val backend = acpService.activeBackendKind
        if (loggedOutExplicitly && state != AcpClientService.ConnectionState.READY) {
            accountLabel.text = "未登录"
            return
        }
        if (backend == GeminiExecutableResolver.BackendKind.ANTIGRAVITY_AGY) {
            accountLabel.text = when {
                tier != null -> "Antigravity ($tier)"
                state == AcpClientService.ConnectionState.READY -> "Antigravity 已登录"
                else -> "未登录"
            }
            return
        }
        val info = GeminiExecutableResolver.readCachedAccountInfo()
        accountLabel.text = when {
            tier != null && info.activeEmail != null -> "${info.activeEmail} ($tier)"
            tier != null -> tier
            info.activeEmail != null -> "账号：${info.activeEmail}"
            info.hasOAuthCreds -> "已检测本地登录"
            else -> "未登录"
        }
    }

    private fun refreshContextPreview() {
        val ref = IdeaContextCollector.captureActiveEditorReference(project, fallbackToCaretLine = false)
        contextBadgeLabel.text = ref?.chipLabel() ?: "无活动编辑器"
        contextBadgeLabel.toolTipText = ref?.let { "<html>相对路径：${it.relativePath}<br/>完整路径：${it.absolutePath}</html>" }
    }

    private fun addHistoryBannerCard(text: String) {
        val banner = JPanel(BorderLayout()).apply {
            alignmentX = LEFT_ALIGNMENT
            border = CompoundBorder(
                LineBorder(JBColor.border(), 1),
                JBUI.Borders.empty(8)
            )
            background = JBColor(Color(0xF8FAFC), Color(0x1E293B))
            maximumSize = Dimension(Int.MAX_VALUE, 100)
            add(JBLabel("<html>$text</html>"), BorderLayout.CENTER)
        }
        addCard(banner)
    }

    private fun addWelcomeCard() {
        val settings = GeminiAcpSettingsState.getInstance().state
        val resolved = GeminiExecutableResolver.resolveExecutable(
            settings.geminiExecutablePath,
            allowNpxFallback = settings.allowNpxFallback
        )
        val envHint = when {
            resolved.found && resolved.backendKind == GeminiExecutableResolver.BackendKind.ANTIGRAVITY_AGY ->
                "✅ 已检测到官方 Antigravity CLI (<code>agy acp</code>)：<code>${resolved.executablePath}</code>"
            resolved.found && resolved.backendKind == GeminiExecutableResolver.BackendKind.GEMINI_CLI ->
                "⚠️ 仅检测到旧版 <code>gemini</code> CLI（个人账号需安装 Antigravity CLI <code>agy</code>）"
            resolved.found && resolved.viaNpxFallback ->
                "⚠️ 未检测到 <code>agy</code>，当前将回退到旧版 <code>npx @google/gemini-cli</code>（个人账号建议安装 <code>agy</code>）"
            else ->
                "⚠️ 未检测到 <code>agy</code> CLI，请先安装 Antigravity CLI (https://antigravity.google)"
        }
        val welcome = JPanel(BorderLayout()).apply {
            alignmentX = LEFT_ALIGNMENT
            border = CompoundBorder(
                LineBorder(JBColor.border(), 1),
                JBUI.Borders.empty(10)
            )
            background = JBColor(Color(0xF8FAFC), Color(0x1E293B))
            maximumSize = Dimension(Int.MAX_VALUE, 300)
            add(
                JBLabel(
                    "<html><b>Gemini 对话编程助手 (ACP)</b><br/>" +
                        "1. 输入 <code>@</code> 可<b>模糊搜索</b>并引用项目文件（支持文件名/驼峰首字母/路径）；输入 <code>/</code> 可模糊选择已安装的 Skills。<br/>" +
                        "2. 在左侧项目树、编辑器标签页或导航栏<b>右键文件 → 引用文件到 Gemini 对话框</b>，或在编辑器内右键引用<b>选中行/当前行</b>，仅传路径与行号不粘贴源码。<br/>" +
                        "3. 底部可切换官方 <b>模型 / 思考强度 / 模式</b>，顶部可查看并重命名 <b>历史记录</b>。<br/><br/>" +
                        "<b>环境检测：</b>$envHint</html>"
                ),
                BorderLayout.CENTER
            )
        }
        addCard(welcome)
    }

    private fun addCard(card: JPanel) {
        messagesContainer.add(card)
        messagesContainer.add(Box.createVerticalStrut(8))
        messagesContainer.revalidate()
        messagesContainer.repaint()
    }

    private fun scrollToBottom() {
        SwingUtilities.invokeLater {
            val bar = scrollPane.verticalScrollBar
            bar.value = bar.maximum
        }
    }

    override fun dispose() {
        completionPopup.hide()
        completionExecutor.shutdownNow()
        acpService.removeListener(eventListener)
        if (!project.isDisposed && project.getUserData(GeminiToolWindowFactory.CHAT_PANEL_KEY) === this) {
            project.putUserData(GeminiToolWindowFactory.CHAT_PANEL_KEY, null)
        }
    }

    private inner class UserTurnCard(prompt: String, refBadges: List<String>) : JPanel(BorderLayout(0, 4)) {
        private val area = JBTextArea(prompt).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
        }

        init {
            alignmentX = LEFT_ALIGNMENT
            border = CompoundBorder(
                LineBorder(JBColor(Color(0xBFDBFE), Color(0x1D4ED8)), 1),
                JBUI.Borders.empty(8)
            )
            background = JBColor(Color(0xEFF6FF), Color(0x172554))
            val header = if (refBadges.isNotEmpty()) {
                "你  ·  引用：${refBadges.joinToString(", ")}"
            } else {
                "你"
            }
            add(JBLabel("<html><b>$header</b></html>"), BorderLayout.NORTH)
            add(area, BorderLayout.CENTER)
        }

        fun appendText(chunk: String) {
            area.append(chunk)
            revalidate()
        }

        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    private inner class AssistantTurnCard(showPendingPlaceholder: Boolean = true) : JPanel() {
        private val thoughtLabel = JBLabel()
        private val toolsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        private val toolLabels = mutableMapOf<String, JBLabel>()
        private val bodyPane = JEditorPane("text/html", "").apply {
            isEditable = false
            isOpaque = false
            addHyperlinkListener { ev ->
                if (ev.eventType == HyperlinkEvent.EventType.ACTIVATED && ev.url != null) {
                    BrowserUtil.browse(ev.url)
                }
            }
        }
        private val actionsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            isOpaque = false
        }
        private val bodyBuffer = StringBuilder()
        private val thoughtBuffer = StringBuilder()

        init {
            alignmentX = LEFT_ALIGNMENT
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = CompoundBorder(
                LineBorder(JBColor.border(), 1),
                JBUI.Borders.empty(8)
            )
            background = JBColor(Color(0xFFFFFF), Color(0x1F242D))

            add(JBLabel("<html><b>Gemini 助手</b></html>").apply { alignmentX = LEFT_ALIGNMENT })
            thoughtLabel.apply {
                isVisible = false
                foreground = JBColor.GRAY
                alignmentX = LEFT_ALIGNMENT
            }
            add(thoughtLabel)
            toolsPanel.alignmentX = LEFT_ALIGNMENT
            add(toolsPanel)
            bodyPane.alignmentX = LEFT_ALIGNMENT
            add(bodyPane)
            actionsPanel.alignmentX = LEFT_ALIGNMENT
            add(actionsPanel)
            if (showPendingPlaceholder) {
                renderBody("⏳ 正在连接并思考中...")
            }
        }

        fun appendThought(chunk: String) {
            thoughtBuffer.append(chunk)
            val preview = thoughtBuffer.toString().trim().takeLast(180).replace('\n', ' ')
            thoughtLabel.text = "<html><i>思考过程：${escapeQuick(preview)}</i></html>"
            thoughtLabel.isVisible = true
        }

        fun upsertToolCall(ev: AcpModels.SessionUpdateEvent.ToolCallEvent) {
            val label = toolLabels.getOrPut(ev.toolCallId) {
                JBLabel().also {
                    it.border = JBUI.Borders.empty(2, 0)
                    toolsPanel.add(it)
                }
            }
            val statusIcon = when (ev.status) {
                "completed" -> "✅"
                "failed" -> "❌"
                else -> "🔧"
            }
            val statusText = when (ev.status) {
                "completed" -> "已完成"
                "failed" -> "失败"
                "pending" -> "等待中"
                else -> "执行中"
            }
            val locText = if (ev.locations.isNotEmpty()) {
                " (${ev.locations.joinToString(", ") { CodeReference.shortenLocationDisplay(it) }})"
            } else {
                ""
            }
            label.text = "$statusIcon ${ev.title}$locText [$statusText]"
            label.toolTipText = if (ev.locations.isNotEmpty()) ev.locations.joinToString("\n") else null
            toolsPanel.revalidate()
        }

        fun appendPlan(summary: String) {
            if (summary.isNotBlank()) {
                appendBody("\n**执行计划：**\n$summary\n\n")
            }
        }

        fun appendSystemNote(msg: String) {
            appendBody("\n> $msg\n")
        }

        fun appendBody(chunk: String) {
            bodyBuffer.append(chunk)
            renderBody(bodyBuffer.toString())
        }

        fun finalizeTurn(stopReason: String?) {
            if (bodyBuffer.isEmpty() && stopReason != null) {
                renderBody("（已完成：$stopReason）")
            }
            actionsPanel.removeAll()
            val blocks = MarkdownToHtmlConverter.extractCodeBlocks(bodyBuffer.toString())
            blocks.forEachIndexed { idx, block ->
                val applyBtn = JButton("Diff 应用代码块 #${idx + 1}").apply {
                    addActionListener {
                        val applied = EditorDiffApplyUtil.applyCodeBlockToActiveEditor(project, block.code)
                        if (!applied) {
                            Messages.showInfoMessage(project, "已取消应用，或当前没有打开的可编辑文件。", "应用代码")
                        }
                    }
                }
                val copyBtn = JButton("复制 #${idx + 1}").apply {
                    addActionListener {
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(block.code), null)
                    }
                }
                actionsPanel.add(applyBtn)
                actionsPanel.add(copyBtn)
            }
            actionsPanel.revalidate()
            actionsPanel.repaint()
        }

        private fun renderBody(md: String) {
            val textHex = "#" + ColorUtil.toHex(UIUtil.getLabelForeground())
            val codeBgHex = "#" + ColorUtil.toHex(JBColor(Color(0xF1F5F9), Color(0x0F172A)))
            val borderHex = "#" + ColorUtil.toHex(JBColor.border())
            bodyPane.text = MarkdownToHtmlConverter.toHtml(md, textHex, codeBgHex, borderHex)
            revalidate()
        }

        private fun escapeQuick(s: String): String =
            s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    }
}
