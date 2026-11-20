package com.geminicode.acp.ide

import java.nio.file.Files
import java.nio.file.Paths

/**
 * 轻量级代码位置引用模型：
 * - 对话框内仅展示精简文件名（或文件名+行号），避免冗长全路径占满输入区；
 * - 实际发送给 AI 时仍携带完整绝对路径与相对路径及 1-based 行号范围，不复制粘贴源码。
 */
data class CodeReference(
    val absolutePath: String,
    val relativePath: String,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val isDirectory: Boolean = false,
    val displayPath: String? = null
) {
    /**
     * 用于对话框 UI 展示的精简路径：默认仅显示文件名/目录名；仅在同一次输入出现同名文件冲突时，
     * 通过 [displayPath] 补充最少父级目录进行区分。
     */
    fun shortName(): String {
        val chosen = displayPath?.takeIf { it.isNotBlank() } ?: extractFileName(relativePath)
        return chosen.ifBlank { relativePath }
    }

    fun chipLabel(): String {
        val name = shortName()
        return when {
            startLine != null && endLine != null && startLine != endLine -> "$name:L$startLine-L$endLine"
            startLine != null -> "$name:L$startLine"
            else -> name
        }
    }

    fun inlineToken(): String {
        val name = shortName()
        val safeName = if (name.any { it.isWhitespace() || it == ':' }) "\"$name\"" else name
        return when {
            startLine != null && endLine != null && startLine != endLine -> "@$safeName#L$startLine-L$endLine"
            startLine != null -> "@$safeName#L$startLine"
            else -> "@$safeName"
        }
    }

    fun dedupKey(): Triple<String, Int?, Int?> =
        Triple(absolutePath.replace('\\', '/').lowercase(), startLine, endLine)

    fun toPromptLine(): String = when {
        isDirectory ->
            "- 目录 `$absolutePath`（相对路径 `$relativePath`）"
        startLine != null && endLine != null && startLine != endLine ->
            "- 文件 `$absolutePath`（相对路径 `$relativePath`），第 $startLine 行至第 $endLine 行"
        startLine != null ->
            "- 文件 `$absolutePath`（相对路径 `$relativePath`），第 $startLine 行"
        else ->
            "- 文件 `$absolutePath`（相对路径 `$relativePath`）"
    }

    data class ResolvedFileBinding(
        val absolutePath: String,
        val relativePath: String,
        val displayPath: String,
        val isDirectory: Boolean = false
    )

    companion object {
        // 同时支持 @Foo.kt#L10-L25、@src/Foo.kt:10-25、中文紧邻（如 请看@Foo.kt，分析）以及带空格路径 @"my file.kt"#L10-L25；自动排除邮箱 user@example.com
        private val inlineMentionRegex = Regex(
            """(?<=^|[^A-Za-z0-9._+-])@(?:"([^"]+)"|([^\s@#:,;()\[\]{}"，。、；：！？（）【】]+))(?:(?:#L|:)(\d+)(?:(?:-L?|-)(\d+))?)?"""
        )

        private val locationLineRegex = Regex("""^(.+?):(\d+)$""")

        fun extractFileName(path: String): String {
            val trimmed = path.replace('\\', '/').trimEnd('/')
            if (trimmed.isEmpty()) return path
            val idx = trimmed.lastIndexOf('/')
            return if (idx >= 0) trimmed.substring(idx + 1) else trimmed
        }

        /**
         * 将工具调用日志里的 `D:/a/b/OrderService.java:42` 精简为 `OrderService.java:42` 供对话框展示。
         */
        fun shortenLocationDisplay(location: String): String {
            val trimmed = location.trim()
            val match = locationLineRegex.matchEntire(trimmed)
            if (match != null && match.groupValues[1].length > 1) {
                val filePart = extractFileName(match.groupValues[1])
                val linePart = match.groupValues[2]
                return "$filePart:$linePart"
            }
            return extractFileName(trimmed)
        }

        /**
         * 为引用的文件分配在当前对话框内唯一的精简展示名：
         * - 无同名冲突时，直接返回纯文件名（如 `OrderService.java`）；
         * - 若该文件已存在绑定，复用原展示名；
         * - 若与其他不同路径的文件重名，逐级补充最少父级目录（如 `b/SKILL.md`）保证无歧义。
         */
        fun resolveUniqueDisplayPath(
            target: CodeReference,
            existingBindings: Map<String, ResolvedFileBinding>
        ): String {
            val targetAbsLower = target.absolutePath.replace('\\', '/').lowercase()
            existingBindings.values.firstOrNull {
                it.absolutePath.replace('\\', '/').lowercase() == targetAbsLower
            }?.let { return it.displayPath }

            val normalizedRel = target.relativePath.replace('\\', '/').trimEnd('/')
            val segments = normalizedRel.split('/').filter { it.isNotEmpty() }
            if (segments.isEmpty()) return extractFileName(target.absolutePath)

            for (takeCount in 1..segments.size) {
                val candidate = segments.takeLast(takeCount).joinToString("/")
                val existing = existingBindings[candidate.lowercase()]
                if (existing == null || existing.absolutePath.replace('\\', '/').lowercase() == targetAbsLower) {
                    return candidate
                }
            }
            return normalizedRel
        }

        fun extractInlineReferences(
            text: String,
            projectBasePath: String?,
            excludeRange: IntRange? = null,
            bindings: Map<String, ResolvedFileBinding> = emptyMap()
        ): List<CodeReference> {
            val found = mutableListOf<CodeReference>()
            for (match in inlineMentionRegex.findAll(text)) {
                if (excludeRange != null && rangesOverlap(match.range, excludeRange)) continue
                val rawPath = (match.groupValues[1].ifEmpty { match.groupValues[2] }).trim()
                if (rawPath.isEmpty()) continue
                val normalizedRaw = rawPath.replace('\\', '/')
                val startLine = match.groupValues[3].toIntOrNull()
                val endLine = match.groupValues[4].toIntOrNull() ?: startLine
                val bound = bindings[normalizedRaw.lowercase()]
                if (bound != null) {
                    found.add(
                        CodeReference(
                            absolutePath = bound.absolutePath,
                            relativePath = bound.relativePath,
                            startLine = startLine,
                            endLine = endLine,
                            isDirectory = bound.isDirectory,
                            displayPath = normalizedRaw
                        )
                    )
                } else {
                    val abs = resolveAbsolute(projectBasePath, rawPath)
                    val isDir = startLine == null && runCatching { Files.isDirectory(Paths.get(abs)) }.getOrDefault(false)
                    found.add(
                        CodeReference(
                            absolutePath = abs,
                            relativePath = normalizedRaw,
                            startLine = startLine,
                            endLine = endLine,
                            isDirectory = isDir
                        )
                    )
                }
            }
            return found.distinctBy { it.dedupKey() }
        }

        fun removeReferenceToken(
            text: String,
            target: CodeReference,
            projectBasePath: String? = null,
            bindings: Map<String, ResolvedFileBinding> = emptyMap()
        ): String {
            val token = target.inlineToken()
            val idx = text.indexOf(token)
            if (idx >= 0) {
                return removeRangeCleanly(text, idx, idx + token.length)
            }
            val targetKey = target.dedupKey()
            for (match in inlineMentionRegex.findAll(text)) {
                val rawPath = (match.groupValues[1].ifEmpty { match.groupValues[2] }).trim()
                if (rawPath.isEmpty()) continue
                val startLine = match.groupValues[3].toIntOrNull()
                val endLine = match.groupValues[4].toIntOrNull() ?: startLine
                val bound = bindings[rawPath.replace('\\', '/').lowercase()]
                val abs = bound?.absolutePath ?: resolveAbsolute(projectBasePath, rawPath)
                val key = Triple(abs.replace('\\', '/').lowercase(), startLine, endLine)
                if (key == targetKey) {
                    return removeRangeCleanly(text, match.range.first, match.range.last + 1)
                }
            }
            return text
        }

        fun removeAllReferenceTokens(text: String): String {
            val ranges = inlineMentionRegex.findAll(text).map { it.range }.toList()
            if (ranges.isEmpty()) return text
            var result = text
            for (range in ranges.asReversed()) {
                result = removeRangeCleanly(result, range.first, range.last + 1)
            }
            return result
        }

        fun formatPromptWithReferences(userPrompt: String, references: List<CodeReference>): String {
            val unique = references.distinctBy { it.dedupKey() }
            if (unique.isEmpty()) return userPrompt
            return buildString {
                appendLine("[代码位置引用]")
                appendLine("以下为本次对话引用的文件与行号位置（仅传递路径与行号，未复制粘贴源码，请按需调用文件读取工具查看对应内容）：")
                unique.forEach { appendLine(it.toPromptLine()) }
                appendLine()
                append(userPrompt)
            }
        }

        private fun rangesOverlap(a: IntRange, b: IntRange): Boolean =
            a.first <= b.last && b.first <= a.last

        private fun removeRangeCleanly(text: String, start: Int, end: Int): String {
            var removeStart = start
            var removeEnd = end
            if (removeEnd < text.length && text[removeEnd] == ' ') {
                removeEnd++
            } else if (removeStart > 0 && text[removeStart - 1] == ' ') {
                removeStart--
            }
            return text.substring(0, removeStart) + text.substring(removeEnd)
        }

        private fun resolveAbsolute(projectBasePath: String?, rawPath: String): String {
            return runCatching {
                val p = Paths.get(rawPath)
                if (p.isAbsolute || projectBasePath.isNullOrBlank()) {
                    p.toAbsolutePath().normalize().toString().replace('\\', '/')
                } else {
                    Paths.get(projectBasePath).resolve(p).toAbsolutePath().normalize().toString().replace('\\', '/')
                }
            }.getOrDefault(rawPath.replace('\\', '/'))
        }
    }
}
