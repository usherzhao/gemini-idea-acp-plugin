package com.geminicode.acp.ide

import com.geminicode.acp.protocol.AcpModels
import com.geminicode.acp.protocol.JsonRpcTransport
import com.geminicode.acp.settings.GeminiAcpSettingsState
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 在 IntelliJ Platform 生命周期内安全响应 ACP 反向 RPC（文件读取、写入前 Diff 确认与细粒度数据权限审批）。
 */
class IdeaFsHandler(
    private val project: Project,
    private val onLog: (String) -> Unit = {}
) {

    private val sessionAlwaysAllowedKinds = ConcurrentHashMap.newKeySet<String>()

    fun clearSessionAllowances() {
        sessionAlwaysAllowedKinds.clear()
    }

    fun handleReadTextFile(params: AcpModels.FsReadTextFileParams): AcpModels.FsReadTextFileResult {
        val targetPath = IdeaPathSecurity.resolveProjectPath(project.basePath, params.path)
        val config = GeminiAcpSettingsState.getInstance().getDataPermissionConfig()

        when (config.effectiveReadLevel()) {
            AccessLevel.DENY -> {
                onLog("🚫 数据权限已拦截读取文件：${targetPath.fileName}")
                throw JsonRpcTransport.JsonRpcRemoteException(
                    -32001,
                    "数据权限策略已禁止读取文件: ${targetPath.fileName}"
                )
            }
            AccessLevel.ASK -> {
                if (!sessionAlwaysAllowedKinds.contains(SESSION_KEY_FS_READ)) {
                    val choice = AtomicInteger(-1)
                    ApplicationManager.getApplication().invokeAndWait({
                        choice.set(
                            Messages.showDialog(
                                project,
                                "Gemini 助手请求读取文件内容：\n$targetPath\n\n是否允许读取？",
                                "数据权限确认（读文件）",
                                arrayOf("允许本次", "本次会话全部允许读取", "拒绝"),
                                0,
                                Messages.getQuestionIcon()
                            )
                        )
                    }, ModalityState.nonModal())
                    when (choice.get()) {
                        0 -> Unit
                        1 -> sessionAlwaysAllowedKinds.add(SESSION_KEY_FS_READ)
                        else -> {
                            onLog("🚫 用户拒绝了读取文件：${targetPath.fileName}")
                            throw JsonRpcTransport.JsonRpcRemoteException(
                                -32001,
                                "用户在数据权限确认中拒绝读取文件: ${targetPath.fileName}"
                            )
                        }
                    }
                }
            }
            AccessLevel.AUTO -> Unit
        }

        ensurePathAuthorized(targetPath, isWrite = false)

        val rawContent = EditorDiffApplyUtil.readDocumentOrDiskText(targetPath)
            ?: throw JsonRpcTransport.JsonRpcRemoteException(-32002, "未找到文件: $targetPath")

        val sliced = IdeaPathSecurity.sliceLines(rawContent, params.line, params.limit)
        return AcpModels.FsReadTextFileResult(content = sliced)
    }

    fun handleWriteTextFile(params: AcpModels.FsWriteTextFileParams) {
        val targetPath = IdeaPathSecurity.resolveProjectPath(project.basePath, params.path)
        val config = GeminiAcpSettingsState.getInstance().getDataPermissionConfig()
        val writeLevel = config.effectiveWriteLevel()

        if (writeLevel == AccessLevel.DENY) {
            val reason = if (config.readOnlyMode) {
                "只读模式已开启，禁止写入或修改文件"
            } else {
                "数据权限策略已禁止写入或修改文件"
            }
            onLog("🚫 $reason：${targetPath.fileName}")
            throw JsonRpcTransport.JsonRpcRemoteException(-32001, "$reason: ${targetPath.fileName}")
        }

        ensurePathAuthorized(targetPath, isWrite = true)

        val existing = EditorDiffApplyUtil.readDocumentOrDiskText(targetPath)
        val isNewFile = existing == null
        val originalText = existing.orEmpty()

        if (writeLevel == AccessLevel.ASK && (originalText != params.content || isNewFile)) {
            val approved = AtomicBoolean(false)
            ApplicationManager.getApplication().invokeAndWait({
                approved.set(
                    EditorDiffApplyUtil.showDiffAndConfirm(
                        project = project,
                        targetFileName = targetPath.fileName?.toString() ?: "file",
                        displayPath = targetPath.toString(),
                        originalText = originalText,
                        proposedText = params.content,
                        isNewFile = isNewFile
                    )
                )
            }, ModalityState.nonModal())

            if (!approved.get()) {
                onLog("🚫 用户拒绝写入文件：${targetPath.fileName}")
                throw JsonRpcTransport.JsonRpcRemoteException(
                    -32001,
                    "用户在 IDEA Diff 预览中拒绝了对 ${targetPath.fileName} 的修改"
                )
            }
        }

        EditorDiffApplyUtil.ensureParentDirectoryExistsOffEdt(targetPath)

        val applyError = AtomicReference<Throwable?>(null)
        ApplicationManager.getApplication().invokeAndWait({
            runCatching {
                EditorDiffApplyUtil.applyTextToFile(project, targetPath, params.content)
            }.onFailure { applyError.set(it) }
        }, ModalityState.nonModal())

        applyError.get()?.let {
            throw JsonRpcTransport.JsonRpcRemoteException(-32603, "写入文件失败: ${it.message}")
        }
    }

    fun handlePermissionRequest(params: AcpModels.PermissionRequestParams): JsonObject {
        val config = GeminiAcpSettingsState.getInstance().getDataPermissionConfig()
        val toolCall = params.toolCall
        val options = params.options
        val category = DataPermissionPolicy.classifyToolCall(toolCall)
        val level = config.effectiveLevelFor(category)
        val titleText = toolCall?.resolvedTitle() ?: "Gemini 请求执行工具操作"

        when (level) {
            AccessLevel.DENY -> {
                val reason = if (config.readOnlyMode && category == ToolOperationCategory.WRITE) {
                    "只读模式已自动拦截"
                } else {
                    "数据权限已自动拦截"
                }
                onLog("🚫 $reason [${category.displayName}]：$titleText")
                return findRejectOption(options)?.let { selectedOutcome(it.optionId) } ?: cancelledOutcome()
            }
            AccessLevel.AUTO -> {
                findAllowOption(options, preferAlways = false)?.let {
                    return selectedOutcome(it.optionId)
                }
            }
            AccessLevel.ASK -> Unit
        }

        val kindKey = toolCall?.kind?.lowercase().orEmpty()
        val sessionKey = "${category.name}:$kindKey"
        if (sessionAlwaysAllowedKinds.contains(sessionKey)) {
            findAllowOption(options, preferAlways = true)?.let {
                return selectedOutcome(it.optionId)
            }
        }

        if (options.isEmpty()) {
            return cancelledOutcome()
        }

        val chosenIndex = AtomicReference(-1)
        val optionLabels = options.map { it.name }.toTypedArray()

        ApplicationManager.getApplication().invokeAndWait({
            val idx = Messages.showDialog(
                project,
                "Gemini 助手请求权限执行：\n$titleText\n\n权限分类：${category.displayName}（${toolCall?.kind ?: "未声明"}）",
                "Gemini 权限确认",
                optionLabels,
                0,
                Messages.getQuestionIcon()
            )
            chosenIndex.set(idx)
        }, ModalityState.nonModal())

        val idx = chosenIndex.get()
        if (idx < 0 || idx >= options.size) {
            return cancelledOutcome()
        }
        val selected = options[idx]
        if (selected.kind == "allow_always") {
            sessionAlwaysAllowedKinds.add(sessionKey)
        }
        return selectedOutcome(selected.optionId)
    }

    private fun ensurePathAuthorized(targetPath: java.nio.file.Path, isWrite: Boolean) {
        val additionalDirs = GeminiAcpSettingsState.getInstance().getAdditionalDirectories(project.basePath)
        if (IdeaPathSecurity.isWithinAnyWorkspace(project.basePath, additionalDirs, targetPath)) return
        if (!isWrite && Files.isRegularFile(targetPath)) {
            val allowed = AtomicBoolean(false)
            ApplicationManager.getApplication().invokeAndWait({
                val res = Messages.showYesNoDialog(
                    project,
                    "Gemini 请求读取当前项目目录之外的文件：\n$targetPath\n\n是否允许读取？",
                    "跨目录文件访问确认",
                    Messages.getWarningIcon()
                )
                allowed.set(res == Messages.YES)
            }, ModalityState.nonModal())
            if (allowed.get()) return
        }
        throw JsonRpcTransport.JsonRpcRemoteException(
            -32001,
            "拒绝访问项目工作区之外的路径: $targetPath"
        )
    }

    private fun findAllowOption(
        options: List<AcpModels.PermissionOption>,
        preferAlways: Boolean
    ): AcpModels.PermissionOption? {
        val order = if (preferAlways) listOf("allow_always", "allow_once") else listOf("allow_once", "allow_always")
        for (kind in order) {
            options.firstOrNull { it.kind == kind }?.let { return it }
        }
        return options.firstOrNull { it.optionId.contains("allow", ignoreCase = true) }
    }

    private fun findRejectOption(options: List<AcpModels.PermissionOption>): AcpModels.PermissionOption? {
        for (kind in listOf("reject_once", "reject_always")) {
            options.firstOrNull { it.kind == kind }?.let { return it }
        }
        return options.firstOrNull {
            it.optionId.contains("reject", ignoreCase = true) ||
                it.optionId.contains("deny", ignoreCase = true) ||
                it.optionId.contains("cancel", ignoreCase = true)
        }
    }

    private fun selectedOutcome(optionId: String): JsonObject {
        return JsonObject().apply {
            add("outcome", JsonObject().apply {
                addProperty("outcome", "selected")
                addProperty("optionId", optionId)
            })
        }
    }

    private fun cancelledOutcome(): JsonObject {
        return JsonObject().apply {
            add("outcome", JsonObject().apply {
                addProperty("outcome", "cancelled")
            })
        }
    }

    companion object {
        private const val SESSION_KEY_FS_READ = "FS_READ_ALWAYS"
    }
}
