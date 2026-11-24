package com.geminicode.acp.ui

import com.geminicode.acp.settings.GeminiAcpSettingsState
import com.geminicode.acp.settings.GeminiExecutableResolver
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer
import javax.swing.border.CompoundBorder
import javax.swing.border.LineBorder

/**
 * 解析官方 `agy acp --login` 交互输出的纯函数工具集（不接触或保存任何 OAuth 令牌）。
 */
object AgyLoginOutputParser {

    private val authUrlRegex = Regex("""https://accounts\.google\.com/[^\s"'<>]+""")
    private val loggedInEmailRegex = Regex("""Logged in as\s+([^\s<>'"]+?)[\.\s]*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    fun extractAuthUrl(output: String): String? {
        return authUrlRegex.find(output)?.value?.trimEnd('.', ',', ';', ')')
    }

    fun extractLoggedInEmail(output: String): String? {
        val matches = loggedInEmailRegex.findAll(output).toList()
        return matches.lastOrNull()?.groupValues?.getOrNull(1)?.trimEnd('.')?.takeIf { it.isNotBlank() }
    }

    /**
     * 规范化用户粘贴的 OAuth 授权码或完整回调 URL：
     * 去除首尾空白及网页换行折断带来的回车换行符。
     */
    fun normalizeCodeSubmission(raw: String): String {
        return raw.replace("\r", "")
            .replace("\n", "")
            .trim()
    }
}

/**
 * IDEA 内置官方 `agy acp --login` 交互登录对话框：
 * - 直接运行官方 `agy acp --login`，将输出实时展示在对话框中；
 * - 提供「在浏览器打开授权页」「复制链接」以及**内嵌授权码输入框**，避免外部黑框被浏览器挡住找不到回填位置；
 * - 检测到 `Logged in as <email>` 并成功退出后自动关闭，由主面板立即自动连接 ACP 并刷新登录状态；
 * - 同时保留「改用外部终端窗口」按钮作为备选。
 */
class AgyInteractiveLoginDialog(
    private val project: Project,
    private val resolved: GeminiExecutableResolver.ResolutionResult
) : DialogWrapper(project, true) {

    @Volatile
    var loginSucceeded: Boolean = false
        private set

    @Volatile
    var loggedInEmail: String? = null
        private set

    @Volatile
    var fallbackToExternalTerminal: Boolean = false
        private set

    @Volatile
    private var loginProcess: Process? = null
    private val closed = AtomicBoolean(false)
    private val outputBuffer = StringBuilder()
    private var detectedAuthUrl: String? = null

    private val statusLabel = JBLabel("正在启动官方 Antigravity 登录流程 (agy acp --login)...").apply {
        foreground = JBColor(Color(0xB45309), Color(0xFBBF24))
    }

    private val openBrowserButton = JButton("在浏览器打开授权页").apply {
        isEnabled = false
        toolTipText = "若浏览器未自动打开 Google 登录页，点击此按钮手动打开"
    }
    private val copyUrlButton = JButton("复制授权链接").apply {
        isEnabled = false
    }

    private val codeField = JBTextField().apply {
        emptyText.text = "将浏览器页面上的授权码（如 4/0A...）粘贴到此处，按回车或点击右侧提交"
    }
    private val pasteClipboardButton = JButton("粘贴").apply {
        toolTipText = "从系统剪贴板粘贴授权码到输入框"
    }
    private val submitCodeButton = JButton("提交授权码").apply {
        isEnabled = false
    }

    private val logArea = JBTextArea(10, 72).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = UIUtil.getLabelFont().deriveFont(12f)
    }

    init {
        title = "Google 账号交互登录 (agy acp --login)"
        setCancelButtonText("取消")
        init()
        wireActions()
        startLoginProcess()
    }

    override fun createActions(): Array<Action> {
        val externalAction = object : AbstractAction("改用外部终端窗口") {
            override fun actionPerformed(e: ActionEvent?) {
                fallbackToExternalTerminal = true
                close(CANCEL_EXIT_CODE)
            }
        }
        return arrayOf(externalAction, cancelAction)
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 10)).apply {
            preferredSize = Dimension(680, 380)
            border = JBUI.Borders.empty(4)
        }

        val topStack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        val guideLabel = JBLabel(
            "<html><b>操作步骤：</b>1. 在浏览器完成 Google 账号授权（若未自动弹出请点「在浏览器打开授权页」）；" +
                "2. 复制网页显示的授权码（<code>4/0A...</code>）粘贴到下方输入框并点击 <b>「提交授权码」</b>；" +
                "3. 验证成功后窗口自动关闭，插件将<b>自动连接并刷新登录状态</b>。</html>"
        ).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = CompoundBorder(
                LineBorder(JBColor.border(), 1),
                JBUI.Borders.empty(8)
            )
            background = JBColor(Color(0xF8FAFC), Color(0x1E293B))
            isOpaque = true
        }

        val statusRow = JPanel(BorderLayout(8, 0)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(6, 0, 2, 0)
            add(statusLabel, BorderLayout.CENTER)
            val urlBtns = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                isOpaque = false
                add(openBrowserButton)
                add(copyUrlButton)
            }
            add(urlBtns, BorderLayout.EAST)
        }

        val inputRow = JPanel(BorderLayout(6, 0)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(4, 0, 4, 0)
            add(JBLabel("回填授权码："), BorderLayout.WEST)
            add(codeField, BorderLayout.CENTER)
            val submitBtns = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                isOpaque = false
                add(pasteClipboardButton)
                add(submitCodeButton)
            }
            add(submitBtns, BorderLayout.EAST)
        }

        topStack.add(guideLabel)
        topStack.add(Box.createVerticalStrut(4))
        topStack.add(statusRow)
        topStack.add(inputRow)

        val logScroll = JBScrollPane(logArea).apply {
            border = CompoundBorder(
                LineBorder(JBColor.border(), 1),
                JBUI.Borders.empty(2)
            )
        }

        root.add(topStack, BorderLayout.NORTH)
        root.add(logScroll, BorderLayout.CENTER)
        return root
    }

    override fun getPreferredFocusedComponent(): JComponent = codeField

    private fun wireActions() {
        openBrowserButton.addActionListener {
            detectedAuthUrl?.let { BrowserUtil.browse(it) }
        }
        copyUrlButton.addActionListener {
            detectedAuthUrl?.let { url ->
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(url), null)
                statusLabel.text = "已复制授权链接到剪贴板，请在浏览器打开并完成授权。"
            }
        }
        pasteClipboardButton.addActionListener {
            runCatching {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                    val pasted = clipboard.getData(DataFlavor.stringFlavor) as? String
                    if (!pasted.isNullOrBlank()) {
                        codeField.text = AgyLoginOutputParser.normalizeCodeSubmission(pasted)
                        codeField.requestFocusInWindow()
                    }
                }
            }
        }
        submitCodeButton.addActionListener { submitAuthorizationCode() }
        codeField.addActionListener { submitAuthorizationCode() }
    }

    private fun startLoginProcess() {
        val exe = resolved.executablePath ?: return
        val settings = GeminiAcpSettingsState.getInstance().state
        val cmd = if (resolved.needsCmdWrapper) {
            listOf("cmd.exe", "/c", "call", exe, "acp", "--login")
        } else {
            listOf(exe, "acp", "--login")
        }
        val workDir = project.basePath?.let(::File)?.takeIf { it.isDirectory }
            ?: File(System.getProperty("user.home"))

        val pb = ProcessBuilder(cmd)
            .directory(workDir)
            .redirectErrorStream(true)

        val env = pb.environment()
        if (settings.proxyUrl.isNotBlank()) {
            val proxy = settings.proxyUrl.trim()
            env["HTTP_PROXY"] = proxy
            env["HTTPS_PROXY"] = proxy
            env["http_proxy"] = proxy
            env["https_proxy"] = proxy
        }
        if (settings.stripGeminiApiKeyEnv) {
            env.remove("GEMINI_API_KEY")
        }

        val worker = Thread({
            try {
                val proc = pb.start()
                loginProcess = proc
                invokeOnEdt {
                    submitCodeButton.isEnabled = true
                }

                InputStreamReader(proc.inputStream, StandardCharsets.UTF_8).use { reader ->
                    val buf = CharArray(512)
                    while (true) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        val chunk = String(buf, 0, n)
                        onProcessOutputChunk(chunk)
                    }
                }

                val exitCode = proc.waitFor()
                onProcessExited(exitCode)
            } catch (t: Throwable) {
                if (!closed.get()) {
                    invokeOnEdt {
                        statusLabel.foreground = JBColor(Color(0xB91C1C), Color(0xF87171))
                        statusLabel.text = "启动 agy acp --login 失败：${t.message}"
                        submitCodeButton.isEnabled = false
                        appendLog("\n[错误] ${t.message}\n")
                    }
                }
            }
        }, "GeminiAcp-InteractiveLogin")
        worker.isDaemon = true
        worker.start()
    }

    private fun onProcessOutputChunk(chunk: String) {
        if (closed.get()) return
        val snapshot: String
        synchronized(outputBuffer) {
            outputBuffer.append(chunk)
            snapshot = outputBuffer.toString()
        }
        val url = AgyLoginOutputParser.extractAuthUrl(snapshot)
        val email = AgyLoginOutputParser.extractLoggedInEmail(snapshot)

        invokeOnEdt {
            appendLog(chunk)
            if (url != null && detectedAuthUrl == null) {
                detectedAuthUrl = url
                openBrowserButton.isEnabled = true
                copyUrlButton.isEnabled = true
                statusLabel.foreground = JBColor(Color(0x1D4ED8), Color(0x60A5FA))
                statusLabel.text = "请在浏览器完成授权（若未自动打开请点右侧按钮），再将授权码粘贴到下方提交："
                codeField.requestFocusInWindow()
            }
            if (email != null) {
                loggedInEmail = email
            }
        }
    }

    private fun onProcessExited(exitCode: Int) {
        if (closed.get()) return
        val snapshot = synchronized(outputBuffer) { outputBuffer.toString() }
        val email = AgyLoginOutputParser.extractLoggedInEmail(snapshot) ?: loggedInEmail

        invokeOnEdt {
            submitCodeButton.isEnabled = false
            if (exitCode == 0) {
                loginSucceeded = true
                loggedInEmail = email
                statusLabel.foreground = JBColor(Color(0x15803D), Color(0x4ADE80))
                statusLabel.text = "✅ 登录成功${email?.let { "（$it）" } ?: ""}！正在返回插件并自动连接..."
                Timer(450) {
                    if (!closed.get()) {
                        close(OK_EXIT_CODE)
                    }
                }.apply {
                    isRepeats = false
                    start()
                }
            } else {
                statusLabel.foreground = JBColor(Color(0xB91C1C), Color(0xF87171))
                statusLabel.text = "⚠️ 登录未完成（退出码 $exitCode），请查看下方输出，或点击左下角「改用外部终端窗口」重试。"
            }
        }
    }

    private fun submitAuthorizationCode() {
        val proc = loginProcess
        if (proc == null || !proc.isAlive) {
            statusLabel.foreground = JBColor(Color(0xB91C1C), Color(0xF87171))
            statusLabel.text = "登录进程已结束，请重新打开登录或点击「改用外部终端窗口」。"
            return
        }
        val normalized = AgyLoginOutputParser.normalizeCodeSubmission(codeField.text)
        if (normalized.isEmpty()) {
            statusLabel.foreground = JBColor(Color(0xB45309), Color(0xFBBF24))
            statusLabel.text = "请先粘贴浏览器显示的授权码（如 4/0A...）再提交。"
            codeField.requestFocusInWindow()
            return
        }

        submitCodeButton.isEnabled = false
        statusLabel.foreground = JBColor(Color(0xB45309), Color(0xFBBF24))
        statusLabel.text = "正在提交授权码并向 Google 完成校验..."
        appendLog("\n[已提交授权码，等待验证...]\n")

        val writerThread = Thread({
            runCatching {
                val out = proc.outputStream
                out.write((normalized + "\n").toByteArray(StandardCharsets.UTF_8))
                out.flush()
                invokeOnEdt {
                    Timer(1500) {
                        if (!closed.get() && proc.isAlive) {
                            submitCodeButton.isEnabled = true
                        }
                    }.apply {
                        isRepeats = false
                        start()
                    }
                }
            }.onFailure { err ->
                invokeOnEdt {
                    submitCodeButton.isEnabled = proc.isAlive
                    statusLabel.foreground = JBColor(Color(0xB91C1C), Color(0xF87171))
                    statusLabel.text = "写入授权码失败：${err.message}"
                }
            }
        }, "GeminiAcp-LoginCodeWriter")
        writerThread.isDaemon = true
        writerThread.start()
    }

    private fun appendLog(text: String) {
        logArea.append(text)
        logArea.caretPosition = logArea.document.length
    }

    private fun invokeOnEdt(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({
            if (!closed.get() && !project.isDisposed) {
                action()
            }
        }, ModalityState.any())
    }

    override fun dispose() {
        closed.set(true)
        runCatching {
            val proc = loginProcess
            if (proc != null && proc.isAlive) {
                proc.destroyForcibly()
            }
        }
        loginProcess = null
        super.dispose()
    }
}
