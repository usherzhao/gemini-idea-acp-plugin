package com.geminicode.acp.protocol

import com.geminicode.acp.ide.AcpContextBuilder
import com.geminicode.acp.ide.IdeaFsHandler
import com.geminicode.acp.ide.ImageAttachmentHelper
import com.geminicode.acp.settings.GeminiAcpSettingsState
import com.geminicode.acp.settings.GeminiExecutableResolver
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 项目级 ACP 客户端服务：管理 Antigravity CLI (agy acp) 或 Gemini CLI 子进程、
 * oauth-personal 认证握手、官方动态 configOptions 切换、历史会话列表/加载以及 IDE 反向 RPC 回调。
 */
@Service(Service.Level.PROJECT)
class AcpClientService(private val project: Project) : Disposable {

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        AUTHENTICATING,
        READY,
        STREAMING,
        ERROR
    }

    interface AcpEventListener {
        fun onStateChanged(state: ConnectionState, detail: String) {}
        fun onCapabilitiesUpdated(capabilities: AcpModels.AgentCapabilitiesSummary) {}
        fun onSessionMetadataChanged(metadata: AcpModels.SessionMetadata) {}
        fun onAvailableCommandsChanged(commands: List<AcpModels.AvailableCommand>) {}
        fun onSessionTitleChanged(title: String) {}
        fun onSessionLoadingStarted(session: AcpModels.SessionSummary) {}
        fun onSessionLoadingFinished(metadata: AcpModels.SessionMetadata?, errorMessage: String?) {}
        fun onSessionUpdate(event: AcpModels.SessionUpdateEvent, isReplay: Boolean) {}
        fun onUsageUpdated(usage: AcpModels.SessionUpdateEvent.UsageEvent?) {}
        fun onTurnFinished(stopReason: String?) {}
        fun onLoggedOut() {}
        fun onAuthUrlDetected(url: String) {}
        fun onLogMessage(message: String) {}
    }

    class LogoutNotSupportedException(message: String) : IllegalStateException(message)

    private val logger = Logger.getInstance(AcpClientService::class.java)
    private val gson = Gson()
    private val listeners = CopyOnWriteArrayList<AcpEventListener>()
    private val fsHandler = IdeaFsHandler(project)
    private val replayingHistory = AtomicBoolean(false)
    private val lifecycleExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "GeminiAcp-Lifecycle").apply { isDaemon = true }
    }

    @Volatile
    private var transport: JsonRpcTransport? = null

    @Volatile
    var currentState: ConnectionState = ConnectionState.DISCONNECTED
        private set

    @Volatile
    var activeSessionId: String? = null
        private set

    @Volatile
    var activeSessionTitle: String? = null
        private set

    @Volatile
    private var activeServerSessionTitle: String? = null

    @Volatile
    var lastMetadata: AcpModels.SessionMetadata? = null
        private set

    @Volatile
    var availableAuthMethods: List<AcpModels.AuthMethod> = emptyList()
        private set

    @Volatile
    var capabilities: AcpModels.AgentCapabilitiesSummary = AcpModels.AgentCapabilitiesSummary()
        private set

    @Volatile
    var supportsLogout: Boolean = false
        private set

    @Volatile
    var activeBackendKind: GeminiExecutableResolver.BackendKind? = null
        private set

    @Volatile
    var availableCommands: List<AcpModels.AvailableCommand> = emptyList()
        private set

    @Volatile
    var lastUsage: AcpModels.SessionUpdateEvent.UsageEvent? = null
        private set

    private val urlRegex = Regex("""https://accounts\.google\.com/[^\s"'<>]+""")

    fun addListener(listener: AcpEventListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: AcpEventListener) {
        listeners.remove(listener)
    }

    fun connectAndAuthenticate(): CompletableFuture<String> {
        return CompletableFuture.supplyAsync({
            connectAndAuthenticateBlocking()
        }, lifecycleExecutor)
    }

    /**
     * 通过官方 ACP `logout`（兼容 v2 `auth/logout`）退出当前登录状态并断开连接。
     * 返回 true 表示服务端已成功执行官方 logout，false 表示当前 CLI 后端未声明 logout 能力（仅断开本地连接）。
     */
    fun logout(): CompletableFuture<Boolean> {
        return CompletableFuture.supplyAsync({
            val serverLoggedOut = performLogoutOnLifecycleThread()
            clearAuthAndDisconnectInternal()
            val detail = if (serverLoggedOut) {
                "已退出当前账号，点击「登录/连接」或「切换账号」重新登录"
            } else {
                "已断开连接（当前 CLI 不支持 ACP logout，请使用「终端登录」重新授权）"
            }
            updateState(ConnectionState.DISCONNECTED, detail)
            listeners.forEach { it.onLoggedOut() }
            serverLoggedOut
        }, lifecycleExecutor)
    }

    /**
     * 一键切换账号 / 重新登录：先通过官方 ACP `logout` 退出旧账号，再启动全新 ACP 连接重新拉起 Google 账号授权并开启新会话。
     */
    fun switchAccount(): CompletableFuture<String> {
        return CompletableFuture.supplyAsync({
            val serverLoggedOut = performLogoutOnLifecycleThread()
            clearAuthAndDisconnectInternal()
            listeners.forEach { it.onLoggedOut() }
            if (!serverLoggedOut) {
                val msg = "当前 CLI 后端未声明官方 ACP logout 能力，请通过「终端登录」切换账号后再点击「登录/连接」。"
                updateState(ConnectionState.DISCONNECTED, "已断开旧连接，请在终端完成重新登录")
                throw LogoutNotSupportedException(msg)
            }
            connectAndAuthenticateBlocking()
        }, lifecycleExecutor)
    }

    fun newSession(): CompletableFuture<String> {
        return CompletableFuture.supplyAsync({
            val currentTransport = transport
            if (currentTransport == null || !currentTransport.isAlive()) {
                return@supplyAsync connectAndAuthenticateBlocking()
            }
            closeActiveSessionBestEffort(currentTransport)
            fsHandler.clearSessionAllowances()
            clearUsage()
            val session = createSession(currentTransport)
            activeServerSessionTitle = null
            activeSessionTitle = "新对话"
            listeners.forEach { it.onSessionTitleChanged("新对话") }
            publishSession(session, lastMetadata?.tierName)
            val authMode = GeminiAcpSettingsState.getInstance().state.authMethodId
            updateState(ConnectionState.READY, formatReadyDetail(authMode, lastMetadata?.tierName))
            session.sessionId
        }, lifecycleExecutor)
    }

    /**
     * 修改指定会话的显示名称（传入 null 或空白则恢复服务端默认标题）。
     * 会持久化到插件本地配置，并在服务端支持 ACP `session/set_title` 时尝试同步到服务端。
     */
    fun renameSession(
        sessionId: String,
        newTitle: String?,
        fallbackServerTitle: String? = null
    ): CompletableFuture<String> {
        val settings = GeminiAcpSettingsState.getInstance()
        val trimmed = newTitle?.trim().orEmpty()
        settings.setCustomTitle(sessionId, trimmed)

        val effectiveServerTitle = if (sessionId == activeSessionId) {
            fallbackServerTitle ?: activeServerSessionTitle
        } else {
            fallbackServerTitle
        }
        val resolvedTitle = if (trimmed.isNotEmpty()) {
            trimmed
        } else {
            effectiveServerTitle?.takeIf { it.isNotBlank() }
                ?: if (sessionId == activeSessionId) "新对话" else settings.resolveDisplayTitle(sessionId, null)
        }

        if (sessionId == activeSessionId) {
            activeSessionTitle = resolvedTitle
            listeners.forEach { it.onSessionTitleChanged(resolvedTitle) }
        }

        return CompletableFuture.supplyAsync({
            val activeTransport = transport
            if (activeTransport != null && activeTransport.isAlive() && trimmed.isNotEmpty()) {
                runCatching {
                    activeTransport.sendRequest(
                        "session/set_title",
                        AcpModels.SetSessionTitleParams(sessionId, trimmed),
                        10
                    ).get()
                    notifyLog("已将新对话名称同步至 ACP 服务端: $trimmed")
                }.onFailure { err ->
                    val root = unwrapCause(err)
                    if (root is JsonRpcTransport.JsonRpcRemoteException && root.code == -32601) {
                        notifyLog("已本地保存对话名称：$trimmed（当前 ACP 服务端未启用 session/set_title）")
                    } else {
                        notifyLog("已本地保存对话名称：$trimmed（服务端同步跳过：${root.message}）")
                    }
                }
            } else {
                notifyLog(
                    if (trimmed.isNotEmpty()) "已修改对话名称为：$resolvedTitle"
                    else "已恢复对话默认名称：$resolvedTitle"
                )
            }
            resolvedTitle
        }, lifecycleExecutor)
    }

    /**
     * 通过官方 ACP `session/list` 获取历史对话记录（按更新时间倒序）。
     */
    fun listSessions(): CompletableFuture<List<AcpModels.SessionSummary>> {
        return CompletableFuture.supplyAsync({
            val activeTransport = ensureConnectedTransportOnLifecycleThread()
            val resultJson = activeTransport.sendRequest("session/list", JsonObject(), 30).get()
            val parsed = gson.fromJson(resultJson, AcpModels.ListSessionsResult::class.java)
            val sessions = parsed?.sessions.orEmpty()
                .sortedByDescending { it.updatedAt.orEmpty() }
            notifyLog("已获取 ${sessions.size} 条历史会话记录")
            sessions
        }, lifecycleExecutor)
    }

    /**
     * 通过官方 ACP `session/load` 切换到指定历史会话并回放完整历史对话上下文。
     */
    fun loadSession(session: AcpModels.SessionSummary): CompletableFuture<AcpModels.SessionMetadata> {
        return switchToHistoricalSession(session, useResume = false)
    }

    /**
     * 通过官方 ACP `session/resume` 快速续聊指定历史会话（不回放历史消息，适合超长对话秒切）。
     */
    fun resumeSession(session: AcpModels.SessionSummary): CompletableFuture<AcpModels.SessionMetadata> {
        return switchToHistoricalSession(session, useResume = true)
    }

    /**
     * 保存并热应用当前项目的官方 ACP `additionalDirectories` 关联目录配置。
     */
    fun applyAdditionalDirectories(directories: List<String>): CompletableFuture<Boolean> {
        GeminiAcpSettingsState.getInstance().setAdditionalDirectories(project.basePath, directories)
        return CompletableFuture.supplyAsync({
            val activeTransport = transport
            val sid = activeSessionId
            if (activeTransport != null && activeTransport.isAlive() && sid != null &&
                capabilities.sessionResume && capabilities.additionalDirectories
            ) {
                val cwd = project.basePath ?: System.getProperty("user.home")
                val params = AcpModels.buildSessionTargetParamsJson(
                    sessionId = sid,
                    cwd = cwd,
                    additionalDirectories = resolveConfiguredAdditionalDirs(),
                    systemPromptAppend = resolveConfiguredSystemPromptAppend()
                )
                runCatching {
                    val resJson = activeTransport.sendRequest("session/resume", params, timeoutSeconds = 30).get()
                    val resumed = gson.fromJson(resJson, AcpModels.NewSessionResult::class.java)
                    if (resumed != null) {
                        publishSession(resumed, lastMetadata?.tierName)
                    }
                    notifyLog("已通过官方 ACP session/resume 热更新关联目录 (${directories.size} 个)")
                    return@supplyAsync true
                }.onFailure { err ->
                    notifyLog("关联目录已保存，将在下次新建对话或重连时生效（热同步跳过：${unwrapCause(err).message}）")
                }
            } else {
                notifyLog("已更新关联目录 (${directories.size} 个)，将在连接或新建会话时通过 additionalDirectories 传入")
            }
            false
        }, lifecycleExecutor)
    }

    private fun switchToHistoricalSession(
        session: AcpModels.SessionSummary,
        useResume: Boolean
    ): CompletableFuture<AcpModels.SessionMetadata> {
        return CompletableFuture.supplyAsync({
            val activeTransport = ensureConnectedTransportOnLifecycleThread()
            if (activeSessionId != null && activeSessionId != session.sessionId) {
                closeActiveSessionBestEffort(activeTransport)
            }
            fsHandler.clearSessionAllowances()
            clearUsage()
            val targetCwd = SessionPathUtil.resolveLoadCwd(session.cwd, project.basePath)
            val displayTitle = GeminiAcpSettingsState.getInstance()
                .resolveDisplayTitle(session.sessionId, session.title)
            val method = if (useResume && capabilities.sessionResume) "session/resume" else "session/load"
            val actionLabel = if (method == "session/resume") "正在快速续聊历史对话" else "正在加载历史对话"

            updateState(ConnectionState.CONNECTING, "$actionLabel：$displayTitle ...")
            replayingHistory.set(method == "session/load")
            listeners.forEach { it.onSessionLoadingStarted(session) }

            try {
                val params = AcpModels.buildSessionTargetParamsJson(
                    sessionId = session.sessionId,
                    cwd = targetCwd,
                    additionalDirectories = resolveConfiguredAdditionalDirs(),
                    systemPromptAppend = resolveConfiguredSystemPromptAppend()
                )
                val resultJson = activeTransport.sendRequest(
                    method,
                    params,
                    timeoutSeconds = 60
                ).get()
                val loaded = gson.fromJson(resultJson, AcpModels.NewSessionResult::class.java)
                    ?: throw IllegalStateException("$method 返回空结果")
                replayingHistory.set(false)
                activeServerSessionTitle = session.title
                activeSessionTitle = displayTitle
                listeners.forEach { it.onSessionTitleChanged(displayTitle) }
                val meta = publishSession(loaded, lastMetadata?.tierName)
                val authMode = GeminiAcpSettingsState.getInstance().state.authMethodId
                updateState(ConnectionState.READY, formatReadyDetail(authMode, meta.tierName))
                listeners.forEach { it.onSessionLoadingFinished(meta, null) }
                notifyLog("已通过 $method 切换到历史对话：${session.sessionId} ($displayTitle)")
                meta
            } catch (t: Throwable) {
                replayingHistory.set(false)
                val root = unwrapCause(t)
                val msg = root.message ?: "切换历史会话失败"
                val authMode = GeminiAcpSettingsState.getInstance().state.authMethodId
                updateState(ConnectionState.READY, formatReadyDetail(authMode, lastMetadata?.tierName))
                listeners.forEach { it.onSessionLoadingFinished(null, msg) }
                throw root
            }
        }, lifecycleExecutor)
    }

    /**
     * 通过官方 ACP `session/set_config_option` 切换模型、思考强度、模式等动态配置。
     */
    fun setConfigOption(configId: String, value: String): CompletableFuture<List<AcpModels.ConfigOption>> {
        return CompletableFuture.supplyAsync({
            val currentTransport = transport ?: throw IllegalStateException("尚未连接 ACP 服务")
            val sid = activeSessionId ?: throw IllegalStateException("尚无活动会话")
            val resultJson = currentTransport.sendRequest(
                "session/set_config_option",
                AcpModels.SetConfigOptionParams(sid, configId, value),
                30
            ).get()
            val parsed = gson.fromJson(resultJson, AcpModels.SetConfigOptionResult::class.java)
            val updatedOptions = parsed?.configOptions.orEmpty()
            notifyLog("已更新配置 $configId = $value")
            if (updatedOptions.isNotEmpty()) {
                applyUpdatedConfigOptions(updatedOptions)
            }
            updatedOptions
        }, lifecycleExecutor)
    }

    fun setModel(modelId: String): CompletableFuture<Unit> {
        return CompletableFuture.supplyAsync({
            val currentTransport = transport ?: throw IllegalStateException("尚未连接 ACP 服务")
            val sid = activeSessionId ?: throw IllegalStateException("尚无活动会话")
            currentTransport.sendRequest("session/set_model", AcpModels.SetModelParams(sid, modelId), 30).get()
            notifyLog("已切换模型: $modelId")
            lastMetadata = lastMetadata?.copy(currentModelId = modelId)
        }, lifecycleExecutor)
    }

    fun setMode(modeId: String): CompletableFuture<Unit> {
        return CompletableFuture.supplyAsync({
            val currentTransport = transport ?: throw IllegalStateException("尚未连接 ACP 服务")
            val sid = activeSessionId ?: throw IllegalStateException("尚无活动会话")
            currentTransport.sendRequest("session/set_mode", AcpModels.SetModeParams(sid, modeId), 30).get()
            notifyLog("已切换模式: $modeId")
            lastMetadata = lastMetadata?.copy(currentModeId = modeId)
        }, lifecycleExecutor)
    }

    fun sendPrompt(promptText: String): CompletableFuture<String?> =
        sendPrompt(promptText, emptyList(), emptyList())

    fun sendPrompt(
        promptText: String,
        images: List<ImageAttachmentHelper.ImageAttachment>,
        embeddedResources: List<AcpModels.PromptBlock.EmbeddedResource>
    ): CompletableFuture<String?> {
        if (promptText.isBlank() && images.isEmpty()) {
            return CompletableFuture.completedFuture(null)
        }
        return CompletableFuture.supplyAsync({
            val activeTransport = ensureConnectedTransportOnLifecycleThread()
            val sid = activeSessionId ?: createSession(activeTransport).also { publishSession(it, lastMetadata?.tierName) }.sessionId

            if (images.isNotEmpty() && !capabilities.promptImage) {
                throw IllegalStateException("当前 ACP 后端未声明图片多模态能力 (promptCapabilities.image)，请移除图片后重试或使用 Antigravity CLI (agy acp)。")
            }

            val blocks = mutableListOf<AcpModels.PromptBlock>()
            if (promptText.isNotBlank()) {
                blocks.add(AcpModels.PromptBlock.Text(promptText))
            }
            if (capabilities.promptEmbeddedContext && embeddedResources.isNotEmpty()) {
                blocks.addAll(embeddedResources)
            }
            if (capabilities.promptImage && images.isNotEmpty()) {
                images.forEach { img ->
                    blocks.add(AcpModels.PromptBlock.Image(data = img.base64Data, mimeType = img.mimeType))
                }
            }

            updateState(ConnectionState.STREAMING, "正在思考并生成响应...")
            try {
                val params = AcpModels.buildPromptParamsJson(sid, blocks)
                val resultJson = activeTransport.sendRequest("session/prompt", params, timeoutSeconds = 300).get()
                val result = gson.fromJson(resultJson, AcpModels.PromptResult::class.java)
                val authMode = GeminiAcpSettingsState.getInstance().state.authMethodId
                updateState(ConnectionState.READY, formatReadyDetail(authMode, lastMetadata?.tierName))
                listeners.forEach { it.onTurnFinished(result?.stopReason) }
                result?.stopReason
            } catch (t: Throwable) {
                val rootCause = unwrapCause(t)
                updateState(ConnectionState.ERROR, rootCause.message ?: "请求失败")
                throw rootCause
            }
        }, lifecycleExecutor)
    }

    fun cancelCurrentTurn() {
        val currentTransport = transport ?: return
        val sid = activeSessionId ?: return
        if (currentTransport.isAlive()) {
            currentTransport.sendNotification("session/cancel", AcpModels.CancelParams(sid))
            notifyLog("已发送取消生成指令 (session/cancel)")
        }
    }

    fun disconnect() {
        lifecycleExecutor.submit {
            closeActiveSessionBestEffort(transport)
            clearUsage()
            disconnectInternal()
            updateState(ConnectionState.DISCONNECTED, "未连接")
        }
    }

    private fun ensureConnectedTransportOnLifecycleThread(): JsonRpcTransport {
        val existing = transport
        if (existing != null && existing.isAlive() && activeSessionId != null) {
            return existing
        }
        connectAndAuthenticateBlocking()
        return transport ?: throw IllegalStateException("无法建立 ACP 连接")
    }

    private fun connectAndAuthenticateBlocking(): String {
        val settings = GeminiAcpSettingsState.getInstance().state
        val resolved = GeminiExecutableResolver.resolveExecutable(
            settings.geminiExecutablePath,
            allowNpxFallback = settings.allowNpxFallback
        )
        if (!resolved.found || resolved.executablePath == null) {
            val msg = "未找到 Antigravity CLI (agy) 或 gemini CLI。个人账号请先安装 Antigravity CLI (https://antigravity.google)。"
            updateState(ConnectionState.ERROR, msg)
            throw IllegalStateException(msg)
        }

        disconnectInternal()
        fsHandler.clearSessionAllowances()

        val acpFlag = settings.acpFlag.ifBlank { "--acp" }
        return try {
            startAndHandshake(resolved, acpFlag, settings)
        } catch (firstErr: Throwable) {
            val root = unwrapCause(firstErr)
            val msg = root.message.orEmpty()
            if (resolved.backendKind != GeminiExecutableResolver.BackendKind.ANTIGRAVITY_AGY &&
                acpFlag == "--acp" &&
                (msg.contains("Unknown argument", true) || msg.contains("unknown option", true))
            ) {
                notifyLog("检测到旧版 Gemini CLI，自动回退尝试 --experimental-acp...")
                disconnectInternal()
                startAndHandshake(resolved, "--experimental-acp", settings)
            } else {
                updateState(ConnectionState.ERROR, msg.ifBlank { "连接或认证失败" })
                throw root
            }
        }
    }

    private fun performLogoutOnLifecycleThread(): Boolean {
        var activeTransport = transport?.takeIf { it.isAlive() }
        if (activeTransport == null) {
            val settings = GeminiAcpSettingsState.getInstance().state
            val resolved = GeminiExecutableResolver.resolveExecutable(
                settings.geminiExecutablePath,
                allowNpxFallback = settings.allowNpxFallback
            )
            if (!resolved.found || resolved.executablePath == null) {
                val msg = "未找到 Antigravity CLI (agy) 或 gemini CLI，无法执行退出登录。"
                updateState(ConnectionState.ERROR, msg)
                throw IllegalStateException(msg)
            }
            val acpFlag = settings.acpFlag.ifBlank { "--acp" }
            val (launched, _) = launchAndInitializeWithFallback(resolved, acpFlag, settings)
            activeTransport = launched
        }

        if (!supportsLogout) {
            notifyLog("当前 CLI 后端未声明官方 ACP logout 能力")
            return false
        }

        closeActiveSessionBestEffort(activeTransport)
        updateState(ConnectionState.AUTHENTICATING, "正在通过官方 ACP 退出当前账号...")
        try {
            activeTransport.sendRequest("logout", JsonObject(), timeoutSeconds = 30).get()
            notifyLog("已通过官方 ACP (logout) 成功退出当前账号")
            return true
        } catch (firstErr: Throwable) {
            val root = unwrapCause(firstErr)
            if (root is JsonRpcTransport.JsonRpcRemoteException && root.code == -32601) {
                // 兼容未来 ACP v2 的 auth/logout 方法名
                activeTransport.sendRequest("auth/logout", JsonObject(), timeoutSeconds = 30).get()
                notifyLog("已通过官方 ACP (auth/logout) 成功退出当前账号")
                return true
            }
            updateState(ConnectionState.ERROR, root.message ?: "退出登录失败")
            throw root
        }
    }

    private fun launchAndInitializeWithFallback(
        resolved: GeminiExecutableResolver.ResolutionResult,
        acpFlag: String,
        settings: GeminiAcpSettingsState.State
    ): Pair<JsonRpcTransport, AcpModels.InitializeResult?> {
        return try {
            launchAndInitializeTransport(resolved, acpFlag, settings)
        } catch (firstErr: Throwable) {
            val root = unwrapCause(firstErr)
            val msg = root.message.orEmpty()
            if (resolved.backendKind != GeminiExecutableResolver.BackendKind.ANTIGRAVITY_AGY &&
                acpFlag == "--acp" &&
                (msg.contains("Unknown argument", true) || msg.contains("unknown option", true))
            ) {
                notifyLog("检测到旧版 Gemini CLI，自动回退尝试 --experimental-acp...")
                disconnectInternal()
                launchAndInitializeTransport(resolved, "--experimental-acp", settings)
            } else {
                updateState(ConnectionState.ERROR, msg.ifBlank { "启动或初始化 ACP 服务失败" })
                throw root
            }
        }
    }

    private fun launchAndInitializeTransport(
        resolved: GeminiExecutableResolver.ResolutionResult,
        acpFlag: String,
        settings: GeminiAcpSettingsState.State
    ): Pair<JsonRpcTransport, AcpModels.InitializeResult?> {
        activeBackendKind = resolved.backendKind
        val launchHint = when (resolved.backendKind) {
            GeminiExecutableResolver.BackendKind.ANTIGRAVITY_AGY -> "正在启动 Antigravity ACP 服务 (agy acp)..."
            GeminiExecutableResolver.BackendKind.NPX_GEMINI_CLI -> "正在通过 npx 启动 @google/gemini-cli ($acpFlag)..."
            GeminiExecutableResolver.BackendKind.GEMINI_CLI -> "正在启动 Gemini CLI ($acpFlag)..."
        }
        updateState(ConnectionState.CONNECTING, launchHint)
        val extraDirs = GeminiAcpSettingsState.getInstance().getAdditionalDirectories(project.basePath)
        val cmd = buildCommand(resolved, acpFlag, settings, extraDirs)
        notifyLog("启动命令: ${cmd.joinToString(" ")}")

        val env = buildProcessEnv(settings)
        val removed = if (settings.stripGeminiApiKeyEnv && settings.authMethodId == "oauth-personal") {
            setOf("GEMINI_API_KEY")
        } else {
            emptySet()
        }
        val workDir = project.basePath?.let(::File)?.takeIf { it.isDirectory }
            ?: File(System.getProperty("user.home"))

        val newTransport = JsonRpcTransport(
            command = cmd,
            workingDirectory = workDir,
            extraEnv = env,
            removedEnvKeys = removed,
            requestHandler = object : JsonRpcTransport.IncomingRequestHandler {
                override fun handleRequest(method: String, params: JsonObject): JsonElement {
                    return handleReverseRpc(method, params)
                }
            },
            onNotification = { method, params ->
                if (method == "session/update") {
                    AcpModels.parseSessionUpdate(params)?.let { ev ->
                        when (ev) {
                            is AcpModels.SessionUpdateEvent.ConfigOptionsUpdated ->
                                applyUpdatedConfigOptions(ev.configOptions)
                            is AcpModels.SessionUpdateEvent.AvailableCommandsUpdated -> {
                                availableCommands = ev.commands
                                listeners.forEach { it.onAvailableCommandsChanged(ev.commands) }
                            }
                            is AcpModels.SessionUpdateEvent.SessionInfoUpdated -> {
                                val t = ev.title?.takeIf { it.isNotBlank() }
                                if (t != null) {
                                    activeServerSessionTitle = t
                                    val sid = activeSessionId
                                    val custom = sid?.let { GeminiAcpSettingsState.getInstance().getCustomTitle(it) }
                                    if (custom == null) {
                                        activeSessionTitle = t
                                        listeners.forEach { it.onSessionTitleChanged(t) }
                                    }
                                }
                            }
                            is AcpModels.SessionUpdateEvent.UsageEvent -> {
                                lastUsage = ev
                                listeners.forEach { it.onUsageUpdated(ev) }
                            }
                            else -> Unit
                        }
                        val isReplay = replayingHistory.get()
                        listeners.forEach { it.onSessionUpdate(ev, isReplay) }
                    }
                }
            },
            onStderrLine = { line ->
                notifyLog("[CLI] $line")
                urlRegex.find(line)?.value?.let { url ->
                    listeners.forEach { it.onAuthUrlDetected(url) }
                }
            },
            onProcessExit = { code, stderr ->
                activeSessionId = null
                replayingHistory.set(false)
                val detail = if (stderr.isNotBlank()) {
                    "进程退出 ($code): ${stderr.lineSequence().lastOrNull { it.isNotBlank() } ?: ""}"
                } else {
                    "ACP 服务进程已退出 ($code)"
                }
                updateState(ConnectionState.DISCONNECTED, detail)
            }
        )

        newTransport.start()
        transport = newTransport

        val initTimeoutSec = if (resolved.viaNpxFallback) 150L else 60L
        val initJson = newTransport.sendRequest("initialize", AcpModels.InitializeParams(), timeoutSeconds = initTimeoutSec).get()
        val initResult = gson.fromJson(initJson, AcpModels.InitializeResult::class.java)
        availableAuthMethods = initResult?.authMethods.orEmpty()
        val parsedCaps = initResult?.parseCapabilities() ?: AcpModels.AgentCapabilitiesSummary()
        capabilities = parsedCaps
        supportsLogout = parsedCaps.authLogout
        listeners.forEach { it.onCapabilitiesUpdated(parsedCaps) }
        val agentName = initResult?.agentInfo?.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: "ACP Server"
        val agentVer = initResult?.agentInfo?.get("version")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        notifyLog(
            "ACP 初始化成功 ($agentName $agentVer) | 认证方式: ${availableAuthMethods.joinToString { it.id }} | " +
                "能力: image=${parsedCaps.promptImage}, embeddedContext=${parsedCaps.promptEmbeddedContext}, " +
                "resume=${parsedCaps.sessionResume}, close=${parsedCaps.sessionClose}, " +
                "additionalDirs=${parsedCaps.additionalDirectories}, logout=${parsedCaps.authLogout}, " +
                "systemPromptAppend=${parsedCaps.antigravitySystemPromptAppend}"
        )
        return newTransport to initResult
    }

    private fun startAndHandshake(
        resolved: GeminiExecutableResolver.ResolutionResult,
        acpFlag: String,
        settings: GeminiAcpSettingsState.State
    ): String {
        val (newTransport, _) = launchAndInitializeTransport(resolved, acpFlag, settings)

        val chosenAuth = settings.authMethodId.ifBlank { "oauth-personal" }
        updateState(ConnectionState.AUTHENTICATING, "正在执行 $chosenAuth 认证（请在弹出的浏览器中完成账号授权）...")
        val authResult = newTransport.sendRequest("authenticate", AcpModels.AuthenticateParams(chosenAuth), timeoutSeconds = 180).get()
        val authTier = AcpModels.extractTierName(authResult)
        notifyLog("$chosenAuth 认证成功" + (authTier?.let { " ($it)" } ?: ""))

        clearUsage()
        val session = createSession(newTransport)
        activeServerSessionTitle = null
        activeSessionTitle = "新对话"
        listeners.forEach { it.onSessionTitleChanged("新对话") }
        publishSession(session, authTier)
        updateState(ConnectionState.READY, formatReadyDetail(chosenAuth, lastMetadata?.tierName))
        return session.sessionId
    }

    private fun createSession(activeTransport: JsonRpcTransport): AcpModels.NewSessionResult {
        val cwd = project.basePath ?: System.getProperty("user.home")
        val params = AcpModels.buildNewSessionParamsJson(
            cwd = cwd,
            additionalDirectories = resolveConfiguredAdditionalDirs(),
            systemPromptAppend = resolveConfiguredSystemPromptAppend()
        )
        val sessionJson = activeTransport.sendRequest(
            "session/new",
            params,
            timeoutSeconds = 45
        ).get()
        return gson.fromJson(sessionJson, AcpModels.NewSessionResult::class.java)
            ?: throw IllegalStateException("session/new 返回空结果")
    }

    private fun resolveConfiguredAdditionalDirs(): List<String> {
        if (!capabilities.additionalDirectories) return emptyList()
        return GeminiAcpSettingsState.getInstance()
            .getAdditionalDirectories(project.basePath)
            .filter { File(it).isDirectory }
    }

    private fun resolveConfiguredSystemPromptAppend(): String? {
        if (!capabilities.antigravitySystemPromptAppend) return null
        val state = GeminiAcpSettingsState.getInstance().state
        return AcpContextBuilder.resolveSystemPromptAppend(
            projectBasePath = project.basePath,
            globalSystemPrompt = state.systemPromptAppend,
            loadProjectRulesFile = state.loadProjectRulesFile
        )
    }

    private fun closeActiveSessionBestEffort(activeTransport: JsonRpcTransport?) {
        val sid = activeSessionId ?: return
        if (activeTransport == null || !activeTransport.isAlive() || !capabilities.sessionClose) return
        runCatching {
            activeTransport.sendRequest("session/close", AcpModels.CloseSessionParams(sid), timeoutSeconds = 5).get()
            notifyLog("已通过官方 ACP session/close 释放会话资源: ${sid.take(8)}")
        }.onFailure { err ->
            notifyLog("关闭旧会话跳过 (${sid.take(8)}): ${unwrapCause(err).message}")
        }
    }

    private fun clearUsage() {
        lastUsage = null
        listeners.forEach { it.onUsageUpdated(null) }
    }

    private fun publishSession(session: AcpModels.NewSessionResult, fallbackTier: String?): AcpModels.SessionMetadata {
        activeSessionId = session.sessionId
        val tier = AcpModels.extractTierName(session._meta) ?: fallbackTier
        val meta = AcpModels.SessionMetadata(
            sessionId = session.sessionId,
            configOptions = session.configOptions,
            models = session.models?.availableModels.orEmpty(),
            currentModelId = session.models?.currentModelId,
            modes = session.modes?.availableModes.orEmpty(),
            currentModeId = session.modes?.currentModeId,
            tierName = tier
        )
        lastMetadata = meta
        listeners.forEach { it.onSessionMetadataChanged(meta) }
        return meta
    }

    private fun applyUpdatedConfigOptions(updated: List<AcpModels.ConfigOption>) {
        val current = lastMetadata ?: return
        val next = current.copy(configOptions = updated)
        lastMetadata = next
        listeners.forEach { it.onSessionMetadataChanged(next) }
    }

    private fun formatReadyDetail(authMode: String, tierName: String?): String {
        return if (!tierName.isNullOrBlank()) {
            "已连接 · $authMode · $tierName"
        } else {
            "已连接 · $authMode"
        }
    }

    private fun handleReverseRpc(method: String, params: JsonObject): JsonElement {
        return when (method) {
            "fs/read_text_file" -> {
                val req = gson.fromJson(params, AcpModels.FsReadTextFileParams::class.java)
                val result = fsHandler.handleReadTextFile(req)
                gson.toJsonTree(result)
            }
            "fs/write_text_file" -> {
                val req = gson.fromJson(params, AcpModels.FsWriteTextFileParams::class.java)
                fsHandler.handleWriteTextFile(req)
                JsonObject()
            }
            "session/request_permission" -> {
                val req = gson.fromJson(params, AcpModels.PermissionRequestParams::class.java)
                val result = fsHandler.handlePermissionRequest(req)
                gson.toJsonTree(result)
            }
            else -> throw JsonRpcTransport.JsonRpcRemoteException(-32601, "不支持的客户端方法: $method")
        }
    }

    internal fun buildCommand(
        resolved: GeminiExecutableResolver.ResolutionResult,
        acpFlag: String,
        settings: GeminiAcpSettingsState.State = GeminiAcpSettingsState.State(),
        additionalDirectories: List<String> = emptyList()
    ): List<String> {
        val exe = resolved.executablePath!!
        if (resolved.backendKind == GeminiExecutableResolver.BackendKind.ANTIGRAVITY_AGY) {
            val base = if (resolved.needsCmdWrapper) {
                mutableListOf("cmd.exe", "/c", "call", exe, "acp")
            } else {
                mutableListOf(exe, "acp")
            }
            if (settings.agySandbox) {
                base.add("--sandbox")
            }
            if (settings.agyDisableTelemetry) {
                base.add("--disable-telemetry")
            }
            if (settings.agyAgentName.isNotBlank()) {
                base.add("--agent")
                base.add(settings.agyAgentName.trim())
            }
            additionalDirectories.map { it.trim() }
                .filter { it.isNotEmpty() && File(it).isDirectory }
                .distinct()
                .forEach { dir ->
                    base.add("--add-dir")
                    base.add(dir)
                }
            return base
        }
        if (resolved.directNodePath != null && resolved.directScriptPath != null) {
            return listOf(resolved.directNodePath, resolved.directScriptPath, acpFlag)
        }
        return when {
            resolved.viaNpxFallback && resolved.needsCmdWrapper ->
                listOf("cmd.exe", "/c", "call", exe, "-y", "@google/gemini-cli", acpFlag)
            resolved.viaNpxFallback ->
                listOf(exe, "-y", "@google/gemini-cli", acpFlag)
            resolved.needsCmdWrapper ->
                listOf("cmd.exe", "/c", "call", exe, acpFlag)
            else ->
                listOf(exe, acpFlag)
        }
    }

    private fun buildProcessEnv(settings: GeminiAcpSettingsState.State): Map<String, String> {
        val env = mutableMapOf<String, String>()
        if (settings.proxyUrl.isNotBlank()) {
            val proxy = settings.proxyUrl.trim()
            env["HTTP_PROXY"] = proxy
            env["HTTPS_PROXY"] = proxy
            env["http_proxy"] = proxy
            env["https_proxy"] = proxy
        }
        if (settings.googleCloudProject.isNotBlank()) {
            env["GOOGLE_CLOUD_PROJECT"] = settings.googleCloudProject.trim()
        }
        return env
    }

    private fun clearAuthAndDisconnectInternal() {
        lastMetadata = null
        availableCommands = emptyList()
        clearUsage()
        fsHandler.clearSessionAllowances()
        disconnectInternal()
    }

    private fun disconnectInternal() {
        activeSessionId = null
        activeSessionTitle = null
        activeServerSessionTitle = null
        replayingHistory.set(false)
        transport?.close()
        transport = null
    }

    private fun updateState(state: ConnectionState, detail: String) {
        currentState = state
        listeners.forEach { it.onStateChanged(state, detail) }
    }

    private fun notifyLog(msg: String) {
        logger.info(msg)
        listeners.forEach { it.onLogMessage(msg) }
    }

    private fun unwrapCause(t: Throwable): Throwable {
        var cur: Throwable = t
        while (cur.cause != null && (cur is java.util.concurrent.ExecutionException || cur is java.util.concurrent.CompletionException)) {
            cur = cur.cause!!
        }
        return cur
    }

    override fun dispose() {
        closeActiveSessionBestEffort(transport)
        disconnectInternal()
        lifecycleExecutor.shutdownNow()
    }

    companion object {
        fun getInstance(project: Project): AcpClientService =
            project.getService(AcpClientService::class.java)
    }
}
