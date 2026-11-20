package com.geminicode.acp.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * 应用级持久化设置服务（含用户自定义会话标题映射，通过 @Service 轻服务自动注册）。
 */
@Service(Service.Level.APP)
@State(
    name = "GeminiAcpSettingsState",
    storages = [Storage("geminiAcpSettings.xml")]
)
class GeminiAcpSettingsState : PersistentStateComponent<GeminiAcpSettingsState.State> {

    data class State(
        var geminiExecutablePath: String = "",
        var acpFlag: String = "--acp",
        var authMethodId: String = "oauth-personal",
        var stripGeminiApiKeyEnv: Boolean = true,
        var allowNpxFallback: Boolean = true,
        var requireDiffBeforeWrite: Boolean = true,
        var autoApproveReadTools: Boolean = true,
        var proxyUrl: String = "",
        var googleCloudProject: String = "",
        var includeActiveFileByDefault: Boolean = true,
        var maxAttachedFileChars: Int = 60_000,
        var customSessionTitles: MutableMap<String, String> = mutableMapOf()
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    fun getCustomTitle(sessionId: String): String? =
        myState.customSessionTitles[sessionId]?.takeIf { it.isNotBlank() }

    /**
     * 设置或清除指定会话的自定义标题（传入空白则清除自定义覆盖，恢复服务端默认标题）。
     */
    fun setCustomTitle(sessionId: String, title: String?) {
        val trimmed = title?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            myState.customSessionTitles.remove(sessionId)
        } else {
            myState.customSessionTitles[sessionId] = trimmed
        }
    }

    fun resolveDisplayTitle(sessionId: String, serverTitle: String?): String {
        return getCustomTitle(sessionId)
            ?: serverTitle?.takeIf { it.isNotBlank() }
            ?: "未命名对话 (${sessionId.take(8)})"
    }

    companion object {
        fun getInstance(): GeminiAcpSettingsState =
            ApplicationManager.getApplication().getService(GeminiAcpSettingsState::class.java)
    }
}
