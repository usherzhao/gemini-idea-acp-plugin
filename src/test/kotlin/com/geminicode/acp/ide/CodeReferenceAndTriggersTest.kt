package com.geminicode.acp.ide

import com.geminicode.acp.protocol.AcpModels
import com.geminicode.acp.ui.InputTriggerParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class CodeReferenceAndTriggersTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun `extractInlineReferences parses file, single line, and multi-line mentions without pasting code`() {
        val text = "请检查 @src/Main.kt#L10-L25 以及 @src/Util.kt#L8 和 @README.md"
        val refs = CodeReference.extractInlineReferences(text, "D:/repo")

        assertEquals(3, refs.size)
        // 对话框展示仅显示文件名或文件名+行号，不展示冗长路径
        assertEquals("Main.kt:L10-L25", refs[0].chipLabel())
        assertEquals("@Main.kt#L10-L25", refs[0].inlineToken())
        assertEquals(10, refs[0].startLine)
        assertEquals(25, refs[0].endLine)

        assertEquals("Util.kt:L8", refs[1].chipLabel())
        assertEquals(8, refs[1].startLine)
        assertEquals(8, refs[1].endLine)

        assertEquals("README.md", refs[2].chipLabel())
        assertNull(refs[2].startLine)

        // 发送给 AI 的 prompt 仍保留精确完整路径与行号
        val formatted = CodeReference.formatPromptWithReferences("帮我重构", listOf(refs[0], refs[0]))
        assertTrue(formatted.contains("src/Main.kt"))
        assertTrue(formatted.contains("第 10 行至第 25 行"))
        assertTrue(formatted.contains("未复制粘贴源码"))
        // 去重后只出现一次
        assertEquals(1, formatted.split("第 10 行至第 25 行").size - 1)
    }

    @Test
    fun `resolveUniqueDisplayPath shows bare filename by default and disambiguates same-name files cleanly`() {
        val bindings = LinkedHashMap<String, CodeReference.ResolvedFileBinding>()
        val refA = CodeReference("D:/repo/docs/a/SKILL.md", "docs/a/SKILL.md")
        val displayA = CodeReference.resolveUniqueDisplayPath(refA, bindings)
        assertEquals("SKILL.md", displayA)
        bindings[displayA.lowercase()] = CodeReference.ResolvedFileBinding(refA.absolutePath, refA.relativePath, displayA)

        // 同名不同路径文件自动补充最少父目录区分
        val refB = CodeReference("D:/repo/docs/b/SKILL.md", "docs/b/SKILL.md")
        val displayB = CodeReference.resolveUniqueDisplayPath(refB, bindings)
        assertEquals("b/SKILL.md", displayB)
        bindings[displayB.lowercase()] = CodeReference.ResolvedFileBinding(refB.absolutePath, refB.relativePath, displayB)

        // 再次引用同一文件保持原大小写别名
        val refALines = refA.copy(startLine = 10, endLine = 20)
        assertEquals("SKILL.md", CodeReference.resolveUniqueDisplayPath(refALines, bindings))

        // 输入框仅显示精简 token，但解析结果准确还原完整绝对/相对路径
        val extracted = CodeReference.extractInlineReferences(
            "对比 @SKILL.md#L10-L20 和 @b/SKILL.md",
            "D:/repo",
            bindings = bindings
        )
        assertEquals(2, extracted.size)
        assertEquals("D:/repo/docs/a/SKILL.md", extracted[0].absolutePath)
        assertEquals("docs/a/SKILL.md", extracted[0].relativePath)
        assertEquals("SKILL.md:L10-L20", extracted[0].chipLabel())

        assertEquals("D:/repo/docs/b/SKILL.md", extracted[1].absolutePath)
        assertEquals("docs/b/SKILL.md", extracted[1].relativePath)
        assertEquals("b/SKILL.md", extracted[1].chipLabel())

        // 工具调用位置精简显示
        assertEquals("OrderService.java:42", CodeReference.shortenLocationDisplay("D:/repo/src/main/OrderService.java:42"))
        assertEquals("OrderService.java", CodeReference.shortenLocationDisplay("D:/repo/src/main/OrderService.java"))
    }

    @Test
    fun `InputTriggerParser detects at-mentions and slash-skills without false triggers inside paths`() {
        val t1 = InputTriggerParser.detectTrigger("看看 @src/Foo", 11)
        assertEquals(InputTriggerParser.TriggerKind.FILE_MENTION, t1?.kind)
        assertEquals("src/Foo", t1?.query)
        assertEquals(3, t1?.tokenStart)

        val t2 = InputTriggerParser.detectTrigger("/pl", 3)
        assertEquals(InputTriggerParser.TriggerKind.SKILL_COMMAND, t2?.kind)
        assertEquals("pl", t2?.query)

        val t3 = InputTriggerParser.detectTrigger("请使用 /ikuai", 10)
        assertEquals(InputTriggerParser.TriggerKind.SKILL_COMMAND, t3?.kind)
        assertEquals("ikuai", t3?.query)

        // 路径中间的斜杠不应触发 `/` 补全
        assertNull(InputTriggerParser.detectTrigger("编辑 src/main/Foo", 17))
    }

    @Test
    fun `SkillDiscoveryService discovers local SKILL md frontmatter and merges with ACP commands`() {
        val projectRoot = tempFolder.newFolder("proj").toPath()
        val fakeHome = tempFolder.newFolder("home").toPath()

        val projSkillDir = projectRoot.resolve(".agents/skills/my-deploy")
        Files.createDirectories(projSkillDir)
        Files.writeString(
            projSkillDir.resolve("SKILL.md"),
            """
            ---
            name: my-deploy
            description: 一键部署项目到测试环境
            ---
            # Deploy Skill
            """.trimIndent()
        )

        val userSkillDir = fakeHome.resolve(".gemini/antigravity/builtin/skills/builtin-helper")
        Files.createDirectories(userSkillDir)
        Files.writeString(
            userSkillDir.resolve("SKILL.md"),
            """
            ---
            name: builtin-helper
            description: 内置辅助技能
            ---
            """.trimIndent()
        )

        val local = SkillDiscoveryService.discoverLocalSkills(projectRoot.toString(), fakeHome)
        assertEquals(2, local.size)
        assertTrue(local.any { it.name == "my-deploy" && it.description == "一键部署项目到测试环境" })

        val acp = listOf(
            AcpModels.AvailableCommand("plan", "ACP 官方规划命令"),
            AcpModels.AvailableCommand("my-deploy", "ACP 服务端描述优先")
        )
        val merged = SkillDiscoveryService.mergeCommands(acp, local)
        assertEquals(3, merged.size)
        assertEquals("ACP 服务端描述优先", merged.first { it.name == "my-deploy" }.description)
    }

    @Test
    fun `extractInlineReferences supports quoted whitespace paths, Chinese boundaries, email exclusion, and token removal`() {
        val dir = tempFolder.newFolder("my dir")
        val text = """请看@"my dir/Foo Bar.kt"#L5-L10，再检查@src/App.kt，联系 user@example.com 以及@"${dir.absolutePath.replace('\\', '/')}" """
        val refs = CodeReference.extractInlineReferences(text, tempFolder.root.absolutePath)

        assertEquals(3, refs.size)
        assertEquals("Foo Bar.kt:L5-L10", refs[0].chipLabel())
        assertEquals("@\"Foo Bar.kt\"#L5-L10", refs[0].inlineToken())
        assertEquals("App.kt", refs[1].chipLabel())
        assertTrue(refs[2].isDirectory)
        assertTrue(refs[2].toPromptLine().startsWith("- 目录"))

        val afterRemove = CodeReference.removeReferenceToken(text, refs[0], tempFolder.root.absolutePath)
        assertTrue(!afterRemove.contains("Foo Bar.kt"))
        assertTrue(afterRemove.contains("@src/App.kt"))
        assertTrue(afterRemove.contains("user@example.com"))

        // 排除正在输入的未完成 @query 范围
        val typingText = "@src/App.kt 请看 @osvc"
        val excluded = CodeReference.extractInlineReferences(typingText, "D:/repo", excludeRange = 15 until 20)
        assertEquals(1, excluded.size)
        assertEquals("src/App.kt", excluded[0].relativePath)
    }

    @Test
    fun `InputTriggerParser detects at-mentions after Chinese text and rejects email addresses`() {
        val chineseBeforeAt = InputTriggerParser.detectTrigger("请看@osvc", 7)
        assertEquals(InputTriggerParser.TriggerKind.FILE_MENTION, chineseBeforeAt?.kind)
        assertEquals("osvc", chineseBeforeAt?.query)
        assertEquals(2, chineseBeforeAt?.tokenStart)

        assertNull(InputTriggerParser.detectTrigger("联系 user@gmail", 13))
    }

    @Test
    fun `ProjectFileIndexHelper prioritizes open editor files and supports CamelCase and fuzzy subsequence search`() {
        val openExact = ProjectFileIndexHelper.matchScore("order.java", "src/order.java", "order", isOpen = true)
        val closedExact = ProjectFileIndexHelper.matchScore("order.java", "src/order.java", "order", isOpen = false)
        val closedPathOnly = ProjectFileIndexHelper.matchScore("main.java", "src/order/main.java", "order", isOpen = false)
        val noMatch = ProjectFileIndexHelper.matchScore("user.java", "src/user.java", "order", isOpen = false)

        assertTrue(openExact > closedExact)
        assertTrue(closedExact > closedPathOnly)
        assertEquals(-1, noMatch)

        // 驼峰首字母模糊匹配
        val camelOrderService = ProjectFileIndexHelper.matchScore(
            "OrderService.java",
            "src/main/java/com/demo/OrderService.java",
            "osvc",
            isOpen = false
        )
        assertTrue("Expected CamelCase 'osvc' to match OrderService.java, got $camelOrderService", camelOrderService > 0)

        val camelConfigurable = ProjectFileIndexHelper.matchScore(
            "GeminiAcpConfigurable.kt",
            "src/main/kotlin/com/geminicode/acp/settings/GeminiAcpConfigurable.kt",
            "gac",
            isOpen = false
        )
        assertTrue("Expected CamelCase 'gac' to match GeminiAcpConfigurable.kt, got $camelConfigurable", camelConfigurable > 0)

        // 跨段词首与路径子序列模糊匹配（同时兼容 Windows 反斜杠查询）
        val hyphenPathFuzzy = ProjectFileIndexHelper.matchScore(
            "SKILL.md",
            "docs/skills/portal-affair-create/SKILL.md",
            "paf",
            isOpen = false
        )
        assertTrue("Expected word-boundary fuzzy 'paf' to match portal-affair-create, got $hyphenPathFuzzy", hyphenPathFuzzy > 0)

        val orderControllerFuzzy = ProjectFileIndexHelper.matchScore(
            "OrderController.java",
            "src/controller/OrderController.java",
            "ordctl",
            isOpen = false
        )
        assertTrue("Expected fuzzy 'ordctl' to match OrderController.java, got $orderControllerFuzzy", orderControllerFuzzy > 0)

        val winSlashPathFuzzy = ProjectFileIndexHelper.matchScore(
            "OrderService.java",
            "src/service/OrderService.java",
            "srv\\ord",
            isOpen = false
        )
        assertTrue("Expected Windows slash path fuzzy 'srv\\\\ord' to match, got $winSlashPathFuzzy", winSlashPathFuzzy > 0)
    }
}
