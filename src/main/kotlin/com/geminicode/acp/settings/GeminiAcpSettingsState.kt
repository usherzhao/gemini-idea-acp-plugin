package com.geminicode.acp.settings

import com.geminicode.acp.ide.AccessLevel
import com.geminicode.acp.ide.DataPermissionConfig
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
        var readOnlyMode: Boolean = false,
        var readFilePermission: String = "",
        var writeFilePermission: String = "",
        var commandPermission: String = "ASK",
        var proxyUrl: String = "",
        var googleCloudProject: String = "",
        var includeActiveFileByDefault: Boolean = true,
        var enableEmbeddedContext: Boolean = true,
        var loadProjectRulesFile: Boolean = true,
        var systemPromptAppend: String = "",
        var agySandbox: Boolean = false,
        var agyDisableTelemetry: Boolean = false,
        var agyAgentName: String = "",
        var maxAttachedFileChars: Int = 60_000,
        var lastLoggedInEmail: String = "",
        var customSessionTitles: MutableMap<String, String> = mutableMapOf(),
        var projectAdditionalDirectories: MutableMap<String, MutableList<String>> = mutableMapOf()
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    fun getDataPermissionConfig(): DataPermissionConfig {
        val readDefault = if (myState.autoApproveReadTools) AccessLevel.AUTO else AccessLevel.ASK
        val writeDefault = if (myState.requireDiffBeforeWrite) AccessLevel.ASK else AccessLevel.AUTO
        return DataPermissionConfig(
            readOnlyMode = myState.readOnlyMode,
            readFile = AccessLevel.fromStoredName(myState.readFilePermission, readDefault),
            writeFile = AccessLevel.fromStoredName(myState.writeFilePermission, writeDefault),
            command = AccessLevel.fromStoredName(myState.commandPermission, AccessLevel.ASK)
        )
    }

    fun setDataPermissionConfig(config: DataPermissionConfig) {
        myState.readOnlyMode = config.readOnlyMode
        myState.readFilePermission = config.readFile.name
        myState.writeFilePermission = config.writeFile.name
        myState.commandPermission = config.command.name
        myState.autoApproveReadTools = config.effectiveReadLevel() == AccessLevel.AUTO
        myState.requireDiffBeforeWrite = config.effectiveWriteLevel() != AccessLevel.AUTO
    }

    fun getLastLoggedInEmail(): String? =
        myState.lastLoggedInEmail.trim().takeIf { it.isNotEmpty() }

    fun setLastLoggedInEmail(email: String?) {
        myState.lastLoggedInEmail = email?.trim().orEmpty()
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

    fun getAdditionalDirectories(projectBasePath: String?): List<String> {
        val key = normalizeProjectKey(projectBasePath) ?: return emptyList()
        return myState.projectAdditionalDirectories[key]
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            .orEmpty()
    }

    fun setAdditionalDirectories(projectBasePath: String?, directories: List<String>) {
        val key = normalizeProjectKey(projectBasePath) ?: return
        val cleaned = directories.map { it.trim().replace('\\', '/') }
            .filter { it.isNotEmpty() }
            .distinct()
            .toMutableList()
        if (cleaned.isEmpty()) {
            myState.projectAdditionalDirectories.remove(key)
        } else {
            myState.projectAdditionalDirectories[key] = cleaned
        }
    }

    private fun normalizeProjectKey(projectBasePath: String?): String? {
        if (projectBasePath.isNullOrBlank()) return null
        return projectBasePath.trim().replace('\\', '/').trimEnd('/').lowercase()
    }

    companion object {
        fun getInstance(): GeminiAcpSettingsState =
            ApplicationManager.getApplication().getService(GeminiAcpSettingsState::class.java)
    }
}
