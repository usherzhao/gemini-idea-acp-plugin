package com.geminicode.acp.ui

import com.geminicode.acp.settings.GeminiAcpSettingsState
import com.geminicode.acp.settings.GeminiExecutableResolver
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 启动外部交互式终端完成 Antigravity CLI (agy acp --login) 或兼容 CLI 的 Google OAuth 登录，
 * 并在登录命令退出后回调通知主面板自动重连刷新状态。
 */
object TerminalLoginLauncher {

    fun launchInteractiveGeminiLogin(
        project: Project,
        onCompleted: ((exitCode: Int) -> Unit)? = null
    ) {
        val settings = GeminiAcpSettingsState.getInstance().state
        val resolved = GeminiExecutableResolver.resolveExecutable(
            settings.geminiExecutablePath,
            allowNpxFallback = false
        )
        if (!resolved.found || resolved.executablePath == null) {
            val choice = Messages.showYesNoDialog(
                project,
                "未检测到 Antigravity CLI (agy)。\nGoogle 已停止旧版 gemini CLI 对个人账号的支持，个人账号 (oauth-personal) 需要使用 Antigravity CLI。\n\n是否打开官网下载页面 https://antigravity.google ？",
                "需要安装 Antigravity CLI",
                "打开下载页",
                "取消",
                Messages.getInformationIcon()
            )
            if (choice == Messages.YES) {
                BrowserUtil.browse("https://antigravity.google")
            }
            return
        }

        val workDir = project.basePath?.let(::File)?.takeIf { it.isDirectory }
            ?: File(System.getProperty("user.home"))

        val commandLine = if (resolved.backendKind == GeminiExecutableResolver.BackendKind.ANTIGRAVITY_AGY) {
            "\"${resolved.executablePath}\" acp --login"
        } else {
            "\"${resolved.executablePath}\""
        }

        var tempScript: File? = null
        runCatching {
            val pb = when {
                SystemInfo.isWindows -> {
                    val scriptFile = File.createTempFile("agy-acp-login-", ".cmd")
                    scriptFile.writeText(
                        buildWindowsWrapperScript(
                            commandLine = commandLine,
                            proxyUrl = settings.proxyUrl,
                            stripGeminiApiKeyEnv = settings.stripGeminiApiKeyEnv
                        ),
                        StandardCharsets.UTF_8
                    )
                    tempScript = scriptFile
                    ProcessBuilder(
                        "cmd.exe",
                        "/c",
                        "start",
                        "/WAIT",
                        "Antigravity ACP 登录",
                        "cmd.exe",
                        "/c",
                        scriptFile.absolutePath
                    )
                }
                SystemInfo.isMac -> {
                    val escapedDir = workDir.absolutePath.replace("\"", "\\\"")
                    val envPrefix = buildString {
                        if (settings.proxyUrl.isNotBlank()) {
                            val proxy = settings.proxyUrl.trim()
                            append("export HTTP_PROXY='$proxy' HTTPS_PROXY='$proxy'; ")
                        }
                        if (settings.stripGeminiApiKeyEnv) {
                            append("unset GEMINI_API_KEY; ")
                        }
                    }
                    val escapedCmd = (envPrefix + commandLine).replace("\"", "\\\"")
                    val script = "tell application \"Terminal\" to do script \"cd '$escapedDir' && $escapedCmd\""
                    ProcessBuilder("osascript", "-e", script)
                }
                else -> {
                    val shellCmd = buildString {
                        if (settings.proxyUrl.isNotBlank()) {
                            val proxy = settings.proxyUrl.trim()
                            append("export HTTP_PROXY='$proxy' HTTPS_PROXY='$proxy'; ")
                        }
                        if (settings.stripGeminiApiKeyEnv) {
                            append("unset GEMINI_API_KEY; ")
                        }
                        append(commandLine)
                        append("; rc=\$?; if [ \$rc -eq 0 ]; then echo ''; echo '登录完成，正在返回 IDEA 自动连接...'; sleep 1; exit 0; else echo ''; echo '登录未完成，按回车关闭窗口...'; read _; exit \$rc; fi")
                    }
                    ProcessBuilder("x-terminal-emulator", "-e", "bash", "-lc", shellCmd)
                }
            }
            pb.directory(workDir)
            val process = pb.start()

            // Windows `start /WAIT` 与 Linux 终端会阻塞直至登录流程结束；macOS osascript 为异步触发。
            if (onCompleted != null && !SystemInfo.isMac) {
                val watcher = Thread({
                    try {
                        val exitCode = process.waitFor()
                        onCompleted(exitCode)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } finally {
                        runCatching { tempScript?.delete() }
                    }
                }, "GeminiAcp-TerminalLoginWatcher")
                watcher.isDaemon = true
                watcher.start()
            } else if (tempScript != null) {
                val cleaner = Thread({
                    try {
                        process.waitFor()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } finally {
                        runCatching { tempScript?.delete() }
                    }
                }, "GeminiAcp-TerminalScriptCleaner")
                cleaner.isDaemon = true
                cleaner.start()
            }
        }.onFailure { err ->
            runCatching { tempScript?.delete() }
            Messages.showErrorDialog(
                project,
                "无法自动打开外部终端：${err.message}\n\n请手动在终端执行：\n$commandLine\n完成授权后，回到插件点击「登录/连接」。",
                "打开终端失败"
            )
        }
    }

    /**
     * 生成 Windows 登录包装脚本：
     * - 切换 UTF-8 代码页避免中文乱码；
     * - 登录成功（exit 0）后自动等待 2 秒关闭窗口，触发 `start /WAIT` 回调插件自动重连；
     * - 登录失败或取消时暂停窗口便于排查错误。
     */
    internal fun buildWindowsWrapperScript(
        commandLine: String,
        proxyUrl: String,
        stripGeminiApiKeyEnv: Boolean
    ): String {
        val sb = StringBuilder()
        sb.appendLine("@echo off")
        sb.appendLine("chcp 65001 >nul")
        val trimmedProxy = proxyUrl.trim()
        if (trimmedProxy.isNotEmpty()) {
            sb.appendLine("set \"HTTP_PROXY=$trimmedProxy\"")
            sb.appendLine("set \"HTTPS_PROXY=$trimmedProxy\"")
        }
        if (stripGeminiApiKeyEnv) {
            sb.appendLine("set \"GEMINI_API_KEY=\"")
        }
        sb.appendLine(commandLine)
        sb.appendLine("set EXITCODE=%ERRORLEVEL%")
        sb.appendLine("if %EXITCODE% EQU 0 (")
        sb.appendLine("    echo.")
        sb.appendLine("    echo [OK] 登录成功！窗口将自动关闭并返回 IntelliJ IDEA 自动连接...")
        sb.appendLine("    timeout /t 2 >nul")
        sb.appendLine("    exit /b 0")
        sb.appendLine(") else (")
        sb.appendLine("    echo.")
        sb.appendLine("    echo [ERROR] 登录未完成（退出码 %EXITCODE%），请查看上方输出后按任意键关闭。")
        sb.appendLine("    pause")
        sb.appendLine("    exit /b %EXITCODE%")
        sb.appendLine(")")
        return sb.toString()
    }
}
