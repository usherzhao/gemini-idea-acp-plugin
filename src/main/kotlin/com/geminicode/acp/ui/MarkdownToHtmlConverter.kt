package com.geminicode.acp.ui

/**
 * Lightweight Markdown-to-HTML converter for Swing JEditorPane and fenced code block extractor.
 */
object MarkdownToHtmlConverter {

    data class CodeBlock(
        val language: String,
        val code: String
    )

    private val fenceStartRegex = Regex("""^```([A-Za-z0-9_+-]*)\s*$""")
    private val inlineCodeRegex = Regex("""`([^`]+)`""")
    private val boldRegex = Regex("""\*\*([^*]+)\*\*""")

    fun extractCodeBlocks(markdown: String): List<CodeBlock> {
        val blocks = mutableListOf<CodeBlock>()
        var inFence = false
        var currentLang = ""
        val buf = StringBuilder()

        for (rawLine in markdown.lines()) {
            val line = rawLine.trimEnd('\r')
            if (!inFence) {
                val match = fenceStartRegex.matchEntire(line.trim())
                if (match != null) {
                    inFence = true
                    currentLang = match.groupValues[1]
                    buf.clear()
                }
            } else {
                if (line.trim() == "```") {
                    blocks.add(CodeBlock(currentLang, buf.toString().trimEnd('\n')))
                    inFence = false
                    currentLang = ""
                } else {
                    buf.append(line).append('\n')
                }
            }
        }
        return blocks
    }

    fun toHtml(markdown: String, textHex: String, codeBgHex: String, borderHex: String): String {
        val sb = StringBuilder()
        sb.append("<html><body style='font-family:sans-serif;font-size:12px;color:$textHex;margin:0;padding:0;'>")

        var inFence = false
        var inList = false
        val codeBuf = StringBuilder()

        for (rawLine in markdown.lines()) {
            val line = rawLine.trimEnd('\r')
            if (!inFence) {
                val fenceMatch = fenceStartRegex.matchEntire(line.trim())
                if (fenceMatch != null) {
                    if (inList) {
                        sb.append("</ul>")
                        inList = false
                    }
                    inFence = true
                    codeBuf.clear()
                    continue
                }

                val trimmed = line.trim()
                when {
                    trimmed.startsWith("### ") -> {
                        if (inList) { sb.append("</ul>"); inList = false }
                        sb.append("<div style='font-weight:bold;font-size:13px;margin:6px 0 3px 0;'>")
                            .append(formatInline(trimmed.removePrefix("### "), codeBgHex))
                            .append("</div>")
                    }
                    trimmed.startsWith("## ") || trimmed.startsWith("# ") -> {
                        if (inList) { sb.append("</ul>"); inList = false }
                        val title = trimmed.replaceFirst(Regex("^#+\\s+"), "")
                        sb.append("<div style='font-weight:bold;font-size:14px;margin:8px 0 4px 0;'>")
                            .append(formatInline(title, codeBgHex))
                            .append("</div>")
                    }
                    trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                        if (!inList) {
                            sb.append("<ul style='margin:3px 0 3px 16px;padding:0;'>")
                            inList = true
                        }
                        sb.append("<li>")
                            .append(formatInline(trimmed.substring(2), codeBgHex))
                            .append("</li>")
                    }
                    trimmed.isEmpty() -> {
                        if (inList) { sb.append("</ul>"); inList = false }
                        sb.append("<div style='height:5px;'></div>")
                    }
                    else -> {
                        if (inList) { sb.append("</ul>"); inList = false }
                        sb.append("<div style='margin:2px 0;'>")
                            .append(formatInline(line, codeBgHex))
                            .append("</div>")
                    }
                }
            } else {
                if (line.trim() == "```") {
                    appendCodeBlockHtml(sb, codeBuf.toString().trimEnd('\n'), codeBgHex, borderHex)
                    inFence = false
                } else {
                    codeBuf.append(line).append('\n')
                }
            }
        }

        if (inFence) {
            appendCodeBlockHtml(sb, codeBuf.toString().trimEnd('\n'), codeBgHex, borderHex)
        }
        if (inList) {
            sb.append("</ul>")
        }

        sb.append("</body></html>")
        return sb.toString()
    }

    private fun appendCodeBlockHtml(sb: StringBuilder, code: String, codeBgHex: String, borderHex: String) {
        sb.append("<pre style='background-color:$codeBgHex;border:1px solid $borderHex;padding:6px;margin:6px 0;font-family:monospace;font-size:11px;'>")
        sb.append(escapeHtml(code))
        sb.append("</pre>")
    }

    private fun formatInline(text: String, codeBgHex: String): String {
        val escaped = escapeHtml(text)
        val withCode = inlineCodeRegex.replace(escaped) { m ->
            "<code style='background-color:$codeBgHex;font-family:monospace;padding:1px 3px;'>${m.groupValues[1]}</code>"
        }
        return boldRegex.replace(withCode) { m ->
            "<b>${m.groupValues[1]}</b>"
        }
    }

    private fun escapeHtml(text: String): String {
        return buildString(text.length) {
            for (ch in text) {
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    else -> append(ch)
                }
            }
        }
    }
}
