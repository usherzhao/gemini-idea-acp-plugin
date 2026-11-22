package com.geminicode.acp.ide

import java.awt.Graphics2D
import java.awt.Image
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO

/**
 * 官方 ACP `promptCapabilities.image` 多模态图片附件编解码工具：
 * 支持剪贴板截图粘贴（Ctrl+V）、拖拽与本地文件选取，并自动控制超大图片尺寸与体积。
 */
object ImageAttachmentHelper {

    private const val MAX_DIMENSION = 2048
    private const val MAX_RAW_BYTES = 4 * 1024 * 1024 // 4 MB

    val supportedExtensions = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

    data class ImageAttachment(
        val id: String = UUID.randomUUID().toString(),
        val displayName: String,
        val mimeType: String,
        val base64Data: String,
        val width: Int,
        val height: Int,
        val sizeBytes: Int
    ) {
        fun chipLabel(): String {
            val sizeStr = if (sizeBytes >= 1024 * 1024) {
                String.format("%.1fMB", sizeBytes / (1024.0 * 1024.0))
            } else {
                "${(sizeBytes / 1024).coerceAtLeast(1)}KB"
            }
            val dimStr = if (width > 0 && height > 0) "${width}x${height}, " else ""
            return "$displayName ($dimStr$sizeStr)"
        }
    }

    fun isSupportedImageFile(file: File): Boolean {
        if (!file.isFile) return false
        return file.extension.lowercase() in supportedExtensions
    }

    /**
     * 尝试从系统剪贴板读取截图或复制的图片文件列表；若剪贴板仅为普通文本则返回空列表。
     */
    fun readImagesFromClipboard(nextIndex: Int): List<ImageAttachment> {
        val transferable = runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
        }.getOrNull() ?: return emptyList()

        // 1. 剪贴板中直接包含图像数据（如微信/QQ/系统截图）
        if (transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) {
            val awtImage = runCatching {
                transferable.getTransferData(DataFlavor.imageFlavor) as? Image
            }.getOrNull()
            if (awtImage != null) {
                val encoded = encodeAwtImage(awtImage, "截图_$nextIndex.png")
                if (encoded != null) return listOf(encoded)
            }
        }

        // 2. 剪贴板中为文件列表且全部为受支持的图片文件
        if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            @Suppress("UNCHECKED_CAST")
            val files = runCatching {
                transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
            }.getOrNull().orEmpty()
            if (files.isNotEmpty() && files.all { isSupportedImageFile(it) }) {
                return files.mapNotNull { loadFromFile(it) }
            }
        }

        return emptyList()
    }

    fun loadFromFile(file: File): ImageAttachment? {
        if (!isSupportedImageFile(file)) return null
        return runCatching {
            val ext = file.extension.lowercase()
            val rawBytes = Files.readAllBytes(file.toPath())

            // WebP / GIF 等若未超过体积限制，直接按原始 MIME 编码以保留动图或透明通道
            if ((ext == "webp" || ext == "gif") && rawBytes.size <= MAX_RAW_BYTES) {
                val mime = if (ext == "webp") "image/webp" else "image/gif"
                val img = runCatching { ImageIO.read(file) }.getOrNull()
                return@runCatching ImageAttachment(
                    displayName = file.name,
                    mimeType = mime,
                    base64Data = Base64.getEncoder().encodeToString(rawBytes),
                    width = img?.width ?: 0,
                    height = img?.height ?: 0,
                    sizeBytes = rawBytes.size
                )
            }

            val buffered = ImageIO.read(file)
            if (buffered != null) {
                if (buffered.width <= MAX_DIMENSION && buffered.height <= MAX_DIMENSION && rawBytes.size <= MAX_RAW_BYTES && ext in setOf("png", "jpg", "jpeg")) {
                    val mime = if (ext == "png") "image/png" else "image/jpeg"
                    return@runCatching ImageAttachment(
                        displayName = file.name,
                        mimeType = mime,
                        base64Data = Base64.getEncoder().encodeToString(rawBytes),
                        width = buffered.width,
                        height = buffered.height,
                        sizeBytes = rawBytes.size
                    )
                }
                return@runCatching encodeBufferedImage(buffered, file.name)
            }

            // ImageIO 无法解码但属于小体积图片（如部分 WebP）
            if (rawBytes.size <= MAX_RAW_BYTES) {
                ImageAttachment(
                    displayName = file.name,
                    mimeType = guessImageMime(ext),
                    base64Data = Base64.getEncoder().encodeToString(rawBytes),
                    width = 0,
                    height = 0,
                    sizeBytes = rawBytes.size
                )
            } else {
                null
            }
        }.getOrNull()
    }

    fun encodeAwtImage(image: Image, displayName: String): ImageAttachment? {
        val buffered = toBufferedImage(image) ?: return null
        return encodeBufferedImage(buffered, displayName)
    }

    internal fun encodeBufferedImage(source: BufferedImage, displayName: String): ImageAttachment? {
        val scaled = scaleDownIfNeeded(source)
        val pngBytes = writeBytes(scaled, "png")
        if (pngBytes != null && pngBytes.size <= MAX_RAW_BYTES) {
            return ImageAttachment(
                displayName = displayName,
                mimeType = "image/png",
                base64Data = Base64.getEncoder().encodeToString(pngBytes),
                width = scaled.width,
                height = scaled.height,
                sizeBytes = pngBytes.size
            )
        }
        // PNG 体积过大时自动转 JPEG 压缩
        val rgb = BufferedImage(scaled.width, scaled.height, BufferedImage.TYPE_INT_RGB).apply {
            val g = createGraphics()
            g.drawImage(scaled, 0, 0, java.awt.Color.WHITE, null)
            g.dispose()
        }
        val jpgBytes = writeBytes(rgb, "jpg") ?: return null
        if (jpgBytes.size > MAX_RAW_BYTES) return null
        return ImageAttachment(
            displayName = displayName.substringBeforeLast('.') + ".jpg",
            mimeType = "image/jpeg",
            base64Data = Base64.getEncoder().encodeToString(jpgBytes),
            width = rgb.width,
            height = rgb.height,
            sizeBytes = jpgBytes.size
        )
    }

    private fun toBufferedImage(image: Image): BufferedImage? {
        if (image is BufferedImage) return image
        val w = image.getWidth(null)
        val h = image.getHeight(null)
        if (w <= 0 || h <= 0) return null
        val buf = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = buf.createGraphics()
        g.drawImage(image, 0, 0, null)
        g.dispose()
        return buf
    }

    private fun scaleDownIfNeeded(img: BufferedImage): BufferedImage {
        val maxSide = maxOf(img.width, img.height)
        if (maxSide <= MAX_DIMENSION) return img
        val ratio = MAX_DIMENSION.toDouble() / maxSide.toDouble()
        val newW = (img.width * ratio).toInt().coerceAtLeast(1)
        val newH = (img.height * ratio).toInt().coerceAtLeast(1)
        val type = if (img.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val out = BufferedImage(newW, newH, type)
        val g: Graphics2D = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(img, 0, 0, newW, newH, null)
        g.dispose()
        return out
    }

    private fun writeBytes(img: BufferedImage, format: String): ByteArray? {
        return runCatching {
            val baos = ByteArrayOutputStream()
            val ok = ImageIO.write(img, format, baos)
            if (ok) baos.toByteArray() else null
        }.getOrNull()
    }

    private fun guessImageMime(ext: String): String = when (ext) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        else -> "image/png"
    }
}
