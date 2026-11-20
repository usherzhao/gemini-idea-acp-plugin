package com.geminicode.acp.protocol

import com.geminicode.acp.ide.IdeaFsHandler
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
        fun onSessionMetadataChanged(metadata: AcpModels.SessionMetadata) {}
        fun onSessionTitleChanged(title: String) {}
        fun onSessionLoadingStarted(session: AcpModels.SessionSummary) {}
        fun onSessionLoadingFinished(metadata: AcpModels.SessionMetadata?, errorMessage: String?) {}
        fun onSessionUpdate(event: AcpModels.SessionUpdateEvent, isReplay: Boolean) {}
        fun onTurnFinished(stopReason: String?) {}
        fun onAuthUrlDetected(url: String) {}
        fun onLogMessage(message: String) {}
    }

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

    fun newSession(): CompletableFuture<String> {
        return CompletableFuture.supplyAsync({
            val currentTransport = transport
            if (currentTransport == null || !currentTransport.isAlive()) {
                return@supplyAsync connectAndAuthenticateBlocking()
            }
            fsHandler.clearSessionAllowances()
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
        return CompletableFuture.supplyAsync({
            val activeTransport = ensureConnectedTransportOnLifecycleThread()
            fsHandler.clearSessionAllowances()
            val targetCwd = SessionPathUtil.resolveLoadCwd(session.cwd, project.basePath)
            val displayTitle = GeminiAcpSettingsState.getInstance()
                .resolveDisplayTitle(session.sessionId, session.title)

            updateState(ConnectionState.CONNECTING, "正在加载历史对话：$displayTitle ...")
            replayingHistory.set(true)
            listeners.forEach { it.onSessionLoadingStarted(session) }

            try {
                val resultJson = activeTransport.sendRequest(
                    "session/load",
                    AcpModels.LoadSessionParams(
                        sessionId = session.sessionId,
                        cwd = targetCwd,
                        mcpServers = emptyList()
                    ),
                    timeoutSeconds = 60
                ).get()
                val loaded = gson.fromJson(resultJson, AcpModels.NewSessionResult::class.java)
                    ?: throw IllegalStateException("session/load 返回空结果")
                replayingHistory.set(false)
                activeServerSessionTitle = session.title
                activeSessionTitle = displayTitle
                listeners.forEach { it.onSessionTitleChanged(displayTitle) }
                val meta = publishSession(loaded, lastMetadata?.tierName)
                val authMode = GeminiAcpSettingsState.getInstance().state.authMethodId
                updateState(ConnectionState.READY, formatReadyDetail(authMode, meta.tierName))
                listeners.forEach { it.onSessionLoadingFinished(meta, null) }
                notifyLog("已切换到历史对话：${session.sessionId} ($displayTitle)")
                meta
            } catch (t: Throwable) {
                replayingHistory.set(false)
                val root = unwrapCause(t)
                val msg = root.message ?: "加载历史会话失败"
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

    fun sendPrompt(promptText: String): CompletableFuture<String?> {
        if (promptText.isBlank()) {
            return CompletableFuture.completedFuture(null)
        }
        return CompletableFuture.supplyAsync({
            val activeTransport = ensureConnectedTransportOnLifecycleThread()
            val sid = activeSessionId ?: createSession(activeTransport).also { publishSession(it, lastMetadata?.tierName) }.sessionId

            updateState(ConnectionState.STREAMING, "正在思考并生成响应...")
            try {
                val params = AcpModels.PromptParams(
                    sessionId = sid,
                    prompt = listOf(AcpModels.ContentBlock(type = "text", text = promptText))
                )
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

    private fun startAndHandshake(
        resolved: GeminiExecutableResolver.ResolutionResult,
        acpFlag: String,
        settings: GeminiAcpSettingsState.State
    ): String {
        val launchHint = when (resolved.backendKind) {
            GeminiExecutableResolver.BackendKind.ANTIGRAVITY_AGY -> "正在启动 Antigravity ACP 服务 (agy acp)..."
            GeminiExecutableResolver.BackendKind.NPX_GEMINI_CLI -> "正在通过 npx 启动 @google/gemini-cli ($acpFlag)..."
            GeminiExecutableResolver.BackendKind.GEMINI_CLI -> "正在启动 Gemini CLI ($acpFlag)..."
        }
        updateState(ConnectionState.CONNECTING, launchHint)
        val cmd = buildCommand(resolved, acpFlag)
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
        val agentName = initResult?.agentInfo?.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: "ACP Server"
        val agentVer = initResult?.agentInfo?.get("version")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        notifyLog("ACP 初始化成功 ($agentName $agentVer)，支持认证方式: ${availableAuthMethods.joinToString { it.id }}")

        val chosenAuth = settings.authMethodId.ifBlank { "oauth-personal" }
        updateState(ConnectionState.AUTHENTICATING, "正在执行 $chosenAuth 认证（首次登录请在弹出的浏览器中授权）...")
        val authResult = newTransport.sendRequest("authenticate", AcpModels.AuthenticateParams(chosenAuth), timeoutSeconds = 180).get()
        val authTier = AcpModels.extractTierName(authResult)
        notifyLog("$chosenAuth 认证成功" + (authTier?.let { " ($it)" } ?: ""))

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
        val sessionJson = activeTransport.sendRequest(
            "session/new",
            AcpModels.NewSessionParams(cwd = cwd, mcpServers = emptyList()),
            timeoutSeconds = 45
        ).get()
        return gson.fromJson(sessionJson, AcpModels.NewSessionResult::class.java)
            ?: throw IllegalStateException("session/new 返回空结果")
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
        acpFlag: String
    ): List<String> {
        val exe = resolved.executablePath!!
        if (resolved.backendKind == GeminiExecutableResolver.BackendKind.ANTIGRAVITY_AGY) {
            return if (resolved.needsCmdWrapper) {
                listOf("cmd.exe", "/c", "call", exe, "acp")
            } else {
                listOf(exe, "acp")
            }
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
        disconnectInternal()
        lifecycleExecutor.shutdownNow()
    }

    companion object {
        fun getInstance(project: Project): AcpClientService =
            project.getService(AcpClientService::class.java)
    }
}
