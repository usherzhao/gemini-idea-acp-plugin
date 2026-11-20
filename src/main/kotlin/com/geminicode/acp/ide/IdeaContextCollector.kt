package com.geminicode.acp.ide

import com.geminicode.acp.settings.GeminiAcpSettingsState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import java.nio.file.Paths

/**
 * Captures active IntelliJ editor file and selection context for conversational prompts.
 */
object IdeaContextCollector {

    data class EditorContextSnapshot(
        val absolutePath: String,
        val displayName: String,
        val selectionText: String?,
        val startLine: Int?,
        val endLine: Int?,
        val fileText: String?,
        val truncated: Boolean
    ) {
        fun badgeLabel(): String {
            return if (selectionText != null && startLine != null && endLine != null) {
                "$displayName (L$startLine-L$endLine)"
            } else {
                displayName
            }
        }
    }

    fun captureActiveEditorContext(project: Project): EditorContextSnapshot? {
        val maxChars = GeminiAcpSettingsState.getInstance().state.maxAttachedFileChars
        return ReadAction.compute<EditorContextSnapshot?, RuntimeException> {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@compute null
            val document = editor.document
            val vFile = FileDocumentManager.getInstance().getFile(document) ?: return@compute null
            val absPath = vFile.path
            val basePath = project.basePath
            val display = computeRelativeDisplay(basePath, absPath, vFile.name)

            val selection = editor.selectionModel
            if (selection.hasSelection()) {
                val selectedText = selection.selectedText
                val startLine = document.getLineNumber(selection.selectionStart) + 1
                val endLine = document.getLineNumber(selection.selectionEnd) + 1
                return@compute EditorContextSnapshot(
                    absolutePath = absPath,
                    displayName = display,
                    selectionText = selectedText,
                    startLine = startLine,
                    endLine = endLine,
                    fileText = null,
                    truncated = false
                )
            }

            val fullText = document.text
            val isTruncated = fullText.length > maxChars
            val snippet = if (isTruncated) fullText.substring(0, maxChars) else fullText
            EditorContextSnapshot(
                absolutePath = absPath,
                displayName = display,
                selectionText = null,
                startLine = null,
                endLine = null,
                fileText = snippet,
                truncated = isTruncated
            )
        }
    }

    fun formatPromptWithContext(userPrompt: String, context: EditorContextSnapshot?): String {
        if (context == null) return userPrompt
        return buildString {
            appendLine("[IDE Active Context]")
            appendLine("File Path: ${context.absolutePath}")
            if (context.selectionText != null) {
                appendLine("Selected Lines: ${context.startLine}-${context.endLine}")
                appendLine("```")
                appendLine(context.selectionText)
                appendLine("```")
            } else if (context.fileText != null) {
                if (context.truncated) {
                    appendLine("(File content truncated; call read_file if you need remaining lines)")
                }
                appendLine("```")
                appendLine(context.fileText)
                appendLine("```")
            }
            appendLine()
            append(userPrompt)
        }
    }

    private fun computeRelativeDisplay(basePath: String?, absPath: String, fallbackName: String): String {
        if (basePath.isNullOrBlank()) return fallbackName
        return runCatching {
            val base = Paths.get(basePath).toAbsolutePath().normalize()
            val target = Paths.get(absPath).toAbsolutePath().normalize()
            if (target.startsWith(base)) base.relativize(target).toString().replace('\\', '/') else fallbackName
        }.getOrDefault(fallbackName)
    }
}
