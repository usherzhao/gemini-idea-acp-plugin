package com.geminicode.acp.ui

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.nio.file.Paths
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * 官方 ACP `sessionCapabilities.additionalDirectories` 管理弹窗：
 * 允许为当前项目添加多个跨仓库/关联工作区目录，并同步放行本地文件读取/Diff 安全白名单。
 */
class AdditionalDirectoriesDialog(
    private val project: Project,
    initialDirectories: List<String>
) : DialogWrapper(project, true) {

    private val listModel = DefaultListModel<String>()
    private val dirList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        emptyText.text = "暂未添加关联目录（点击下方「添加目录...」引入其他项目或微服务目录）"
    }
    private val addButton = JButton("添加目录...")
    private val removeButton = JButton("移除选中").apply { isEnabled = false }

    init {
        title = "管理关联工作区目录 (additionalDirectories)"
        setOKButtonText("保存并应用")
        setCancelButtonText("取消")
        initialDirectories.distinct().forEach { listModel.addElement(it) }
        init()
        wireEvents()
    }

    fun getSelectedDirectories(): List<String> {
        val result = mutableListOf<String>()
        for (i in 0 until listModel.size()) {
            result.add(listModel.getElementAt(i))
        }
        return result
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 8)).apply {
            preferredSize = Dimension(580, 320)
        }
        val hint = JBLabel(
            "<html>将以下目录作为官方 ACP <code>additionalDirectories</code> 挂载到当前对话，适合前后端分离或多微服务联调。<br/>" +
                "挂载后 AI 可直接读取并在 Diff 确认后编辑这些目录内的文件。</html>"
        )
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(addButton)
            add(removeButton)
        }
        root.add(hint, BorderLayout.NORTH)
        root.add(JBScrollPane(dirList), BorderLayout.CENTER)
        root.add(buttons, BorderLayout.SOUTH)
        return root
    }

    private fun wireEvents() {
        dirList.addListSelectionListener {
            removeButton.isEnabled = !dirList.isSelectionEmpty
        }
        addButton.addActionListener {
            val descriptor = FileChooserDescriptorFactory.createMultipleFoldersDescriptor()
                .withTitle("选择要关联的工作区目录")
                .withDescription("可多选项目外的关联文件夹（如前端/公共库/其他微服务目录）")
            val chosen = FileChooser.chooseFiles(descriptor, project, null)
            for (vFile in chosen) {
                if (!vFile.isDirectory) continue
                val norm = runCatching {
                    Paths.get(vFile.path).toAbsolutePath().normalize().toString().replace('\\', '/')
                }.getOrDefault(vFile.path.replace('\\', '/'))
                if (!containsIgnoreCase(norm)) {
                    listModel.addElement(norm)
                }
            }
        }
        removeButton.addActionListener {
            val indices = dirList.selectedIndices
            for (idx in indices.sortedDescending()) {
                listModel.removeElementAt(idx)
            }
        }
    }

    private fun containsIgnoreCase(path: String): Boolean {
        for (i in 0 until listModel.size()) {
            if (listModel.getElementAt(i).equals(path, ignoreCase = true)) return true
        }
        return false
    }
}
