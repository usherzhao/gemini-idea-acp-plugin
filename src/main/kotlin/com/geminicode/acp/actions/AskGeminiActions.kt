package com.geminicode.acp.actions

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
import com.intellij.openapi.wm.ToolWindowManager

internal object GeminiActionHelper {

    fun openChatPanelAndSend(
        project: Project,
        prompt: String,
        context: IdeaContextCollector.EditorContextSnapshot?
    ) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Gemini Code") ?: return
        toolWindow.activate {
            val panel: GeminiChatPanel? = project.getUserData(GeminiToolWindowFactory.CHAT_PANEL_KEY)
            panel?.sendExternalPrompt(prompt, context)
        }
    }
}

/**
 * 编辑器右键菜单操作：将当前选中代码或文件发送到 Gemini 侧边栏进行提问与分析。
 */
class AskGeminiAboutSelectionAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val context = IdeaContextCollector.captureActiveEditorContext(project) ?: return
        val question = Messages.showInputDialog(
            project,
            "将附带 ${context.badgeLabel()} 发送给 Gemini：",
            "向 Gemini 询问代码",
            Messages.getQuestionIcon(),
            "请解释这段代码并检查是否存在潜在问题或改进空间。",
            null
        )?.trim()
        if (question.isNullOrEmpty()) return
        GeminiActionHelper.openChatPanelAndSend(project, question, context)
    }
}

/**
 * 编辑器右键菜单操作：输入修改指令，让 Gemini 通过 Diff 预览直接修改目标文件。
 */
class EditSelectionWithGeminiAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val context = IdeaContextCollector.captureActiveEditorContext(project) ?: return
        val instruction = Messages.showInputDialog(
            project,
            "请描述需要如何修改 ${context.badgeLabel()}（写入前将弹出 IDEA Diff 预览）：",
            "用 Gemini 对话编辑代码",
            Messages.getQuestionIcon()
        )?.trim()
        if (instruction.isNullOrEmpty()) return

        val prompt = "请根据以下需求直接修改文件 `${context.absolutePath}`：\n$instruction"
        GeminiActionHelper.openChatPanelAndSend(project, prompt, context)
    }
}
