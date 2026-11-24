package com.geminicode.acp.ui

import com.geminicode.acp.ide.AccessLevel
import com.geminicode.acp.ide.DataPermissionConfig
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Point
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.border.CompoundBorder

/**
 * 聊天面板上的轻量级「数据与操作权限」快捷弹出面板（无需打开复杂设置页即可实时切换）。
 */
object DataPermissionPopover {

    fun show(
        anchor: JComponent,
        initialConfig: DataPermissionConfig,
        onConfigChanged: (DataPermissionConfig) -> Unit
    ) {
        val content = createContentPanel(initialConfig, onConfigChanged)
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, null)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .setCancelKeyEnabled(true)
            .setResizable(false)
            .setMovable(false)
            .createPopup()

        val pref = content.preferredSize
        val anchorOnScreen = runCatching { anchor.locationOnScreen }.getOrNull()
        val showAbove = anchorOnScreen != null && anchorOnScreen.y > pref.height + JBUI.scale(24)
        if (showAbove) {
            val xOffset = (anchor.width - pref.width).coerceAtMost(0)
            popup.show(RelativePoint(anchor, Point(xOffset, -pref.height - JBUI.scale(6))))
        } else {
            popup.showUnderneathOf(anchor)
        }
    }

    internal fun createContentPanel(
        initialConfig: DataPermissionConfig,
        onConfigChanged: (DataPermissionConfig) -> Unit
    ): JPanel {
        var current = initialConfig
        var updating = false

        val root = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(10, 12)
        }

        val readOnlyCheck = JBCheckBox("只读模式（强制禁止写入或修改任何文件）").apply {
            font = font.deriveFont(Font.BOLD, 12f)
        }

        val readRow = SegmentedLevelRow("读文件：")
        val writeRow = SegmentedLevelRow("写文件：")
        val commandRow = SegmentedLevelRow("终端命令：")

        val statusHint = JBLabel().apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(Font.PLAIN, 11f)
        }

        fun syncUiFromCurrent() {
            updating = true
            try {
                readOnlyCheck.isSelected = current.readOnlyMode
                readRow.select(current.readFile)
                writeRow.select(current.effectiveWriteLevel())
                writeRow.setButtonsEnabled(!current.readOnlyMode)
                writeRow.setDisabledTooltip(
                    if (current.readOnlyMode) "只读模式已锁定写入为「禁止」，取消勾选只读模式后可调整" else null
                )
                commandRow.select(current.command)

                statusHint.text = when {
                    current.readOnlyMode -> "🔒 只读保护已生效：任何文件修改请求将被自动拦截"
                    current.isFullAutoPreset() -> "⚡ 全自动模式：读/写/命令均自动放行，请确认工作区安全"
                    else -> "变更实时生效；收紧权限时会自动同步至 Default 审批模式"
                }
                statusHint.foreground = when {
                    current.readOnlyMode -> JBColor(Color(0x1D4ED8), Color(0x60A5FA))
                    current.isFullAutoPreset() -> JBColor(Color(0xB45309), Color(0xFBBF24))
                    else -> JBColor.GRAY
                }
            } finally {
                updating = false
            }
        }

        fun commit(next: DataPermissionConfig) {
            if (next == current) return
            current = next
            syncUiFromCurrent()
            onConfigChanged(current)
        }

        val readOnlyPresetBtn = createPresetButton("🔒 只读", "读自动 / 写禁止 / 命令禁止") {
            commit(DataPermissionConfig.READ_ONLY)
        }
        val standardPresetBtn = createPresetButton("🛡️ 标准", "读自动 / 写确认 / 命令确认") {
            commit(DataPermissionConfig.STANDARD)
        }
        val autoPresetBtn = createPresetButton("⚡ 全自动", "读自动 / 写自动 / 命令自动") {
            commit(DataPermissionConfig.FULL_AUTO)
        }

        readOnlyCheck.addActionListener {
            if (updating) return@addActionListener
            val enabled = readOnlyCheck.isSelected
            val next = if (enabled) {
                current.copy(
                    readOnlyMode = true,
                    command = if (current.command == AccessLevel.AUTO) AccessLevel.ASK else current.command
                )
            } else {
                current.copy(
                    readOnlyMode = false,
                    writeFile = if (current.writeFile == AccessLevel.DENY) AccessLevel.ASK else current.writeFile
                )
            }
            commit(next)
        }

        readRow.onSelected = { level ->
            if (!updating) commit(current.copy(readFile = level))
        }
        writeRow.onSelected = { level ->
            if (!updating && !current.readOnlyMode) commit(current.copy(writeFile = level))
        }
        commandRow.onSelected = { level ->
            if (!updating) commit(current.copy(command = level))
        }

        val headerRow = JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
            isOpaque = false
            border = CompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.emptyBottom(6)
            )
            add(JBLabel("<html><b>数据与操作权限</b></html>"), BorderLayout.WEST)
            val presets = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
                isOpaque = false
                add(readOnlyPresetBtn)
                add(standardPresetBtn)
                add(autoPresetBtn)
            }
            add(presets, BorderLayout.EAST)
        }

        val matrixPanel = JPanel(GridBagLayout()).apply {
            isOpaque = false
        }
        readRow.addToGrid(matrixPanel, 0)
        writeRow.addToGrid(matrixPanel, 1)
        commandRow.addToGrid(matrixPanel, 2)

        val centerStack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            readOnlyCheck.alignmentX = JComponent.LEFT_ALIGNMENT
            matrixPanel.alignmentX = JComponent.LEFT_ALIGNMENT
            add(readOnlyCheck)
            add(Box.createVerticalStrut(JBUI.scale(6)))
            add(matrixPanel)
        }

        val footerPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = CompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
                JBUI.Borders.emptyTop(6)
            )
            add(statusHint, BorderLayout.CENTER)
        }

        root.add(headerRow, BorderLayout.NORTH)
        root.add(centerStack, BorderLayout.CENTER)
        root.add(footerPanel, BorderLayout.SOUTH)

        syncUiFromCurrent()
        return root
    }

    private fun createPresetButton(text: String, tooltip: String, onClick: () -> Unit): JButton {
        return JButton(text).apply {
            font = font.deriveFont(11f)
            margin = Insets(1, 6, 1, 6)
            toolTipText = tooltip
            addActionListener { onClick() }
        }
    }

    private class SegmentedLevelRow(private val labelText: String) {
        private val group = ButtonGroup()
        private val buttons = linkedMapOf<AccessLevel, JToggleButton>()
        var onSelected: ((AccessLevel) -> Unit)? = null

        init {
            for (level in AccessLevel.entries) {
                val btn = JToggleButton(level.displayName).apply {
                    font = font.deriveFont(11f)
                    margin = Insets(2, 8, 2, 8)
                    isFocusable = false
                    addActionListener {
                        if (isSelected) {
                            onSelected?.invoke(level)
                        }
                    }
                }
                group.add(btn)
                buttons[level] = btn
            }
        }

        fun addToGrid(container: JPanel, row: Int) {
            container.add(
                JBLabel(labelText),
                GridBagConstraints().apply {
                    gridx = 0
                    gridy = row
                    anchor = GridBagConstraints.WEST
                    insets = Insets(3, 0, 3, 8)
                }
            )
            val btnStrip = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
                isOpaque = false
                buttons.values.forEach { add(it) }
            }
            container.add(
                btnStrip,
                GridBagConstraints().apply {
                    gridx = 1
                    gridy = row
                    weightx = 1.0
                    anchor = GridBagConstraints.WEST
                    fill = GridBagConstraints.HORIZONTAL
                    insets = Insets(2, 0, 2, 0)
                }
            )
        }

        fun select(level: AccessLevel) {
            buttons.forEach { (lv, btn) ->
                val active = lv == level
                btn.isSelected = active
                btn.font = btn.font.deriveFont(if (active) Font.BOLD else Font.PLAIN, 11f)
            }
        }

        fun setButtonsEnabled(enabled: Boolean) {
            buttons.values.forEach { it.isEnabled = enabled }
        }

        fun setDisabledTooltip(tip: String?) {
            buttons.values.forEach { it.toolTipText = tip }
        }
    }
}
