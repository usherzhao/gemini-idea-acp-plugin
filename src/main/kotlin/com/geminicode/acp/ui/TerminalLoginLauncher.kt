package com.geminicode.acp.ui

import com.geminicode.acp.settings.GeminiAcpSettingsState
import com.geminicode.acp.settings.GeminiExecutableResolver
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import java.io.File

/**
 * 启动外部交互式终端完成 Antigravity CLI (agy acp --login) 或兼容 CLI 的 Google OAuth 登录。
 */
object TerminalLoginLauncher {

    fun launchInteractiveGeminiLogin(project: Project) {
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

        runCatching {
            val pb = when {
                SystemInfo.isWindows -> {
                    val envPrefix = buildString {
                        if (settings.proxyUrl.isNotBlank()) {
                            val proxy = settings.proxyUrl.trim()
                            append("set HTTP_PROXY=$proxy && set HTTPS_PROXY=$proxy && ")
                        }
                        if (settings.stripGeminiApiKeyEnv) {
                            append("set GEMINI_API_KEY= && ")
                        }
                    }
                    ProcessBuilder("cmd.exe", "/c", "start", "Antigravity ACP 登录", "cmd.exe", "/k", envPrefix + commandLine)
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
                        append("; exec bash")
                    }
                    ProcessBuilder("x-terminal-emulator", "-e", "bash", "-lc", shellCmd)
                }
            }
            pb.directory(workDir)
            pb.start()
        }.onFailure { err ->
            Messages.showErrorDialog(
                project,
                "无法自动打开外部终端：${err.message}\n\n请手动在终端执行：\n$commandLine\n完成授权后，回到插件点击「oauth-personal 登录/连接」。",
                "打开终端失败"
            )
        }
    }
}
