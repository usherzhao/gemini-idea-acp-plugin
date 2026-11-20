package com.geminicode.acp.ide

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Paths

/**
 * 采集 IntelliJ IDEA 活动编辑器或项目树中的轻量级代码位置引用（仅文件路径与 1-based 行号范围，不复制整段源码）。
 */
object IdeaContextCollector {

    /**
     * 捕获活动编辑器的位置引用：
     * - 若存在选中内容，返回选中起始行至结束行（自动处理末尾换行符落在下一行第 0 列的情况）；
     * - 若未选中内容，当 [fallbackToCaretLine] 为 true 时返回光标所在单行，否则返回文件级引用。
     */
    fun captureActiveEditorReference(project: Project, fallbackToCaretLine: Boolean = false): CodeReference? {
        return ReadAction.compute<CodeReference?, RuntimeException> {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@compute null
            captureFromEditor(project, editor, fallbackToCaretLine)
        }
    }

    fun captureFromEditor(project: Project, editor: Editor, fallbackToCaretLine: Boolean): CodeReference? {
        return ReadAction.compute<CodeReference?, RuntimeException> {
            val document = editor.document
            val vFile = FileDocumentManager.getInstance().getFile(document) ?: return@compute null
            val absPath = vFile.path.replace('\\', '/')
            val relPath = computeRelativeDisplay(project.basePath, absPath, vFile.name)

            val selection = editor.selectionModel
            if (selection.hasSelection()) {
                val startOffset = selection.selectionStart
                val endOffset = selection.selectionEnd
                val startLine = document.getLineNumber(startOffset) + 1
                var endLineNum = document.getLineNumber(endOffset)
                if (endOffset > startOffset && endLineNum > 0 && document.getLineStartOffset(endLineNum) == endOffset) {
                    endLineNum = document.getLineNumber(endOffset - 1)
                }
                val endLine = (endLineNum + 1).coerceAtLeast(startLine)
                return@compute CodeReference(
                    absolutePath = absPath,
                    relativePath = relPath,
                    startLine = startLine,
                    endLine = endLine
                )
            }

            if (fallbackToCaretLine) {
                val caretLine = document.getLineNumber(editor.caretModel.offset) + 1
                return@compute CodeReference(
                    absolutePath = absPath,
                    relativePath = relPath,
                    startLine = caretLine,
                    endLine = caretLine
                )
            }

            CodeReference(
                absolutePath = absPath,
                relativePath = relPath,
                startLine = null,
                endLine = null
            )
        }
    }

    fun captureVirtualFileReference(project: Project, vFile: VirtualFile): CodeReference {
        val absPath = vFile.path.replace('\\', '/')
        val relPath = computeRelativeDisplay(project.basePath, absPath, vFile.name)
        return CodeReference(
            absolutePath = absPath,
            relativePath = relPath,
            startLine = null,
            endLine = null,
            isDirectory = vFile.isDirectory
        )
    }

    private fun computeRelativeDisplay(basePath: String?, absPath: String, fallbackName: String): String {
        if (basePath.isNullOrBlank()) return absPath.ifBlank { fallbackName }
        return runCatching {
            val base = Paths.get(basePath).toAbsolutePath().normalize()
            val target = Paths.get(absPath).toAbsolutePath().normalize()
            if (target.startsWith(base)) base.relativize(target).toString().replace('\\', '/') else absPath
        }.getOrDefault(fallbackName)
    }
}
