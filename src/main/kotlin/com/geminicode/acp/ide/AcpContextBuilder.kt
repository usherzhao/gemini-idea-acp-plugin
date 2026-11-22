package com.geminicode.acp.ide

import com.geminicode.acp.protocol.AcpModels
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 负责构建官方 ACP 高级上下文负载：
 * 1. `promptCapabilities.embeddedContext`：将用户通过 `@` 或右键引用的文件/行号范围按标准 ACP `type="resource"` 块结构化传递；
 * 2. `_meta.antigravity.systemPrompt.append`：合并全局系统提示词与项目级 `.gemini/rules.md`。
 */
object AcpContextBuilder {

    private const val MAX_RULES_FILE_CHARS = 32_000

    /**
     * 合并全局自定义系统提示词与项目根目录 `.gemini/rules.md`（若启用且存在）。
     */
    fun resolveSystemPromptAppend(
        projectBasePath: String?,
        globalSystemPrompt: String,
        loadProjectRulesFile: Boolean
    ): String? {
        val parts = mutableListOf<String>()
        val trimmedGlobal = globalSystemPrompt.trim()
        if (trimmedGlobal.isNotEmpty()) {
            parts.add(trimmedGlobal)
        }
        if (loadProjectRulesFile && !projectBasePath.isNullOrBlank()) {
            val rulesFile = runCatching {
                Paths.get(projectBasePath).resolve(".gemini").resolve("rules.md")
            }.getOrNull()
            if (rulesFile != null && Files.isRegularFile(rulesFile)) {
                val content = runCatching {
                    Files.readString(rulesFile, StandardCharsets.UTF_8).trim()
                }.getOrNull()
                if (!content.isNullOrEmpty()) {
                    val clipped = if (content.length > MAX_RULES_FILE_CHARS) {
                        content.take(MAX_RULES_FILE_CHARS) + "\n...[truncated]"
                    } else {
                        content
                    }
                    parts.add("[Project Rules (.gemini/rules.md)]\n$clipped")
                }
            }
        }
        return parts.joinToString("\n\n").takeIf { it.isNotBlank() }
    }

    /**
     * 将引用的代码文件/行号区间构造成标准 ACP `EmbeddedResource` 内容块。
     * 注意：仅作为底层协议块发送，绝不会把源码粘贴到用户对话框中；超大文件或二进制文件自动跳过（由 AI 按需调工具读取）。
     */
    fun buildEmbeddedResourceBlocks(
        references: List<CodeReference>,
        maxTotalChars: Int,
        contentReader: (Path) -> String? = { path ->
            runCatching {
                if (Files.isRegularFile(path) && Files.size(path) <= maxTotalChars.toLong() * 2L) {
                    Files.readString(path, StandardCharsets.UTF_8)
                } else {
                    null
                }
            }.getOrNull()
        }
    ): List<AcpModels.PromptBlock.EmbeddedResource> {
        if (references.isEmpty() || maxTotalChars <= 0) return emptyList()
        val result = mutableListOf<AcpModels.PromptBlock.EmbeddedResource>()
        var remaining = maxTotalChars

        val unique = references.distinctBy { it.dedupKey() }
        for (ref in unique) {
            if (ref.isDirectory || remaining <= 0) continue
            val path = runCatching { Paths.get(ref.absolutePath).toAbsolutePath().normalize() }.getOrNull()
                ?: continue
            val rawText = contentReader(path) ?: continue
            // 跳过明显包含 NUL 字节的二进制文件
            if (rawText.indexOf('\u0000') >= 0) continue

            val textToEmbed = if (ref.startLine != null) {
                val limit = if (ref.endLine != null && ref.endLine >= ref.startLine) {
                    ref.endLine - ref.startLine + 1
                } else {
                    1
                }
                IdeaPathSecurity.sliceLines(rawText, ref.startLine, limit)
            } else {
                rawText
            }

            if (textToEmbed.isEmpty() || textToEmbed.length > remaining) continue
            remaining -= textToEmbed.length

            val baseUri = path.toUri().toString()
            val uriWithFragment = when {
                ref.startLine != null && ref.endLine != null && ref.endLine != ref.startLine ->
                    "$baseUri#L${ref.startLine}-L${ref.endLine}"
                ref.startLine != null ->
                    "$baseUri#L${ref.startLine}"
                else -> baseUri
            }
            result.add(
                AcpModels.PromptBlock.EmbeddedResource(
                    uri = uriWithFragment,
                    mimeType = guessTextMimeType(path.fileName?.toString().orEmpty()),
                    text = textToEmbed
                )
            )
        }
        return result
    }

    internal fun guessTextMimeType(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "kt", "kts" -> "text/x-kotlin"
            "java" -> "text/x-java"
            "py" -> "text/x-python"
            "js", "mjs", "cjs" -> "text/javascript"
            "ts", "tsx" -> "text/typescript"
            "json" -> "application/json"
            "xml", "html", "htm" -> "text/xml"
            "md", "markdown" -> "text/markdown"
            "yaml", "yml" -> "text/yaml"
            "sql" -> "text/x-sql"
            "sh", "bash" -> "text/x-shellscript"
            "css", "scss" -> "text/css"
            else -> "text/plain"
        }
    }
}
