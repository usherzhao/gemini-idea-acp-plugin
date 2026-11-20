package com.geminicode.acp.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * 在 IntelliJ IDEA 右侧边栏注册「Gemini 助手」工具窗口。
 */
class GeminiToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.stripeTitle = "Gemini 助手"
        toolWindow.title = "对话与代码编辑"
        val chatPanel = GeminiChatPanel(project)
        project.putUserData(CHAT_PANEL_KEY, chatPanel)
        val content = ContentFactory.getInstance().createContent(chatPanel, "", false)
        Disposer.register(content, chatPanel)
        toolWindow.contentManager.addContent(content)
    }

    companion object {
        val CHAT_PANEL_KEY: Key<GeminiChatPanel> = Key.create("GeminiCodeAcp.ChatPanel")
    }
}
