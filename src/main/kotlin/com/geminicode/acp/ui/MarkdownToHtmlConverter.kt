package com.geminicode.acp.ui

/**
 * 面向 Swing JEditorPane (HTMLEditorKit) 深度优化的 GFM Markdown 转 HTML 渲染器与代码块提取器。
 * 支持：1~6 级标题、Setext 标题、多反引号/波浪线围栏代码块（带语言标签）、GFM 表格与列对齐、
 * 嵌套有序/无序列表与任务勾选框、引用块与 GitHub Alert、分隔线、链接（兼容含括号路径）、
 * 粗体/斜体/删除线/行内代码/转义字符，且不会误伤 Java 泛型与下划线标识符。
 */
object MarkdownToHtmlConverter {

    data class CodeBlock(
        val language: String,
        val code: String
    )

    data class RenderTheme(
        val textHex: String,
        val mutedTextHex: String,
        val codeBgHex: String,
        val codeHeaderBgHex: String,
        val borderHex: String,
        val linkHex: String,
        val tableHeaderBgHex: String,
        val tableStripeBgHex: String,
        val quoteBgHex: String,
        val quoteBarHex: String
    ) {
        companion object {
            fun fromBasicColors(textHex: String, codeBgHex: String, borderHex: String): RenderTheme {
                val dark = isDarkHex(codeBgHex)
                return RenderTheme(
                    textHex = textHex,
                    mutedTextHex = if (dark) "#9CA3AF" else "#64748B",
                    codeBgHex = codeBgHex,
                    codeHeaderBgHex = if (dark) "#1E293B" else "#E2E8F0",
                    borderHex = borderHex,
                    linkHex = if (dark) "#60A5FA" else "#2563EB",
                    tableHeaderBgHex = if (dark) "#1E293B" else "#E2E8F0",
                    tableStripeBgHex = if (dark) "#172030" else "#F8FAFC",
                    quoteBgHex = if (dark) "#172030" else "#F8FAFC",
                    quoteBarHex = if (dark) "#60A5FA" else "#3B82F6"
                )
            }

            private fun isDarkHex(hex: String): Boolean {
                val clean = hex.removePrefix("#").trim()
                if (clean.length != 6) return false
                val r = clean.substring(0, 2).toIntOrNull(16) ?: return false
                val g = clean.substring(2, 4).toIntOrNull(16) ?: return false
                val b = clean.substring(4, 6).toIntOrNull(16) ?: return false
                val luma = 0.299 * r + 0.587 * g + 0.114 * b
                return luma < 128.0
            }
        }
    }

    private data class FenceSpec(
        val char: Char,
        val length: Int,
        val language: String
    )

    private data class ListItemMatch(
        val indent: Int,
        val ordered: Boolean,
        val startNumber: Int,
        val content: String
    )

    private val atxHeadingRegex = Regex("""^\s{0,3}(#{1,6})(?:\s+(.*?))?\s*#*\s*$""")
    private val setextH1Regex = Regex("""^\s{0,3}=+\s*$""")
    private val setextH2Regex = Regex("""^\s{0,3}-+\s*$""")
    private val hrRegex = Regex("""^\s{0,3}(?:(?:\*\s*){3,}|(?:-\s*){3,}|(?:_\s*){3,})$""")
    private val unorderedListRegex = Regex("""^(\s*)[-*+]\s+(.*)$""")
    private val orderedListRegex = Regex("""^(\s*)(\d{1,9})[.)]\s+(.*)$""")
    private val blockquoteLineRegex = Regex("""^\s{0,3}>\s?(.*)$""")
    private val alertHeaderRegex = Regex("""^\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\]\s*$""", RegexOption.IGNORE_CASE)
    private val taskCheckedRegex = Regex("""^\[[xX]\]\s+(.*)$""")
    private val taskUncheckedRegex = Regex("""^\[\s\]\s+(.*)$""")
    private val brTagRegex = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val angleAutolinkRegex = Regex("""<(https?://[^\s<>]+|file://[^\s<>]+)>""")
    private val bareUrlRegex = Regex("""(?<![A-Za-z0-9_])https?://[^\s<>"'\uE000\uE001]+""")
    private val strikeRegex = Regex("""~~(?=\S)(.+?)(?<=\S)~~""")
    private val boldItalicStarRegex = Regex("""\*\*\*(?=\S)(.+?)(?<=\S)\*\*\*""")
    private val boldItalicUnderRegex = Regex("""(?<![\p{L}\p{N}_])___(?=\S)(.+?)(?<=\S)___(?![\p{L}\p{N}_])""")
    private val boldStarRegex = Regex("""\*\*(?=\S)(.+?)(?<=\S)\*\*""")
    private val boldUnderRegex = Regex("""(?<![\p{L}\p{N}_])__(?=\S)(.+?)(?<=\S)__(?![\p{L}\p{N}_])""")
    private val italicStarRegex = Regex("""(?<!\*)\*(?!\*|\s)(.+?)(?<!\s|\*)\*(?!\*)""")
    private val italicUnderRegex = Regex("""(?<![\p{L}\p{N}_])_(?!_|\s)(.+?)(?<!\s|_)_(?![\p{L}\p{N}_])""")

    private const val PH_OPEN = '\uE000'
    private const val PH_CLOSE = '\uE001'
    private val placeholderRegex = Regex("""\uE000(\d+)\uE001""")

    fun extractCodeBlocks(markdown: String): List<CodeBlock> {
        val blocks = mutableListOf<CodeBlock>()
        var openFence: FenceSpec? = null
        val buf = StringBuilder()

        for (rawLine in markdown.lines()) {
            val line = rawLine.trimEnd('\r')
            val current = openFence
            if (current == null) {
                val parsed = parseFenceOpen(line)
                if (parsed != null) {
                    openFence = parsed
                    buf.clear()
                }
            } else {
                if (isFenceClose(line, current)) {
                    blocks.add(CodeBlock(current.language, buf.toString().trimEnd('\n')))
                    openFence = null
                } else {
                    buf.append(line).append('\n')
                }
            }
        }
        return blocks
    }

    fun toHtml(markdown: String, textHex: String, codeBgHex: String, borderHex: String): String {
        return toHtml(markdown, RenderTheme.fromBasicColors(textHex, codeBgHex, borderHex))
    }

    fun toHtml(markdown: String, theme: RenderTheme): String {
        val sb = StringBuilder(markdown.length * 2 + 256)
        sb.append("<html><head><style>")
        sb.append("body { font-family: sans-serif; font-size: 12px; color: ${theme.textHex}; margin: 0; padding: 0; }")
        sb.append("p { margin: 4px 0; }")
        sb.append("a { color: ${theme.linkHex}; text-decoration: underline; }")
        sb.append("ul, ol { margin: 4px 0 4px 18px; padding: 0; }")
        sb.append("li { margin: 2px 0; }")
        sb.append("pre { margin: 0; padding: 0; font-family: monospace; font-size: 11px; }")
        sb.append("code { font-family: monospace; font-size: 11px; }")
        sb.append("</style></head><body>")
        renderBlocks(markdown.replace("\r\n", "\n").replace("\r", "\n").split("\n"), theme, sb)
        sb.append("</body></html>")
        return sb.toString()
    }

    /**
     * 将含 Markdown 标记的单行摘要（如思考过程预览）清理为易读纯文本，去除裸露的 `**`、`##`、反引号等符号。
     */
    fun stripToPlainPreview(markdown: String): String {
        return markdown
            .replace("\r\n", " ")
            .replace("\n", " ")
            .replace(Regex("""\[([^\]]+)\]\([^)]+\)"""), "$1")
            .replace(Regex("""`([^`]+)`"""), "$1")
            .replace(Regex("""[*_~#]+"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun renderBlocks(lines: List<String>, theme: RenderTheme, sb: StringBuilder) {
        var i = 0
        val n = lines.size

        while (i < n) {
            val line = lines[i]
            val trimmed = line.trim()

            // 1. 空行
            if (trimmed.isEmpty()) {
                i++
                continue
            }

            // 2. 围栏代码块（支持 ``` / ~~~ 及 4+ 反引号嵌套）
            val fence = parseFenceOpen(line)
            if (fence != null) {
                val codeBuf = StringBuilder()
                i++
                while (i < n && !isFenceClose(lines[i], fence)) {
                    codeBuf.append(lines[i]).append('\n')
                    i++
                }
                if (i < n) i++ // 跳过结束围栏
                appendCodeBlockHtml(sb, fence.language, codeBuf.toString().trimEnd('\n'), theme)
                continue
            }

            // 3. ATX 标题 (# ~ ######)
            val headingMatch = atxHeadingRegex.matchEntire(line)
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].length
                val title = headingMatch.groupValues[2].trim()
                appendHeadingHtml(sb, level, title, theme)
                i++
                continue
            }

            // 4. 分隔线 (--- / *** / ___)
            if (hrRegex.matches(line)) {
                sb.append("<hr style='border:none;border-top:1px solid ${theme.borderHex};margin:8px 0;' color='${theme.borderHex}' size='1'/>")
                i++
                continue
            }

            // 5. 引用块 (> ...) 与 GitHub Alert ([!NOTE]/[TIP]/[WARNING]...)
            if (blockquoteLineRegex.matches(line)) {
                val quoteLines = mutableListOf<String>()
                while (i < n) {
                    val m = blockquoteLineRegex.matchEntire(lines[i])
                    if (m != null) {
                        quoteLines.add(m.groupValues[1])
                        i++
                    } else {
                        break
                    }
                }
                appendBlockquoteHtml(sb, quoteLines, theme)
                continue
            }

            // 6. GFM 表格（表头行 + 分隔对齐行 + 数据行）
            if (i + 1 < n && isTableHeaderAndSeparator(line, lines[i + 1])) {
                val headerCells = splitTableCells(line)
                val alignments = parseTableAlignments(lines[i + 1])
                i += 2
                val bodyRows = mutableListOf<List<String>>()
                while (i < n) {
                    val rowLine = lines[i]
                    val rowTrimmed = rowLine.trim()
                    if (rowTrimmed.isEmpty() || !rowLine.contains('|')) break
                    if (parseFenceOpen(rowLine) != null || atxHeadingRegex.matches(rowLine) ||
                        blockquoteLineRegex.matches(rowLine) || hrRegex.matches(rowLine)
                    ) {
                        break
                    }
                    bodyRows.add(splitTableCells(rowLine))
                    i++
                }
                appendTableHtml(sb, headerCells, alignments, bodyRows, theme)
                continue
            }

            // 7. 有序 / 无序 / 嵌套 / 任务列表
            if (matchListItem(line) != null) {
                i = renderListBlock(lines, i, theme, sb)
                continue
            }

            // 8. Setext 标题 (标题文本 + 下一行 === 或 ---)
            if (i + 1 < n) {
                val nextLine = lines[i + 1]
                when {
                    setextH1Regex.matches(nextLine) -> {
                        appendHeadingHtml(sb, 1, trimmed, theme)
                        i += 2
                        continue
                    }
                    setextH2Regex.matches(nextLine) && !trimmed.contains('|') -> {
                        appendHeadingHtml(sb, 2, trimmed, theme)
                        i += 2
                        continue
                    }
                }
            }

            // 9. 普通段落（合并连续普通行，保留行内换行）
            val paraLines = mutableListOf<String>()
            while (i < n) {
                val cur = lines[i]
                val curTrimmed = cur.trim()
                if (curTrimmed.isEmpty()) break
                if (paraLines.isNotEmpty() && (
                        parseFenceOpen(cur) != null ||
                            atxHeadingRegex.matches(cur) ||
                            hrRegex.matches(cur) ||
                            blockquoteLineRegex.matches(cur) ||
                            matchListItem(cur) != null ||
                            (i + 1 < n && isTableHeaderAndSeparator(cur, lines[i + 1]))
                        )
                ) {
                    break
                }
                // 检测段落最后一行变成 Setext 标题
                if (paraLines.size == 1 && (setextH1Regex.matches(cur) || setextH2Regex.matches(cur))) {
                    val level = if (setextH1Regex.matches(cur)) 1 else 2
                    appendHeadingHtml(sb, level, paraLines[0].trim(), theme)
                    paraLines.clear()
                    i++
                    break
                }
                paraLines.add(cur)
                i++
            }
            if (paraLines.isNotEmpty()) {
                sb.append("<p style='margin:4px 0;'>")
                paraLines.forEachIndexed { idx, pLine ->
                    if (idx > 0) sb.append("<br/>")
                    sb.append(formatInline(pLine.trimEnd(), theme))
                }
                sb.append("</p>")
            }
        }
    }

    private fun renderListBlock(
        lines: List<String>,
        startIndex: Int,
        theme: RenderTheme,
        sb: StringBuilder
    ): Int {
        data class OpenList(val indent: Int, val ordered: Boolean)

        val stack = ArrayDeque<OpenList>()
        var openLi = false
        var i = startIndex
        val n = lines.size

        fun closeLiIfOpen() {
            if (openLi) {
                sb.append("</li>")
                openLi = false
            }
        }

        fun closeTopList() {
            closeLiIfOpen()
            val popped = stack.removeLast()
            sb.append(if (popped.ordered) "</ol>" else "</ul>")
        }

        while (i < n) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                // 允许列表项之间有一个空行（松散列表），只要下一非空行仍是列表项
                var look = i + 1
                while (look < n && lines[look].trim().isEmpty()) look++
                if (look < n && matchListItem(lines[look]) != null) {
                    i = look
                    continue
                }
                break
            }

            val item = matchListItem(line)
            if (item != null) {
                while (stack.isNotEmpty() && item.indent < stack.last().indent) {
                    closeTopList()
                }
                if (stack.isNotEmpty() && item.indent == stack.last().indent && item.ordered != stack.last().ordered) {
                    closeTopList()
                }
                if (stack.isEmpty() || item.indent > stack.last().indent) {
                    val tag = if (item.ordered) {
                        if (item.startNumber != 1) {
                            "<ol start='${item.startNumber}' style='margin:3px 0 3px 20px;padding:0;'>"
                        } else {
                            "<ol style='margin:3px 0 3px 20px;padding:0;'>"
                        }
                    } else {
                        "<ul style='margin:3px 0 3px 18px;padding:0;'>"
                    }
                    sb.append(tag)
                    stack.addLast(OpenList(item.indent, item.ordered))
                    openLi = false
                } else {
                    closeLiIfOpen()
                }

                sb.append("<li style='margin:2px 0;'>")
                openLi = true
                sb.append(formatListItemContent(item.content, theme))
                i++
                continue
            }

            // 列表项内的缩进续行或围栏代码块
            val currentIndent = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
            if (stack.isNotEmpty() && currentIndent > stack.last().indent) {
                val fence = parseFenceOpen(line)
                if (fence != null) {
                    val codeBuf = StringBuilder()
                    i++
                    while (i < n && !isFenceClose(lines[i], fence)) {
                        codeBuf.append(lines[i]).append('\n')
                        i++
                    }
                    if (i < n) i++
                    appendCodeBlockHtml(sb, fence.language, codeBuf.toString().trimEnd('\n'), theme)
                    continue
                }
                if (blockquoteLineRegex.matches(line) || hrRegex.matches(line) || atxHeadingRegex.matches(line)) {
                    break
                }
                sb.append("<br/>").append(formatInline(trimmed, theme))
                i++
                continue
            }
            break
        }

        while (stack.isNotEmpty()) {
            closeTopList()
        }
        return i
    }

    private fun formatListItemContent(content: String, theme: RenderTheme): String {
        val checked = taskCheckedRegex.matchEntire(content)
        if (checked != null) {
            return "<span style='color:#16A34A;font-weight:bold;'>&#9745;</span> " +
                formatInline(checked.groupValues[1], theme)
        }
        val unchecked = taskUncheckedRegex.matchEntire(content)
        if (unchecked != null) {
            return "<span style='color:${theme.mutedTextHex};'>&#9744;</span> " +
                formatInline(unchecked.groupValues[1], theme)
        }
        return formatInline(content, theme)
    }

    private fun appendHeadingHtml(sb: StringBuilder, level: Int, title: String, theme: RenderTheme) {
        val fontSize = when (level) {
            1 -> "16px"
            2 -> "14px"
            3 -> "13px"
            4 -> "12px"
            else -> "12px"
        }
        val topMargin = when (level) {
            1 -> "10px"
            2 -> "8px"
            else -> "6px"
        }
        val bottomBorder = if (level <= 2) "border-bottom:1px solid ${theme.borderHex};padding-bottom:3px;" else ""
        val colorStyle = if (level >= 5) "color:${theme.mutedTextHex};" else ""
        sb.append("<div style='font-weight:bold;font-size:$fontSize;margin:$topMargin 0 4px 0;$bottomBorder$colorStyle'>")
        sb.append(formatInline(title, theme))
        sb.append("</div>")
    }

    private fun appendCodeBlockHtml(sb: StringBuilder, language: String, code: String, theme: RenderTheme) {
        sb.append("<table width='100%' cellspacing='0' cellpadding='0' style='margin:6px 0;border:1px solid ${theme.borderHex};'>")
        if (language.isNotBlank()) {
            sb.append("<tr><td style='background-color:${theme.codeHeaderBgHex};color:${theme.mutedTextHex};font-family:sans-serif;font-size:10px;padding:2px 8px;border-bottom:1px solid ${theme.borderHex};'>")
            sb.append(escapeHtml(language))
            sb.append("</td></tr>")
        }
        sb.append("<tr><td style='background-color:${theme.codeBgHex};padding:6px 8px;'>")
        sb.append("<pre style='margin:0;padding:0;font-family:monospace;font-size:11px;color:${theme.textHex};'>")
        sb.append(escapeHtml(code))
        sb.append("</pre></td></tr></table>")
    }

    private fun appendBlockquoteHtml(sb: StringBuilder, quoteLines: List<String>, theme: RenderTheme) {
        if (quoteLines.isEmpty()) return
        val alertMatch = alertHeaderRegex.matchEntire(quoteLines.first().trim())
        val (barColor, alertTitle, contentLines) = if (alertMatch != null) {
            val kind = alertMatch.groupValues[1].uppercase()
            val rest = quoteLines.drop(1)
            when (kind) {
                "NOTE" -> Triple("#3B82F6", "&#8505; 说明 (Note)", rest)
                "TIP" -> Triple("#10B981", "&#128161; 提示 (Tip)", rest)
                "IMPORTANT" -> Triple("#8B5CF6", "&#128204; 重要 (Important)", rest)
                "WARNING" -> Triple("#F59E0B", "&#9888; 注意 (Warning)", rest)
                "CAUTION" -> Triple("#EF4444", "&#128680; 警告 (Caution)", rest)
                else -> Triple(theme.quoteBarHex, null, quoteLines)
            }
        } else {
            Triple(theme.quoteBarHex, null, quoteLines)
        }

        sb.append("<table width='100%' cellspacing='0' cellpadding='0' style='margin:6px 0;'><tr>")
        sb.append("<td width='3' bgcolor='$barColor'></td>")
        sb.append("<td style='background-color:${theme.quoteBgHex};padding:5px 8px;color:${theme.mutedTextHex};'>")
        if (alertTitle != null) {
            sb.append("<div style='font-weight:bold;color:$barColor;margin-bottom:3px;'>$alertTitle</div>")
        }
        renderBlocks(contentLines, theme, sb)
        sb.append("</td></tr></table>")
    }

    private fun appendTableHtml(
        sb: StringBuilder,
        headers: List<String>,
        alignments: List<String>,
        rows: List<List<String>>,
        theme: RenderTheme
    ) {
        val colCount = maxOf(headers.size, alignments.size, rows.maxOfOrNull { it.size } ?: 0)
        if (colCount == 0) return

        sb.append("<table width='100%' cellspacing='0' cellpadding='4' style='margin:6px 0;border-collapse:collapse;border:1px solid ${theme.borderHex};'>")
        sb.append("<tr style='background-color:${theme.tableHeaderBgHex};'>")
        for (c in 0 until colCount) {
            val align = alignments.getOrElse(c) { "left" }
            val cellText = headers.getOrElse(c) { "" }
            sb.append("<th align='$align' style='border:1px solid ${theme.borderHex};padding:4px 6px;font-weight:bold;text-align:$align;'>")
            sb.append(formatInline(cellText, theme))
            sb.append("</th>")
        }
        sb.append("</tr>")

        rows.forEachIndexed { rIdx, row ->
            val bgAttr = if (rIdx % 2 == 1) " style='background-color:${theme.tableStripeBgHex};'" else ""
            sb.append("<tr$bgAttr>")
            for (c in 0 until colCount) {
                val align = alignments.getOrElse(c) { "left" }
                val cellText = row.getOrElse(c) { "" }
                sb.append("<td align='$align' style='border:1px solid ${theme.borderHex};padding:4px 6px;text-align:$align;'>")
                sb.append(formatInline(cellText, theme))
                sb.append("</td>")
            }
            sb.append("</tr>")
        }
        sb.append("</table>")
    }

    private fun parseFenceOpen(line: String): FenceSpec? {
        var idx = 0
        while (idx < line.length && line[idx] == ' ') idx++
        if (idx >= line.length) return null
        val ch = line[idx]
        if (ch != '`' && ch != '~') return null
        var count = 0
        while (idx + count < line.length && line[idx + count] == ch) count++
        if (count < 3) return null
        val info = line.substring(idx + count).trim()
        // CommonMark：反引号围栏的 info string 不能再包含反引号（防止把单行 ```code``` 误判为块）
        if (ch == '`' && info.contains('`')) return null
        val lang = info.split(Regex("""\s+""")).firstOrNull().orEmpty()
        return FenceSpec(ch, count, lang)
    }

    private fun isFenceClose(line: String, open: FenceSpec): Boolean {
        val trimmed = line.trim()
        if (trimmed.length < open.length) return false
        return trimmed.all { it == open.char }
    }

    private fun matchListItem(line: String): ListItemMatch? {
        if (hrRegex.matches(line)) return null
        val uMatch = unorderedListRegex.matchEntire(line)
        if (uMatch != null) {
            return ListItemMatch(
                indent = measureIndent(uMatch.groupValues[1]),
                ordered = false,
                startNumber = 1,
                content = uMatch.groupValues[2]
            )
        }
        val oMatch = orderedListRegex.matchEntire(line)
        if (oMatch != null) {
            return ListItemMatch(
                indent = measureIndent(oMatch.groupValues[1]),
                ordered = true,
                startNumber = oMatch.groupValues[2].toIntOrNull() ?: 1,
                content = oMatch.groupValues[3]
            )
        }
        return null
    }

    private fun measureIndent(leadingWs: String): Int {
        var cols = 0
        for (ch in leadingWs) {
            cols += if (ch == '\t') 4 else 1
        }
        return cols
    }

    private fun isTableHeaderAndSeparator(headerLine: String, sepLine: String): Boolean {
        if (!headerLine.contains('|') || !sepLine.contains('|')) return false
        val sepCells = splitTableCells(sepLine)
        if (sepCells.isEmpty()) return false
        val minDashes = if (sepCells.size >= 2) 1 else 3
        val cellPattern = Regex("""^:?-{${minDashes},}:?$""")
        return sepCells.all { cellPattern.matches(it.trim()) }
    }

    private fun parseTableAlignments(sepLine: String): List<String> {
        return splitTableCells(sepLine).map { raw ->
            val s = raw.trim()
            val left = s.startsWith(":")
            val right = s.endsWith(":")
            when {
                left && right -> "center"
                right -> "right"
                else -> "left"
            }
        }
    }

    private fun splitTableCells(line: String): List<String> {
        val trimmed = line.trim()
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var inCode = false
        var escaped = false

        for (ch in trimmed) {
            if (escaped) {
                if (ch == '|') {
                    current.append('|')
                } else {
                    current.append('\\').append(ch)
                }
                escaped = false
                continue
            }
            if (ch == '\\') {
                escaped = true
                continue
            }
            if (ch == '`') {
                inCode = !inCode
                current.append(ch)
                continue
            }
            if (ch == '|' && !inCode) {
                cells.add(current.toString().trim())
                current.clear()
            } else {
                current.append(ch)
            }
        }
        if (escaped) current.append('\\')
        cells.add(current.toString().trim())

        if (trimmed.startsWith("|") && cells.isNotEmpty() && cells.first().isEmpty()) {
            cells.removeAt(0)
        }
        if (trimmed.endsWith("|") && cells.isNotEmpty() && cells.last().isEmpty()) {
            cells.removeAt(cells.lastIndex)
        }
        return cells
    }

    internal fun formatInline(text: String, theme: RenderTheme): String {
        if (text.isEmpty()) return ""
        val placeholders = mutableListOf<String>()
        fun stash(html: String): String {
            val idx = placeholders.size
            placeholders.add(html)
            return "$PH_OPEN$idx$PH_CLOSE"
        }

        // 1. 优先提取行内代码（支持多反引号 ``code``），避免内部 * _ ~ < > 被后续规则误解析
        val afterCode = extractInlineCodeSpans(text) { rawCode ->
            stash("<code style='background-color:${theme.codeBgHex};font-family:monospace;padding:1px 3px;'>${escapeHtml(rawCode)}</code>")
        }

        // 2. 保护反斜杠转义字符（\* \_ \` \[ \] \| \~ 等）
        val afterEscapes = extractBackslashEscapes(afterCode) { literal ->
            stash(escapeHtml(literal))
        }

        // 3. 保留显式 <br> / <br/>（常用于表格内换行）
        val afterBr = brTagRegex.replace(afterEscapes) { stash("<br/>") }

        // 4. 尖括号自动链接 <https://...> / <file://...>
        val afterAutoLink = angleAutolinkRegex.replace(afterBr) { m ->
            val url = m.groupValues[1]
            stash("<a href='${escapeAttr(url)}' style='color:${theme.linkHex};text-decoration:underline;'>${escapeHtml(url)}</a>")
        }

        // 5. 对剩余纯文本做 HTML 实体转义（保护 List<String> 等泛型）
        val escaped = escapeHtml(afterAutoLink)

        // 6. 解析 Markdown 图片 ![alt](url) 与链接 [text](url)（支持路径内平衡括号如 znpxfwxt(haidong)）
        val afterLinks = replaceMarkdownLinksAndImages(escaped, theme, ::stash)

        // 7. 裸露的 http(s):// URL 转为可点击链接
        val afterBareUrls = bareUrlRegex.replace(afterLinks) { m ->
            val raw = m.value
            val trimmedUrl = trimTrailingUrlPunctuation(raw)
            val suffix = raw.substring(trimmedUrl.length)
            val unescapedHref = unescapeBasicEntities(trimmedUrl)
            stash("<a href='${escapeAttr(unescapedHref)}' style='color:${theme.linkHex};text-decoration:underline;'>$trimmedUrl</a>") + suffix
        }

        // 8. 删除线 / 粗斜体 / 粗体 / 斜体
        val formatted = applyInlineEmphasis(afterBareUrls)

        // 9. 还原全部占位符（支持链接文本内嵌套行内代码的多层占位）
        return restorePlaceholders(formatted, placeholders)
    }

    private fun applyInlineEmphasis(input: String): String {
        var s = input
        s = strikeRegex.replace(s) { "<s>${it.groupValues[1]}</s>" }
        s = boldItalicStarRegex.replace(s) { "<b><i>${it.groupValues[1]}</i></b>" }
        s = boldItalicUnderRegex.replace(s) { "<b><i>${it.groupValues[1]}</i></b>" }
        s = boldStarRegex.replace(s) { "<b>${it.groupValues[1]}</b>" }
        s = boldUnderRegex.replace(s) { "<b>${it.groupValues[1]}</b>" }
        s = italicStarRegex.replace(s) { "<i>${it.groupValues[1]}</i>" }
        s = italicUnderRegex.replace(s) { "<i>${it.groupValues[1]}</i>" }
        return s
    }

    private fun extractInlineCodeSpans(text: String, onCode: (String) -> String): String {
        val out = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            if (text[i] == '\\' && i + 1 < n && text[i + 1] == '`') {
                out.append("\\`")
                i += 2
                continue
            }
            if (text[i] == '`') {
                var tickCount = 0
                while (i + tickCount < n && text[i + tickCount] == '`') tickCount++
                val closePos = findClosingBackticks(text, i + tickCount, tickCount)
                if (closePos >= 0) {
                    var content = text.substring(i + tickCount, closePos)
                    if (content.length >= 2 && content.startsWith(' ') && content.endsWith(' ') && content.any { it != ' ' }) {
                        content = content.substring(1, content.length - 1)
                    }
                    out.append(onCode(content))
                    i = closePos + tickCount
                    continue
                }
                out.append(text, i, i + tickCount)
                i += tickCount
                continue
            }
            out.append(text[i])
            i++
        }
        return out.toString()
    }

    private fun findClosingBackticks(text: String, start: Int, expectedCount: Int): Int {
        var i = start
        val n = text.length
        while (i < n) {
            if (text[i] == '`') {
                var count = 0
                while (i + count < n && text[i + count] == '`') count++
                if (count == expectedCount) return i
                i += count
            } else {
                i++
            }
        }
        return -1
    }

    private fun extractBackslashEscapes(text: String, onLiteral: (String) -> String): String {
        val escapable = "\\`*_{}[]()#+-.!|~$><"
        val out = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            if (text[i] == '\\' && i + 1 < n && text[i + 1] in escapable) {
                out.append(onLiteral(text[i + 1].toString()))
                i += 2
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }

    private fun replaceMarkdownLinksAndImages(
        text: String,
        theme: RenderTheme,
        stash: (String) -> String
    ): String {
        val out = StringBuilder(text.length)
        var i = 0
        val n = text.length

        while (i < n) {
            val isImage = text[i] == '!' && i + 1 < n && text[i + 1] == '['
            val bracketStart = if (isImage) i + 1 else if (text[i] == '[') i else -1
            if (bracketStart >= 0) {
                val bracketEnd = findMatchingBracket(text, bracketStart)
                if (bracketEnd > bracketStart && bracketEnd + 1 < n && text[bracketEnd + 1] == '(') {
                    val parenEnd = findMatchingParen(text, bracketEnd + 1)
                    if (parenEnd > bracketEnd + 1) {
                        val rawLabel = text.substring(bracketStart + 1, bracketEnd)
                        val rawTarget = text.substring(bracketEnd + 2, parenEnd).trim()
                        val href = extractLinkDestination(rawTarget)
                        if (href.isNotEmpty()) {
                            val renderedLabel = applyInlineEmphasis(rawLabel)
                            val safeHref = escapeAttr(unescapeBasicEntities(href))
                            val html = if (isImage) {
                                val alt = renderedLabel.ifBlank { "图片" }
                                if (safeHref.startsWith("http://") || safeHref.startsWith("https://")) {
                                    "<a href='$safeHref' style='color:${theme.linkHex};text-decoration:underline;'>[&#128444; $alt]</a>"
                                } else {
                                    "<span style='color:${theme.mutedTextHex};'>[&#128444; $alt]</span>"
                                }
                            } else {
                                "<a href='$safeHref' style='color:${theme.linkHex};text-decoration:underline;'>$renderedLabel</a>"
                            }
                            out.append(stash(html))
                            i = parenEnd + 1
                            continue
                        }
                    }
                }
            }
            out.append(text[i])
            i++
        }
        return out.toString()
    }

    private fun findMatchingBracket(text: String, openPos: Int): Int {
        var depth = 0
        for (i in openPos until text.length) {
            when (text[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    private fun findMatchingParen(text: String, openPos: Int): Int {
        var depth = 0
        for (i in openPos until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    private val linkTitleSuffixRegex = Regex("""\s+(?:&quot;[^&]*&quot;|'[^']*')\s*$""")

    private fun extractLinkDestination(rawTarget: String): String {
        if (rawTarget.isEmpty()) return ""
        val withoutTitle = linkTitleSuffixRegex.replace(rawTarget, "").trim()
        return withoutTitle.removePrefix("&lt;").removeSuffix("&gt;").trim()
    }

    private fun trimTrailingUrlPunctuation(url: String): String {
        var end = url.length
        while (end > 0) {
            val ch = url[end - 1]
            if (ch in ".,;:!?。，；：！？") {
                end--
            } else if (ch == ')' && url.substring(0, end).count { it == ')' } > url.substring(0, end).count { it == '(' }) {
                end--
            } else {
                break
            }
        }
        return url.substring(0, end)
    }

    private fun restorePlaceholders(text: String, placeholders: List<String>): String {
        var result = text
        var guard = 0
        while (result.indexOf(PH_OPEN) >= 0 && guard < 8) {
            result = placeholderRegex.replace(result) { m ->
                val idx = m.groupValues[1].toIntOrNull()
                if (idx != null && idx in placeholders.indices) placeholders[idx] else m.value
            }
            guard++
        }
        return result
    }

    private fun unescapeBasicEntities(s: String): String {
        return s.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
    }

    private fun escapeAttr(text: String): String {
        return escapeHtml(text).replace("'", "&#39;")
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
