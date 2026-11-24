package com.geminicode.acp.ui

import com.geminicode.acp.protocol.AcpClientService
import com.geminicode.acp.protocol.AcpModels
import com.geminicode.acp.protocol.SessionPathUtil
import com.geminicode.acp.settings.GeminiAcpSettingsState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Action
import javax.swing.AbstractAction
import javax.swing.DefaultListModel
import java.awt.event.ActionEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.border.CompoundBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * 历史对话管理弹窗：基于官方 ACP `session/list` 展示历史会话，支持搜索、按当前项目筛选、
 * 右键/F2/按钮修改对话名称以及双击一键切换。
 */
class SessionHistoryDialog(
    private val project: Project,
    private val acpService: AcpClientService
) : DialogWrapper(project, true) {

    private val searchField = JBTextField().apply {
        emptyText.text = "按对话标题或目录关键词搜索..."
    }
    private val currentProjectOnlyCheck = JBCheckBox("仅显示当前项目", false)
    private val renameButton = JButton("重命名").apply {
        isEnabled = false
        toolTipText = "修改选中对话的显示名称（快捷键 F2）"
    }
    private val refreshButton = JButton("刷新")
    private val statusLabel = JBLabel("正在获取历史会话...")
    private val listModel = DefaultListModel<AcpModels.SessionSummary>()
    private val sessionList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = SessionCellRenderer()
    }

    private var allSessions: List<AcpModels.SessionSummary> = emptyList()
    var selectedSession: AcpModels.SessionSummary? = null
        private set
    var resumeMode: Boolean = false
        private set

    private val resumeDialogAction = object : AbstractAction("快速续聊 (Resume)") {
        init {
            putValue(SHORT_DESCRIPTION, "通过官方 ACP session/resume 秒切上下文（不回放历史消息，适合超长对话）")
            isEnabled = false
        }

        override fun actionPerformed(e: ActionEvent?) {
            if (selectedSession == null) return
            resumeMode = true
            doOKAction()
        }
    }

    init {
        title = "聊天对话历史记录"
        setOKButtonText("加载并回放对话")
        setCancelButtonText("关闭")
        init()
        isOKActionEnabled = false
        wireEvents()
        loadSessionsAsync()
    }

    override fun createActions(): Array<Action> {
        return arrayOf(okAction, resumeDialogAction, cancelAction)
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 8)).apply {
            preferredSize = Dimension(660, 450)
        }

        val rightControls = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            add(currentProjectOnlyCheck)
            add(renameButton)
            add(refreshButton)
        }
        val filterBar = JPanel(BorderLayout(8, 0)).apply {
            add(searchField, BorderLayout.CENTER)
            add(rightControls, BorderLayout.EAST)
        }

        val scrollPane = JBScrollPane(sessionList)
        root.add(filterBar, BorderLayout.NORTH)
        root.add(scrollPane, BorderLayout.CENTER)
        root.add(statusLabel, BorderLayout.SOUTH)
        return root
    }

    private fun wireEvents() {
        refreshButton.addActionListener { loadSessionsAsync() }
        renameButton.addActionListener { renameSelectedSession() }
        currentProjectOnlyCheck.addActionListener { applyFilter() }
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = applyFilter()
            override fun removeUpdate(e: DocumentEvent?) = applyFilter()
            override fun changedUpdate(e: DocumentEvent?) = applyFilter()
        })
        sessionList.addListSelectionListener {
            selectedSession = sessionList.selectedValue
            val hasSelection = selectedSession != null
            isOKActionEnabled = hasSelection
            resumeDialogAction.isEnabled = hasSelection && acpService.capabilities.sessionResume
            renameButton.isEnabled = hasSelection
        }
        sessionList.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_F2 && sessionList.selectedValue != null) {
                    e.consume()
                    renameSelectedSession()
                }
            }
        })
        sessionList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e) && e.clickCount == 2 && sessionList.selectedValue != null) {
                    selectedSession = sessionList.selectedValue
                    doOKAction()
                }
            }

            override fun mousePressed(e: MouseEvent) = maybeShowContextMenu(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowContextMenu(e)
        })
    }

    private fun maybeShowContextMenu(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val idx = sessionList.locationToIndex(e.point)
        if (idx >= 0) {
            sessionList.selectedIndex = idx
        }
        val target = sessionList.selectedValue ?: return
        val hasCustom = GeminiAcpSettingsState.getInstance().getCustomTitle(target.sessionId) != null

        val popup = JPopupMenu()
        popup.add(JMenuItem("加载并回放对话 (session/load)").apply {
            addActionListener {
                selectedSession = target
                resumeMode = false
                doOKAction()
            }
        })
        if (acpService.capabilities.sessionResume) {
            popup.add(JMenuItem("快速续聊不回放 (session/resume)").apply {
                addActionListener {
                    selectedSession = target
                    resumeMode = true
                    doOKAction()
                }
            })
        }
        popup.addSeparator()
        popup.add(JMenuItem("重命名对话...").apply {
            addActionListener { renameSessionItem(target) }
        })
        if (hasCustom) {
            popup.add(JMenuItem("恢复默认名称").apply {
                addActionListener {
                    acpService.renameSession(target.sessionId, null, target.title)
                    sessionList.repaint()
                    applyFilter(preserveSessionId = target.sessionId)
                }
            })
        }
        popup.show(sessionList, e.x, e.y)
    }

    private fun renameSelectedSession() {
        val target = sessionList.selectedValue ?: return
        renameSessionItem(target)
    }

    private fun renameSessionItem(target: AcpModels.SessionSummary) {
        val settings = GeminiAcpSettingsState.getInstance()
        val currentDisplay = settings.resolveDisplayTitle(target.sessionId, target.title)
        val input = Messages.showInputDialog(
            project,
            "请输入新的对话名称（留空确定可恢复默认标题）：",
            "修改对话名称",
            Messages.getQuestionIcon(),
            currentDisplay,
            null
        ) ?: return

        acpService.renameSession(target.sessionId, input, target.title)
        applyFilter(preserveSessionId = target.sessionId)
    }

    private fun loadSessionsAsync() {
        refreshButton.isEnabled = false
        statusLabel.text = "正在从官方 ACP 服务拉取历史记录..."
        acpService.listSessions().whenComplete { sessions, err ->
            // 模态弹窗打开期间必须使用 ModalityState.any()，否则默认的 nonModal 会被阻塞到弹窗关闭后才执行
            ApplicationManager.getApplication().invokeLater({
                if (isDisposed) return@invokeLater
                refreshButton.isEnabled = true
                if (err != null) {
                    statusLabel.text = "加载历史记录失败：${err.message}"
                    return@invokeLater
                }
                allSessions = sessions.orEmpty()
                val currentCount = allSessions.count { SessionPathUtil.isSameProject(it.cwd, project.basePath) }
                if (currentCount == 0 && allSessions.isNotEmpty() && currentProjectOnlyCheck.isSelected) {
                    currentProjectOnlyCheck.isSelected = false
                }
                applyFilter()
            }, ModalityState.any())
        }
    }

    private fun applyFilter(preserveSessionId: String? = null) {
        val settings = GeminiAcpSettingsState.getInstance()
        val keyword = searchField.text.trim().lowercase()
        val onlyCurrent = currentProjectOnlyCheck.isSelected
        val filtered = allSessions.filter { s ->
            val projectMatch = !onlyCurrent || SessionPathUtil.isSameProject(s.cwd, project.basePath)
            val resolvedTitle = settings.resolveDisplayTitle(s.sessionId, s.title).lowercase()
            val rawTitle = s.title.orEmpty().lowercase()
            val kwMatch = keyword.isEmpty() ||
                resolvedTitle.contains(keyword) ||
                rawTitle.contains(keyword) ||
                SessionPathUtil.normalizeSessionCwd(s.cwd).lowercase().contains(keyword)
            projectMatch && kwMatch
        }

        listModel.clear()
        filtered.forEach { listModel.addElement(it) }

        val targetId = preserveSessionId ?: acpService.activeSessionId
        val targetIndex = filtered.indexOfFirst { it.sessionId == targetId }
        if (targetIndex >= 0) {
            sessionList.selectedIndex = targetIndex
        } else if (filtered.isNotEmpty()) {
            sessionList.selectedIndex = 0
        }

        selectedSession = sessionList.selectedValue
        val hasSelection = selectedSession != null
        isOKActionEnabled = hasSelection
        resumeDialogAction.isEnabled = hasSelection && acpService.capabilities.sessionResume
        renameButton.isEnabled = hasSelection
        statusLabel.text = if (onlyCurrent) {
            "共 ${allSessions.size} 条历史记录，当前项目显示 ${filtered.size} 条（取消勾选可看全部；双击切换，右键/F2 重命名）"
        } else {
            "共 ${allSessions.size} 条历史记录，当前显示 ${filtered.size} 条（勾选「仅显示当前项目」可按目录筛选；双击切换，右键/F2 重命名）"
        }
    }

    private inner class SessionCellRenderer : ListCellRenderer<AcpModels.SessionSummary> {
        override fun getListCellRendererComponent(
            list: JList<out AcpModels.SessionSummary>,
            value: AcpModels.SessionSummary,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val settings = GeminiAcpSettingsState.getInstance()
            val isActive = value.sessionId == acpService.activeSessionId
            val customTitle = settings.getCustomTitle(value.sessionId)
            val effectiveTitle = settings.resolveDisplayTitle(value.sessionId, value.title)

            val panel = JPanel(BorderLayout(8, 2)).apply {
                border = CompoundBorder(
                    JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                    JBUI.Borders.empty(6, 8)
                )
                background = if (isSelected) list.selectionBackground else list.background
            }

            val prefix = if (isActive) "● [当前] " else ""
            val titleLabel = JBLabel(prefix + effectiveTitle).apply {
                font = font.deriveFont(if (isActive) Font.BOLD else Font.PLAIN)
                foreground = when {
                    isSelected -> list.selectionForeground
                    isActive -> JBColor(Color(0x1D4ED8), Color(0x60A5FA))
                    else -> list.foreground
                }
            }

            val timeLabel = JBLabel(SessionPathUtil.formatUpdatedAt(value.updatedAt)).apply {
                foreground = if (isSelected) list.selectionForeground else JBColor.GRAY
                font = font.deriveFont(11f)
            }

            val topRow = JPanel(BorderLayout(8, 0)).apply {
                isOpaque = false
                add(titleLabel, BorderLayout.CENTER)
                add(timeLabel, BorderLayout.EAST)
            }

            val normPath = SessionPathUtil.normalizeSessionCwd(value.cwd)
            val subText = buildString {
                if (normPath.isNotBlank()) append("目录：$normPath") else append("会话 ID：${value.sessionId.take(12)}")
                if (customTitle != null && !value.title.isNullOrBlank() && value.title != customTitle) {
                    append("  ·  原标题：${value.title}")
                }
            }
            val subLabel = JBLabel(subText).apply {
                foreground = if (isSelected) list.selectionForeground else JBColor.GRAY
                font = font.deriveFont(11f)
            }

            panel.add(topRow, BorderLayout.NORTH)
            panel.add(subLabel, BorderLayout.SOUTH)
            return panel
        }
    }
}
