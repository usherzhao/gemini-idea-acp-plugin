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
import java.util.concurrent.atomic.AtomicReference

/**
 * 在 IntelliJ Platform 生命周期内安全响应 ACP 反向 RPC（文件读取、写入前 Diff 确认与权限审批）。
 */
class IdeaFsHandler(private val project: Project) {

    private val sessionAlwaysAllowedKinds = ConcurrentHashMap.newKeySet<String>()

    fun clearSessionAllowances() {
        sessionAlwaysAllowedKinds.clear()
    }

    fun handleReadTextFile(params: AcpModels.FsReadTextFileParams): AcpModels.FsReadTextFileResult {
        val targetPath = IdeaPathSecurity.resolveProjectPath(project.basePath, params.path)
        ensurePathAuthorized(targetPath, isWrite = false)

        val rawContent = EditorDiffApplyUtil.readDocumentOrDiskText(targetPath)
            ?: throw JsonRpcTransport.JsonRpcRemoteException(-32002, "未找到文件: $targetPath")

        val sliced = IdeaPathSecurity.sliceLines(rawContent, params.line, params.limit)
        return AcpModels.FsReadTextFileResult(content = sliced)
    }

    fun handleWriteTextFile(params: AcpModels.FsWriteTextFileParams) {
        val targetPath = IdeaPathSecurity.resolveProjectPath(project.basePath, params.path)
        ensurePathAuthorized(targetPath, isWrite = true)

        val settings = GeminiAcpSettingsState.getInstance().state
        val existing = EditorDiffApplyUtil.readDocumentOrDiskText(targetPath)
        val isNewFile = existing == null
        val originalText = existing.orEmpty()

        if (settings.requireDiffBeforeWrite && originalText != params.content) {
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
        val settings = GeminiAcpSettingsState.getInstance().state
        val toolCall = params.toolCall
        val options = params.options
        val kindKey = toolCall?.kind?.lowercase().orEmpty()

        if (settings.autoApproveReadTools && isReadOnlyKind(kindKey)) {
            findOptionByKind(options, "allow_once", "allow_always")?.let {
                return selectedOutcome(it.optionId)
            }
        }

        if (kindKey.isNotEmpty() && sessionAlwaysAllowedKinds.contains(kindKey)) {
            findOptionByKind(options, "allow_always", "allow_once")?.let {
                return selectedOutcome(it.optionId)
            }
        }

        if (options.isEmpty()) {
            return cancelledOutcome()
        }

        val chosenIndex = AtomicReference(-1)
        val titleText = toolCall?.resolvedTitle() ?: "Gemini 请求执行工具操作"
        val optionLabels = options.map { it.name }.toTypedArray()

        ApplicationManager.getApplication().invokeAndWait({
            val idx = Messages.showDialog(
                project,
                "Gemini 助手请求权限执行：\n$titleText\n\n操作类型：${toolCall?.kind ?: "未知"}",
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
        if (selected.kind == "allow_always" && kindKey.isNotEmpty()) {
            sessionAlwaysAllowedKinds.add(kindKey)
        }
        return selectedOutcome(selected.optionId)
    }

    private fun ensurePathAuthorized(targetPath: java.nio.file.Path, isWrite: Boolean) {
        if (IdeaPathSecurity.isWithinWorkspace(project.basePath, targetPath)) return
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

    private fun isReadOnlyKind(kind: String): Boolean {
        return kind in setOf("read", "search", "fetch", "think", "list")
    }

    private fun findOptionByKind(
        options: List<AcpModels.PermissionOption>,
        vararg kinds: String
    ): AcpModels.PermissionOption? {
        for (kind in kinds) {
            options.firstOrNull { it.kind == kind }?.let { return it }
        }
        return options.firstOrNull { it.optionId.contains("allow", ignoreCase = true) }
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
}
