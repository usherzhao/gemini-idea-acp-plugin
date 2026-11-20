package com.geminicode.acp.protocol

import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 历史会话路径归一化（处理 ACP 返回的 /d%3A/... 或 /D:/... 路径格式）与时间格式化工具。
 */
object SessionPathUtil {

    private val sameDayFmt = DateTimeFormatter.ofPattern("今天 HH:mm")
    private val sameYearFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    private val fullDateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun normalizeSessionCwd(rawCwd: String?): String {
        if (rawCwd.isNullOrBlank()) return ""
        val decoded = runCatching {
            URLDecoder.decode(rawCwd.trim(), StandardCharsets.UTF_8)
        }.getOrDefault(rawCwd.trim())

        // 兼容 ACP 返回的 Windows 路径前缀，例如 "/D:/ideaProject/haidong" 或 "/d:/antigravityProject"
        val stripped = if (decoded.length >= 3 && decoded[0] == '/' && decoded[2] == ':' && decoded[1].isLetter()) {
            decoded.substring(1)
        } else {
            decoded
        }
        return runCatching {
            Paths.get(stripped).normalize().toString()
        }.getOrDefault(stripped)
    }

    fun isSameProject(sessionCwd: String?, projectBasePath: String?): Boolean {
        if (sessionCwd.isNullOrBlank() || projectBasePath.isNullOrBlank()) return false
        val a = normalizeComparable(normalizeSessionCwd(sessionCwd))
        val b = normalizeComparable(normalizeSessionCwd(projectBasePath))
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || a.startsWith("$b/") || b.startsWith("$a/")
    }

    fun resolveLoadCwd(sessionCwd: String?, projectBasePath: String?): String {
        val normalizedSession = normalizeSessionCwd(sessionCwd)
        if (normalizedSession.isNotBlank() && File(normalizedSession).isDirectory) {
            return normalizedSession
        }
        if (!projectBasePath.isNullOrBlank() && File(projectBasePath).isDirectory) {
            return projectBasePath
        }
        return System.getProperty("user.home")
    }

    fun shortFolderName(rawCwd: String?): String {
        val norm = normalizeSessionCwd(rawCwd).replace('\\', '/').trimEnd('/')
        if (norm.isEmpty()) return "未知目录"
        val idx = norm.lastIndexOf('/')
        return if (idx >= 0 && idx < norm.length - 1) norm.substring(idx + 1) else norm
    }

    fun formatUpdatedAt(isoTimestamp: String?, zone: ZoneId = ZoneId.systemDefault()): String {
        if (isoTimestamp.isNullOrBlank()) return ""
        return runCatching {
            val instant = Instant.parse(isoTimestamp.trim())
            val zoned = instant.atZone(zone)
            val today = LocalDate.now(zone)
            when {
                zoned.toLocalDate() == today -> sameDayFmt.format(zoned)
                zoned.year == today.year -> sameYearFmt.format(zoned)
                else -> fullDateFmt.format(zoned)
            }
        }.getOrDefault(isoTimestamp)
    }

    private fun normalizeComparable(path: String): String {
        val unified = path.replace('\\', '/').trimEnd('/')
        val isWindowsLike = unified.length >= 2 && unified[1] == ':'
        return if (isWindowsLike) unified.lowercase() else unified
    }
}
