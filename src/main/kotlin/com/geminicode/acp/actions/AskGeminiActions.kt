package com.geminicode.acp.actions

import com.geminicode.acp.ide.CodeReference
import com.geminicode.acp.ide.IdeaContextCollector
import com.geminicode.acp.ui.GeminiChatPanel
import com.geminicode.acp.ui.GeminiToolWindowFactory
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager

internal object GeminiActionHelper {

    fun openChatPanelAndSend(
        project: Project,
        prompt: String,
        reference: CodeReference?
    ) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Gemini Code") ?: return
        toolWindow.activate {
            val panel: GeminiChatPanel? = project.getUserData(GeminiToolWindowFactory.CHAT_PANEL_KEY)
            panel?.sendExternalPrompt(prompt, listOfNotNull(reference))
        }
    }

    fun openChatPanelAndQuote(
        project: Project,
        references: List<CodeReference>
    ) {
        if (references.isEmpty()) return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Gemini Code") ?: return
        toolWindow.activate {
            val panel: GeminiChatPanel? = project.getUserData(GeminiToolWindowFactory.CHAT_PANEL_KEY)
            panel?.addCodeReferences(references)
        }
    }
}

/**
 * 编辑器右键菜单操作：仅将当前行或选中多行的「文件路径 + 行号范围」引用带入到 Gemini 输入框，不粘贴源码。
 */
class QuoteSelectionToGeminiAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val ref = IdeaContextCollector.captureFromEditor(project, editor, fallbackToCaretLine = true) ?: return
        GeminiActionHelper.openChatPanelAndQuote(project, listOf(ref))
    }
}

/**
 * 项目文件树 / 编辑器标签页 / 导航栏右键菜单操作：支持单选或多选文件/目录，直接将 `@路径` 引用带入到 Gemini 对话框内。
 */
class QuoteFileFromProjectViewAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val files = resolveTargetFiles(e)
        val enabled = e.project != null && files.isNotEmpty()
        e.presentation.isEnabledAndVisible = enabled
        if (enabled) {
            e.presentation.text = when {
                files.size > 1 -> "引用已选 ${files.size} 个文件/目录到 Gemini 对话框"
                files.first().isDirectory -> "引用目录到 Gemini 对话框"
                else -> "引用文件到 Gemini 对话框"
            }
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = resolveTargetFiles(e)
        if (files.isEmpty()) return
        val refs = files.map { IdeaContextCollector.captureVirtualFileReference(project, it) }
        GeminiActionHelper.openChatPanelAndQuote(project, refs)
    }

    private fun resolveTargetFiles(e: AnActionEvent): List<VirtualFile> {
        val multi = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        if (!multi.isNullOrEmpty()) {
            return multi.filter { it.isValid }
        }
        val single = e.getData(CommonDataKeys.VIRTUAL_FILE)
        return if (single != null && single.isValid) listOf(single) else emptyList()
    }
}

/**
 * 编辑器右键菜单操作：附带当前行/选中行范围的位置引用向 Gemini 提问（不粘贴源码）。
 */
class AskGeminiAboutSelectionAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val ref = IdeaContextCollector.captureFromEditor(project, editor, fallbackToCaretLine = true) ?: return
        val question = Messages.showInputDialog(
            project,
            "将附带代码位置引用 ${ref.chipLabel()} 发送给 Gemini：",
            "向 Gemini 询问代码",
            Messages.getQuestionIcon(),
            "请查看上述代码位置并解释逻辑，同时检查是否存在潜在问题。",
            null
        )?.trim()
        if (question.isNullOrEmpty()) return
        GeminiActionHelper.openChatPanelAndSend(project, question, ref)
    }
}

/**
 * 编辑器右键菜单操作：附带代码位置引用让 Gemini 通过 Diff 预览直接修改目标文件。
 */
class EditSelectionWithGeminiAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val ref = IdeaContextCollector.captureFromEditor(project, editor, fallbackToCaretLine = true) ?: return
        val instruction = Messages.showInputDialog(
            project,
            "请描述需要如何修改 ${ref.chipLabel()}（写入前将弹出 IDEA Diff 预览）：",
            "用 Gemini 对话编辑代码",
            Messages.getQuestionIcon()
        )?.trim()
        if (instruction.isNullOrEmpty()) return

        val prompt = "请读取并直接修改指定代码位置（${ref.chipLabel()}）：\n$instruction"
        GeminiActionHelper.openChatPanelAndSend(project, prompt, ref)
    }
}
