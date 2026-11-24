package com.geminicode.acp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownToHtmlConverterTest {

    @Test
    fun `extractCodeBlocks finds fenced blocks with languages and extended fences`() {
        val md = """
            Here is the fix:
            ```kotlin title="Main.kt"
            fun greet() = println("hi")
            ```
            And config:
            ~~~json
            {"ok": true}
            ~~~
            Nested example:
            ````markdown
            ```java
            int x = 1;
            ```
            ````
        """.trimIndent()

        val blocks = MarkdownToHtmlConverter.extractCodeBlocks(md)
        assertEquals(3, blocks.size)
        assertEquals("kotlin", blocks[0].language)
        assertEquals("""fun greet() = println("hi")""", blocks[0].code)
        assertEquals("json", blocks[1].language)
        assertEquals("markdown", blocks[2].language)
        assertTrue(blocks[2].code.contains("```java"))
    }

    @Test
    fun `toHtml escapes html tags and formats inline markdown cleanly`() {
        val md = """
            ### Title
            #### Sub Heading
            Use **bold**, *italic*, ***both***, ~~old~~, **`boldCode`**, and `List<String>` with `**raw**`
            Identifier user_context_interceptor stays intact and 2 * 3 * 4 stays math.
            ```xml
            <tag value="1"/>
            ```
        """.trimIndent()

        val html = MarkdownToHtmlConverter.toHtml(md, "#111111", "#eeeeee", "#cccccc")
        assertFalse("Should not leak raw #### heading symbols", html.contains("####"))
        assertTrue(html.contains("<b>bold</b>"))
        assertTrue(html.contains("<i>italic</i>"))
        assertTrue(html.contains("<b><i>both</i></b>"))
        assertTrue(html.contains("<s>old</s>"))
        assertTrue(html.contains("<b><code"))
        assertTrue(html.contains("List&lt;String&gt;"))
        assertTrue("Inline code containing ** must not be bolded", html.contains(">**raw**</code>"))
        assertTrue("Snake case must not turn into italic", html.contains("user_context_interceptor"))
        assertFalse("Snake case must not create <i>context</i>", html.contains("<i>context</i>"))
        assertTrue("Math asterisks must not turn into italic", html.contains("2 * 3 * 4"))
        assertTrue(html.contains("&lt;tag value=&quot;1&quot;/&gt;"))
        assertTrue("Code block header should show xml language", html.contains(">xml</td>"))
    }

    @Test
    fun `toHtml renders GFM tables with alignment, inline code pipes, and br tags`() {
        val md = """
            | 字段 | 类型 | 说明 |
            | :--- | :---: | ---: |
            | `id` | `Long` | 主键<br/>非空 |
            | **mask** | `a | b` | 位掩码 |
        """.trimIndent()

        val html = MarkdownToHtmlConverter.toHtml(md, "#e5e7eb", "#161b22", "#30363d")
        assertTrue(html.contains("<table"))
        assertTrue(html.contains("<th align='left'"))
        assertTrue(html.contains("<th align='center'"))
        assertTrue(html.contains("<th align='right'"))
        assertTrue(html.contains("主键<br/>非空"))
        assertTrue(html.contains(">a | b</code>"))
        assertTrue(html.contains("<b>mask</b>"))
        assertFalse("Should not leave raw table separator dashes", html.contains(":---"))
    }

    @Test
    fun `toHtml renders nested lists, ordered lists, task checkboxes, hr, and blockquotes`() {
        val md = """
            1. 第一步
               - 子项 A
               - [x] 已完成任务
               - [ ] 待办任务
            2. 第二步

            ---

            > [!WARNING]
            > 请先备份 **配置** 文件
        """.trimIndent()

        val html = MarkdownToHtmlConverter.toHtml(md, "#111111", "#eeeeee", "#cccccc")
        assertTrue(html.contains("<ol"))
        assertTrue(html.contains("<ul"))
        assertTrue(html.contains("&#9745;"))
        assertTrue(html.contains("&#9744;"))
        assertTrue(html.contains("<hr"))
        assertTrue(html.contains("注意 (Warning)"))
        assertTrue(html.contains("<b>配置</b>"))
        assertFalse("Should not leak raw [!WARNING] marker", html.contains("[!WARNING]"))
        assertFalse("Should not leak raw [x] marker", html.contains("[x]"))
    }

    @Test
    fun `toHtml handles markdown links with parentheses in file paths and escapes`() {
        val md = """
            查看 [OrderService.java](file:///D:/ideaProject/haidong/znpxfwxt(haidong)/OrderService.java#L42) 与 [文档](https://example.com/a_(b)).
            转义星号：\*不是斜体\*
        """.trimIndent()

        val html = MarkdownToHtmlConverter.toHtml(md, "#111111", "#eeeeee", "#cccccc")
        assertTrue(
            html.contains("href='file:///D:/ideaProject/haidong/znpxfwxt(haidong)/OrderService.java#L42'")
        )
        assertTrue(html.contains(">OrderService.java</a>"))
        assertTrue(html.contains("href='https://example.com/a_(b)'"))
        assertTrue(html.contains("转义星号：*不是斜体*"))
        assertFalse(html.contains("<i>不是斜体</i>"))
    }

    @Test
    fun `stripToPlainPreview removes raw markdown punctuation`() {
        val preview = MarkdownToHtmlConverter.stripToPlainPreview("### **分析** `UserContext.java` 中的 [登录逻辑](file:///a.java)")
        assertEquals("分析 UserContext.java 中的 登录逻辑", preview)
    }
}
