package com.geminicode.acp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownToHtmlConverterTest {

    @Test
    fun `extractCodeBlocks finds fenced blocks with languages`() {
        val md = """
            Here is the fix:
            ```kotlin
            fun greet() = println("hi")
            ```
            And config:
            ```json
            {"ok": true}
            ```
        """.trimIndent()

        val blocks = MarkdownToHtmlConverter.extractCodeBlocks(md)
        assertEquals(2, blocks.size)
        assertEquals("kotlin", blocks[0].language)
        assertEquals("""fun greet() = println("hi")""", blocks[0].code)
        assertEquals("json", blocks[1].language)
    }

    @Test
    fun `toHtml escapes html tags and formats inline markdown`() {
        val md = """
            ### Title
            Use **bold** and `List<String>`
            ```xml
            <tag value="1"/>
            ```
        """.trimIndent()

        val html = MarkdownToHtmlConverter.toHtml(md, "#111111", "#eeeeee", "#cccccc")
        assertTrue(html.contains("<b>bold</b>"))
        assertTrue(html.contains("List&lt;String&gt;"))
        assertTrue(html.contains("&lt;tag value=&quot;1&quot;/&gt;"))
    }
}
