package com.geminicode.acp.ui

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.border.CompoundBorder

/**
 * 基于 IntelliJ 原生 JBPopup 的非抢占焦点补全弹窗：
 * 持续输入模糊搜索字符时原地刷新列表，不抢占输入框焦点、不闪烁，支持 ↑↓ 选择、Enter/Tab 或单击确认。
 */
class InputCompletionPopup(
    private val anchorComponent: Component,
    private val onItemChosen: (CompletionItem) -> Unit
) {

    data class CompletionItem(
        val iconPrefix: String,
        val primaryText: String,
        val secondaryText: String,
        val insertText: String,
        val payload: Any? = null
    )

    private val listModel = DefaultListModel<CompletionItem>()
    private val list = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        isFocusable = false
        cellRenderer = CompletionCellRenderer()
    }
    private val headerLabel = JBLabel().apply {
        border = JBUI.Borders.empty(4, 8)
        foreground = JBColor.GRAY
        font = font.deriveFont(Font.BOLD, 11f)
    }
    private val scrollPane = JBScrollPane(list).apply {
        border = JBUI.Borders.empty()
        isFocusable = false
    }
    private val contentPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.customLine(JBColor.border(), 1)
        isFocusable = false
        add(headerLabel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
    }

    private var activePopup: JBPopup? = null

    init {
        list.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                val idx = list.locationToIndex(e.point)
                if (idx >= 0) {
                    list.selectedIndex = idx
                    confirmSelection()
                }
            }
        })
    }

    val isVisible: Boolean
        get() = activePopup?.let { !it.isDisposed && it.isVisible } == true

    fun showItems(title: String, items: List<CompletionItem>) {
        if (items.isEmpty()) {
            hide()
            return
        }
        if (!anchorComponent.isShowing) return

        headerLabel.text = title
        listModel.clear()
        items.forEach { listModel.addElement(it) }
        list.selectedIndex = 0
        list.ensureIndexIsVisible(0)

        val width = anchorComponent.width.coerceIn(340, 580)
        val rowHeight = 40
        val height = (items.size.coerceAtMost(7) * rowHeight + 28).coerceAtMost(310)
        contentPanel.preferredSize = Dimension(width, height)

        val anchorOnScreen = runCatching { anchorComponent.locationOnScreen }.getOrNull() ?: return
        val screenBounds = anchorComponent.graphicsConfiguration?.bounds
        val popupX = if (screenBounds != null) {
            val maxX = (screenBounds.x + screenBounds.width - width - 4).coerceAtLeast(screenBounds.x + 4)
            anchorOnScreen.x.coerceIn(screenBounds.x + 4, maxX)
        } else {
            anchorOnScreen.x
        }
        val minY = (screenBounds?.y ?: 0) + 10
        val popupY = (anchorOnScreen.y - height - 6).coerceAtLeast(minY)
        val targetPoint = Point(popupX, popupY)

        val existing = activePopup
        if (existing != null && !existing.isDisposed && existing.isVisible) {
            existing.size = Dimension(width, height)
            existing.setLocation(targetPoint)
            contentPanel.revalidate()
            contentPanel.repaint()
            return
        }

        hide()
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(contentPanel, null)
            .setRequestFocus(false)
            .setFocusable(false)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .setCancelKeyEnabled(false)
            .setMovable(false)
            .setResizable(false)
            .createPopup()

        activePopup = popup
        popup.show(RelativePoint.fromScreen(targetPoint))
    }

    fun hide() {
        val existing = activePopup
        activePopup = null
        if (existing != null && !existing.isDisposed) {
            existing.cancel()
        }
    }

    fun moveSelection(delta: Int) {
        if (!isVisible || listModel.isEmpty) return
        val size = listModel.size()
        val next = (list.selectedIndex + delta + size) % size
        list.selectedIndex = next
        list.ensureIndexIsVisible(next)
    }

    fun confirmSelection(): Boolean {
        if (!isVisible) return false
        val item = list.selectedValue ?: return false
        hide()
        onItemChosen(item)
        return true
    }

    private class CompletionCellRenderer : ListCellRenderer<CompletionItem> {
        override fun getListCellRendererComponent(
            list: JList<out CompletionItem>,
            value: CompletionItem,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val panel = JPanel(BorderLayout(6, 1)).apply {
                border = CompoundBorder(
                    JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                    JBUI.Borders.empty(4, 8)
                )
                background = if (isSelected) list.selectionBackground else list.background
            }

            val mainLabel = JBLabel("${value.iconPrefix} ${value.primaryText}").apply {
                font = font.deriveFont(Font.BOLD, 12f)
                foreground = if (isSelected) list.selectionForeground else list.foreground
            }
            val subText = value.secondaryText.replace('\n', ' ').let {
                if (it.length > 90) it.take(90) + "..." else it
            }
            val subLabel = JBLabel(subText).apply {
                font = font.deriveFont(Font.PLAIN, 11f)
                foreground = if (isSelected) list.selectionForeground else JBColor(Color(0x64748B), Color(0x94A3B8))
            }

            panel.add(mainLabel, BorderLayout.NORTH)
            if (subText.isNotBlank()) {
                panel.add(subLabel, BorderLayout.SOUTH)
            }
            return panel
        }
    }
}
