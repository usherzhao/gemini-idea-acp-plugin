package com.geminicode.acp.ide

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.border.EmptyBorder

/**
 * 展示 IntelliJ IDEA 原生 Diff 预览弹窗，并通过 WriteCommandAction 安全应用代码修改。
 */
object EditorDiffApplyUtil {

    class DiffPreviewDialog(
        private val project: Project,
        dialogTitle: String,
        private val fileName: String,
        private val headerSummary: String,
        private val originalText: String,
        private val proposedText: String
    ) : DialogWrapper(project, true) {

        private var diffPanel: DiffRequestPanel? = null

        init {
            title = dialogTitle
            setOKButtonText("应用修改")
            setCancelButtonText("拒绝修改")
            init()
        }

        override fun createCenterPanel(): JComponent {
            val container = JPanel(BorderLayout(0, 8))
            container.preferredSize = Dimension(960, 620)

            val headerLabel = JLabel(headerSummary).apply {
                border = EmptyBorder(2, 4, 4, 4)
            }
            container.add(headerLabel, BorderLayout.NORTH)

            val panel = DiffManager.getInstance().createRequestPanel(project, disposable, null)
            diffPanel = panel

            val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)
            val left = DiffContentFactory.getInstance().create(project, originalText, fileType)
            val right = DiffContentFactory.getInstance().create(project, proposedText, fileType)
            val request = SimpleDiffRequest(
                "代码变更预览：$fileName",
                left,
                right,
                "当前内容",
                "Gemini 建议修改"
            )
            panel.setRequest(request)
            container.add(panel.component, BorderLayout.CENTER)
            return container
        }
    }

    fun showDiffAndConfirm(
        project: Project,
        targetFileName: String,
        displayPath: String,
        originalText: String,
        proposedText: String,
        isNewFile: Boolean
    ): Boolean {
        val summary = if (isNewFile) {
            "新建文件：$displayPath"
        } else {
            "修改文件：$displayPath"
        }
        val dialog = DiffPreviewDialog(
            project = project,
            dialogTitle = "Gemini 代码修改确认",
            fileName = targetFileName,
            headerSummary = summary,
            originalText = originalText,
            proposedText = proposedText
        )
        return dialog.showAndGet()
    }

    fun readDocumentOrDiskText(path: Path): String? {
        val vFile = LocalFileSystem.getInstance().findFileByNioFile(path)
        if (vFile != null && vFile.isValid && !vFile.isDirectory) {
            val docText = ApplicationManager.getApplication().runReadAction<String?> {
                FileDocumentManager.getInstance().getDocument(vFile)?.text
            }
            if (docText != null) return docText
        }
        return if (Files.isRegularFile(path)) {
            Files.readString(path, StandardCharsets.UTF_8)
        } else {
            null
        }
    }

    /**
     * 在后台线程可提前调用此方法确保父目录存在，避免在 EDT 上做阻塞磁盘操作。
     */
    fun ensureParentDirectoryExistsOffEdt(path: Path) {
        val parent = path.parent
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent)
        }
    }

    fun applyTextToFile(project: Project, path: Path, newContent: String) {
        val normalizedContent = StringUtil.convertLineSeparators(newContent)
        val vFile = LocalFileSystem.getInstance().findFileByNioFile(path)
        if (vFile != null && vFile.isValid && !vFile.isDirectory) {
            val doc: Document? = ApplicationManager.getApplication().runReadAction<Document?> {
                FileDocumentManager.getInstance().getDocument(vFile)
            }
            if (doc != null) {
                WriteCommandAction.runWriteCommandAction(project, "Gemini 代码修改", null, {
                    doc.setText(normalizedContent)
                })
                return
            }
        }

        WriteCommandAction.runWriteCommandAction(project, "Gemini 代码修改", null, {
            val targetVFile = if (vFile != null && vFile.isValid) {
                vFile
            } else {
                val parentPath = path.parent?.toString()?.replace('\\', '/')
                val parentVFile = if (parentPath != null) VfsUtil.createDirectoryIfMissing(parentPath) else null
                parentVFile?.findOrCreateChildData(this, path.fileName.toString())
            }
            if (targetVFile != null) {
                targetVFile.setBinaryContent(newContent.toByteArray(StandardCharsets.UTF_8))
                VfsUtil.markDirtyAndRefresh(true, false, false, targetVFile)
            } else {
                Files.writeString(path, newContent, StandardCharsets.UTF_8)
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
            }
        })
    }

    fun applyCodeBlockToActiveEditor(project: Project, proposedSnippet: String): Boolean {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return false
        val document = editor.document
        val vFile = FileDocumentManager.getInstance().getFile(document) ?: return false
        val selectionModel = editor.selectionModel

        val originalFull = document.text
        val newFull = if (selectionModel.hasSelection()) {
            val start = selectionModel.selectionStart
            val end = selectionModel.selectionEnd
            originalFull.substring(0, start) + StringUtil.convertLineSeparators(proposedSnippet) + originalFull.substring(end)
        } else {
            StringUtil.convertLineSeparators(proposedSnippet)
        }

        val confirmed = showDiffAndConfirm(
            project = project,
            targetFileName = vFile.name,
            displayPath = vFile.path,
            originalText = originalFull,
            proposedText = newFull,
            isNewFile = false
        )
        if (!confirmed) return false

        WriteCommandAction.runWriteCommandAction(project, "应用 Gemini 代码片段", null, {
            document.setText(newFull)
        })
        return true
    }
}
