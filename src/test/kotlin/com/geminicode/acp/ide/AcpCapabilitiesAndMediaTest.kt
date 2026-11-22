package com.geminicode.acp.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO

class AcpCapabilitiesAndMediaTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun `ImageAttachmentHelper loads PNG files, scales oversized images down, and rejects non-images`() {
        val imgFile = tempFolder.newFile("demo.png")
        val small = BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB).apply {
            val g = createGraphics()
            g.color = Color.BLUE
            g.fillRect(0, 0, 64, 32)
            g.dispose()
        }
        ImageIO.write(small, "png", imgFile)

        val loaded = ImageAttachmentHelper.loadFromFile(imgFile)
        assertNotNull(loaded)
        assertEquals("image/png", loaded!!.mimeType)
        assertEquals(64, loaded.width)
        assertEquals(32, loaded.height)
        assertTrue(Base64.getDecoder().decode(loaded.base64Data).isNotEmpty())
        assertTrue(loaded.chipLabel().contains("64x32"))

        // 超大图片自动等比缩放至 2048px 以内
        val huge = BufferedImage(3000, 1500, BufferedImage.TYPE_INT_RGB)
        val encodedHuge = ImageAttachmentHelper.encodeBufferedImage(huge, "huge.png")
        assertNotNull(encodedHuge)
        assertEquals(2048, encodedHuge!!.width)
        assertEquals(1024, encodedHuge.height)

        val txtFile = tempFolder.newFile("notes.txt")
        Files.writeString(txtFile.toPath(), "not an image")
        assertFalse(ImageAttachmentHelper.isSupportedImageFile(txtFile))
        assertNull(ImageAttachmentHelper.loadFromFile(txtFile))
    }

    @Test
    fun `AcpContextBuilder resolves systemPrompt append with rules file and builds embeddedContext resource blocks`() {
        val projDir = tempFolder.newFolder("myproj").toPath()
        val geminiDir = projDir.resolve(".gemini")
        Files.createDirectories(geminiDir)
        Files.writeString(geminiDir.resolve("rules.md"), "Always write unit tests.")

        val combined = AcpContextBuilder.resolveSystemPromptAppend(
            projectBasePath = projDir.toString(),
            globalSystemPrompt = "Reply in Chinese.",
            loadProjectRulesFile = true
        )
        assertNotNull(combined)
        assertTrue(combined!!.contains("Reply in Chinese."))
        assertTrue(combined.contains("Always write unit tests."))

        val withoutRules = AcpContextBuilder.resolveSystemPromptAppend(
            projectBasePath = projDir.toString(),
            globalSystemPrompt = "",
            loadProjectRulesFile = false
        )
        assertNull(withoutRules)

        // 构建 embeddedContext resource 块：行区间切片 + 忽略目录与二进制文件
        val ktFile = projDir.resolve("Main.kt")
        Files.writeString(ktFile, "line1\nline2\nline3\nline4")
        val binFile = projDir.resolve("blob.bin")
        Files.write(binFile, byteArrayOf(0x00, 0x01, 0x02))

        val refs = listOf(
            CodeReference(ktFile.toString(), "Main.kt", startLine = 2, endLine = 3),
            CodeReference(binFile.toString(), "blob.bin"),
            CodeReference(projDir.toString(), "myproj", isDirectory = true)
        )
        val blocks = AcpContextBuilder.buildEmbeddedResourceBlocks(refs, maxTotalChars = 10_000)
        assertEquals(1, blocks.size)
        assertEquals("text/x-kotlin", blocks[0].mimeType)
        assertEquals("line2\nline3", blocks[0].text)
        assertTrue(blocks[0].uri.endsWith("#L2-L3"))
    }

    @Test
    fun `IdeaPathSecurity authorizes main workspace and additionalDirectories while rejecting external paths`() {
        val mainDir = tempFolder.newFolder("main-repo").toPath()
        val extraDir = tempFolder.newFolder("shared-lib").toPath()
        val outsideDir = tempFolder.newFolder("outside").toPath()

        val mainFile = mainDir.resolve("App.kt")
        val extraFile = extraDir.resolve("Util.kt")
        val outsideFile = outsideDir.resolve("secret.txt")

        assertTrue(IdeaPathSecurity.isWithinAnyWorkspace(mainDir.toString(), listOf(extraDir.toString()), mainFile))
        assertTrue(IdeaPathSecurity.isWithinAnyWorkspace(mainDir.toString(), listOf(extraDir.toString()), extraFile))
        assertFalse(IdeaPathSecurity.isWithinAnyWorkspace(mainDir.toString(), listOf(extraDir.toString()), outsideFile))
    }
}
