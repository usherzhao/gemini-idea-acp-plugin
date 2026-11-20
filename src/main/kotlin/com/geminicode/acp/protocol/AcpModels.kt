package com.geminicode.acp.protocol

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken

/**
 * Agent Client Protocol (ACP) JSON-RPC 2.0 请求、响应与流式事件模型。
 */
object AcpModels {

    private val internalGson = Gson()
    private val configOptionsListType = object : TypeToken<List<ConfigOption>>() {}.type
    private val availableCommandsListType = object : TypeToken<List<AvailableCommand>>() {}.type

    data class ClientInfo(
        val name: String = "gemini-idea-acp-plugin",
        val version: String = "1.0.10"
    )

    data class FsCapabilities(
        val readTextFile: Boolean = true,
        val writeTextFile: Boolean = true
    )

    data class ClientCapabilities(
        val fs: FsCapabilities = FsCapabilities()
    )

    data class InitializeParams(
        val protocolVersion: Int = 1,
        val clientCapabilities: ClientCapabilities = ClientCapabilities(),
        val clientInfo: ClientInfo = ClientInfo()
    )

    data class AuthMethod(
        val id: String,
        val name: String? = null,
        val description: String? = null
    )

    data class InitializeResult(
        val protocolVersion: Int = 1,
        val authMethods: List<AuthMethod> = emptyList(),
        val agentInfo: JsonObject? = null,
        val agentCapabilities: JsonObject? = null
    ) {
        fun supportsLogout(): Boolean {
            if (protocolVersion >= 2 && authMethods.isNotEmpty()) return true
            val authObj = agentCapabilities?.getAsJsonObject("auth") ?: return false
            return authObj.has("logout") && !authObj.get("logout").isJsonNull
        }
    }

    data class AuthenticateParams(
        val methodId: String
    )

    data class NewSessionParams(
        val cwd: String,
        val mcpServers: List<Any> = emptyList()
    )

    data class SessionSummary(
        val sessionId: String,
        val cwd: String? = null,
        val title: String? = null,
        val updatedAt: String? = null
    ) {
        fun displayTitle(): String = title?.takeIf { it.isNotBlank() } ?: "未命名对话 (${sessionId.take(8)})"
    }

    data class ListSessionsParams(
        val cwd: String? = null
    )

    data class ListSessionsResult(
        val sessions: List<SessionSummary> = emptyList()
    )

    data class LoadSessionParams(
        val sessionId: String,
        val cwd: String,
        val mcpServers: List<Any> = emptyList()
    )

    data class SetSessionTitleParams(
        val sessionId: String,
        val title: String
    )

    data class ModelInfo(
        val modelId: String,
        val name: String
    ) {
        override fun toString(): String = name
    }

    data class ModelsState(
        val availableModels: List<ModelInfo> = emptyList(),
        val currentModelId: String? = null
    )

    data class ModeInfo(
        val id: String,
        val name: String,
        val description: String? = null
    ) {
        override fun toString(): String = name
    }

    data class ModesState(
        val availableModes: List<ModeInfo> = emptyList(),
        val currentModeId: String? = null
    )

    /**
     * 官方 ACP configOptions 中的单个候选项（保持服务端原始 value 与 name，不硬编码）。
     */
    data class ConfigSelectOption(
        val value: String,
        val name: String,
        val description: String? = null
    ) {
        override fun toString(): String = name
    }

    /**
     * 官方 ACP 动态配置项（如 model、thought_level、mode 等）。
     */
    data class ConfigOption(
        val id: String,
        val name: String,
        val category: String? = null,
        val type: String = "select",
        val currentValue: String? = null,
        val options: List<ConfigSelectOption> = emptyList()
    )

    data class SetConfigOptionParams(
        val sessionId: String,
        val configId: String,
        val value: String
    )

    data class SetConfigOptionResult(
        val configOptions: List<ConfigOption> = emptyList()
    )

    data class NewSessionResult(
        val sessionId: String,
        val models: ModelsState? = null,
        val modes: ModesState? = null,
        val configOptions: List<ConfigOption> = emptyList(),
        val _meta: JsonObject? = null
    )

    data class SessionMetadata(
        val sessionId: String,
        val configOptions: List<ConfigOption>,
        val models: List<ModelInfo>,
        val currentModelId: String?,
        val modes: List<ModeInfo>,
        val currentModeId: String?,
        val tierName: String?
    )

    data class SetModelParams(
        val sessionId: String,
        val modelId: String
    )

    data class SetModeParams(
        val sessionId: String,
        val modeId: String
    )

    data class ContentBlock(
        val type: String = "text",
        val text: String? = null
    )

    data class PromptParams(
        val sessionId: String,
        val prompt: List<ContentBlock>
    )

    data class PromptResult(
        val stopReason: String? = null
    )

    data class CancelParams(
        val sessionId: String
    )

    data class AvailableCommand(
        val name: String,
        val description: String? = null,
        val source: String = "acp"
    )

    data class FsReadTextFileParams(
        val sessionId: String? = null,
        val path: String,
        val line: Int? = null,
        val limit: Int? = null
    )

    data class FsReadTextFileResult(
        val content: String
    )

    data class FsWriteTextFileParams(
        val sessionId: String? = null,
        val path: String,
        val content: String
    )

    data class PermissionOption(
        val optionId: String,
        val name: String,
        val kind: String? = null
    )

    data class PermissionToolCall(
        val toolCallId: String? = null,
        val callId: String? = null,
        val title: String? = null,
        val kind: String? = null,
        val status: String? = null,
        val rawInput: JsonElement? = null
    ) {
        fun resolvedTitle(): String = title?.takeIf { it.isNotBlank() } ?: (kind ?: "工具执行")
    }

    data class PermissionRequestParams(
        val sessionId: String? = null,
        val toolCall: PermissionToolCall? = null,
        val options: List<PermissionOption> = emptyList()
    )

    sealed class SessionUpdateEvent {
        data class UserMessageChunk(val text: String) : SessionUpdateEvent()
        data class AgentMessageChunk(val text: String) : SessionUpdateEvent()
        data class AgentThoughtChunk(val text: String) : SessionUpdateEvent()
        data class ToolCallEvent(
            val toolCallId: String,
            val title: String,
            val kind: String?,
            val status: String?,
            val locations: List<String>
        ) : SessionUpdateEvent()
        data class PlanEvent(val summary: String) : SessionUpdateEvent()
        data class ConfigOptionsUpdated(val configOptions: List<ConfigOption>) : SessionUpdateEvent()
        data class AvailableCommandsUpdated(val commands: List<AvailableCommand>) : SessionUpdateEvent()
        data class SessionInfoUpdated(val title: String?) : SessionUpdateEvent()
        data class UsageEvent(val used: Long, val size: Long) : SessionUpdateEvent()
        data class Other(val kind: String) : SessionUpdateEvent()
    }

    fun extractTierName(metaOrResult: JsonElement?): String? {
        if (metaOrResult == null || !metaOrResult.isJsonObject) return null
        val obj = metaOrResult.asJsonObject
        val meta = obj.getAsJsonObject("_meta") ?: obj
        return meta.getAsJsonObject("antigravity")
            ?.getAsJsonObject("tier")
            ?.get("name")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
    }

    fun parseSessionUpdate(params: JsonObject): SessionUpdateEvent? {
        val update = params.getAsJsonObject("update") ?: return null
        val kind = update.get("sessionUpdate")?.asString ?: return null
        return when (kind) {
            "user_message_chunk" -> {
                val text = extractTextContent(update.get("content"))
                if (text.isNotEmpty()) SessionUpdateEvent.UserMessageChunk(text) else null
            }
            "agent_message_chunk" -> {
                val text = extractTextContent(update.get("content"))
                if (text.isNotEmpty()) SessionUpdateEvent.AgentMessageChunk(text) else null
            }
            "agent_thought_chunk" -> {
                val text = extractTextContent(update.get("content"))
                if (text.isNotEmpty()) SessionUpdateEvent.AgentThoughtChunk(text) else null
            }
            "tool_call", "tool_call_update" -> {
                val id = update.get("toolCallId")?.asString
                    ?: update.get("callId")?.asString
                    ?: "tool"
                val title = update.get("title")?.asString
                    ?: update.get("name")?.asString
                    ?: kind
                val toolKind = update.get("kind")?.asString
                val status = update.get("status")?.asString
                val locations = extractLocations(update.get("locations"))
                SessionUpdateEvent.ToolCallEvent(id, title, toolKind, status, locations)
            }
            "plan" -> {
                val entries = update.getAsJsonArray("entries")
                    ?.mapNotNull { it.asJsonObject?.get("content")?.asString }
                    ?.joinToString("\n• ", prefix = "• ")
                    .orEmpty()
                SessionUpdateEvent.PlanEvent(entries)
            }
            "config_option_update" -> {
                val arr = update.get("configOptions")
                if (arr != null && arr.isJsonArray) {
                    val parsed: List<ConfigOption> = internalGson.fromJson(arr, configOptionsListType) ?: emptyList()
                    SessionUpdateEvent.ConfigOptionsUpdated(parsed)
                } else {
                    null
                }
            }
            "available_commands_update" -> {
                val arr = update.get("availableCommands")
                if (arr != null && arr.isJsonArray) {
                    val parsed: List<AvailableCommand> =
                        internalGson.fromJson(arr, availableCommandsListType) ?: emptyList()
                    SessionUpdateEvent.AvailableCommandsUpdated(parsed)
                } else {
                    null
                }
            }
            "session_info_update" -> {
                val title = update.get("title")?.takeIf { it.isJsonPrimitive }?.asString
                SessionUpdateEvent.SessionInfoUpdated(title)
            }
            "usage_update" -> {
                val used = update.get("used")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
                val size = update.get("size")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
                SessionUpdateEvent.UsageEvent(used, size)
            }
            else -> SessionUpdateEvent.Other(kind)
        }
    }

    private fun extractTextContent(element: JsonElement?): String {
        if (element == null || element.isJsonNull) return ""
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            return obj.get("text")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        }
        if (element.isJsonArray) {
            return element.asJsonArray.joinToString("") { extractTextContent(it) }
        }
        return ""
    }

    private fun extractLocations(element: JsonElement?): List<String> {
        if (element == null || !element.isJsonArray) return emptyList()
        return element.asJsonArray.mapNotNull { item ->
            if (!item.isJsonObject) return@mapNotNull null
            val obj = item.asJsonObject
            val path = obj.get("path")?.asString ?: return@mapNotNull null
            val line = obj.get("line")?.takeIf { it.isJsonPrimitive }?.asInt
            if (line != null) "$path:$line" else path
        }
    }
}
