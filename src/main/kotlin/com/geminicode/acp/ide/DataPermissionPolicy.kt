package com.geminicode.acp.ide

import com.geminicode.acp.protocol.AcpModels

/**
 * 细粒度数据与操作访问级别：自动放行 / 每次确认 / 禁止。
 */
enum class AccessLevel(
    val displayName: String,
    val shortChar: String
) {
    AUTO("自动放行", "自"),
    ASK("每次确认", "问"),
    DENY("禁止", "禁");

    companion object {
        fun fromStoredName(raw: String?, default: AccessLevel): AccessLevel {
            if (raw.isNullOrBlank()) return default
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: default
        }
    }
}

/**
 * ACP 工具调用与文件访问的权限分类。
 */
enum class ToolOperationCategory(val displayName: String) {
    READ("读文件/检索"),
    WRITE("写文件/修改"),
    COMMAND("终端命令"),
    OTHER("其他工具")
}

/**
 * 数据权限配置快照（对应聊天面板快捷权限开关）。
 */
data class DataPermissionConfig(
    val readOnlyMode: Boolean = false,
    val readFile: AccessLevel = AccessLevel.AUTO,
    val writeFile: AccessLevel = AccessLevel.ASK,
    val command: AccessLevel = AccessLevel.ASK
) {
    fun effectiveReadLevel(): AccessLevel = readFile

    fun effectiveWriteLevel(): AccessLevel = if (readOnlyMode) AccessLevel.DENY else writeFile

    fun effectiveCommandLevel(): AccessLevel = command

    fun effectiveLevelFor(category: ToolOperationCategory): AccessLevel = when (category) {
        ToolOperationCategory.READ -> effectiveReadLevel()
        ToolOperationCategory.WRITE -> effectiveWriteLevel()
        ToolOperationCategory.COMMAND -> effectiveCommandLevel()
        ToolOperationCategory.OTHER -> AccessLevel.ASK
    }

    /**
     * 当写入或命令执行未处于「自动放行」状态时，需要确保 ACP 服务端处于默认询问模式（而非 auto / accept-edits）。
     */
    fun requiresStrictServerApproval(): Boolean =
        readOnlyMode || effectiveWriteLevel() != AccessLevel.AUTO || effectiveCommandLevel() != AccessLevel.AUTO

    fun isReadOnlyPreset(): Boolean =
        readOnlyMode && readFile == AccessLevel.AUTO && command == AccessLevel.DENY

    fun isStandardPreset(): Boolean =
        !readOnlyMode && readFile == AccessLevel.AUTO && writeFile == AccessLevel.ASK && command == AccessLevel.ASK

    fun isFullAutoPreset(): Boolean =
        !readOnlyMode && readFile == AccessLevel.AUTO && writeFile == AccessLevel.AUTO && command == AccessLevel.AUTO

    /**
     * 聊天面板上展示的简洁状态徽章文案。
     */
    fun summaryBadgeText(): String = when {
        isReadOnlyPreset() -> "🔒 只读模式 ▾"
        readOnlyMode -> "🔒 只读(读:${readFile.shortChar} 命:${command.shortChar}) ▾"
        isStandardPreset() -> "🛡️ 权限: 标准 ▾"
        isFullAutoPreset() -> "⚡ 权限: 全自动 ▾"
        else -> "🛡️ 读:${readFile.shortChar} 写:${effectiveWriteLevel().shortChar} 命:${command.shortChar} ▾"
    }

    fun tooltipHtml(): String {
        val roText = if (readOnlyMode) "<b>已开启</b>（强制禁止修改/写入任何文件）" else "已关闭"
        return "<html><b>数据与操作权限</b>（点击快速切换）<br/>" +
            "• 只读模式：$roText<br/>" +
            "• 读文件：${effectiveReadLevel().displayName}<br/>" +
            "• 写文件：${effectiveWriteLevel().displayName}<br/>" +
            "• 终端命令：${effectiveCommandLevel().displayName}</html>"
    }

    companion object {
        val READ_ONLY = DataPermissionConfig(
            readOnlyMode = true,
            readFile = AccessLevel.AUTO,
            writeFile = AccessLevel.DENY,
            command = AccessLevel.DENY
        )

        val STANDARD = DataPermissionConfig(
            readOnlyMode = false,
            readFile = AccessLevel.AUTO,
            writeFile = AccessLevel.ASK,
            command = AccessLevel.ASK
        )

        val FULL_AUTO = DataPermissionConfig(
            readOnlyMode = false,
            readFile = AccessLevel.AUTO,
            writeFile = AccessLevel.AUTO,
            command = AccessLevel.AUTO
        )
    }
}

/**
 * 将 ACP `session/request_permission` 工具请求归类为读文件 / 写文件 / 终端命令 / 其他。
 */
object DataPermissionPolicy {

    private val readKinds = setOf("read", "search", "fetch", "think", "list", "grep", "glob", "view", "find")
    private val writeKinds = setOf("edit", "write", "delete", "move", "patch", "create", "replace", "modify", "rename", "remove")
    private val commandKinds = setOf("execute", "command", "shell", "terminal", "run", "bash", "powershell", "cmd", "exec")

    private val commandTitleHints = listOf(
        "run_command", "run command", "shell", "terminal", "bash", "powershell",
        "cmd.exe", "execute", "exec ", "执行命令", "运行命令", "终端"
    )
    private val writeTitleHints = listOf(
        "write", "edit", "delete", "remove", "patch", "replace", "create file",
        "create_file", "save_file", "move_file", "rename", "mkdir", "写入", "修改", "删除"
    )
    private val readTitleHints = listOf(
        "read", "list_dir", "list ", "search", "grep", "find", "view_file", "glob", "读取", "搜索", "查看"
    )

    fun classifyToolCall(toolCall: AcpModels.PermissionToolCall?): ToolOperationCategory {
        return classifyKindAndTitle(toolCall?.kind, toolCall?.title)
    }

    fun classifyKindAndTitle(kind: String?, title: String?): ToolOperationCategory {
        val normKind = kind?.trim()?.lowercase().orEmpty()
        if (normKind.isNotEmpty() && normKind != "other" && normKind != "unknown") {
            if (normKind in commandKinds) return ToolOperationCategory.COMMAND
            if (normKind in writeKinds) return ToolOperationCategory.WRITE
            if (normKind in readKinds) return ToolOperationCategory.READ
        }

        val normTitle = title?.trim()?.lowercase().orEmpty()
        if (normTitle.isNotEmpty()) {
            if (commandTitleHints.any { normTitle.contains(it) }) return ToolOperationCategory.COMMAND
            if (writeTitleHints.any { normTitle.contains(it) }) return ToolOperationCategory.WRITE
            if (readTitleHints.any { normTitle.contains(it) }) return ToolOperationCategory.READ
        }
        return ToolOperationCategory.OTHER
    }
}
