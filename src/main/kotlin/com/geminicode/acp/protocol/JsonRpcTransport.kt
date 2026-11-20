package com.geminicode.acp.protocol

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 基于标准输入输出 (stdio) 的双向 JSON-RPC 2.0 传输层，用于与 Gemini CLI ACP 子进程通信。
 */
class JsonRpcTransport(
    private val command: List<String>,
    private val workingDirectory: File,
    private val extraEnv: Map<String, String>,
    private val removedEnvKeys: Set<String>,
    private val requestHandler: IncomingRequestHandler,
    private val onNotification: (method: String, params: JsonObject) -> Unit,
    private val onStderrLine: (line: String) -> Unit,
    private val onProcessExit: (exitCode: Int, stderrSnapshot: String) -> Unit
) {

    interface IncomingRequestHandler {
        fun handleRequest(method: String, params: JsonObject): JsonElement
    }

    class JsonRpcRemoteException(val code: Int, override val message: String, val data: JsonElement? = null) :
        RuntimeException("JSON-RPC 错误 $code: $message")

    private val logger = Logger.getInstance(JsonRpcTransport::class.java)
    private val gson: Gson = GsonBuilder().serializeNulls().create()
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonElement>>()
    private val closed = AtomicBoolean(false)
    private val writeLock = Any()
    private val stderrBuffer = StringBuilder()

    private val workerExecutor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "GeminiAcp-RpcWorker").apply { isDaemon = true }
    }

    @Volatile
    private var process: Process? = null

    @Volatile
    private var writer: BufferedWriter? = null

    fun start() {
        val pb = ProcessBuilder(command)
        pb.directory(workingDirectory)
        val env = pb.environment()
        removedEnvKeys.forEach { env.remove(it) }
        extraEnv.forEach { (k, v) -> env[k] = v }

        val proc = pb.start()
        process = proc
        writer = BufferedWriter(OutputStreamWriter(proc.outputStream, StandardCharsets.UTF_8))

        Thread({ readStdoutLoop(proc) }, "GeminiAcp-Stdout").apply {
            isDaemon = true
            start()
        }
        Thread({ readStderrLoop(proc) }, "GeminiAcp-Stderr").apply {
            isDaemon = true
            start()
        }
    }

    fun isAlive(): Boolean = process?.isAlive == true && !closed.get()

    fun sendRequest(method: String, params: Any?, timeoutSeconds: Long = 120): CompletableFuture<JsonElement> {
        if (!isAlive()) {
            return CompletableFuture.failedFuture(IllegalStateException("ACP 子进程未运行"))
        }
        val id = nextId.getAndIncrement()
        val future = CompletableFuture<JsonElement>()
        pending[id] = future

        val payload = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", method)
            add("params", gson.toJsonTree(params))
        }

        try {
            writeLine(gson.toJson(payload))
        } catch (t: Throwable) {
            pending.remove(id)
            future.completeExceptionally(t)
            return future
        }

        return future.orTimeout(timeoutSeconds, TimeUnit.SECONDS).whenComplete { _, err ->
            if (err != null) pending.remove(id)
        }
    }

    fun sendNotification(method: String, params: Any?) {
        if (!isAlive()) return
        val payload = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("method", method)
            add("params", gson.toJsonTree(params))
        }
        runCatching { writeLine(gson.toJson(payload)) }
            .onFailure { logger.warn("发送通知 $method 失败", it) }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        val proc = process
        runCatching { writer?.close() }
        if (proc != null) {
            terminateProcessTree(proc)
        }
        val err = IllegalStateException("ACP 连接已关闭")
        pending.values.forEach { it.completeExceptionally(err) }
        pending.clear()
        workerExecutor.shutdownNow()
    }

    private fun terminateProcessTree(proc: Process) {
        runCatching {
            proc.toHandle().descendants().forEach { child ->
                runCatching { child.destroy() }
            }
        }
        if (proc.isAlive) {
            proc.destroy()
            if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                runCatching {
                    proc.toHandle().descendants().forEach { child ->
                        runCatching { child.destroyForcibly() }
                    }
                }
                proc.destroyForcibly()
            }
        }
    }

    private fun writeLine(json: String) {
        val out = writer ?: throw IllegalStateException("输出流尚未初始化")
        synchronized(writeLock) {
            out.write(json)
            out.newLine()
            out.flush()
        }
    }

    private fun readStdoutLoop(proc: Process) {
        try {
            BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val trimmed = line!!.trim()
                    if (trimmed.isEmpty()) continue
                    runCatching { handleIncomingLine(trimmed) }
                        .onFailure { logger.warn("解析 ACP stdout 消息失败: $trimmed", it) }
                }
            }
        } catch (t: Throwable) {
            if (!closed.get()) logger.warn("读取 ACP stdout 异常", t)
        } finally {
            val exitCode = runCatching { proc.waitFor() }.getOrDefault(-1)
            val snapshot = synchronized(stderrBuffer) { stderrBuffer.toString() }
            if (!closed.get()) {
                val exitErr = IllegalStateException("Gemini CLI ACP 进程已退出，退出码 $exitCode")
                pending.values.forEach { it.completeExceptionally(exitErr) }
                pending.clear()
                onProcessExit(exitCode, snapshot)
            }
        }
    }

    private fun readStderrLoop(proc: Process) {
        try {
            BufferedReader(InputStreamReader(proc.errorStream, StandardCharsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val text = line!!
                    synchronized(stderrBuffer) {
                        if (stderrBuffer.length > 16_000) {
                            stderrBuffer.delete(0, stderrBuffer.length - 8_000)
                        }
                        stderrBuffer.append(text).append('\n')
                    }
                    onStderrLine(text)
                }
            }
        } catch (t: Throwable) {
            if (!closed.get()) logger.debug("读取 ACP stderr 异常", t)
        }
    }

    private fun handleIncomingLine(line: String) {
        if (!line.startsWith("{")) {
            onStderrLine("[stdout] $line")
            return
        }
        val element = runCatching { JsonParser.parseString(line) }.getOrNull()
        if (element == null || !element.isJsonObject) {
            onStderrLine("[stdout] $line")
            return
        }
        val obj = element.asJsonObject
        val idElem = obj.get("id")
        val method = obj.get("method")?.takeIf { it.isJsonPrimitive }?.asString

        when {
            idElem != null && !idElem.isJsonNull && method != null ->
                dispatchIncomingRequest(idElem, method, obj.getAsJsonObject("params") ?: JsonObject())
            idElem != null && !idElem.isJsonNull -> {
                val numericId = parseLongId(idElem)
                if (numericId != null) {
                    dispatchResponse(numericId, obj)
                }
            }
            method != null -> dispatchNotification(method, obj.getAsJsonObject("params") ?: JsonObject())
        }
    }

    private fun parseLongId(idElem: JsonElement): Long? {
        if (!idElem.isJsonPrimitive) return null
        val prim = idElem.asJsonPrimitive
        return when {
            prim.isNumber -> runCatching { prim.asLong }.getOrNull()
            prim.isString -> prim.asString.toLongOrNull()
            else -> null
        }
    }

    private fun dispatchResponse(id: Long, obj: JsonObject) {
        val future = pending.remove(id) ?: return
        val error = obj.get("error")?.takeIf { it.isJsonObject }?.asJsonObject
        if (error != null) {
            val code = error.get("code")?.takeIf { it.isJsonPrimitive }?.asInt ?: -32000
            val message = error.get("message")?.takeIf { it.isJsonPrimitive }?.asString ?: "未知 JSON-RPC 错误"
            future.completeExceptionally(JsonRpcRemoteException(code, message, error.get("data")))
        } else {
            future.complete(obj.get("result") ?: JsonNull.INSTANCE)
        }
    }

    private fun dispatchNotification(method: String, params: JsonObject) {
        runCatching { onNotification(method, params) }
            .onFailure { logger.warn("处理通知 $method 时异常", it) }
    }

    private fun dispatchIncomingRequest(idElem: JsonElement, method: String, params: JsonObject) {
        workerExecutor.submit {
            val response = JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                add("id", idElem)
            }
            try {
                val result = requestHandler.handleRequest(method, params)
                response.add("result", result)
            } catch (e: JsonRpcRemoteException) {
                response.add("error", JsonObject().apply {
                    addProperty("code", e.code)
                    addProperty("message", e.message)
                })
            } catch (t: Throwable) {
                logger.warn("处理 ACP 反向 RPC 方法 $method 失败", t)
                response.add("error", JsonObject().apply {
                    addProperty("code", -32603)
                    addProperty("message", t.message ?: "IDE 内部处理异常")
                })
            }
            runCatching { writeLine(gson.toJson(response)) }
                .onFailure { logger.warn("返回 RPC 响应失败 ($method)", it) }
        }
    }
}
