package com.geminicode.acp.ui

/**
 * 纯函数解析输入框当前光标处是否处于 `@文件引用` 或 `/skill命令` 触发状态。
 */
object InputTriggerParser {

    enum class TriggerKind {
        FILE_MENTION,
        SKILL_COMMAND
    }

    data class ActiveTrigger(
        val kind: TriggerKind,
        val tokenStart: Int,
        val caretOffset: Int,
        val query: String
    )

    fun detectTrigger(text: String, caretOffset: Int): ActiveTrigger? {
        if (caretOffset <= 0 || caretOffset > text.length) return null

        var pos = caretOffset - 1
        while (pos >= 0 && !text[pos].isWhitespace()) {
            pos--
        }
        val segmentStart = pos + 1
        if (segmentStart >= caretOffset) return null

        // 1. 先检查当前非空白段内是否存在合法的 `@` 文件触发（兼容 `请看@order`、`( @foo` 等紧跟中文/标点的场景，同时排除 `user@example.com` 邮箱）
        val atIdx = text.lastIndexOf('@', caretOffset - 1)
        if (atIdx >= segmentStart) {
            val prevChar = if (atIdx > 0) text[atIdx - 1] else null
            if (prevChar == null || !isAsciiEmailLocalChar(prevChar)) {
                val query = text.substring(atIdx + 1, caretOffset)
                return ActiveTrigger(TriggerKind.FILE_MENTION, atIdx, caretOffset, query)
            }
        }

        // 2. 仅在句首或空白后触发 `/skill`，避免输入路径片段（如 `src/main`）时误弹
        if (text[segmentStart] == '/' && (segmentStart == 0 || text[segmentStart - 1].isWhitespace())) {
            val query = text.substring(segmentStart + 1, caretOffset)
            return ActiveTrigger(TriggerKind.SKILL_COMMAND, segmentStart, caretOffset, query)
        }

        return null
    }

    private fun isAsciiEmailLocalChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '_' || c == '-' || c == '+'
}
